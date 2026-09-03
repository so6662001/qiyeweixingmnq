package com.wecom.bridge.archive;

import com.wecom.bridge.config.BridgeProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 解密存档消息里的 {@code encrypt_random_key}。
 *
 * <p>按官方说明：先 base64 decode，再用 {@code publickey_ver} 对应版本的企业私钥、
 * 以 RSA/PKCS1 解密，得到的明文即传给 SDK {@code DecryptData} 的密钥。</p>
 */
@Component
public class ArchiveRandomKeyDecryptor {

    private final BridgeProperties properties;
    private final Map<String, PrivateKey> keyCache = new ConcurrentHashMap<>();

    public ArchiveRandomKeyDecryptor(BridgeProperties properties) {
        this.properties = properties;
    }

    /**
     * @param publicKeyVersion 存档消息回带的公钥版本号
     * @param encryptRandomKey base64 的密文
     * @return RSA 解密后的对称密钥明文
     */
    public String decrypt(String publicKeyVersion, String encryptRandomKey) {
        if (encryptRandomKey == null || encryptRandomKey.isBlank()) {
            throw new ArchiveSdkException("encrypt_random_key 为空");
        }
        PrivateKey privateKey = privateKey(publicKeyVersion);
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            byte[] decoded = Base64.getDecoder().decode(encryptRandomKey);
            return new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ArchiveSdkException(
                    "解密 encrypt_random_key 失败（检查 publickey_ver=" + publicKeyVersion + " 对应的私钥是否正确）", e);
        }
    }

    private PrivateKey privateKey(String version) {
        String key = version == null || version.isBlank() ? "default" : version.trim();
        PrivateKey cached = keyCache.get(key);
        if (cached != null) {
            return cached;
        }

        Map<String, String> configured = properties.getArchive().getPrivateKeys();
        String pem = configured.get(key);
        if (pem == null || pem.isBlank()) {
            // 只配了一把私钥时，允许不区分版本
            if (configured.size() == 1) {
                pem = configured.values().iterator().next();
            }
        }
        if (pem == null || pem.isBlank()) {
            throw new ArchiveSdkException("未配置 publickey_ver=" + key
                    + " 对应的私钥（wecom.bridge.archive.private-keys）");
        }

        PrivateKey parsed = parsePkcs8(pem);
        keyCache.put(key, parsed);
        return parsed;
    }

    /**
     * 解析 PKCS#8 PEM。会容忍 BEGIN/END 头尾与换行。
     */
    static PrivateKey parsePkcs8(String pem) {
        String normalized = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        if (normalized.isEmpty()) {
            throw new ArchiveSdkException("私钥内容为空");
        }
        try {
            byte[] der = Base64.getDecoder().decode(normalized);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new ArchiveSdkException("解析企业私钥失败，需为 PKCS#8 格式（BEGIN PRIVATE KEY）", e);
        }
    }
}
