package cn.iocoder.yudao.framework.dbswitch.product.saphana;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** HANA 会话身份、真实 SET SCHEMA 与精确系统目录分别记录证据。 */
public final class SaphanaProductProvider implements DbSwitchProductProvider {
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.SAPHANA).connect(new ConnectionProbe() {
            @Override
            public void probe(ConnectionProbeRequest request, JdbcOpener opener) throws Exception {
                validate(request);
                try (var connection = opener.open()) {
                    target(connection, request);
                    JdbcConnectionProbe.fixedSql(connection, "SELECT CURRENT_USER FROM DUMMY", (int) request.timeout().toSeconds());
                }
            }

            @Override
            public ConnectionDiagnosticResult diagnose(ConnectionProbeRequest request, JdbcOpener opener) throws Exception {
                long started = System.nanoTime();
                var checks = new ArrayList<ConnectionDiagnosticResult.Check>();
                String stage = "AUTH";
                boolean connected = false;
                try {
                    validate(request);
                    try (var connection = opener.open()) {
                        JdbcConnectionProbe.fixedSql(connection, "SELECT CURRENT_USER FROM DUMMY", (int) request.timeout().toSeconds());
                        checks.add(check("BASIC", "AUTH", "PASS", "已取得服务器当前主体响应，不推断表级权限", null, null, null));
                        stage = "TARGET";
                        target(connection, request);
                        connected = true;
                        if (request.schema() != null && !request.schema().isBlank()) {
                            checks.add(check("TARGET", "TARGET", "PASS", "指定 schema 已通过真实 SET SCHEMA 验证", null, null, null));
                            stage = "SCHEMA_METADATA";
                            try (var statement = connection.prepareStatement("SELECT SCHEMA_NAME FROM SYS.SCHEMAS WHERE SCHEMA_NAME = ?")) {
                                statement.setQueryTimeout((int) request.timeout().toSeconds()); statement.setString(1, request.schema());
                                try (var rows = statement.executeQuery()) {
                                    if (!rows.next() || !request.schema().equals(rows.getString(1))) {
                                        throw new SQLException("目标 schema 目录不可见", "3F000");
                                    }
                                }
                            }
                            checks.add(check("METADATA", "SCHEMA_METADATA", "PASS", "已从精确系统目录读取目标 schema", null, null, null));
                        } else {
                            checks.add(check("TARGET", "TARGET", "SKIPPED", "未指定 schema，仅确认默认数据库会话", null, null, "NOT_CONFIGURED"));
                        }
                        stage = "CLEANUP";
                    }
                } catch (Exception failure) {
                    if (checks.isEmpty() && (failure instanceof ClassNotFoundException || failure instanceof IllegalArgumentException)) {
                        throw failure;
                    }
                    SQLException sql = failure instanceof SQLException value ? value : null;
                    String state = sql == null ? null : sql.getSQLState();
                    if (state != null && !state.matches("[A-Z0-9]{5}")) { state = null; }
                    checks.add(check("CLEANUP".equals(stage) ? "ADVANCED" : "SCHEMA_METADATA".equals(stage) ? "METADATA" : "AUTH".equals(stage) ? "BASIC" : "TARGET",
                            stage, "CLEANUP".equals(stage) ? "WARN" : "FAIL", "本项未取得完整响应，不返回厂商原始内容", state,
                            sql == null ? null : sql.getErrorCode(), null));
                }
                for (String key : List.of("TARGET", "SCHEMA_METADATA")) {
                    if (checks.stream().noneMatch(c -> key.equals(c.key()))) {
                        checks.add(check("TARGET".equals(key) ? "TARGET" : "METADATA", key, "SKIPPED",
                                "前置会话或目标检查未完成，未取得本项目录证据", null, null,
                                connected ? "NOT_CONFIGURED" : "PREREQUISITE_FAILED"));
                    }
                }
                checks.add(check("TARGET", "ACCOUNT_PRIVILEGES", "SKIPPED", "当前主体响应不证明完整有效授权目录", null, null, "UNVERIFIED"));
                checks.add(check("READ", "TABLE_READ", "SKIPPED", "未指定授权表，不读取业务数据", null, null, "UNVERIFIED"));
                checks.add(check("WRITE", "WRITE", "SKIPPED", "没有显式写入授权，不创建诊断对象", null, null, "NOT_AUTHORIZED"));
                checks.add(check("METADATA", "COMMENTS", "SKIPPED", "未指定授权表，尚未读取表列 COMMENT", null, null, "UNVERIFIED"));
                checks.add(check("ADVANCED", "CDC", "SKIPPED", "未验证日志配置及变更读取授权", null, null, "UNVERIFIED"));
                boolean failed = checks.stream().anyMatch(c -> "FAIL".equals(c.status()));
                boolean warned = checks.stream().anyMatch(c -> "WARN".equals(c.status()));
                return new ConnectionDiagnosticResult(failed ? "FAIL" : warned ? "WARN" : "PASS", connected,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), checks);
            }
        }).build());
    }

    private static void validate(ConnectionProbeRequest request) {
        if (request.jdbcUrl() == null || !request.jdbcUrl().startsWith("jdbc:sap:")) {
            throw new IllegalArgumentException("缺少合法 SAP HANA JDBC 地址");
        }
    }

    private static void target(Connection connection, ConnectionProbeRequest request) throws SQLException {
        if (request.schema() != null && !request.schema().isBlank()) {
            if (request.schema().indexOf('\0') >= 0) { throw new IllegalArgumentException("SAP HANA schema 不合法"); }
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout((int) request.timeout().toSeconds());
                statement.execute("SET SCHEMA \"" + request.schema().replace("\"", "\"\"") + "\"");
            }
        }
    }

    private static ConnectionDiagnosticResult.Check check(String group, String key, String status, String message,
            String state, Integer vendor, String reason) {
        return new ConnectionDiagnosticResult.Check(group, key, key, status, 0, message, state, vendor, reason);
    }
}
