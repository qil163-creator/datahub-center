package cn.iocoder.yudao.framework.etl.flink;

import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedCrypto;
import org.apache.flink.table.functions.ScalarFunction;

/** 由管理员安装并注册，密钥目录仅来自工作节点环境。 */
public final class EtlCryptoEncryptFunction extends ScalarFunction {
    public String eval(String algorithm, String reference, String input) {
        return EtlManagedCrypto.transformFromEnvironment(false, algorithm, reference, input);
    }
}
