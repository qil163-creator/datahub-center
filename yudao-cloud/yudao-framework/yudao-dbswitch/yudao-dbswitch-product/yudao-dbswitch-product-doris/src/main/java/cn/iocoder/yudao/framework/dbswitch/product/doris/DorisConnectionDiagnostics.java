package cn.iocoder.yudao.framework.dbswitch.product.doris;

import cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic;
import cn.iocoder.yudao.framework.dbswitch.core.model.DorisTableDefinition;
import cn.iocoder.yudao.framework.dbswitch.core.model.TemporaryTableDiagnosticPlan;
import cn.iocoder.yudao.framework.dbswitch.core.util.DorisDdlBuilder;
import java.sql.*;
import java.util.Arrays;
import java.util.List;

/** Doris 4.0 原生诊断；临时表始终绑定调用方独占的同一会话，不调用跨连接搬数写入器。 */
public final class DorisConnectionDiagnostics {
    private DorisConnectionDiagnostics() { }

    public static TemporaryTableDiagnosticPlan temporaryTable(String name) {
        if (name == null || !name.matches("dbswitch_diag_[a-f0-9]{32}")) {
            throw new IllegalArgumentException("诊断表必须使用组件生成的随机名称");
        }
        var definition = DorisTableDefinition.builder().tableName(name)
                .columns(List.of(DorisTableDefinition.Column.builder().name("probe_id").typeName("bigint")
                                .nullable(false).build(),
                        DorisTableDefinition.Column.builder().name("probe_value").typeName("varchar")
                                .length(32).nullable(true).build()))
                .tableModel(DorisTableDefinition.MODEL_UNIQUE).keyColumns(List.of("probe_id"))
                .distributionColumns(List.of("probe_id")).bucketMode(DorisTableDefinition.BUCKET_FIXED)
                .bucketCount(1).replicationNum(1).enableMow(true).build();
        String sql = DorisDdlBuilder.buildCreateTable(definition)
                .replace("CREATE TABLE IF NOT EXISTS ", "CREATE TEMPORARY TABLE ");
        String relation = "`" + name + "`";
        // Doris 4.0.8 临时表明确拒绝 ALTER/RENAME；TRUNCATE 和两个 MERGE 分支已经真实验证。
        return new TemporaryTableDiagnosticPlan(sql, relation, null, "DROP TEMPORARY TABLE IF EXISTS " + relation,
                "INSERT INTO " + relation + " (probe_id,probe_value) VALUES (4,'upserted')",
                "MERGE INTO " + relation + " t USING (SELECT 5 probe_id,'merged' probe_value "
                        + "UNION ALL SELECT 6,'inserted') s ON t.probe_id=s.probe_id "
                        + "WHEN MATCHED THEN UPDATE SET probe_value=s.probe_value "
                        + "WHEN NOT MATCHED THEN INSERT (probe_id,probe_value) VALUES (s.probe_id,s.probe_value)",
                0, "TRUNCATE TABLE " + relation, null, null, null, "BEGIN", "ROLLBACK", true, true);
    }

    /** 不采用 JDBC 的 MySQL 5.7.99 协议版本，必须观察实际 FE 产品版本。 */
    public static void verifyVersion(Connection connection, int timeout) throws SQLException {
        bounds(timeout);
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(65);
            try (var rows = statement.executeQuery("SHOW FRONTENDS")) {
                int count = 0;
                while (rows.next()) {
                    if (++count > 64) {
                        throw new SQLException("Doris FE 版本目录超过诊断上限");
                    }
                    String version = rows.getString("Version");
                    if (version == null || !version.matches("doris-4\\.0\\.[0-9]+(?:[-+].*)?")) {
                        throw new SQLFeatureNotSupportedException("此 Doris 原生诊断仅验证 4.0 系列", "0A000");
                    }
                }
                if (count == 0) {
                    throw new SQLException("Doris FE 未返回产品版本");
                }
            }
        }
    }

    /** 仅检查 FE 原生日志开关和全局消费读取权限；不启用 binlog，不证明某张表的 binlog 或 RPC 可消费性。 */
    public static CdcReadinessDiagnostic cdcReadiness(Connection connection, int timeout) throws SQLException {
        verifyVersion(connection, timeout);
        boolean enabled;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            // Connector/J 将 ADMIN 分类为非查询，必须使用 execute 再读取实际结果集。
            if (!statement.execute("ADMIN SHOW FRONTEND CONFIG LIKE 'experimental_enable_feature_binlog'")) {
                throw new SQLException("Doris 原生 binlog 配置未返回结果集");
            }
            try (var rows = statement.getResultSet()) {
                if (!rows.next() || !"experimental_enable_feature_binlog".equals(rows.getString("Key"))) {
                    throw new SQLException("Doris 原生 binlog 开关缺失或不明确");
                }
                String value = rows.getString("Value");
                if (!("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) || rows.next()) {
                    throw new SQLException("Doris 原生 binlog 配置不是唯一布尔值");
                }
                enabled = Boolean.parseBoolean(value);
            }
        }
        boolean authorized = false;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(65);
            try (var rows = statement.executeQuery("SHOW GRANTS")) {
                int count = 0;
                while (rows.next()) {
                    if (++count > 64) {
                        throw new SQLException("Doris 授权目录超过诊断上限");
                    }
                    String privileges = rows.getString("GlobalPrivs");
                    if (privileges != null) {
                        authorized |= Arrays.stream(privileges.split(",")).map(String::trim)
                                .anyMatch(privilege -> privilege.equalsIgnoreCase("Admin_priv")
                                        || privilege.equalsIgnoreCase("Select_priv"));
                    }
                }
                if (count == 0) {
                    throw new SQLException("Doris 未返回当前账号授权记录");
                }
            }
        }
        return new CdcReadinessDiagnostic(enabled, authorized);
    }

    private static void bounds(int timeout) {
        if (timeout < 1 || timeout > 60) {
            throw new IllegalArgumentException("诊断超时必须在 1 到 60 秒之间");
        }
    }
}
