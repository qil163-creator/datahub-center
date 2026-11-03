package cn.iocoder.yudao.framework.etl.core.executor.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.digest.DigestAlgorithm;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.crypto.digest.Digester;
import cn.hutool.crypto.symmetric.SymmetricCrypto;
import cn.hutool.crypto.SmUtil;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * ETL 加解密工具：密钥按摘要派生为算法所需长度，密文以 Base64 表示
 *
 * @author Li
 */
public class EtlCryptoUtils {

    /**
     * 初始化 {@code EtlCryptoUtils}。
     */
    private EtlCryptoUtils() {
    }

    /** 可逆算法集合（解密节点仅支持这些） */
    public static boolean isReversible(String algorithm) {
        return switch (StrUtil.nullToEmpty(algorithm).toUpperCase()) {
            case "AES", "SM4", "AES128", "AES256", "DES", "3DES", "BASE64" -> true;
            default -> false;
        };
    }

    /**
     * 按指定算法加密当前明文值。
     */
    public static String encrypt(String algorithm, String secretKey, String plain) {
        if (plain == null) {
            return null;
        }
        String algo = StrUtil.nullToEmpty(algorithm).toUpperCase();
        return switch (algo) {
            case "AES", "SM4", "AES128", "AES256", "DES", "3DES" -> Base64.getEncoder().encodeToString(
                    cipher(algo, secretKey).encrypt(plain.getBytes(StandardCharsets.UTF_8)));
            case "MD5" -> DigestUtil.md5Hex(plain);
            case "SHA1" -> DigestUtil.sha1Hex(plain);
            case "SHA256" -> DigestUtil.sha256Hex(plain);
            case "SHA512" -> new Digester(DigestAlgorithm.SHA512).digestHex(plain);
            case "BASE64" -> Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
            default -> throw new IllegalStateException(String.format("不支持的加密算法【%s】", algorithm));
        };
    }

    /**
     * 按指定算法解密当前密文值。
     */
    public static String decrypt(String algorithm, String secretKey, String cipherText) {
        if (cipherText == null) {
            return null;
        }
        String algo = StrUtil.nullToEmpty(algorithm).toUpperCase();
        return switch (algo) {
            case "AES", "SM4", "AES128", "AES256", "DES", "3DES" ->
                    new String(cipher(algo, secretKey).decrypt(Base64.getDecoder().decode(cipherText)),
                            StandardCharsets.UTF_8);
            case "BASE64" -> new String(Base64.getDecoder().decode(cipherText), StandardCharsets.UTF_8);
            default -> throw new IllegalStateException(String.format("算法【%s】不可解密", algorithm));
        };
    }

    /**
     * 密钥派生：AES128 取 MD5 16 字节；AES256 取 SHA256 32 字节；DES 8 字节；3DES 24 字节
     */
    private static SymmetricCrypto cipher(String algorithm, String secretKey) {
        if (StrUtil.isBlank(secretKey)) {
            throw new IllegalStateException("加解密密钥不能为空");
        }
        byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
        return switch (algorithm) {
            case "AES" -> SecureUtil.aes(referenceKey(secretKey));
            case "SM4" -> SmUtil.sm4(referenceKey(secretKey));
            case "AES128" -> SecureUtil.aes(DigestUtil.md5(secretKey));
            case "AES256" -> SecureUtil.aes(DigestUtil.sha256(keyBytes));
            case "DES" -> SecureUtil.des(Arrays.copyOf(DigestUtil.md5(secretKey), 8));
            case "3DES" -> SecureUtil.desede(Arrays.copyOf(DigestUtil.sha256(keyBytes), 24));
            default -> throw new IllegalStateException("不支持的对称算法");
        };
    }

    /** 参考配置使用16位字母数字密钥，兼容旧算法时不改变其摘要派生方式。 */
    private static byte[] referenceKey(String value) {
        if (!value.matches("(?=.*[0-9])[A-Za-z0-9]{16}"))
            throw new IllegalStateException("AES/SM4 密钥必须为16位字母数字并包含数字");
        return value.getBytes(StandardCharsets.UTF_8);
    }

}
