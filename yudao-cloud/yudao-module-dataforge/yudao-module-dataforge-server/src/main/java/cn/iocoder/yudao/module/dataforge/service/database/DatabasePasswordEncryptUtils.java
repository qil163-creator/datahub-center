package cn.iocoder.yudao.module.dataforge.service.database;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;

/**
 * 数据库连接密码加解密，使用记录上的 secretKey
 */
public class DatabasePasswordEncryptUtils {

    /**
     * 限制数据源密码加密工具的实例化，统一通过静态能力使用。
     */
    private DatabasePasswordEncryptUtils() {
    }

    /**
     * 加密数据源密码加密。
     *
     * @param password 密码
     * @param secretKey 密钥键
     * @return 加密数据源密码加密文本
     */
    public static String encrypt(String password, String secretKey) {
        if (StrUtil.hasBlank(password, secretKey)) {
            return password;
        }
        return getAes(secretKey).encryptBase64(password);
    }

    /**
     * 解密数据源密码加密。
     *
     * @param password 密码
     * @param secretKey 密钥键
     * @return 解密数据源密码加密文本
     */
    public static String decrypt(String password, String secretKey) {
        if (StrUtil.hasBlank(password, secretKey)) {
            return password;
        }
        try {
            return getAes(secretKey).decryptStr(password);
        } catch (Exception ex) {
            // 兼容历史明文
            return password;
        }
    }

    /**
     * 已加密则原样返回，明文则加密
     */
    public static String encryptIfPlaintext(String password, String secretKey) {
        if (StrUtil.hasBlank(password, secretKey)) {
            return password;
        }
        String decrypted = decrypt(password, secretKey);
        if (StrUtil.equals(password, decrypted)) {
            return encrypt(password, secretKey);
        }
        return password;
    }

    /**
     * 读取AES。
     *
     * @param secretKey 密钥键
     * @return AES
     */
    private static AES getAes(String secretKey) {
        byte[] key = StrUtil.utf8Bytes(secretKey);
        if (key.length != 16 && key.length != 24 && key.length != 32) {
            key = SecureUtil.sha256().digest(secretKey);
        }
        return SecureUtil.aes(key);
    }

}
