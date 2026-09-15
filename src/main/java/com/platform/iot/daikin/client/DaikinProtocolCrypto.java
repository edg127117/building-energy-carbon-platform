package com.platform.iot.daikin.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 实现厂家协议指定的兼容算法；本地向量只证明算法实现，不代表已通过厂家正式验签。
 */
public final class DaikinProtocolCrypto {

    private DaikinProtocolCrypto() {
    }

    public static String sign(Map<String, String> parameters, String salt) {
        if (parameters == null || salt == null) {
            throw new IllegalArgumentException("签名参数和 salt 不能为空");
        }
        if (parameters.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getValue() == null)) {
            throw new IllegalArgumentException("签名字段不得包含空键或空值");
        }
        String sorted = parameters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(entry -> entry.getKey() + entry.getValue())
                .collect(Collectors.joining());
        return md5Upper(sorted + salt);
    }

    public static String encrypt(String plaintext, String key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey(key));
            return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new DaikinClientException(DaikinClientException.Code.CRYPTO_FAILURE, ex);
        }
    }

    public static String decrypt(String ciphertext, String key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey(key));
            byte[] decoded = Base64.getDecoder().decode(ciphertext);
            return new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new DaikinClientException(DaikinClientException.Code.CRYPTO_FAILURE, ex);
        }
    }

    private static SecretKeySpec aesKey(String key) {
        if (key == null) {
            throw new IllegalArgumentException("AES key 不能为空");
        }
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != 16 && bytes.length != 24 && bytes.length != 32) {
            throw new IllegalArgumentException("AES key 必须为 16、24 或 32 字节");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    private static String md5Upper(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 未提供 MD5", ex);
        }
    }
}
