package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import java.util.*;

/** 从管理员受管目录复制表定义；凭据只留在引擎 Catalog，工作节点再核对真实 JDBC 选项与主键。 */
public final class EtlNativeFlinkJdbcSinkSql {
    private EtlNativeFlinkJdbcSinkSql() { }
    public static EtlNativeConnectorCompiler.TablePlan wrap(EtlNativeSqlCompiler.Table table, String jdbcUrl, List<String> primaryKeys, int batchSize) {
        if (table == null || table.product() != ProductTypeEnum.POSTGRESQL || jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")
                || primaryKeys == null || primaryKeys.isEmpty() || !table.columns().keySet().containsAll(primaryKeys)
                || primaryKeys.stream().distinct().count() != primaryKeys.size() || batchSize < 1 || batchSize > 10000)
            throw new IllegalArgumentException("受管实时增量输出需要 PostgreSQL JDBC、真实非空主键及有界批次");
        String alias = "dh_native_jdbc_upsert_" + DigestUtil.sha256Hex(String.join(".", table.path()) + ":" + batchSize).substring(0, 24);
        String sql = "CREATE TEMPORARY TABLE " + quote(alias) + " WITH ('connector'='datahub-verified-jdbc', 'datahub.url-sha256'='" + DigestUtil.sha256Hex(jdbcUrl)
                + "', 'datahub.schema'=" + literal(table.schema()) + ", 'datahub.table'=" + literal(table.physicalTable())
                + ", 'datahub.primary-keys'=" + literal(String.join(",", primaryKeys)) + ", 'sink.buffer-flush.max-rows'='" + batchSize
                + "') LIKE " + String.join(".", table.path().stream().map(EtlNativeFlinkJdbcSinkSql::quote).toList()) + " (INCLUDING ALL OVERWRITING OPTIONS);\n";
        var resolved = new EtlNativeSqlCompiler.Table(List.of(alias), table.columns(), table.product(), table.schema(), table.physicalTable(), false,
                new EtlNativeSqlCompiler.JdbcUpsertSink(primaryKeys));
        return new EtlNativeConnectorCompiler.TablePlan(sql, resolved);
    }
    private static String quote(String value) {
        if (value == null || value.isBlank() || value.length() > 128 || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("受管目录标识无效");
        return "`" + value.replace("`", "``") + "`";
    }
    private static String literal(String value) {
        if (value == null || value.isBlank() || value.length() > 512 || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("受管目录事实无效");
        return "'" + value.replace("'", "''") + "'";
    }
}
