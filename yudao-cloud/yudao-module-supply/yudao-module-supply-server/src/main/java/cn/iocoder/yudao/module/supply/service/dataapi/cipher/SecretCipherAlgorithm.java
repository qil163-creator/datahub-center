package cn.iocoder.yudao.module.supply.service.dataapi.cipher;

/**
 * 应用密钥落库加密算法扩展点。
 *
 * <p>落库格式统一为 {@code <id>:<payload>}；读取时按前缀选择算法，因此不同算法写入的密文可以并存。
 * 新增算法时实现本接口并在 {@code SupplyAppSecretCipher} 的注册表中登记，{@link #id()} 一经使用不得更改。</p>
 */
public interface SecretCipherAlgorithm {

    /**
     * 算法标识，同时作为密文前缀（不含冒号），例如 {@code SM4}、{@code AES}。
     *
     * @return 算法标识
     */
    String id();

    /**
     * 加密明文。
     *
     * @param plain 明文
     * @param masterPassword 平台主密钥，算法自行派生所需长度的密钥
     * @return 不含前缀的密文载荷
     */
    String encrypt(String plain, String masterPassword);

    /**
     * 解密密文载荷；认证失败或格式错误时抛出 {@link IllegalStateException}，不得返回部分明文。
     *
     * @param payload 不含前缀的密文载荷
     * @param masterPassword 平台主密钥
     * @return 明文
     */
    String decrypt(String payload, String masterPassword);

}
