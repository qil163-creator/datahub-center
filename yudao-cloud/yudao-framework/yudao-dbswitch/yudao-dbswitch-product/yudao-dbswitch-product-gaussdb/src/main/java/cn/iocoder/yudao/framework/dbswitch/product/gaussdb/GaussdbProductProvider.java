package cn.iocoder.yudao.framework.dbswitch.product.gaussdb;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import cn.iocoder.yudao.framework.dbswitch.spi.*;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** GaussDB 使用独立测连 SPI，不借诊断之名注册未实现的元数据或写入工厂。 */
public final class GaussdbProductProvider implements DbSwitchProductProvider {
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.GAUSSDB).connect(new ReadProbe()).build());
    }

    private static final class ReadProbe implements ConnectionProbe {
        @Override
        public void probe(ConnectionProbeRequest request, JdbcOpener opener) throws Exception {
            JdbcConnectionProbe.standard().probe(request, opener);
        }

        @Override
        public ConnectionDiagnosticResult diagnose(ConnectionProbeRequest request, JdbcOpener opener) throws Exception {
            if (request.product() != ProductTypeEnum.GAUSSDB || request.jdbcUrl() == null || !request.jdbcUrl().startsWith("jdbc:")) throw new IllegalArgumentException("GaussDB 产品和 JDBC 配置不匹配");
            long started = System.nanoTime();
            var checks = new ArrayList<ConnectionDiagnosticResult.Check>();
            int timeout = (int) request.timeout().toSeconds();
            try (var connection = opener.open()) {
                JdbcConnectionProbe.fixedSql(connection, ProductTypeEnum.GAUSSDB.getTestSql(), timeout);
                checks.add(check("BASIC", "connection", "PASS", "已取得 GaussDB 实际服务器响应", null));
                var target = new String[] {request.schema()};
                // 默认 schema 的解析也是目标检查，失败不得丢失已经取得的服务器响应证据。
                observe(checks, "TARGET", "SCHEMA", () -> {
                    if (target[0] == null || target[0].isBlank()) {
                        try (var statement = connection.prepareStatement("SELECT current_schema()")) {
                            statement.setQueryTimeout(timeout); statement.setMaxRows(2);
                            try (var rows = statement.executeQuery()) {
                                if (!rows.next() || (target[0] = rows.getString(1)) == null || target[0].isBlank()) {
                                    throw new SQLException("GaussDB 当前 schema 证据缺失");
                                }
                            }
                        }
                    }
                    try (var statement = connection.prepareStatement("SELECT pg_catalog.has_schema_privilege(current_user,n.oid,'USAGE') FROM pg_catalog.pg_namespace n WHERE n.nspname=?")) {
                        statement.setString(1, target[0]); statement.setQueryTimeout(timeout); statement.setMaxRows(2);
                        try (var rows = statement.executeQuery()) {
                            if (!rows.next()) throw new SQLException("命名空间不存在", "3F000");
                            if (!rows.getBoolean(1) || rows.wasNull()) throw new SQLException("命名空间访问被拒绝", "42501");
                        }
                    }
                });
                if (schemaVerified(checks)) {
                    observe(checks, "METADATA", "TABLES", () -> {
                        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM (SELECT 1 FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relkind='r' LIMIT 101) diagnostic_tables")) {
                            statement.setString(1, target[0]); statement.setQueryTimeout(timeout); statement.setMaxRows(2);
                            try (var rows = statement.executeQuery()) {
                                if (!rows.next()) throw new SQLException("目录无响应");
                                if (rows.getInt(1) > 100) throw new SQLException("目录超出上限，拒绝截断", "54000");
                            }
                        }
                    });
                    observe(checks, "TARGET", "PRIVILEGES", () -> {
                        try (var statement = connection.prepareStatement("SELECT rolname FROM pg_catalog.pg_roles WHERE rolname=current_user")) {
                            statement.setQueryTimeout(timeout); statement.setMaxRows(2);
                            try (var rows = statement.executeQuery()) {
                                if (!rows.next() || rows.getString(1) == null) throw new SQLException("当前角色目录无响应");
                            }
                        }
                    });
                    extendedProperties(connection,target[0],timeout,checks);
                } else {
                    checks.add(new ConnectionDiagnosticResult.Check("METADATA", "TABLES", "TABLES", "SKIPPED", 0,
                            "目标 schema 未确认，未读取表目录", null, null, "PREREQUISITE_FAILED"));
                    checks.add(new ConnectionDiagnosticResult.Check("TARGET", "PRIVILEGES", "PRIVILEGES", "SKIPPED", 0,
                            "目标 schema 未确认，未读取账号目录", null, null, "PREREQUISITE_FAILED"));
                }
            }
            boolean success = checks.stream().noneMatch(c -> "FAIL".equals(c.status()));
            return new ConnectionDiagnosticResult(success ? "PASS" : "FAIL", schemaVerified(checks),
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), checks);
        }
    }

    /** 原生目录内采样一张精确基本表，不将注释存在性或目录可见性当作 schema 授权。 */
    private static void extendedProperties(java.sql.Connection connection,String namespace,int timeout,
            List<ConnectionDiagnosticResult.Check> checks) {
        String sql="SELECT 1,CASE WHEN pg_catalog.obj_description(c.oid,'pg_class') IS NULL THEN 0 ELSE 1 END,"
                + "COUNT(a.attnum),COUNT(pg_catalog.col_description(c.oid,a.attnum)) "
                + "FROM (SELECT c.oid FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                + "WHERE n.nspname=? AND c.relkind='r' ORDER BY c.relname LIMIT 1) sample "
                + "JOIN pg_catalog.pg_class c ON c.oid=sample.oid "
                + "LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped GROUP BY c.oid";
        try (var statement=connection.prepareStatement(sql)) {
            statement.setString(1,namespace); statement.setQueryTimeout(timeout); statement.setMaxRows(2);
            try (var rows=statement.executeQuery()) {
                if (!rows.next()) {
                    checks.add(new ConnectionDiagnosticResult.Check("METADATA","EXTENDED_PROPERTIES","表列注释目录","SKIPPED",0,"GaussDB 没有可见基本表可供注释采样",null,null,"PREREQUISITE_FAILED"));
                    return;
                }
                int tableComments=rows.getInt(2); int columns=rows.getInt(3); int comments=rows.getInt(4);
                if (rows.wasNull() || tableComments<0 || tableComments>1 || columns<1 || comments<0 || comments>columns)
                    throw new SQLException("GaussDB 注释目录证据不完整");
                checks.add(check("METADATA","EXTENDED_PROPERTIES","PASS","GaussDB 已读取基本表和列注释目录；无注释也是有效结果，不证明业务行 SELECT 权限",null));
            }
        } catch (SQLException failure) {
            checks.add(check("METADATA","EXTENDED_PROPERTIES","FAIL","GaussDB 表列注释目录读取未取得有效证据",failure.getSQLState()));
        }
    }

    /** 目标使用权限必须实际成功；角色或目录的附加失败不推翻该证据。 */
    private static boolean schemaVerified(List<ConnectionDiagnosticResult.Check> checks) {
        return checks.stream().anyMatch(c -> "TARGET".equals(c.group()) && "SCHEMA".equals(c.key()) && "PASS".equals(c.status()));
    }

    @FunctionalInterface
    private interface ReadOperation { void run() throws SQLException; }

    private static void observe(List<ConnectionDiagnosticResult.Check> checks, String group, String key, ReadOperation operation) {
        try {
            operation.run();
            checks.add(check(group, key, "PASS", "GaussDB 固定只读目录检查已实际执行；不证明写入或 CDC 能力", null));
        } catch (SQLException failure) {
            checks.add(check(group, key, "FAIL", "GaussDB 只读目录或权限检查未取得有效证据", failure.getSQLState()));
        }
    }

    private static ConnectionDiagnosticResult.Check check(String group, String key, String status, String message, String state) {
        String safeState = state != null && state.matches("[A-Z0-9]{5}") ? state : null;
        return new ConnectionDiagnosticResult.Check(group, key, key, status, 0, message, safeState, null);
    }
}
