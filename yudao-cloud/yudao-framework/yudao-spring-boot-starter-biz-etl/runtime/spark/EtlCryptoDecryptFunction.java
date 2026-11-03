package cn.iocoder.yudao.framework.etl.spark;

import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedCrypto;
import org.apache.hadoop.hive.ql.exec.UDF;

/** Spark SQL 解密仅按工作节点环境中的受管目录解析引用。 */
public final class EtlCryptoDecryptFunction extends UDF {
    public String evaluate(String algorithm, String reference, String input) {
        return EtlManagedCrypto.transformFromEnvironment(true, algorithm, reference, input);
    }
}
