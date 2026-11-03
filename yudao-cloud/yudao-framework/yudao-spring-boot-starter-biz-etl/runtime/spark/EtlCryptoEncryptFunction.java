package cn.iocoder.yudao.framework.etl.spark;

import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedCrypto;
import org.apache.hadoop.hive.ql.exec.UDF;

/** Spark SQL 使用官方 Hive UDF 接口加载同一受管加密实现。 */
public final class EtlCryptoEncryptFunction extends UDF {
    public String evaluate(String algorithm, String reference, String input) {
        return EtlManagedCrypto.transformFromEnvironment(false, algorithm, reference, input);
    }
}
