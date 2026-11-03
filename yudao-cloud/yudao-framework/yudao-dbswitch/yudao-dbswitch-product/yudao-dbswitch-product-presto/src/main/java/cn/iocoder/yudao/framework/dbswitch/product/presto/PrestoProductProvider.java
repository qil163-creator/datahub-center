package cn.iocoder.yudao.framework.dbswitch.product.presto;

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

/** 只注册独立连接及分项诊断，不将信息模式可见性扩大为业务读写能力。 */
public final class PrestoProductProvider implements DbSwitchProductProvider {
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.PRESTO).connect(new ConnectionProbe() {
            @Override
            public void probe(ConnectionProbeRequest request, JdbcOpener opener) throws Exception {
                validate(request);
                try (var connection = opener.open()) { target(connection, request); }
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
                        JdbcConnectionProbe.fixedSql(connection, "SELECT current_user", (int) request.timeout().toSeconds());
                        checks.add(check("BASIC", "AUTH", "PASS", "会话已取得当前主体响应，不推断任意表授权", null, null, null));
                        stage = "TARGET";
                        target(connection, request);
                        connected = true;
                        if (request.database() != null && !request.database().isBlank()) {
                            checks.add(check("TARGET", "TARGET", "PASS", "已读取指定 catalog 或精确 schema 的信息模式目录", null, null, null));
                            checks.add(check("METADATA", "SCHEMATA", "PASS", "已取得目标命名空间目录响应，不代表任意表可读", null, null, null));
                        } else {
                            checks.add(check("TARGET", "TARGET", "SKIPPED", "未指定 catalog，仅检查默认会话", null, null, "NOT_CONFIGURED"));
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
                    checks.add(check("CLEANUP".equals(stage) ? "ADVANCED" : "AUTH".equals(stage) ? "BASIC" : "TARGET", stage,
                            "CLEANUP".equals(stage) ? "WARN" : "FAIL", "本项未取得完整响应，不返回厂商原始内容", state,
                            sql == null ? null : sql.getErrorCode(), null));
                }
                for (String key : List.of("TARGET", "SCHEMATA")) {
                    if (checks.stream().noneMatch(c -> key.equals(c.key()))) {
                        checks.add(check("TARGET".equals(key) ? "TARGET" : "METADATA", key, "SKIPPED",
                                "前置会话或目标检查未完成，未取得本项目录证据", null, null,
                                connected ? "NOT_CONFIGURED" : "PREREQUISITE_FAILED"));
                    }
                }
                checks.add(check("TARGET", "ACCOUNT_PRIVILEGES", "SKIPPED", "当前主体响应不等于连接器和表级授权目录", null, null, "UNVERIFIED"));
                checks.add(check("READ", "TABLE_READ", "SKIPPED", "未指定授权表，不读取连接器业务数据", null, null, "UNVERIFIED"));
                checks.add(check("WRITE", "WRITE", "SKIPPED", "没有显式写入授权，不执行连接器写入", null, null, "NOT_AUTHORIZED"));
                checks.add(check("METADATA", "COMMENTS", "SKIPPED", "表列注释依赖具体连接器及授权对象，尚未验证", null, null, "UNVERIFIED"));
                checks.add(check("ADVANCED", "CDC", "SKIPPED", "未验证连接器的变更读取能力", null, null, "UNVERIFIED"));
                boolean failed = checks.stream().anyMatch(c -> "FAIL".equals(c.status()));
                boolean warned = checks.stream().anyMatch(c -> "WARN".equals(c.status()));
                return new ConnectionDiagnosticResult(failed ? "FAIL" : warned ? "WARN" : "PASS", connected,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), checks);
            }
        }).build());
    }

    private static void validate(ConnectionProbeRequest request) {
        if (request.jdbcUrl() == null || !request.jdbcUrl().startsWith("jdbc:presto:")) {
            throw new IllegalArgumentException("缺少合法 Presto JDBC 地址");
        }
    }

    /** 仅使用内置 SQL 和精确参数；catalog 是经转义的物理标识符。 */
    private static void target(Connection connection, ConnectionProbeRequest request) throws SQLException {
        String catalog = request.database();
        if (request.schema() == null || request.schema().isBlank()) {
            String sql = "SELECT current_user";
            if (catalog != null && !catalog.isBlank()) {
                if (catalog.indexOf('\0') >= 0) { throw new IllegalArgumentException("Presto catalog 不合法"); }
                sql = "SELECT schema_name FROM \"" + catalog.replace("\"", "\"\"") + "\".information_schema.schemata LIMIT 1";
            }
            JdbcConnectionProbe.fixedSql(connection, sql, (int) request.timeout().toSeconds());
            return;
        }
        if (catalog == null || catalog.isBlank() || catalog.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("指定 Presto schema 时必须明确 catalog");
        }
        String sql = "SELECT schema_name FROM \"" + catalog.replace("\"", "\"\"") + "\".information_schema.schemata WHERE schema_name = ?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout((int) request.timeout().toSeconds());
            statement.setString(1, request.schema());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !request.schema().equals(rows.getString(1))) {
                    throw new SQLException("Presto 目标 schema 不存在或不可见", "3F000");
                }
            }
        }
    }

    private static ConnectionDiagnosticResult.Check check(String group, String key, String status, String message,
            String state, Integer vendor, String reason) {
        return new ConnectionDiagnosticResult.Check(group, key, key, status, 0, message, state, vendor, reason);
    }
}
