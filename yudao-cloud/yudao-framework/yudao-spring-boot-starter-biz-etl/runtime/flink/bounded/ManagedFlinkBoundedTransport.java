package cn.iocoder.yudao.framework.etl.flink.bounded;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.executor.support.*;

import java.util.*;
import java.util.function.Consumer;

/** 各有界连接器使用已存在的受管传输能力，Flink 负责真正的行调度和表写入。 */
public final class ManagedFlinkBoundedTransport implements EtlBoundedConnectorTransport {
    @Override public Set<String> kinds() { return Set.of("api", "soap", "file", "excel", "ftp", "redis", "odps", "hdfs", "mongodb"); }
    @Override public void read(String kind, JSONObject plan, Consumer<String> rows) throws Exception {
        switch (kind) {
            case "api" -> EtlHttpRows.read(plan, httpAddresses(), EtlManagedSecrets::readFromEnvironment,
                    plan.getStr("runtimeAuthenticatedContext"), rows);
            case "soap" -> EtlWebServiceRows.read(plan, httpAddresses(), EtlManagedSecrets::readFromEnvironment, rows);
            case "file", "excel" -> ManagedFlinkFileRows.read(plan, rows);
            case "ftp" -> EtlManagedFtp.read(plan, rows);
            case "redis" -> EtlManagedRedis.read(plan, EtlManagedSecrets::readFromEnvironment, rows);
            case "odps" -> EtlManagedOdps.read(plan, EtlManagedSecrets::readFromEnvironment, rows);
            case "hdfs" -> ManagedFlinkHdfs.read(plan, rows);
            case "mongodb" -> ManagedFlinkMongo.read(plan, rows);
            default -> throw new IllegalArgumentException("未知有界连接器");
        }
    }
    @Override public Writer writer(String kind, JSONObject plan) throws Exception { return ManagedFlinkSinks.open(kind, plan); }
    private static Set<String> httpAddresses() {
        String value = System.getenv("DATAHUB_ETL_HTTP_ALLOWLIST");
        return value == null ? Set.of() : Set.copyOf(JSONUtil.parseArray(value).toList(String.class));
    }
}
