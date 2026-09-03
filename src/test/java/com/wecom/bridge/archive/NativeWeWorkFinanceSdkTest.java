package com.wecom.bridge.archive;

import com.tencent.wework.Finance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用同名同签名的桩类验证反射适配层：方法名、参数类型、slice 生命周期。
 * 这是最容易和官方 SDK 对不上的地方。
 */
class NativeWeWorkFinanceSdkTest {

    private NativeWeWorkFinanceSdk sdk;

    @BeforeEach
    void setUp() {
        Finance.reset();
        // 留空 library-path，测试里不加载任何本地动态库
        sdk = new NativeWeWorkFinanceSdk("");
    }

    @Test
    void 初始化会调用NewSdk与Init() {
        sdk.init("ww-corp-1", "archive-secret");

        assertThat(sdk.available()).isTrue();
        assertThat(Finance.initCalls()).singleElement().satisfies(call -> {
            assertThat(call.corpId()).isEqualTo("ww-corp-1");
            assertThat(call.secret()).isEqualTo("archive-secret");
            assertThat(call.sdk()).isPositive();
        });
    }

    @Test
    void Init返回非0时抛异常并销毁句柄() {
        Finance.setInitReturn(10001);

        assertThatThrownBy(() -> sdk.init("ww-corp-1", "bad-secret"))
                .isInstanceOf(ArchiveSdkException.class)
                .hasMessageContaining("10001");

        assertThat(sdk.available()).isFalse();
        assertThat(Finance.destroyedSdks()).hasSize(1);
    }

    @Test
    void 拉取会话数据按官方参数顺序传参并释放slice() {
        Finance.setChatDataResponse("{\"errcode\":0,\"errmsg\":\"ok\",\"chatdata\":[{\"seq\":9}]}");
        sdk.init("ww-corp-1", "secret");

        String raw = sdk.getChatData(42L, 500L, "socks5://10.0.0.1:8081", "user:pass", 15L);

        assertThat(raw).contains("\"seq\":9");
        assertThat(Finance.chatDataCalls()).singleElement().satisfies(call -> {
            assertThat(call.seq()).isEqualTo(42L);
            assertThat(call.limit()).isEqualTo(500L);
            assertThat(call.proxy()).isEqualTo("socks5://10.0.0.1:8081");
            assertThat(call.passwd()).isEqualTo("user:pass");
            assertThat(call.timeout()).isEqualTo(15L);
        });
        // slice 必须归还，否则会累积内存
        assertThat(Finance.freedSlices()).hasSize(1);
        assertThat(Finance.openSliceCount()).isZero();
    }

    @Test
    void 代理为空时传空串而不是null() {
        sdk.init("ww-corp-1", "secret");

        sdk.getChatData(0L, 10L, null, null, 10L);

        assertThat(Finance.chatDataCalls()).singleElement().satisfies(call -> {
            assertThat(call.proxy()).isEmpty();
            assertThat(call.passwd()).isEmpty();
        });
    }

    @Test
    void 解密调用传对称密钥与密文并释放slice() {
        Finance.setDecryptResponse("{\"msgid\":\"m-1\",\"msgtype\":\"text\"}");
        sdk.init("ww-corp-1", "secret");

        String plain = sdk.decryptData("random-key", "CIPHER");

        assertThat(plain).contains("m-1");
        assertThat(Finance.decryptCalls()).singleElement().satisfies(call -> {
            assertThat(call.encryptKey()).isEqualTo("random-key");
            assertThat(call.encryptMsg()).isEqualTo("CIPHER");
        });
        assertThat(Finance.freedSlices()).hasSize(1);
    }

    @Test
    void 拉取返回非0时抛异常且仍然释放slice() {
        Finance.setChatDataReturn(10002);
        sdk.init("ww-corp-1", "secret");

        assertThatThrownBy(() -> sdk.getChatData(0L, 10L, "", "", 10L))
                .isInstanceOf(ArchiveSdkException.class)
                .hasMessageContaining("10002");

        assertThat(Finance.freedSlices()).hasSize(1);
        assertThat(Finance.openSliceCount()).isZero();
    }

    @Test
    void 解密返回非0时抛异常且仍然释放slice() {
        Finance.setDecryptReturn(10003);
        sdk.init("ww-corp-1", "secret");

        assertThatThrownBy(() -> sdk.decryptData("k", "c"))
                .isInstanceOf(ArchiveSdkException.class)
                .hasMessageContaining("10003");

        assertThat(Finance.freedSlices()).hasSize(1);
    }

    @Test
    void 未初始化时调用会给出可读提示() {
        assertThatThrownBy(() -> sdk.getChatData(0L, 10L, "", "", 10L))
                .isInstanceOf(ArchiveSdkException.class)
                .hasMessageContaining("未就绪");
    }

    @Test
    void 重复初始化不会重复创建句柄() {
        sdk.init("ww-corp-1", "secret");
        sdk.init("ww-corp-1", "secret");

        assertThat(Finance.initCalls()).hasSize(1);
    }

    @Test
    void 关闭时销毁句柄() {
        sdk.init("ww-corp-1", "secret");
        sdk.close();

        assertThat(Finance.destroyedSdks()).hasSize(1);
        assertThat(sdk.available()).isFalse();
    }

    @Test
    void 动态库路径不存在时给出可读异常() {
        NativeWeWorkFinanceSdk missing = new NativeWeWorkFinanceSdk("/nonexistent/libWeWorkFinanceSdk_Java.so");

        assertThatThrownBy(() -> missing.init("ww-corp-1", "secret"))
                .isInstanceOf(ArchiveSdkException.class)
                .hasMessageContaining("动态库");
    }
}
