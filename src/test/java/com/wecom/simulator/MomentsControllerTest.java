package com.wecom.simulator;

import com.sun.net.httpserver.HttpServer;
import com.wecom.simulator.store.MomentStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MomentsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MomentStore momentStore;

    @Value("${wecom.simulator.moment-image-dir}")
    private String momentImageDir;

    private HttpServer crmServer;
    private final List<String> crmBodies = new CopyOnWriteArrayList<>();
    private int crmPort;

    @BeforeEach
    void setUp() throws Exception {
        momentStore.clear();
        momentStore.setCrmEnabled(false);
        momentStore.setCrmUrl(null);
        momentStore.setCrmAuthHeader(null);
        momentStore.setCrmAutoSync(false);
        Path dir = Path.of(momentImageDir);
        Files.createDirectories(dir);
        try (var stream = Files.list(dir)) {
            stream.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // ignore
                }
            });
        }

        crmBodies.clear();
        crmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        crmPort = crmServer.getAddress().getPort();
        crmServer.createContext("/crm/leads", exchange -> {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            crmBodies.add(new String(bytes, StandardCharsets.UTF_8));
            byte[] resp = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        crmServer.start();
    }

    @AfterEach
    void tearDown() {
        if (crmServer != null) {
            crmServer.stop(0);
        }
    }

    @Test
    void publishLikeCommentAndSyncToCrm() throws Exception {
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        MockMultipartFile image = new MockMultipartFile(
                "image",
                "promo.png",
                "image/png",
                png
        );

        MvcResult created = mockMvc.perform(multipart("/api/moments")
                        .file(image)
                        .param("content", "春季活动上线啦")
                        .param("plan", "获客方案A")
                        .param("author_id", "seller001")
                        .param("author_name", "销售顾问"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("春季活动上线啦"))
                .andExpect(jsonPath("$.plan").value("获客方案A"))
                .andExpect(jsonPath("$.image_url").value(org.hamcrest.Matchers.startsWith("/api/moments/media/")))
                .andReturn();

        String momentId = com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(),
                "$.moment_id"
        );
        String imageUrl = com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(),
                "$.image_url"
        );

        mockMvc.perform(get(imageUrl)).andExpect(status().isOk());

        mockMvc.perform(post("/api/moments/" + momentId + "/likes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"user_id":"lead001","user_name":"潜在客户甲"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("like"))
                .andExpect(jsonPath("$.synced_to_crm").value(false));

        mockMvc.perform(post("/api/moments/" + momentId + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"user_id":"lead002","user_name":"潜在客户乙","content":"怎么报名？"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("comment"))
                .andExpect(jsonPath("$.content").value("怎么报名？"));

        mockMvc.perform(get("/api/moments/interactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(put("/api/moments/crm/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"http://127.0.0.1:%d/crm/leads","enabled":true,"auto_sync":false}
                                """.formatted(crmPort)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        mockMvc.perform(post("/api/moments/crm/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.success").value(2))
                .andExpect(jsonPath("$.failed").value(0));

        assertThat(crmBodies).hasSize(2);
        assertThat(crmBodies.get(0)).contains("wecom_moment");
        assertThat(crmBodies.get(0)).contains("获客方案A");

        mockMvc.perform(get("/api/moments/interactions").param("unsynced_only", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void rejectsUnsafeCrmUrlAndClearsMedia() throws Exception {
        mockMvc.perform(put("/api/moments/crm/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"http://169.254.169.254/latest/meta-data/","enabled":true}
                                """))
                .andExpect(status().isBadRequest());

        MockMultipartFile image = new MockMultipartFile(
                "image",
                "a.png/../../tmp/pwned.png",
                "image/png",
                "img".getBytes()
        );
        MvcResult created = mockMvc.perform(multipart("/api/moments")
                        .file(image)
                        .param("content", "安全测试"))
                .andExpect(status().isOk())
                .andReturn();
        String momentId = com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(),
                "$.moment_id"
        );
        assertThat(Files.exists(Path.of(momentImageDir).resolve(momentId + ".png"))
                || Files.exists(Path.of(momentImageDir).resolve(momentId + ".jpg"))).isTrue();
        assertThat(Files.exists(Path.of("/tmp/pwned.png"))).isFalse();

        mockMvc.perform(delete("/api/moments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        try (var stream = Files.list(Path.of(momentImageDir))) {
            assertThat(stream.filter(Files::isRegularFile).toList()).isEmpty();
        }
    }

    @Test
    void autoSyncOnInteraction() throws Exception {
        mockMvc.perform(put("/api/moments/crm/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"http://127.0.0.1:%d/crm/leads","enabled":true,"auto_sync":true}
                                """.formatted(crmPort)))
                .andExpect(status().isOk());

        MockMultipartFile image = new MockMultipartFile(
                "image",
                "auto.jpg",
                "image/jpeg",
                "jpeg-bytes".getBytes()
        );
        MvcResult created = mockMvc.perform(multipart("/api/moments")
                        .file(image)
                        .param("content", "自动同步文案")
                        .param("plan", "方案B"))
                .andExpect(status().isOk())
                .andReturn();
        String momentId = com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(),
                "$.moment_id"
        );

        mockMvc.perform(post("/api/moments/" + momentId + "/likes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"user_id":"lead009","user_name":"客户丙"}
                                """))
                .andExpect(status().isOk());

        boolean synced = false;
        for (int i = 0; i < 40; i++) {
            if (!crmBodies.isEmpty()) {
                synced = true;
                break;
            }
            Thread.sleep(50);
        }
        assertThat(synced).isTrue();
        assertThat(new ArrayList<>(crmBodies).get(0)).contains("lead009");
    }
}
