package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseClickhouseImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.ClickHouseNativeCatalog;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** No ClickHouse server. Proves the column read uses system.columns and does not hide authorization failures. */
class DatabaseClickhouseColumnMetaTest {
    @Test
    void readsSystemColumnsCommentAndDoesNotCallJdbcGetColumns() throws Exception {
        Connection connection = mock(Connection.class);
        Statement projection = mock(Statement.class);
        Statement version = mock(Statement.class);
        PreparedStatement catalog = mock(PreparedStatement.class);
        ResultSet projected = projectionOf("note", "n");
        ResultSet versions = version("25.8.33.6");
        ResultSet catalogResult = catalogRows();
        when(connection.createStatement()).thenReturn(projection, version);
        when(connection.prepareStatement(ClickHouseNativeCatalog.SQL)).thenReturn(catalog);
        when(projection.executeQuery(" SELECT * FROM `db`.`t` WHERE 1=2 LIMIT 0,1")).thenReturn(projected);
        when(version.executeQuery("SELECT version()")).thenReturn(versions);
        when(catalog.executeQuery()).thenReturn(catalogResult);

        List<ColumnDescription> columns = new DatabaseClickhouseImpl().queryTableColumnMeta(connection, "db", "t");

        assertEquals(List.of("note", "n"), columns.stream().map(ColumnDescription::getFieldName).toList());
        assertEquals("wave18", columns.get(0).getRemarks());
        assertEquals("String", columns.get(0).getMetadataEvidence().nativeType().value());
        assertFalse(columns.get(0).isNullable());
        assertEquals(9999999, columns.get(0).getDisplaySize());
        assertNull(columns.get(1).getRemarks());
        assertEquals("Nullable(String)", columns.get(1).getMetadataEvidence().nativeType().value());
        assertTrue(columns.get(1).isNullable());
        assertEquals(9999999, columns.get(1).getDisplaySize());
        assertTrue(ClickHouseNativeCatalog.SQL.contains("comment AS column_comment"));
        assertFalse(ClickHouseNativeCatalog.SQL.contains("GENERATION_EXPRESSION"));
        verify(catalog).setString(1, "db");
        verify(catalog).setString(2, "t");
        verify(connection, never()).getMetaData();
    }

    @Test
    void versionAuthorizationFailureIsNotAnEmptyColumnList() throws Exception {
        Statement version = mock(Statement.class);
        when(version.executeQuery("SELECT version()")).thenThrow(new SQLException("Authentication failed", "28000"));
        Statement projection = projectionStatement();
        Connection connection = connected(projection, version);
        SQLException failure = failureOf(connection);
        assertEquals("28000", failure.getSQLState());
        assertEquals("Authentication failed", failure.getMessage());
        verify(connection, never()).prepareStatement(ClickHouseNativeCatalog.SQL);
        verify(connection, never()).getMetaData();
    }

    @Test
    void catalogAuthorizationFailureIsNotAnEmptyColumnList() throws Exception {
        Statement version = mock(Statement.class);
        ResultSet versions = version("25.8.33.6");
        when(version.executeQuery("SELECT version()")).thenReturn(versions);
        Statement projection = projectionStatement();
        Connection connection = connected(projection, version);
        PreparedStatement catalog = mock(PreparedStatement.class);
        when(connection.prepareStatement(ClickHouseNativeCatalog.SQL)).thenReturn(catalog);
        when(catalog.executeQuery()).thenThrow(new SQLException("Authentication failed", "28000"));
        SQLException failure = failureOf(connection);
        assertEquals("28000", failure.getSQLState());
        assertEquals("Authentication failed", failure.getMessage());
        verify(connection, never()).getMetaData();
    }

    @Test
    void unverifiedVersionDoesNotFallBackToJdbcColumns() throws Exception {
        Statement version = mock(Statement.class);
        ResultSet versions = version("24.8.1.1");
        when(version.executeQuery("SELECT version()")).thenReturn(versions);
        Statement projection = projectionStatement();
        Connection connection = connected(projection, version);
        assertInstanceOf(SQLFeatureNotSupportedException.class, failureOf(connection));
        verify(connection, never()).prepareStatement(ClickHouseNativeCatalog.SQL);
        verify(connection, never()).getMetaData();
    }

    @Test
    void emptyOrPartialCatalogIsNotPublishedAsMissingColumns() throws Exception {
        Statement version = mock(Statement.class);
        ResultSet versions = version("25.8.33.6");
        when(version.executeQuery("SELECT version()")).thenReturn(versions);
        Statement projection = projectionStatement();
        Connection connection = connected(projection, version);
        PreparedStatement catalog = mock(PreparedStatement.class);
        ResultSet empty = mock(ResultSet.class);
        when(empty.next()).thenReturn(false);
        when(connection.prepareStatement(ClickHouseNativeCatalog.SQL)).thenReturn(catalog);
        when(catalog.executeQuery()).thenReturn(empty);
        SQLException failure = failureOf(connection);
        assertEquals("ClickHouse system.columns did not match the observed columns", failure.getMessage());
        Statement partialVersion = mock(Statement.class);
        ResultSet partialVersions = version("25.8.33.6");
        when(partialVersion.executeQuery("SELECT version()")).thenReturn(partialVersions);
        Connection partialConnection = connected(projectionStatement(), partialVersion);
        PreparedStatement partialCatalog = mock(PreparedStatement.class);
        ResultSet partialRows = oneColumn("note", "String", "wave18");
        when(partialConnection.prepareStatement(ClickHouseNativeCatalog.SQL)).thenReturn(partialCatalog);
        when(partialCatalog.executeQuery()).thenReturn(partialRows);
        assertEquals("ClickHouse system.columns did not match the observed columns", failureOf(partialConnection).getMessage());
        verify(connection, never()).getMetaData();
        verify(partialConnection, never()).getMetaData();
    }

    private static SQLException failureOf(Connection connection) {
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> new DatabaseClickhouseImpl().queryTableColumnMeta(connection, "db", "t"));
        return assertInstanceOf(SQLException.class, thrown.getCause());
    }

    private static Connection connected(Statement projection, Statement version) throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.createStatement()).thenReturn(projection, version);
        return connection;
    }

    private static Statement projectionStatement() throws Exception {
        Statement projection = mock(Statement.class);
        ResultSet projected = projectionOf("note", "n");
        when(projection.executeQuery(" SELECT * FROM `db`.`t` WHERE 1=2 LIMIT 0,1")).thenReturn(projected);
        return projection;
    }

    private static ResultSet version(String value) throws Exception {
        ResultSet rows = mock(ResultSet.class);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn(value);
        return rows;
    }

    private static ResultSet projectionOf(String first, String second) throws Exception {
        ResultSet rows = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(rows.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(2);
        when(metadata.getColumnLabel(1)).thenReturn(first);
        when(metadata.getColumnLabel(2)).thenReturn(second);
        when(metadata.getColumnType(1)).thenReturn(Types.VARCHAR);
        when(metadata.getColumnType(2)).thenReturn(Types.VARCHAR);
        when(metadata.getColumnTypeName(1)).thenReturn("String");
        when(metadata.getColumnTypeName(2)).thenReturn("Nullable(String)");
        when(metadata.getColumnClassName(1)).thenReturn(String.class.getName());
        when(metadata.getColumnClassName(2)).thenReturn(String.class.getName());
        when(metadata.getColumnDisplaySize(1)).thenReturn(0);
        when(metadata.getColumnDisplaySize(2)).thenReturn(0);
        when(metadata.getPrecision(1)).thenReturn(0);
        when(metadata.getPrecision(2)).thenReturn(0);
        when(metadata.getScale(1)).thenReturn(0);
        when(metadata.getScale(2)).thenReturn(0);
        when(metadata.isAutoIncrement(1)).thenReturn(false);
        when(metadata.isAutoIncrement(2)).thenReturn(false);
        when(metadata.isNullable(1)).thenReturn(ResultSetMetaData.columnNoNulls);
        when(metadata.isNullable(2)).thenReturn(ResultSetMetaData.columnNullable);
        when(metadata.isSigned(1)).thenReturn(false);
        when(metadata.isSigned(2)).thenReturn(false);
        return rows;
    }

    /** Catalog order is the reverse of the JDBC projection. Repeated reads of one row stay on that row. */
    private static ResultSet catalogRows() throws Exception {
        String[] names = {"n", "note"};
        String[] types = {"Nullable(String)", "String"};
        String[] comments = {null, "wave18"};
        var index = new java.util.concurrent.atomic.AtomicInteger(-1);
        ResultSet rows = mock(ResultSet.class);
        when(rows.next()).thenAnswer(call -> index.incrementAndGet() < names.length);
        when(rows.getString(anyString())).thenAnswer(call -> {
            String label = call.getArgument(0);
            int row = index.get();
            return switch (label) {
                case "column_name" -> names[row];
                case "native_type" -> types[row];
                case "default_kind", "default_expression" -> "";
                case "column_comment" -> comments[row];
                default -> null;
            };
        });
        return rows;
    }

    private static ResultSet oneColumn(String name, String type, String comment) throws Exception {
        var index = new java.util.concurrent.atomic.AtomicInteger(-1);
        ResultSet rows = mock(ResultSet.class);
        when(rows.next()).thenAnswer(call -> index.incrementAndGet() < 1);
        when(rows.getString(anyString())).thenAnswer(call -> switch (call.getArgument(0).toString()) {
            case "column_name" -> name;
            case "native_type" -> type;
            case "default_kind", "default_expression" -> "";
            case "column_comment" -> comment;
            default -> null;
        });
        return rows;
    }
}
