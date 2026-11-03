package cn.iocoder.yudao.framework.dbswitch.product.phoenix;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Phoenix 系统目录是真实协议响应；命名空间映射没有证明前不宣布目标可用。 */
public final class PhoenixProductProvider implements DbSwitchProductProvider {
    @Override
    public Collection<DbSwitchProductDescriptor> products() {
        return List.of(DbSwitchProductDescriptor.builder(ProductTypeEnum.PHOENIX).connect(new ConnectionProbe() {
            @Override
            public void probe(ConnectionProbeRequest request, JdbcOpener opener) throws Exception {
                validate(request);
                try (var connection = opener.open()) {
                    if (explicitSchema(request)) {
                        throw new SQLFeatureNotSupportedException("Phoenix 指定 schema 的命名空间访问尚未验证，不能用 setSchema 或常量查询冒充成功", "0A000");
                    }
                    catalog(connection, request);
                }
            }

            @Override
            public ConnectionDiagnosticResult diagnose(ConnectionProbeRequest request, JdbcOpener opener) throws Exception {
                long started = System.nanoTime();
                var checks = new ArrayList<ConnectionDiagnosticResult.Check>();
                boolean connected = false;
                String stage = "AUTH";
                try {
                    validate(request);
                    try (var connection = opener.open()) {
                        catalog(connection, request);
                        checks.add(check("BASIC", "AUTH", "PASS", "已取得服务器系统目录响应，不推断任意 HBase 表权限", null, null, null));
                        checks.add(check("METADATA", "SYSTEM_CATALOG", "PASS", "已读取 SYSTEM.CATALOG 的有界目录响应", null, null, null));
                        if (explicitSchema(request)) {
                            checks.add(check("TARGET", "TARGET", "SKIPPED", "Phoenix schema/HBase namespace 映射及访问尚未验证", null, null, "UNVERIFIED"));
                        } else {
                            connected = true;
                            checks.add(check("TARGET", "TARGET", "SKIPPED", "未指定 schema，仅确认系统目录会话", null, null, "NOT_CONFIGURED"));
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
                    checks.add(check("CLEANUP".equals(stage) ? "ADVANCED" : "BASIC", stage,
                            "CLEANUP".equals(stage) ? "WARN" : "FAIL", "本项未取得完整响应，不返回厂商原始内容", state,
                            sql == null ? null : sql.getErrorCode(), null));
                }
                checks.add(check("TARGET", "ACCOUNT_PRIVILEGES", "SKIPPED", "系统目录可见不证明 HBase ACL 授权", null, null, "UNVERIFIED"));
                checks.add(check("READ", "TABLE_READ", "SKIPPED", "未指定授权表，不读取用户表数据", null, null, "UNVERIFIED"));
                checks.add(check("WRITE", "WRITE", "SKIPPED", "没有显式写入授权，不执行 UPSERT 或创建表", null, null, "NOT_AUTHORIZED"));
                checks.add(check("METADATA", "COMMENTS", "SKIPPED", "当前目录未提供已验证的原生 COMMENT 语义", null, null, "UNVERIFIED"));
                checks.add(check("ADVANCED", "CDC", "SKIPPED", "未验证 Phoenix CDC 版本、流定义和 ACL", null, null, "UNVERIFIED"));
                boolean failed = checks.stream().anyMatch(c -> "FAIL".equals(c.status()));
                boolean warned = checks.stream().anyMatch(c -> "WARN".equals(c.status())) || explicitSchema(request);
                return new ConnectionDiagnosticResult(failed ? "FAIL" : warned ? "WARN" : "PASS", connected,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), checks);
            }
        }).build());
    }

    private static void validate(ConnectionProbeRequest request) {
        if (request.jdbcUrl() == null || !request.jdbcUrl().startsWith("jdbc:phoenix:")) {
            throw new IllegalArgumentException("缺少合法 Phoenix JDBC 地址");
        }
    }

    private static boolean explicitSchema(ConnectionProbeRequest request) {
        return request.schema() != null && !request.schema().isBlank();
    }

    private static void catalog(Connection connection, ConnectionProbeRequest request) throws SQLException {
        JdbcConnectionProbe.fixedSql(connection, "SELECT TABLE_NAME FROM SYSTEM.CATALOG LIMIT 1", (int) request.timeout().toSeconds());
    }

    private static ConnectionDiagnosticResult.Check check(String group, String key, String status, String message,
            String state, Integer vendor, String reason) {
        return new ConnectionDiagnosticResult.Check(group, key, key, status, 0, message, state, vendor, reason);
    }
}
