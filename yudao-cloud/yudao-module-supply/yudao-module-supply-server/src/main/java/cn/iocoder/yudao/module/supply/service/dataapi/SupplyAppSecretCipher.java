package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.iocoder.yudao.module.supply.service.dataapi.cipher.AesGcmSecretCipher;
import cn.iocoder.yudao.module.supply.service.dataapi.cipher.SecretCipherAlgorithm;
import cn.iocoder.yudao.module.supply.service.dataapi.cipher.Sm4GcmSecretCipher;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Supply 应用密钥落库加解密入口。
 *
 * <p>写入算法由 {@code yudao.supply.data-api.secret-cipher} 选择（默认 {@code SM4}，可选 {@code AES}），
 * 密文保存为 {@code <算法>:<载荷>}。读取按前缀分派，因此切换写入算法后，旧算法写入的密文仍可解密。
 * 平台主密钥 {@code mybatis-plus.encryptor.password} 缺失时 fail closed；无已注册前缀的值一律拒绝，不当作明文。</p>
 */
public final class SupplyAppSecretCipher {

    public static final String ALGORITHM_PROPERTY_NAME = "yudao.supply.data-api.secret-cipher";

    public static final String DEFAULT_ALGORITHM = Sm4GcmSecretCipher.ID;

    private static final String MASTER_PROPERTY_NAME = "mybatis-plus.encryptor.password";

    /** 已注册算法；新增算法在此登记即可同时支持写入与读取。 */
    private static final Map<String, SecretCipherAlgorithm> ALGORITHMS = register(
            new Sm4GcmSecretCipher(), new AesGcmSecretCipher());

    private SupplyAppSecretCipher() {
    }

    /**
     * 使用当前配置的写入算法加密应用密钥明文。
     *
     * @param plainSecret 明文密钥
     * @return {@code <算法>:<载荷>} 格式密文
     */
    public static String encrypt(String plainSecret) {
        return encrypt(plainSecret, configuredAlgorithm(), masterPassword());
    }

    /**
     * 按密文前缀选择算法解密应用密钥。
     *
     * @param storedSecret 已存储密钥
     * @return 明文密钥
     */
    public static String decrypt(String storedSecret) {
        return decrypt(storedSecret, masterPassword());
    }

    /**
     * 已注册的算法标识。
     *
     * @return 算法标识集合
     */
    public static Set<String> supportedAlgorithms() {
        return ALGORITHMS.keySet();
    }

    // 包内可见：单测直接传入算法与主密钥，不依赖 Spring 上下文。
    static String encrypt(String plainSecret, String algorithmId, String masterPassword) {
        Assert.notNull(plainSecret, "应用密钥不能为空");
        SecretCipherAlgorithm algorithm = algorithm(algorithmId);
        return algorithm.id() + ":" + algorithm.encrypt(plainSecret, masterPassword);
    }

    static String decrypt(String storedSecret, String masterPassword) {
        int separator = storedSecret == null ? -1 : storedSecret.indexOf(':');
        SecretCipherAlgorithm algorithm = separator <= 0 ? null : ALGORITHMS.get(storedSecret.substring(0, separator));
        if (algorithm == null) {
            throw new IllegalStateException("应用密钥不是受支持的密文格式");
        }
        return algorithm.decrypt(storedSecret.substring(separator + 1), masterPassword);
    }

    private static SecretCipherAlgorithm algorithm(String algorithmId) {
        SecretCipherAlgorithm algorithm = ALGORITHMS.get(StrUtil.blankToDefault(algorithmId, DEFAULT_ALGORITHM)
                .trim().toUpperCase(Locale.ROOT));
        if (algorithm == null) {
            throw new IllegalStateException("配置项(" + ALGORITHM_PROPERTY_NAME + ") 不支持：" + algorithmId
                    + "，可选 " + ALGORITHMS.keySet());
        }
        return algorithm;
    }

    private static String configuredAlgorithm() {
        return SpringUtil.getProperty(ALGORITHM_PROPERTY_NAME);
    }

    private static String masterPassword() {
        String password = SpringUtil.getProperty(MASTER_PROPERTY_NAME);
        Assert.notEmpty(password, "配置项({}) 不能为空", MASTER_PROPERTY_NAME);
        return password;
    }

    private static Map<String, SecretCipherAlgorithm> register(SecretCipherAlgorithm... algorithms) {
        Map<String, SecretCipherAlgorithm> registry = new LinkedHashMap<>();
        for (SecretCipherAlgorithm algorithm : algorithms) {
            if (!algorithm.id().matches("[A-Z0-9]{2,16}") || registry.putIfAbsent(algorithm.id(), algorithm) != null) {
                throw new IllegalStateException("密钥加密算法标识非法或重复：" + algorithm.id());
            }
        }
        return Map.copyOf(registry);
    }

}
