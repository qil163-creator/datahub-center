package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The upstream contract preserves JDBC observation order, without inventing ordinal evidence. */
class ColumnNameOrderTest {
    @Test void preservesObservedOrderAndFirstOccurrenceForDefaultMigrationProjection() {
        var closed = new AtomicBoolean();
        var observations = List.of("z_payload", "a_identity", "m_amount", "z_payload");
        var arguments = new ArrayList<Object>();
        var rows = rows(observations, closed);
        var connection = connection(rows, arguments);

        assertEquals(List.of("z_payload", "a_identity", "m_amount"),
                new Dialect().queryTableColumnName(connection, "APP", "ITEMS"));
        assertEquals(java.util.Arrays.asList(null, "APP", "ITEMS", null), arguments);
        assertTrue(closed.get(), "The metadata result belongs to this call");
    }

    @Test void emptyMetadataRemainsAnEmptyProjectionAndStillReleasesItsResult() {
        var closed = new AtomicBoolean();
        assertTrue(new Dialect().queryTableColumnName(connection(rows(List.of(), closed), new ArrayList<>()),
                "APP", "ABSENT").isEmpty());
        assertTrue(closed.get());
    }

    @Test void failedObservationDoesNotReturnAPartialProjection() {
        var closed = new AtomicBoolean();
        ResultSet rows = proxy(ResultSet.class, (owner, method, arguments) -> switch (method.getName()) {
            case "next" -> true;
            case "getString" -> throw new SQLException("metadata unavailable");
            case "close" -> { closed.set(true); yield null; }
            default -> throw new AssertionError(method.getName());
        });
        var failure = assertThrows(RuntimeException.class, () -> new Dialect().queryTableColumnName(
                connection(rows, new ArrayList<>()), "APP", "ITEMS"));
        assertInstanceOf(SQLException.class, failure.getCause());
        assertTrue(closed.get());
    }

    private static ResultSet rows(List<String> names, AtomicBoolean closed) {
        var index = new AtomicInteger(-1);
        return proxy(ResultSet.class, (owner, method, arguments) -> switch (method.getName()) {
            case "next" -> index.incrementAndGet() < names.size();
            case "getString" -> {
                assertEquals("COLUMN_NAME", arguments[0]);
                yield names.get(index.get());
            }
            case "close" -> { closed.set(true); yield null; }
            default -> throw new AssertionError(method.getName());
        });
    }

    private static Connection connection(ResultSet rows, List<Object> captured) {
        DatabaseMetaData metadata = proxy(DatabaseMetaData.class, (owner, method, arguments) -> {
            assertEquals("getColumns", method.getName());
            captured.addAll(java.util.Arrays.asList(arguments));
            return rows;
        });
        return proxy(Connection.class, (owner, method, arguments) -> {
            assertEquals("getMetaData", method.getName());
            return metadata;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> contract, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, handler);
    }

    private static final class Dialect extends AbstractDatabase {
        private Dialect() { super("java.lang.String"); }
        @Override public ProductTypeEnum getDatabaseType() { return ProductTypeEnum.DB2; }
        @Override public String getTableDDL(Connection connection, String schema, String table) {
            throw new UnsupportedOperationException("Not used by column-name observation");
        }
        @Override public String getViewDDL(Connection connection, String schema, String view) {
            throw new UnsupportedOperationException("Not used by column-name observation");
        }
        @Override public List<cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription>
                querySelectSqlColumnMeta(Connection connection, String sql) {
            throw new UnsupportedOperationException("Not used by column-name observation");
        }
        @Override protected String getDefaultValueSql(String schema, String table) { return null; }
        @Override protected String getTableFieldsQuerySQL(String schema, String table) { return "SELECT 1"; }
        @Override protected String getTestQuerySQL(String sql) { return sql; }
    }
}
