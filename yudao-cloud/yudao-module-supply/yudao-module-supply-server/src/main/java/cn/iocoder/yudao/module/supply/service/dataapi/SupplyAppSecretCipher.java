package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;
import cn.hutool.extra.spring.SpringUtil;

/**
 * Supply 应用密钥静态加密工具。
 *
 * <p>新值使用 ENC: 前缀保存；历史明文无前缀时兼容读取，重置后会转为密文。</p>
 */
public final class SupplyAppSecretCipher {

    private static final String PREFIX = "ENC:";

    private static final String ENCRYPTOR_PROPERTY_NAME = "mybatis-plus.encryptor.password";

    private static volatile AES aes;

    /**
     * 限制供应应用密钥加密器工具的实例化，统一通过静态能力使用。
     */
    private SupplyAppSecretCipher() {
    }

    /**
     * 加密供应应用密钥加密器。
     *
     * @param plainSecret 明文密钥
     * @return 加密供应应用密钥加密器文本
     */
    public static String encrypt(String plainSecret) {
        if (plainSecret == null || plainSecret.startsWith(PREFIX)) {
            return plainSecret;
        }
        return PREFIX + encryptor().encryptBase64(plainSecret);
    }

    /**
     * 解密供应应用密钥加密器。
     *
     * @param storedSecret 已存储密钥
     * @return 解密供应应用密钥加密器文本
     */
    public static String decrypt(String storedSecret) {
        if (storedSecret == null || !storedSecret.startsWith(PREFIX)) {
            return storedSecret;
        }
        return encryptor().decryptStr(StrUtil.removePrefix(storedSecret, PREFIX));
    }

    /**
     * 获取供应应用密钥加密器的加密器。
     *
     * @return AES
     */
    private static AES encryptor() {
        AES current = aes;
        if (current != null) {
            return current;
        }
        String password = SpringUtil.getProperty(ENCRYPTOR_PROPERTY_NAME);
        Assert.notEmpty(password, "配置项({}) 不能为空", ENCRYPTOR_PROPERTY_NAME);
        current = SecureUtil.aes(password.getBytes());
        aes = current;
        return current;
    }

}
