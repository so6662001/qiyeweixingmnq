package com.wecom.bridge.crypto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallbackCryptoTest {

    private static final String TOKEN = "QDG6eK";
    private static final String AES_KEY = "jWmYm7qr5nMoAUwZRjGtBxmz3KA1tkAj3ykkR6q2B2C";
    private static final String CORP_ID = "wx5823bf96d3bd56c7";

    private final CallbackCrypto crypto = new CallbackCrypto(TOKEN, AES_KEY, CORP_ID);

    @Test
    void 签名按字典序拼接后取sha1() {
        // 四个参数顺序不同不应影响结果
        String a = CallbackCrypto.signature(TOKEN, "1409659589", "263014780", "cipher");
        String b = CallbackCrypto.signature("cipher", "263014780", "1409659589", TOKEN);
        assertThat(a).isEqualTo(b).hasSize(40).matches("[0-9a-f]{40}");
    }

    @Test
    void 加密后可原样解密回明文() {
        String plain = "<xml><ToUserName><![CDATA[wx5823bf96d3bd56c7]]></ToUserName>"
                + "<FromUserName><![CDATA[mycreate]]></FromUserName>"
                + "<MsgType><![CDATA[text]]></MsgType><Content><![CDATA[你好，报个价]]></Content></xml>";
        String timestamp = "1409659813";
        String nonce = "1372623149";

        String encryptedXml = crypto.encryptMsg(plain, timestamp, nonce);
        Map<String, String> fields = CallbackCrypto.parseXml(encryptedXml);

        String decrypted = crypto.decryptMsg(fields.get("MsgSignature"), timestamp, nonce, encryptedXml);
        assertThat(decrypted).isEqualTo(plain);
        assertThat(CallbackCrypto.parseXml(decrypted).get("Content")).isEqualTo("你好，报个价");
    }

    @Test
    void URL校验返回解密后的echostr() {
        String echoPlain = "1616140317555161061";
        String timestamp = "1409659589";
        String nonce = "263014780";

        // 用同一套算法造出一个合法的 echostr 密文
        String encryptedXml = crypto.encryptMsg(echoPlain, timestamp, nonce);
        Map<String, String> fields = CallbackCrypto.parseXml(encryptedXml);
        String echostr = fields.get("Encrypt");
        String signature = CallbackCrypto.signature(TOKEN, timestamp, nonce, echostr);

        assertThat(crypto.verifyUrl(signature, timestamp, nonce, echostr)).isEqualTo(echoPlain);
    }

    @Test
    void 签名不匹配时拒绝() {
        String encryptedXml = crypto.encryptMsg("<xml><A>1</A></xml>", "100", "abc");
        assertThatThrownBy(() -> crypto.decryptMsg("deadbeef", "100", "abc", encryptedXml))
                .isInstanceOf(CallbackCryptoException.class)
                .hasMessageContaining("msg_signature");
    }

    @Test
    void receiveid与配置不一致时拒绝() {
        CallbackCrypto other = new CallbackCrypto(TOKEN, AES_KEY, "wxOtherCorpId");
        String encryptedXml = other.encryptMsg("<xml><A>1</A></xml>", "100", "abc");
        String encrypt = CallbackCrypto.parseXml(encryptedXml).get("Encrypt");
        String signature = CallbackCrypto.signature(TOKEN, "100", "abc", encrypt);

        assertThatThrownBy(() -> crypto.decryptMsg(signature, "100", "abc", encryptedXml))
                .isInstanceOf(CallbackCryptoException.class)
                .hasMessageContaining("receiveid");
    }

    @Test
    void 非法AesKey长度直接报错() {
        assertThatThrownBy(() -> new CallbackCrypto(TOKEN, "tooShort", CORP_ID))
                .isInstanceOf(CallbackCryptoException.class)
                .hasMessageContaining("43");
    }

    @Test
    void 解析回调XML时禁用外部实体() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE root [<!ENTITY payload SYSTEM "file:///etc/passwd">]>
                <xml><Encrypt>&payload;</Encrypt></xml>
                """;
        assertThatThrownBy(() -> CallbackCrypto.parseXml(xxe))
                .isInstanceOf(CallbackCryptoException.class);
    }

    @Test
    void 解析普通回调XML得到一级字段() {
        Map<String, String> fields = CallbackCrypto.parseXml(
                "<xml><ToUserName><![CDATA[ww123]]></ToUserName><Event><![CDATA[kf_msg_or_event]]></Event>"
                        + "<Token><![CDATA[abc123]]></Token><OpenKfId><![CDATA[wkxxx]]></OpenKfId></xml>");

        assertThat(fields)
                .containsEntry("ToUserName", "ww123")
                .containsEntry("Event", "kf_msg_or_event")
                .containsEntry("Token", "abc123")
                .containsEntry("OpenKfId", "wkxxx");
    }
}
