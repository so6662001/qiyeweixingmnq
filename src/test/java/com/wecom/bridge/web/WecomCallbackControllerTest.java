package com.wecom.bridge.web;

import com.wecom.bridge.crypto.CallbackCrypto;
import com.wecom.bridge.model.InboxChannel;
import com.wecom.bridge.model.InboxConversation;
import com.wecom.bridge.store.InboxStore;
import com.wecom.bridge.support.FakeHttpTransport;
import com.wecom.simulator.WecomSimulatorApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {
        WecomSimulatorApplication.class,
        WecomCallbackControllerTest.Config.class
}, properties = {
        "wecom.bridge.enabled=true",
        "wecom.bridge.corp-id=ww-corp-1",
        "wecom.bridge.data-dir=target/test-bridge-callback",
        "wecom.bridge.demo-inbox=false",
        "wecom.bridge.kf.enabled=true",
        "wecom.bridge.kf.secret=kf-secret",
        "wecom.bridge.kf.callback-token=QDG6eK",
        "wecom.bridge.kf.callback-aes-key=jWmYm7qr5nMoAUwZRjGtBxmz3KA1tkAj3ykkR6q2B2C"
})
@AutoConfigureMockMvc
class WecomCallbackControllerTest {

    private static final String TOKEN = "QDG6eK";
    private static final String AES_KEY = "jWmYm7qr5nMoAUwZRjGtBxmz3KA1tkAj3ykkR6q2B2C";
    private static final String CORP_ID = "ww-corp-1";

    private final CallbackCrypto crypto = new CallbackCrypto(TOKEN, AES_KEY, CORP_ID);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InboxStore store;

    @Autowired
    private FakeHttpTransport transport;

    @TestConfiguration
    static class Config {
        /** 覆盖真实 HTTP 实现，测试中不产生任何外部请求。 */
        @Bean
        @Primary
        FakeHttpTransport fakeHttpTransport() {
            return new FakeHttpTransport();
        }
    }

    @BeforeEach
    void setUp() {
        store.clear();
        transport.reset();
    }

    @Test
    void 回调URL校验返回解密后的明文() throws Exception {
        String echoPlain = "1616140317555161061";
        String timestamp = "1409659589";
        String nonce = "263014780";
        String echostr = CallbackCrypto.parseXml(crypto.encryptMsg(echoPlain, timestamp, nonce)).get("Encrypt");
        String signature = CallbackCrypto.signature(TOKEN, timestamp, nonce, echostr);

        mockMvc.perform(get("/callback/wecom/kf")
                        .param("msg_signature", signature)
                        .param("timestamp", timestamp)
                        .param("nonce", nonce)
                        .param("echostr", echostr))
                .andExpect(status().isOk())
                .andExpect(content().string(echoPlain));
    }

    @Test
    void 签名错误的回调被拒绝() throws Exception {
        mockMvc.perform(get("/callback/wecom/kf")
                        .param("msg_signature", "bad-signature")
                        .param("timestamp", "1409659589")
                        .param("nonce", "263014780")
                        .param("echostr", "whatever"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 收到消息事件后拉取并入库() throws Exception {
        transport.respond("/cgi-bin/kf/sync_msg", """
                {
                  "errcode": 0, "errmsg": "ok", "next_cursor": "cursor-1", "has_more": 0,
                  "msg_list": [{
                    "msgid": "cb-msg-1", "open_kfid": "wk-open-1", "external_userid": "wm-user-9",
                    "send_time": 1756000000, "origin": 3, "msgtype": "text",
                    "text": { "content": "在吗，问个价" }
                  }]
                }
                """).respond("/cgi-bin/kf/customer/batchget",
                "{\"errcode\":0,\"errmsg\":\"ok\",\"customer_list\":[]}");

        String plain = "<xml><ToUserName><![CDATA[ww-corp-1]]></ToUserName>"
                + "<CreateTime>1756000000</CreateTime><MsgType><![CDATA[event]]></MsgType>"
                + "<Event><![CDATA[kf_msg_or_event]]></Event><Token><![CDATA[event-token]]></Token>"
                + "<OpenKfId><![CDATA[wk-open-1]]></OpenKfId></xml>";
        String timestamp = "1756000000";
        String nonce = "123456";
        String encryptedXml = crypto.encryptMsg(plain, timestamp, nonce);
        Map<String, String> fields = CallbackCrypto.parseXml(encryptedXml);

        mockMvc.perform(post("/callback/wecom/kf")
                        .param("msg_signature", fields.get("MsgSignature"))
                        .param("timestamp", timestamp)
                        .param("nonce", nonce)
                        .contentType("text/xml")
                        .content(encryptedXml))
                .andExpect(status().isOk());

        String conversationId = InboxConversation.buildId(InboxChannel.WECHAT_KF, "wm-user-9");
        awaitMessage(conversationId);

        assertThat(store.listMessages(conversationId, 10))
                .singleElement()
                .satisfies(message -> assertThat(message.getContent()).isEqualTo("在吗，问个价"));
    }

    private void awaitMessage(String conversationId) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            if (!store.listMessages(conversationId, 10).isEmpty()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待回调消息入库超时: " + conversationId);
    }
}
