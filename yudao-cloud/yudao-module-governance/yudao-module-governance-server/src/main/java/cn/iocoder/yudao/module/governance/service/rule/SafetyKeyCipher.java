package cn.iocoder.yudao.module.governance.service.rule;

/**
 * 按密钥引用加密：由密钥中心实现，脱敏引擎只通过它处理 ENCRYPT/KEY_REF，不接触密钥材料。
 */
@FunctionalInterface
public interface SafetyKeyCipher {

    /**
     * 用密钥引用的数据密钥加密文本；引用不可用时抛错。
     */
    String encrypt(String keyCode, String plaintext);
}
