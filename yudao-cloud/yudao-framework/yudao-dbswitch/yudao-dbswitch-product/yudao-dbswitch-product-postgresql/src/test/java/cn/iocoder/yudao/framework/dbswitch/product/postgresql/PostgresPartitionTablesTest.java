package cn.iocoder.yudao.framework.dbswitch.product.postgresql;

import cn.iocoder.yudao.framework.dbswitch.product.postgresql.PostgresPartitionTables.CatalogRow;
import cn.iocoder.yudao.framework.dbswitch.product.postgresql.PostgresPartitionTables.Kind;
import cn.iocoder.yudao.framework.dbswitch.product.postgresql.PostgresPartitionTables.Listed;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 伪造的目录行，不打开数据库连接。 */
class PostgresPartitionTablesTest {
    @Test void listsPartitionChildrenApartFromOrdinaryTablesAndRecordsTheParent() throws Exception {
        assertTrue(PostgresPartitionTables.SQL.contains("c.relispartition"));
        assertTrue(PostgresPartitionTables.SQL.contains("pg_inherits"));
        var listed = PostgresPartitionTables.classify(rows());
        assertEquals(List.of("orders", "orders_view", "orders_mat", "root", "branch", "leaf", "other_leaf", "base", "child", "shared", "foreign_data"),
                listed.stream().map(Listed::getTableName).toList());

        var orders = one(listed, "orders");
        assertEquals(Kind.TABLE, orders.kind());
        assertNull(orders.parentTable());
        assertTrue(orders.defaultSync());
        assertEquals("TABLE", orders.getTableType());
        assertEquals("sales", orders.getRemarks());

        var root = one(listed, "root");
        assertEquals(Kind.PARTITION_PARENT, root.kind());
        assertNull(root.parentSchema());
        assertNull(root.parentTable());
        assertFalse(root.defaultSync());

        var branch = one(listed, "branch");
        assertEquals(Kind.PARTITION_CHILD, branch.kind());
        assertEquals("public", branch.parentSchema());
        assertEquals("root", branch.parentTable());
        assertFalse(branch.defaultSync());
        assertEquals("quarters", branch.getRemarks());

        var leaf = one(listed, "leaf");
        assertEquals(Kind.PARTITION_CHILD, leaf.kind());
        assertEquals("public", leaf.parentSchema());
        assertEquals("branch", leaf.parentTable());
        assertFalse(leaf.defaultSync());
        assertFalse(leaf.isViewTable());

        var other = one(listed, "other_leaf");
        assertEquals(Kind.PARTITION_CHILD, other.kind());
        assertEquals("archive", other.parentSchema());
        assertEquals("root", other.parentTable());

        var inherited = one(listed, "child");
        assertEquals(Kind.INHERITED, inherited.kind());
        assertNull(inherited.parentTable());
        assertTrue(inherited.defaultSync());
        assertEquals("TABLE", inherited.getTableType());

        var shared = one(listed, "shared");
        assertEquals(Kind.INHERITED, shared.kind());
        assertTrue(shared.defaultSync());

        assertEquals(Kind.VIEW, one(listed, "orders_view").kind());
        assertEquals("VIEW", one(listed, "orders_view").getTableType());
        assertFalse(one(listed, "orders_view").defaultSync());
        assertEquals(Kind.VIEW, one(listed, "orders_mat").kind());
        assertFalse(one(listed, "orders_mat").defaultSync());
        assertEquals(Kind.TABLE, one(listed, "foreign_data").kind());
        assertTrue(one(listed, "foreign_data").defaultSync());

        assertEquals(List.of("orders", "base", "child", "shared", "foreign_data"),
                PostgresPartitionTables.defaultSyncTargets(listed).stream().map(Listed::getTableName).toList());
    }

    @Test void duplicateInheritRowsCollapseAndPartitionAncestryMustBeExactlyOneParent() throws Exception {
        var collapsed = PostgresPartitionTables.classify(List.of(
                row("public", "leaf", null, "r", true, "public", "branch"),
                row("public", "leaf", null, "r", true, "public", "branch")));
        assertEquals(1, collapsed.size());
        assertEquals("branch", collapsed.getFirst().parentTable());

        assertThrows(SQLException.class, () -> PostgresPartitionTables.classify(List.of(
                row("public", "leaf", null, "r", true, null, null))));
        assertThrows(SQLException.class, () -> PostgresPartitionTables.classify(List.of(
                row("public", "leaf", null, "r", true, "public", "root"),
                row("public", "leaf", null, "r", true, "public", "other"))));
        assertThrows(SQLException.class, () -> PostgresPartitionTables.classify(List.of(
                row("public", "leaf", null, "r", true, "public", "leaf"))));
    }

    @Test void readUsesFakeCatalogRowsAndDoesNotOpenADatabase() throws Exception {
        var data = List.of(
                new String[]{"public", "root", null, "p", "f", null, null},
                new String[]{"public", "leaf", "bound", "r", "t", "public", "root"});
        var cursor = new int[]{-1};
        var last = new int[]{1};
        ResultSet results = proxy(ResultSet.class, (method, args) -> switch (method) {
            case "next" -> ++cursor[0] < data.size();
            case "getString" -> cell(data, cursor[0], last, (Integer) args[0]);
            case "getBoolean" -> "t".equals(cell(data, cursor[0], last, (Integer) args[0]));
            case "wasNull" -> cell(data, cursor[0], last, last[0]) == null;
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method);
        });
        PreparedStatement statement = proxy(PreparedStatement.class, (method, args) -> switch (method) {
            case "executeQuery" -> results;
            case "setString", "setQueryTimeout", "setMaxRows", "close" -> null;
            default -> throw new UnsupportedOperationException(method);
        });
        var seen = new ArrayList<String>();
        Connection connection = proxy(Connection.class, (method, args) -> switch (method) {
            case "prepareStatement" -> { seen.add((String) args[0]); yield statement; }
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method);
        });
        var listed = PostgresPartitionTables.read(connection, "public");
        assertEquals(PostgresPartitionTables.SQL, seen.getFirst());
        assertEquals(Kind.PARTITION_PARENT, listed.get(0).kind());
        assertFalse(listed.get(0).defaultSync());
        assertEquals(Kind.PARTITION_CHILD, listed.get(1).kind());
        assertEquals("root", listed.get(1).parentTable());
        assertEquals("bound", listed.get(1).getRemarks());
        assertFalse(listed.get(1).defaultSync());
    }

    private static Listed one(List<Listed> listed, String name) {
        return listed.stream().filter(table -> name.equals(table.getTableName())).findFirst().orElseThrow();
    }

    private static List<CatalogRow> rows() {
        var rows = new ArrayList<CatalogRow>();
        rows.add(row("public", "orders", "sales", "r", false, null, null));
        rows.add(row("public", "orders_view", null, "v", false, null, null));
        rows.add(row("public", "orders_mat", null, "m", false, null, null));
        rows.add(row("public", "root", null, "p", false, null, null));
        rows.add(row("public", "branch", "quarters", "p", true, "public", "root"));
        rows.add(row("public", "leaf", null, "r", true, "public", "branch"));
        rows.add(row("public", "other_leaf", null, "r", true, "archive", "root"));
        rows.add(row("public", "base", null, "r", false, null, null));
        rows.add(row("public", "child", null, "r", false, "public", "base"));
        rows.add(row("public", "shared", null, "r", false, "public", "base"));
        rows.add(row("public", "shared", null, "r", false, "public", "second_parent"));
        rows.add(row("public", "foreign_data", null, "f", false, null, null));
        return rows;
    }

    private static CatalogRow row(String schema, String name, String remarks, String relkind, boolean partition,
                                  String parentSchema, String parentName) {
        return new CatalogRow(schema, name, remarks, relkind, partition, parentSchema, parentName);
    }

    private static String cell(List<String[]> data, int row, int[] last, int column) {
        last[0] = column;
        return data.get(row)[column - 1];
    }

    private interface Call { Object apply(String method, Object[] args) throws Throwable; }

    private static <T> T proxy(Class<T> type, Call call) {
        Object value = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (target, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(target);
                    case "equals" -> target == args[0];
                    default -> null;
                };
            }
            return call.apply(method.getName(), args);
        });
        return type.cast(value);
    }
}
