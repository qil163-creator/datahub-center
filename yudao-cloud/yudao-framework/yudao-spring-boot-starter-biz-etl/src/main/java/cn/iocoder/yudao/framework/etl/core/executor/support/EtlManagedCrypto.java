package cn.iocoder.yudao.framework.etl.core.executor.support;

import java.nio.file.Path;

/** 工作节点按受管引用读取挂载密钥；任务只携带引用，不携带密钥值或任意文件路径。 */
public final class EtlManagedCrypto {
    private EtlManagedCrypto() { }

    public static String transform(boolean decrypt, String algorithm, String reference, String input, Path secretRoot) {
        EtlManagedSecrets.validate(reference, secretRoot);
        if (input == null) return null;
        return crypt(decrypt, algorithm, EtlManagedSecrets.read(reference, secretRoot), input);
    }

    public static String transformFromEnvironment(boolean decrypt, String algorithm, String reference, String input) {
        return crypt(decrypt, algorithm, EtlManagedSecrets.readFromEnvironment(reference), input);
    }

    private static String crypt(boolean decrypt, String algorithm, String secret, String input) {
        try {
            return decrypt ? EtlCryptoUtils.decrypt(algorithm, secret, input) : EtlCryptoUtils.encrypt(algorithm, secret, input);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("受管加解密失败，请核对算法、密钥绑定和输入格式");
        }
    }
}
