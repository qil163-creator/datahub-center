package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseTdengineImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.TdengineNativeCatalog;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * No database. Column reads must follow DESCRIBE through {@link TdengineNativeCatalog},
 * not JDBC getColumns or ins_columns.col_length.
 */
class TdengineColumnReadTest {
    @Test
    void describeOmitsTagsAndKeepsStoredIntAndVarchar() throws Exception {
        var database = new DatabaseTdengineImpl();
        Connection connection = mock(Connection.class);
        List<String> sql = new ArrayList<>();
        List<Statement> queried = new ArrayList<>();
        List<Field> fields = List.of(
                field("ts", "TIMESTAMP", 8, "", Types.TIMESTAMP, "TIMESTAMP", "java.sql.Timestamp", 23, 0, false),
                field("id", "INT", 4, "COMPOSITE KEY", Types.INTEGER, "INT", "java.lang.Integer", 10, 0, true),
                field("qty", "INT", 4, "", Types.INTEGER, "INT", "java.lang.Integer", 10, 0, true),
                field("b", "VARCHAR", 10, "", Types.VARCHAR, "BINARY", "[B", 10, 0, false),
                field("loc", "NCHAR", 8, "TAG", Types.NCHAR, "NCHAR", "java.lang.String", 8, 0, false),
                field("info", "JSON", 4095, "TAG", Types.VARCHAR, "JSON", "java.lang.String", 4095, 0, false));
        stub(connection, "app", "items", fields, null, sql, queried);

        List<ColumnDescription> columns = database.queryTableColumnMeta(connection, "app", "items");
        List<ColumnDescription> only = database.queryTableColumnMetaOnly(connection, "app", "items");

        assertEquals(List.of("ts", "id", "qty", "b"), names(columns));
        assertEquals(names(columns), names(only));
        assertEquals(names(columns), database.queryTableColumnName(connection, "app", "items"));
        assertNotSame(columns.getFirst(), only.getFirst());
        assertFalse(names(columns).contains("loc"));
        assertFalse(names(columns).contains("info"));
        assertStored(columns, "ts", "TIMESTAMP", true);
        assertEquals(MetadataValue.value(3), column(columns, "ts").getMetadataEvidence().numericScale());
        assertEquals(0, column(columns, "ts").getScaleSize());
        assertEquals(MetadataValue.absent(), column(columns, "ts").getMetadataEvidence().characterLength());
        assertStored(columns, "id", "INT", true);
        assertStored(columns, "qty", "INT", false);
        assertEquals(MetadataValue.absent(), column(columns, "qty").getMetadataEvidence().characterLength());
        assertEquals(MetadataValue.value(10), column(columns, "qty").getMetadataEvidence().numericPrecision());
        ColumnDescription binary = column(columns, "b");
        assertEquals("VARCHAR(10)", binary.getMetadataEvidence().nativeType().value());
        assertEquals("BINARY", binary.getFieldTypeName());
        // ins_columns.col_length for VARCHAR(10) is 12. DESCRIBE length stays 10.
        assertEquals(MetadataValue.value(10L), binary.getMetadataEvidence().characterLength());
        assertEquals("TDENGINE_CATALOG_V1", binary.getMetadataEvidence().provenance().method());
        assertEquals(ProductTypeEnum.TDENGINE, binary.getDbType());
        assertEquals(MetadataValue.absent(), binary.getMetadataEvidence().nullable());
        assertEquals(List.of(
                TdengineNativeCatalog.VERSION_SQL,
                TdengineNativeCatalog.DESCRIBE_SQL + "`app`.`items`",
                "SELECT * FROM `app`.`items` LIMIT 0",
                TdengineNativeCatalog.PRECISION_SQL + "'app'"), sql.subList(0, 4));
        assertEquals(12, sql.size());
        for (String text : sql) {
            String lower = text.toLowerCase(Locale.ROOT);
            assertFalse(lower.contains("ins_columns"), text);
            assertFalse(lower.contains("col_length"), text);
            assertFalse(text.contains("getColumns"), text);
        }
        for (Statement statement : queried) {
            verify(statement).setQueryTimeout(30);
            verify(statement).close();
            verify(statement, never()).execute(anyString());
        }
        assertNull(database.catalogName);
        verify(connection, never()).getMetaData();
        verify(connection, never()).close();
        verify(connection, never()).commit();
        verify(connection, never()).rollback();
    }

    @Test
    void missingVersionDoesNotFallBackToJdbcColumns() throws Exception {
        var database = new DatabaseTdengineImpl();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet empty = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(TdengineNativeCatalog.VERSION_SQL)).thenReturn(empty);
        when(empty.next()).thenReturn(false);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> database.queryTableColumnMeta(connection, "app", "items"));
        assertInstanceOf(SQLFeatureNotSupportedException.class, failure.getCause());
        assertEquals("0A000", ((SQLException) failure.getCause()).getSQLState());
        verify(statement).close();
        verify(connection, never()).getMetaData();
        verify(connection, never()).close();
        verify(connection, never()).commit();
    }

    @Test
    void concurrentReadsStayOnTheirOwnDescribe() throws Exception {
        var database = new DatabaseTdengineImpl();
        Connection first = mock(Connection.class);
        Connection second = mock(Connection.class);
        var entered = new CountDownLatch(2);
        stub(first, "first_db", "example", named("first_col"), entered, new ArrayList<>(), new ArrayList<>());
        stub(second, "second_db", "example", named("second_col"), entered, new ArrayList<>(), new ArrayList<>());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> database.queryTableColumnName(first, "first_db", "example"));
            var right = executor.submit(() -> database.queryTableColumnName(second, "second_db", "example"));
            assertEquals(List.of("ts", "first_col"), left.get(8, TimeUnit.SECONDS));
            assertEquals(List.of("ts", "second_col"), right.get(8, TimeUnit.SECONDS));
        }
        assertNull(database.catalogName);
        verify(first, never()).getMetaData();
        verify(second, never()).getMetaData();
        verify(first, never()).close();
        verify(second, never()).close();
        verify(first, never()).commit();
        verify(second, never()).commit();
    }

    @Test
    void existingColumnProbeUsesDescribeNamesAndSkipsTags() throws Exception {
        var database = new DatabaseTdengineImpl();
        Connection connection = mock(Connection.class);
        List<String> altered = new ArrayList<>();
        List<String> sql = new ArrayList<>();
        when(connection.createStatement()).thenAnswer(call -> {
            Statement statement = mock(Statement.class);
            when(statement.executeQuery(anyString())).thenAnswer(query -> {
                String text = query.getArgument(0);
                sql.add(text);
                return dispatch(text, "app", "items", named("qty"), null);
            });
            when(statement.execute(anyString())).thenAnswer(query -> {
                altered.add(query.getArgument(0));
                return false;
            });
            return statement;
        });
        database.addNoExistColumnsByTarget(connection, "app", "items", List.of(), List.of(column("qty"), column("note")));
        assertEquals(List.of("ALTER TABLE `app`.`items` ADD COLUMN `note` NCHAR(20)"), altered);
        assertTrue(sql.stream().anyMatch(text -> text.startsWith(TdengineNativeCatalog.DESCRIBE_SQL)));
        assertTrue(sql.stream().noneMatch(text -> text.toLowerCase(Locale.ROOT).contains("ins_columns")));
        verify(connection, never()).getMetaData();
        verify(connection, never()).close();
        verify(connection, never()).commit();
    }

    private static void stub(Connection connection, String schema, String table, List<Field> fields,
                             CountDownLatch entered, List<String> sql, List<Statement> queried) throws SQLException {
        when(connection.createStatement()).thenAnswer(call -> {
            Statement statement = mock(Statement.class);
            when(statement.executeQuery(anyString())).thenAnswer(query -> {
                queried.add(statement);
                String text = query.getArgument(0);
                sql.add(text);
                return dispatch(text, schema, table, fields, entered);
            });
            return statement;
        });
    }

    private static ResultSet dispatch(String sql, String schema, String table, List<Field> fields, CountDownLatch entered)
            throws SQLException {
        String qualified = "`" + schema + "`.`" + table + "`";
        if (sql.equals(TdengineNativeCatalog.VERSION_SQL)) {
            if (entered != null) {
                entered.countDown();
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Interrupted", failure);
                }
            }
            return oneRow("3.3.8.8");
        }
        if (sql.equals(TdengineNativeCatalog.DESCRIBE_SQL + qualified)) return describe(fields);
        if (sql.equals("SELECT * FROM " + qualified + " LIMIT 0")) return projection(fields);
        if (sql.equals(TdengineNativeCatalog.PRECISION_SQL + "'" + schema + "'")) return oneRow("ms");
        throw new SQLException("Unexpected SQL: " + sql);
    }

    private static ResultSet oneRow(String value) throws SQLException {
        ResultSet rows = mock(ResultSet.class);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn(value);
        return rows;
    }

    private static ResultSet describe(List<Field> fields) throws SQLException {
        ResultSet rows = mock(ResultSet.class);
        var cursor = new AtomicInteger(-1);
        when(rows.next()).thenAnswer(call -> cursor.incrementAndGet() < fields.size());
        when(rows.getString(1)).thenAnswer(call -> fields.get(cursor.get()).name());
        when(rows.getString(2)).thenAnswer(call -> fields.get(cursor.get()).describeType());
        when(rows.getInt(3)).thenAnswer(call -> fields.get(cursor.get()).length());
        when(rows.getString(4)).thenAnswer(call -> fields.get(cursor.get()).note());
        return rows;
    }

    private static ResultSet projection(List<Field> fields) throws SQLException {
        ResultSet rows = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(rows.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(fields.size());
        for (int index = 0; index < fields.size(); index++) {
            Field field = fields.get(index);
            int column = index + 1;
            when(metadata.getColumnName(column)).thenReturn(field.name());
            when(metadata.getColumnType(column)).thenReturn(field.jdbcType());
            when(metadata.getColumnTypeName(column)).thenReturn(field.jdbcName());
            when(metadata.getColumnClassName(column)).thenReturn(field.className());
            when(metadata.getPrecision(column)).thenReturn(field.precision());
            when(metadata.getScale(column)).thenReturn(field.scale());
            when(metadata.getColumnDisplaySize(column)).thenReturn(field.precision());
            when(metadata.isSigned(column)).thenReturn(field.signed());
            when(metadata.isAutoIncrement(column)).thenReturn(false);
            when(metadata.isNullable(column)).thenReturn(ResultSetMetaData.columnNullableUnknown);
        }
        return rows;
    }

    private static void assertStored(List<ColumnDescription> columns, String name, String nativeType, boolean key) {
        ColumnDescription column = column(columns, name);
        assertEquals(nativeType, column.getMetadataEvidence().nativeType().value());
        assertFalse(column.getMetadataEvidence().nativeType().value().equals("INTEGER"));
        assertEquals(key, column.isPk());
    }

    private static ColumnDescription column(List<ColumnDescription> columns, String name) {
        return columns.stream().filter(column -> name.equals(column.getFieldName())).findFirst().orElseThrow();
    }

    private static List<String> names(List<ColumnDescription> columns) {
        return columns.stream().map(ColumnDescription::getFieldName).toList();
    }

    private static List<Field> named(String name) {
        return List.of(
                field("ts", "TIMESTAMP", 8, "", Types.TIMESTAMP, "TIMESTAMP", "java.sql.Timestamp", 23, 0, false),
                field(name, "INT", 4, "", Types.INTEGER, "INT", "java.lang.Integer", 10, 0, true),
                field("loc", "NCHAR", 8, "TAG", Types.NCHAR, "NCHAR", "java.lang.String", 8, 0, false),
                field("info", "JSON", 4095, "TAG", Types.VARCHAR, "JSON", "java.lang.String", 4095, 0, false));
    }

    private static Field field(String name, String describeType, int length, String note, int jdbcType, String jdbcName,
                               String className, int precision, int scale, boolean signed) {
        return new Field(name, describeType, length, note, jdbcType, jdbcName, className, precision, scale, signed);
    }

    private static ColumnDescription column(String name) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        column.setNullable(true);
        column.setValType("note".equals(name) ? ColumnMetaData.TYPE_STRING : ColumnMetaData.TYPE_INTEGER);
        column.setLength("note".equals(name) ? 20 : 9);
        column.setPrecision(0);
        return column;
    }

    private record Field(String name, String describeType, int length, String note, int jdbcType, String jdbcName,
                         String className, int precision, int scale, boolean signed) { }
}
