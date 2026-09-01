package com.wecom.bridge.crypto;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 企业微信回调消息加解密，按官方公开算法实现（AES-256-CBC + PKCS#7，SHA1 签名）。
 *
 * <p>明文结构：16 字节随机串 + 4 字节网络序长度 + 消息体 + receiveid。</p>
 */
public final class CallbackCrypto {

    private static final int RANDOM_PREFIX_LEN = 16;
    private static final int MSG_LEN_BYTES = 4;
    private static final int BLOCK_SIZE = 32;

    private final byte[] aesKey;
    private final String token;
    private final String receiveId;
    private final SecureRandom random = new SecureRandom();

    public CallbackCrypto(String token, String encodingAesKey, String receiveId) {
        if (encodingAesKey == null || encodingAesKey.length() != 43) {
            throw new CallbackCryptoException(CallbackCryptoException.Reason.ILLEGAL_AES_KEY);
        }
        this.token = token == null ? "" : token;
        this.receiveId = receiveId == null ? "" : receiveId;
        try {
            this.aesKey = Base64.getDecoder().decode(encodingAesKey + "=");
        } catch (IllegalArgumentException e) {
            throw new CallbackCryptoException(CallbackCryptoException.Reason.ILLEGAL_AES_KEY, e);
        }
        if (aesKey.length != BLOCK_SIZE) {
            throw new CallbackCryptoException(CallbackCryptoException.Reason.ILLEGAL_AES_KEY);
        }
    }

    /**
     * 回调 URL 校验（GET）：验签后解密 echostr，原样返回明文。
     */
    public String verifyUrl(String msgSignature, String timestamp, String nonce, String echoStr) {
        checkSignature(msgSignature, timestamp, nonce, echoStr);
        return decrypt(echoStr);
    }

    /**
     * 回调消息解密（POST）：从密文 XML 中取出 Encrypt，验签后解密出明文 XML。
     */
    public String decryptMsg(String msgSignature, String timestamp, String nonce, String encryptedXml) {
        String encrypt = extractEncrypt(encryptedXml);
        checkSignature(msgSignature, timestamp, nonce, encrypt);
        return decrypt(encrypt);
    }

    /**
     * 明文加密为回调应答 XML（被动回复场景使用；本服务默认返回空串，此方法主要用于自测）。
     */
    public String encryptMsg(String plainXml, String timestamp, String nonce) {
        String encrypt = encrypt(plainXml);
        String signature = signature(token, timestamp, nonce, encrypt);
        return "<xml>"
                + "<Encrypt><![CDATA[" + encrypt + "]]></Encrypt>"
                + "<MsgSignature><![CDATA[" + signature + "]]></MsgSignature>"
                + "<TimeStamp>" + timestamp + "</TimeStamp>"
                + "<Nonce><![CDATA[" + nonce + "]]></Nonce>"
                + "</xml>";
    }

    private void checkSignature(String msgSignature, String timestamp, String nonce, String encrypt) {
        String expected = signature(token, timestamp, nonce, encrypt);
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = (msgSignature == null ? "" : msgSignature).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(a, b)) {
            throw new CallbackCryptoException(CallbackCryptoException.Reason.INVALID_SIGNATURE);
        }
    }

    /**
     * 官方签名算法：token/timestamp/nonce/encrypt 字典序排序后拼接取 SHA1。
     */
    public static String signature(String token, String timestamp, String nonce, String encrypt) {
        String[] parts = new String[]{
                token == null ? "" : token,
                timestamp == null ? "" : timestamp,
                nonce == null ? "" : nonce,
                encrypt == null ? "" : encrypt
        };
        Arrays.sort(parts);
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            sb.append(part);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new CallbackCryptoException(CallbackCryptoException.Reason.INVALID_SIGNATURE, e);
        }
    }

    private String decrypt(String base64Cipher) {
        byte[] plain;
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
            IvParameterSpec iv = new IvParameterSpec(aesKey, 0, 16);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, iv);
            plain = cipher.doFinal(Base64.getDecoder().decode(base64Cipher));
        } catch (Exception e) {
            throw new CallbackCryptoException(CallbackCryptoException.Reason.DECRYPT_FAILED, e);
        }

        byte[] unpadded = pkcs7Unpad(plain);
        if (unpadded.length < RANDOM_PREFIX_LEN + MSG_LEN_BYTES) {
            throw new CallbackCryptoException(CallbackCryptoException.Reason.DECRYPT_FAILED);
        }

        int msgLen = networkOrderToInt(unpadded, RANDOM_PREFIX_LEN);
        int msgStart = RANDOM_PREFIX_LEN + MSG_LEN_BYTES;
        if (msgLen < 0 || msgStart + msgLen > unpadded.length) {
            throw new CallbackCryptoException(CallbackCryptoException.Reason.DECRYPT_FAILED);
        }

        String content = new String(unpadded, msgStart, msgLen, StandardCharsets.UTF_8);
        String fromReceiveId = new String(
                unpadded, msgStart + msgLen, unpadded.length - msgStart - msgLen, StandardCharsets.UTF_8);
        if (!receiveId.isEmpty() && !receiveId.equals(fromReceiveId)) {
            throw new CallbackCryptoException(CallbackCryptoException.Reason.RECEIVE_ID_MISMATCH);
        }
        return content;
    }

    private String encrypt(String plainText) {
        byte[] content = plainText.getBytes(StandardCharsets.UTF_8);
        byte[] receive = receiveId.getBytes(StandardCharsets.UTF_8);
        byte[] prefix = new byte[RANDOM_PREFIX_LEN];
        random.nextBytes(prefix);

        byte[] buffer = new byte[RANDOM_PREFIX_LEN + MSG_LEN_BYTES + content.length + receive.length];
        System.arraycopy(prefix, 0, buffer, 0, RANDOM_PREFIX_LEN);
        System.arraycopy(intToNetworkOrder(content.length), 0, buffer, RANDOM_PREFIX_LEN, MSG_LEN_BYTES);
        System.arraycopy(content, 0, buffer, RANDOM_PREFIX_LEN + MSG_LEN_BYTES, content.length);
        System.arraycopy(receive, 0, buffer, RANDOM_PREFIX_LEN + MSG_LEN_BYTES + content.length, receive.length);

        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
            IvParameterSpec iv = new IvParameterSpec(aesKey, 0, 16);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, iv);
            return Base64.getEncoder().encodeToString(cipher.doFinal(pkcs7Pad(buffer)));
        } catch (Exception e) {
            throw new CallbackCryptoException(CallbackCryptoException.Reason.ENCRYPT_FAILED, e);
        }
    }

    private static byte[] pkcs7Pad(byte[] source) {
        int padLen = BLOCK_SIZE - (source.length % BLOCK_SIZE);
        if (padLen == 0) {
            padLen = BLOCK_SIZE;
        }
        byte[] padded = new byte[source.length + padLen];
        System.arraycopy(source, 0, padded, 0, source.length);
        Arrays.fill(padded, source.length, padded.length, (byte) padLen);
        return padded;
    }

    private static byte[] pkcs7Unpad(byte[] source) {
        if (source.length == 0) {
            throw new CallbackCryptoException(CallbackCryptoException.Reason.DECRYPT_FAILED);
        }
        int pad = source[source.length - 1];
        if (pad < 1 || pad > BLOCK_SIZE || pad > source.length) {
            return source;
        }
        return Arrays.copyOfRange(source, 0, source.length - pad);
    }

    private static byte[] intToNetworkOrder(int value) {
        return new byte[]{
                (byte) (value >> 24 & 0xFF),
                (byte) (value >> 16 & 0xFF),
                (byte) (value >> 8 & 0xFF),
                (byte) (value & 0xFF)
        };
    }

    private static int networkOrderToInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    private static String extractEncrypt(String xml) {
        Map<String, String> fields = parseXml(xml);
        String encrypt = fields.get("Encrypt");
        if (encrypt == null || encrypt.isBlank()) {
            throw new CallbackCryptoException(CallbackCryptoException.Reason.PARSE_XML_FAILED);
        }
        return encrypt;
    }

    /**
     * 解析回调 XML 为一级字段表。禁用 DTD / 外部实体，避免 XXE。
     */
    public static Map<String, String> parseXml(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new CallbackCryptoException(CallbackCryptoException.Reason.PARSE_XML_FAILED);
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            Document document = builder.parse(new InputSource(new StringReader(xml)));
            Document doc = document;
            doc.getDocumentElement().normalize();

            Map<String, String> fields = new HashMap<>();
            NodeList children = doc.getDocumentElement().getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                org.w3c.dom.Node node = children.item(i);
                if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                    fields.put(node.getNodeName(), node.getTextContent() == null ? "" : node.getTextContent().trim());
                }
            }
            return fields;
        } catch (CallbackCryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CallbackCryptoException(CallbackCryptoException.Reason.PARSE_XML_FAILED, e);
        }
    }
}
