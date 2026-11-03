package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MysqlCatalogMetadataTest {
    private final DatabaseMysqlImpl database = new DatabaseMysqlImpl();

    @Test
    void catalogsComeFromDriverAndRetainFirstObservationOrder() throws Exception {
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        var rows = mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getCatalogs()).thenReturn(rows);
        when(rows.next()).thenReturn(true, true, true, true, false);
        when(rows.getString(1)).thenReturn("other", null, "current", "other");

        assertEquals(List.of("other", "current"), database.querySchemaList(connection));
        verify(metadata, never()).getURL();
        verify(rows).close();
        verify(connection, never()).setCatalog(any());
        verify(connection, never()).close();
    }

    @Test
    void failedCatalogEnumerationDoesNotReturnPartialResult() throws Exception {
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        var rows = mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getCatalogs()).thenReturn(rows);
        when(rows.next()).thenReturn(true).thenThrow(new SQLException("read failed"));
        when(rows.getString(1)).thenReturn("first");
        assertInstanceOf(SQLException.class, assertThrows(RuntimeException.class,
                () -> database.querySchemaList(connection)).getCause());
        verify(rows).close();
    }

    @Test
    void columnsUseRequestedCatalogAndAnEscapedTablePattern() throws Exception {
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        var rows = mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getSearchStringEscape()).thenReturn("\\");
        when(metadata.getColumns("catalog_%", null, "orders\\_\\%\\\\tail", null)).thenReturn(rows);
        when(rows.next()).thenReturn(true, true, true, false);
        when(rows.getString("COLUMN_NAME")).thenReturn("z", "a", "z");

        assertEquals(List.of("z", "a"), database.queryTableColumnName(connection, "catalog_%", "orders_%\\tail"));
        verify(rows).close();
        verify(connection, never()).getCatalog();
        verify(connection, never()).setCatalog(any());
        verify(connection, never()).close();
    }

    @Test
    void patternNamesFailBeforeMetadataReadWhenDriverHasNoEscape() throws Exception {
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getSearchStringEscape()).thenReturn("");
        var failure = assertThrows(RuntimeException.class,
                () -> database.queryTableColumnName(connection, "catalog", "orders_%"));
        assertInstanceOf(SQLFeatureNotSupportedException.class, failure.getCause());
        verify(metadata, never()).getColumns(any(), any(), any(), any());
    }

    @Test
    void legacyTableListBindsSchemaAndPreservesTableViewComments() throws Exception {
        var probe = new TableProbe("catalog'_%", "z_table", "a_view");
        var result = database.queryTableList(probe.connection, "catalog'_%");
        assertEquals(List.of("z_table", "a_view"), names(result));
        assertEquals(List.of("TABLE", "VIEW"), result.stream().map(TableDescription::getTableType).toList());
        assertEquals("table comment", result.getFirst().getRemarks());
        verify(probe.statement).setString(1, "catalog'_%");
        verify(probe.connection).prepareStatement(argThat(sql -> sql.contains("`TABLE_SCHEMA` = ?")
                && !sql.contains("catalog'_%")));
        probe.verifyClosed();
    }

    @Test
    void exactPickerSortsPhysicalNamesAndAppliesServerBounds() throws Exception {
        var probe = new TableProbe("catalog_%", "z_table", "a_view");
        var result = database.queryCatalogTableList(probe.connection, "catalog_%", 2, 7);
        assertEquals(List.of("a_view", "z_table"), names(result));
        assertEquals("VIEW", result.getFirst().getTableType());
        verify(probe.statement).setString(1, "catalog_%");
        verify(probe.statement).setQueryTimeout(7);
        verify(probe.statement).setMaxRows(3);
        probe.verifyClosed();
    }

    @Test
    void exactPickerNeverReturnsTruncatedResults() throws Exception {
        var probe = new TableProbe("catalog", "first", "second");
        assertThrows(IllegalArgumentException.class,
                () -> database.queryCatalogTableList(probe.connection, "catalog", 1, 5));
        probe.verifyClosed();
    }

    @Test
    void exactPickerRejectsAliasesAndDuplicateNames() throws Exception {
        var alias = new TableProbe("CATALOG", "first", "second");
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> database.queryCatalogTableList(alias.connection, "catalog", 10, 5));
        alias.verifyClosed();
        var duplicate = new TableProbe("catalog", "same", "same");
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> database.queryCatalogTableList(duplicate.connection, "catalog", 10, 5));
        duplicate.verifyClosed();
    }

    @Test
    void exactPickerValidatesBoundsAndDoesNotOptInOtherProducts() throws Exception {
        var connection = mock(Connection.class);
        for (int max : new int[]{0, -1, 10_001}) {
            assertThrows(IllegalArgumentException.class,
                    () -> database.queryCatalogTableList(connection, "catalog", max, 5));
        }
        for (int timeout : new int[]{0, -1, 61}) {
            assertThrows(IllegalArgumentException.class,
                    () -> database.queryCatalogTableList(connection, "catalog", 10, timeout));
        }
        var unverified = new DatabaseMysqlImpl() {
            @Override public ProductTypeEnum getDatabaseType() { return ProductTypeEnum.GBASE8A; }
        };
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> unverified.queryCatalogTableList(connection, "catalog", 10, 5));
        verifyNoInteractions(connection);
    }

    private static List<String> names(List<TableDescription> tables) {
        return tables.stream().map(TableDescription::getTableName).toList();
    }

    private static final class TableProbe {
        final Connection connection = mock(Connection.class);
        final PreparedStatement statement = mock(PreparedStatement.class);
        final ResultSet rows = mock(ResultSet.class);

        TableProbe(String schema, String first, String second) throws SQLException {
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeQuery()).thenReturn(rows);
            when(rows.next()).thenReturn(true, true, false);
            when(rows.getString("TABLE_SCHEMA")).thenReturn(schema);
            when(rows.getString("TABLE_NAME")).thenReturn(first, second);
            when(rows.getString("TABLE_TYPE")).thenReturn("BASE TABLE", "VIEW");
            when(rows.getString("TABLE_COMMENT")).thenReturn("table comment", "view comment");
        }

        void verifyClosed() throws SQLException {
            verify(rows).close();
            verify(statement).close();
            verify(connection, never()).close();
            verify(connection, never()).commit();
            verify(connection, never()).setCatalog(any());
        }
    }
}
