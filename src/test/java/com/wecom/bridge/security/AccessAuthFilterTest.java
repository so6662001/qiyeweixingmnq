package com.wecom.bridge.security;

import com.wecom.simulator.WecomSimulatorApplication;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 收件箱能读到全部客户聊天、还能以使用者身份发消息，
 * 所以「谁能进来」这件事的每条分支都要有测试兜住。
 */
class AccessAuthFilterTest {

    private static final String BASE_PROPS_DATA_DIR = "wecom.bridge.data-dir=target/test-auth";

    @Nested
    @SpringBootTest(classes = WecomSimulatorApplication.class, properties = {
            "wecom.bridge.enabled=true",
            BASE_PROPS_DATA_DIR,
            "wecom.bridge.demo-inbox=false",
            "wecom.bridge.auth.enabled=true",
            "wecom.bridge.auth.access-code=test-pass-123"
    })
    @AutoConfigureMockMvc
    class 配置了口令 {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void 未登录访问接口返回401() throws Exception {
            mockMvc.perform(get("/api/inbox/conversations"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.login_required").value(true));
        }

        @Test
        void 未登录访问页面跳转登录页() throws Exception {
            mockMvc.perform(get("/inbox/").accept(MediaType.TEXT_HTML))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(header().string("Location", "/login"));
        }

        @Test
        void 登录页本身不需要登录() throws Exception {
            mockMvc.perform(get("/api/auth/state"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.access_code_configured").value(true));
        }

        @Test
        void 口令错误时登录失败() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.ok").value(false));
        }

        @Test
        void 口令正确后拿到会话cookie并能访问接口() throws Exception {
            MvcResult login = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"test-pass-123\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andReturn();

            String setCookie = login.getResponse().getHeader("Set-Cookie");
            assertThat(setCookie)
                    .contains(AccessAuthFilter.COOKIE_NAME)
                    .contains("HttpOnly")
                    .contains("SameSite=Lax");

            String token = extractToken(setCookie);
            mockMvc.perform(get("/api/inbox/conversations")
                            .cookie(new Cookie(AccessAuthFilter.COOKIE_NAME, token)))
                    .andExpect(status().isOk());
        }

        @Test
        void 伪造的cookie不被接受() throws Exception {
            mockMvc.perform(get("/api/inbox/conversations")
                            .cookie(new Cookie(AccessAuthFilter.COOKIE_NAME, "forged.token")))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void 支持用Bearer口令直接调接口() throws Exception {
            mockMvc.perform(get("/api/inbox/conversations")
                            .header("Authorization", "Bearer test-pass-123"))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/inbox/conversations")
                            .header("Authorization", "Bearer nope"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void 插件回推接口不受登录限制() throws Exception {
            // 未登录也能到达该接口，由它自己的共享密钥判定（这里未配密钥所以是 503）
            mockMvc.perform(post("/api/openclaw/inbound")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"peer_id\":\"p\",\"content\":\"hi\"}"))
                    .andExpect(status().isServiceUnavailable());
        }

        @Test
        void 退出后cookie被清除() throws Exception {
            MockHttpServletResponse response = mockMvc.perform(post("/api/auth/logout"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse();

            assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=0");
        }

        private String extractToken(String setCookie) {
            String prefix = AccessAuthFilter.COOKIE_NAME + "=";
            int start = setCookie.indexOf(prefix) + prefix.length();
            int end = setCookie.indexOf(';', start);
            return setCookie.substring(start, end < 0 ? setCookie.length() : end);
        }
    }

    @Nested
    @SpringBootTest(classes = WecomSimulatorApplication.class, properties = {
            "wecom.bridge.enabled=true",
            BASE_PROPS_DATA_DIR,
            "wecom.bridge.demo-inbox=false",
            "wecom.bridge.auth.enabled=true",
            "wecom.bridge.auth.access-code="
    })
    @AutoConfigureMockMvc
    class 未配置口令 {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void 本机访问放行() throws Exception {
            mockMvc.perform(get("/api/inbox/conversations").with(request -> {
                request.setRemoteAddr("127.0.0.1");
                return request;
            })).andExpect(status().isOk());
        }

        @Test
        void 外部访问被拒绝并说明原因() throws Exception {
            mockMvc.perform(get("/api/inbox/conversations").with(request -> {
                        request.setRemoteAddr("192.168.1.50");
                        return request;
                    }))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error")
                            .value(org.hamcrest.Matchers.containsString("WECOM_ACCESS_CODE")));
        }

        @Test
        void 未配置口令时无法登录() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"anything\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.ok").value(false));
        }

        @Test
        void 状态接口如实反映未配置() throws Exception {
            mockMvc.perform(get("/api/auth/state"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.access_code_configured").value(false));
        }
    }

    @Nested
    @SpringBootTest(classes = WecomSimulatorApplication.class, properties = {
            "wecom.bridge.enabled=true",
            BASE_PROPS_DATA_DIR,
            "wecom.bridge.demo-inbox=false",
            "wecom.bridge.auth.enabled=false"
    })
    @AutoConfigureMockMvc
    class 关闭鉴权 {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void 外部访问也放行() throws Exception {
            mockMvc.perform(get("/api/inbox/conversations").with(request -> {
                request.setRemoteAddr("203.0.113.9");
                return request;
            })).andExpect(status().isOk());
        }
    }
}
