package cn.iocoder.yudao.framework.etl.flink;

import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedCrypto;
import org.apache.flink.table.functions.ScalarFunction;

/** 解密与加密共用同一受管密钥解析边界，不回传密钥或底层异常。 */
public final class EtlCryptoDecryptFunction extends ScalarFunction {
    public String eval(String algorithm, String reference, String input) {
        return EtlManagedCrypto.transformFromEnvironment(true, algorithm, reference, input);
    }
}
