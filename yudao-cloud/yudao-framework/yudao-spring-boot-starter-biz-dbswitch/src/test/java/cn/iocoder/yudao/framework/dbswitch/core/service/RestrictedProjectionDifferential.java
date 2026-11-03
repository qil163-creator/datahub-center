package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #17 差异测试公共部分：加固后，全部越权输入必须在授权前被 {@link RestrictedSelectGuard} 拒绝，
 * 不触达数据库；同时合法查询在真实服务器的每种会话模式下都能执行且只返回授权列。
 * <p>
 * 表中所有行 secret 非空、name 固定为 'row'，用于在合法查询上取到真实行，并在万一有越权输入被放行时
 * 二次校验其不会经未授权谓词选出行或返回未授权列。
 */
final class RestrictedProjectionDifferential {
    static final List<String> PHYSICAL = List.of("id", "name", "secret");
    static final Set<String> ALLOWED = Set.of("id", "name");
    private static final String Q = "'";
    private static final String B = "\\";

    private RestrictedProjectionDifferential() { }

    /** 试图借引号、反斜杠、注释、双引号、变量或附加子句触达 secret 的输入；后缀为表名占位。 */
    static List<String> attacks(String table) {
        String base = "select id, name from " + table + " where name = ";
        return List.of(
                base + Q + "x" + B + Q + " or secret is not null --" + Q,
                base + Q + "x" + B + Q + " or secret is not null or name = " + Q,
                base + Q + "x" + B + B + Q + " or secret is not null or name = " + Q,
                base + Q + "x" + Q + Q + " or secret is not null or name = " + Q,
                base + "\"secret\"",
                base + Q + "x" + Q + " -- or secret is not null",
                base + Q + "x" + Q + " /* or secret is not null */",
                base + Q + "x" + Q + " # or secret is not null",
                "select id /*!50000 , secret */ from " + table,
                "select /*+ no_index(t) */ id from " + table,
                base + "@@version",
                base + "@x",
                base + "$$x$$",
                base + "E" + Q + "x" + B + Q + " or secret is not null --" + Q,
                "select id from " + table + " for share",
                "select id from " + table + " lock in share mode",
                "select sql_calc_found_rows id from " + table,
                "select high_priority id from " + table,
                "select straight_join id from " + table,
                "select id from " + table + " tablesample system(50)",
                "select id from " + table + " use index (primary)",
                base + Q + "x" + Q + " or secret is not null",
                "select id from " + table + " order by secret");
    }

    /** 只引用授权列的合法查询：必须被接受、在每种会话模式下执行且仅返回授权列，并能取到已插入行。 */
    static List<String> benignQueries(String table) {
        return List.of(
                "select id, name from " + table,
                "select id, name from " + table + " where name = " + Q + "row" + Q + " order by name desc",
                "select id from " + table + " order by id limit 1");
    }

    static Connection open(String url, String user) throws Exception {
        return DriverManager.getConnection(url, user, "");
    }

    /**
     * 返回被放行的越权输入条数（加固后应为 0）。内部同时断言：
     * 越权输入若被放行则在真实库上不得返回未授权列或经未授权谓词选出行；合法查询必须在每种模式下执行、
     * 仅返回授权列并能取到真实行，以证明加固没有过度拒绝。
     */
    static int run(ProductTypeEnum product, Connection connection, String schema, String table,
                   List<String> modes) throws Exception {
        return run(product, connection, schema, table, modes, null);
    }

    static int run(ProductTypeEnum product, Connection connection, String schema, String table,
                   List<String> modes, List<String> benign) throws Exception {
        String mark = product.getQuote();
        String qualified = mark == null || mark.isEmpty()
                ? schema + "." + table : mark + schema + mark + "." + table;
        boolean upperFold = product == ProductTypeEnum.ORACLE || product == ProductTypeEnum.DB2
                || product == ProductTypeEnum.DM || product == ProductTypeEnum.OSCAR;
        List<String> physical = upperFold ? List.of("ID", "NAME", "SECRET") : PHYSICAL;
        Set<String> allowed = upperFold ? Set.of("ID", "NAME") : ALLOWED;
        List<String> failures = new ArrayList<>();
        int accepted = 0;
        // 1) 越权输入：加固后应在授权前被拒绝；万一被放行，则在真实库上二次校验没有泄漏（防御性）。
        for (String attack : attacks(qualified)) {
            String sql;
            try {
                sql = RestrictedSelectGuard.restrictProjection(product, attack, schema, table, physical, allowed).sql();
            } catch (IllegalArgumentException | UnsupportedOperationException rejected) {
                continue;
            }
            accepted++;
            failures.add("应被拒绝却被接受：" + attack + " => " + sql);
            for (String mode : modes) {
                applyMode(connection, mode);
                try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
                    assertOnlyApprovedColumns(rows.getMetaData(), mode, attack, failures);
                    // 所有行 name='row' 且 secret 非空：带过滤的查询只有在未授权谓词生效时才会返回行。
                    if (rows.next() && attack.contains(" where ")) {
                        failures.add(mode + " | " + attack + " => 经 secret 谓词选出行：" + sql);
                    }
                } catch (SQLException syntax) {
                    // 服务器按不同模式解析失败即不执行任何谓词，保守失败可以接受。
                }
            }
        }
        // 2) 合法查询：必须被接受，并在每种模式下执行、只返回授权列、且能取到已插入行。
        int benignExecuted = 0;
        boolean sawRow = false;
        List<String> acceptedQueries = benign == null ? benignQueries(qualified) : benign;
        for (String query : acceptedQueries) {
            String sql = RestrictedSelectGuard.restrictProjection(product, query, schema, table, physical, allowed).sql();
            for (String mode : modes) {
                applyMode(connection, mode);
                try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
                    assertOnlyApprovedColumns(rows.getMetaData(), mode, "合法 " + query, failures);
                    if (rows.next()) {
                        sawRow = true;
                    }
                    benignExecuted++;
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertTrue(benignExecuted > 0, "没有合法查询被执行，差异测试未覆盖真实执行");
        assertTrue(sawRow, "合法查询未取到任何行，无法证明加固后仍能正常执行");
        return accepted;
    }

    private static void assertOnlyApprovedColumns(ResultSetMetaData meta, String mode, String label,
                                                  List<String> failures) throws SQLException {
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            if (!ALLOWED.contains(meta.getColumnLabel(i).toLowerCase(Locale.ROOT))) {
                failures.add(mode + " | " + label + " => 返回未授权列 " + meta.getColumnLabel(i));
            }
        }
    }

    private static void applyMode(Connection connection, String mode) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(mode);
        }
    }

    static String schemaName(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
