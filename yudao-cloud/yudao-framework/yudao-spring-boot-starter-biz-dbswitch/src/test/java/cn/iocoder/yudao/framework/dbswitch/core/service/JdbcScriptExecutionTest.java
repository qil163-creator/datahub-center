package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.model.JdbcSelectResult;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.driver.JdbcConnectionProvider;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcScriptExecutionTest {
    @Test
    void callerOwnedConnectionRetainsItsTransactionAndLiteralSemicolons() throws Exception {
        AbstractDatabase database = database();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.getUpdateCount()).thenReturn(1);

        JdbcSelectResult result = database.executeBySql(connection, "postgresql",
                "insert into example(value) values ('left;right');", 5, null);

        assertTrue(result.getSuccess());
        assertEquals(JdbcSelectResult.TransactionOutcome.NOT_MANAGED, result.getTransactionOutcome());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(statement).execute(sql.capture());
        assertTrue(sql.getValue().contains("'left;right'"));
        verify(connection, never()).setAutoCommit(anyBoolean());
        verify(connection, never()).commit();
        verify(connection, never()).rollback();
        verify(connection, never()).close();
        verify(statement).close();
    }

    @Test
    void wholeScriptMustParseBeforeAnyStatementOrTransactionChange() {
        Connection connection = mock(Connection.class);
        assertThrows(IllegalArgumentException.class, () -> database().executeBySql(connection, "postgresql",
                "insert into example(value) values (1); nonsense unparseable;", 5, null));
        verifyNoInteractions(connection);
    }

    @Test
    void executionFailureStopsScriptWithoutRollingBackTheCaller() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenThrow(new SQLException("private JDBC detail"));

        JdbcSelectResult result = database().executeBySql(connection, "postgresql",
                "insert into example(value) values (1); insert into example(value) values (2);", 5, null);

        assertFalse(result.getSuccess());
        assertEquals(1, result.getResults().size());
        assertFalse(result.getErrorMsg().contains("private JDBC detail"));
        verify(connection, times(1)).createStatement();
        verify(connection, never()).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void ownedConnectionCommitsOnlyAfterSuccessfulExecution() throws Exception {
        Fixture fixture = new Fixture(true);
        JdbcSelectResult result = fixture.execute();
        assertTrue(result.getSuccess());
        assertEquals(JdbcSelectResult.TransactionOutcome.COMMITTED, result.getTransactionOutcome());
        var order = inOrder(fixture.connection);
        order.verify(fixture.connection).setAutoCommit(false);
        order.verify(fixture.connection).commit();
        order.verify(fixture.connection).close();
        verify(fixture.connection, never()).rollback();
    }

    @Test
    void ownedConnectionRollsBackAnExecutionFailure() throws Exception {
        Fixture fixture = new Fixture(false);
        JdbcSelectResult result = fixture.execute();
        assertFalse(result.getSuccess());
        assertEquals(JdbcSelectResult.TransactionOutcome.ROLLED_BACK, result.getTransactionOutcome());
        verify(fixture.connection).rollback();
        verify(fixture.connection, never()).commit();
        verify(fixture.connection).close();
    }

    @Test
    void commitExceptionIsUnknownAndDoesNotAttemptToProveRollback() throws Exception {
        Fixture fixture = new Fixture(true);
        doThrow(new SQLException("private JDBC detail")).when(fixture.connection).commit();
        JdbcSelectResult result = fixture.execute();
        assertFalse(result.getSuccess());
        assertEquals(JdbcSelectResult.TransactionOutcome.UNKNOWN, result.getTransactionOutcome());
        assertFalse(result.getErrorMsg().contains("private JDBC detail"));
        verify(fixture.connection, never()).rollback();
        verify(fixture.connection).close();
    }

    @Test
    void rollbackExceptionIsUnknown() throws Exception {
        Fixture fixture = new Fixture(false);
        doThrow(new SQLException("private JDBC detail")).when(fixture.connection).rollback();
        JdbcSelectResult result = fixture.execute();
        assertFalse(result.getSuccess());
        assertEquals(JdbcSelectResult.TransactionOutcome.UNKNOWN, result.getTransactionOutcome());
    }

    @Test
    void cleanupFailureDoesNotEraseConfirmedCommit() throws Exception {
        Fixture fixture = new Fixture(true);
        doThrow(new SQLException("private JDBC detail")).when(fixture.connection).close();
        JdbcSelectResult result = fixture.execute();
        assertFalse(result.getSuccess());
        assertEquals(JdbcSelectResult.TransactionOutcome.COMMITTED, result.getTransactionOutcome());
    }

    @Test
    void syntaxCheckUsesComponentModelAndDoesNotClaimDatabaseExplain() {
        JdbcConnectionProvider connections = mock(JdbcConnectionProvider.class);
        var service = new MetaDataByJdbcServiceImpl(ProductTypeEnum.POSTGRESQL,
                DbSwitchProductRegistry.discover(), connections);
        var results = service.explain("select ';' as separator; select 2;", "postgresql");
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(result -> result.isParseTrue() && !result.isExplainTrue()));
        assertEquals(2, results.get(1).getIndex());
        assertFalse(results.getFirst().toString().contains("separator"));
        assertFalse(service.explain("unparseable", "postgresql").getFirst().isParseTrue());
        verifyNoInteractions(connections);
    }

    @Test
    void ownedMysqlTransactionRejectsDdlBeforeOpeningConnection() {
        JdbcConnectionProvider connections = mock(JdbcConnectionProvider.class);
        var products = DbSwitchProductRegistry.discover();
        for (ProductTypeEnum product : new ProductTypeEnum[]{ProductTypeEnum.MYSQL, ProductTypeEnum.MARIADB}) {
            var service = new MetaDataByJdbcServiceImpl(product, products, connections);
            assertThrows(UnsupportedOperationException.class, () -> service.executeBySql(
                    "jdbc:test", product.name(), "test", "", "create table example(id int)", 1, 5, null));
        }
        verifyNoInteractions(connections);
    }

    private static AbstractDatabase database() {
        AbstractDatabase database = mock(AbstractDatabase.class, CALLS_REAL_METHODS);
        when(database.getDatabaseType()).thenReturn(ProductTypeEnum.POSTGRESQL);
        return database;
    }

    private static final class Fixture {
        private final Connection connection = mock(Connection.class);
        private final MetaDataByJdbcServiceImpl service;

        Fixture(boolean success) {
            AbstractDatabase database = mock(AbstractDatabase.class);
            JdbcSelectResult result = new JdbcSelectResult();
            result.setSuccess(success);
            when(database.executePlan(any(), any(SqlScriptPlan.class), anyList(), anyInt(), isNull(), any())).thenReturn(result);
            var products = mock(DbSwitchProductRegistry.class);
            when(products.metadata(ProductTypeEnum.POSTGRESQL)).thenReturn(database);
            service = new MetaDataByJdbcServiceImpl(ProductTypeEnum.POSTGRESQL, products, (url, user, password) -> connection);
        }

        JdbcSelectResult execute() {
            return service.executeBySql("jdbc:test", "postgresql", "test", "", "select 1", 1, 5, null);
        }

    }
}
