package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OlapConnectionDiagnosticTest {
    private final DatabaseMysqlCompatibleOlapImpl database = new DatabaseMysqlCompatibleOlapImpl("unused") {
        @Override public ProductTypeEnum getDatabaseType() { return ProductTypeEnum.DORIS; }
    };

    @Test void validationExecutesSqlInsteadOfConnectorPingShortcut() throws Exception {
        var c=mock(Connection.class);var s=mock(Statement.class);var r=mock(ResultSet.class);
        when(c.createStatement()).thenReturn(s);when(s.executeQuery("SELECT 1")).thenReturn(r);when(r.next()).thenReturn(true);
        database.probeConnection(c,3);
        verify(s).executeQuery("SELECT 1");verify(c,never()).close();
    }
    @Test void catalogRejectsTruncationAndAmbiguousNames() throws Exception {
        for (String returnedSchema : new String[]{"warehouse", "other"}) {
            var c=mock(Connection.class);var s=mock(PreparedStatement.class);var r=mock(ResultSet.class);
            when(c.prepareStatement(anyString())).thenReturn(s);when(s.executeQuery()).thenReturn(r);
            when(r.next()).thenReturn(true,true,false);when(r.getString(1)).thenReturn(returnedSchema);
            when(r.getString(2)).thenReturn("first","second");
            assertThrows(SQLException.class,()->database.queryCatalogTableList(c,"warehouse",1,3));
            verify(c,never()).close();verify(r).close();
        }
    }
    @Test void catalogRejectsInvalidBoundsBeforeCreatingStatements() {
        var c=mock(Connection.class);
        assertThrows(IllegalArgumentException.class,()->database.queryCatalogTableList(c,"warehouse",0,3));
        assertThrows(IllegalArgumentException.class,()->database.queryCatalogTableList(c,"warehouse",10001,3));
        assertThrows(IllegalArgumentException.class,()->database.queryCatalogTableList(c,"warehouse",1,0));
        verifyNoInteractions(c);
    }

    @Test void namespaceActuallyEntersDatabaseWithoutClosingBorrowedConnection() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        database.probeNamespace(connection, "warehouse`east", 3);
        verify(statement).execute("USE `warehouse``east`");
        verify(statement).setQueryTimeout(3);
        verify(statement).close();
        verify(connection, never()).close();
    }

    @Test void namespaceFailureIsNotDowngradedToSuccess() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenThrow(new SQLException("denied", "42000"));
        assertThrows(SQLException.class, () -> database.probeNamespace(connection, "warehouse", 3));
    }

    @Test void accountReadsActualGrantResponse() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var result = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SHOW GRANTS")).thenReturn(result);
        when(result.next()).thenReturn(true);
        database.probeAccountPrivileges(connection, "warehouse", 3);
        verify(statement).execute("USE `warehouse`");
        verify(result).close();
    }

    @Test void missingGrantResponseFails() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SHOW GRANTS")).thenReturn(mock(ResultSet.class));
        assertThrows(SQLException.class, () -> database.probeAccountPrivileges(connection, "warehouse", 3));
    }

    @Test void propertiesUseExactBoundParametersAndOnlyCountComments() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var result = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true, false);
        when(result.getString(1)).thenReturn("table remark");
        when(result.getString(2)).thenReturn("column remark");
        var evidence = database.probeExtendedProperties(connection, "warehouse'", "events'", 3);
        assertEquals(1, evidence.columnsRead());
        assertTrue(evidence.tableCommentPresent());
        assertEquals(1, evidence.columnCommentsPresent());
        verify(statement).setString(1, "warehouse'");
        verify(statement).setString(2, "events'");
    }

    @Test void mysqlBinlogAndTemporaryTableSemanticsNeverLeakToOlap() {
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> database.probeCdcReadiness(mock(Connection.class), 3));
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> database.temporaryTableDiagnosticPlan("dbswitch_probe_01234567890123456789012345678901"));
    }
}
