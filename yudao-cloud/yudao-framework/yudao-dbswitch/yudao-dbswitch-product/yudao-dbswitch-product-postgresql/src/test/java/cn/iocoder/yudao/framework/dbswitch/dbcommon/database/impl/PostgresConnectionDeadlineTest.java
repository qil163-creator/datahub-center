package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(15)
class PostgresConnectionDeadlineTest {
    @Test void acquisitionAndValidationTimeIsSubtractedFromTheJdbcBudget()throws Exception {
        var pool=pool();var connection=mock(Connection.class);
        when(pool.getConnection()).thenAnswer(call->{Thread.sleep(250);return connection;});
        try(var deadline=new PostgresConnectionDeadline(pool,1);var borrowed=deadline.open()) {
            var milliseconds=ArgumentCaptor.forClass(Integer.class);
            verify(connection).setNetworkTimeout(any(),milliseconds.capture());
            assertTrue(milliseconds.getValue()>0&&milliseconds.getValue()<=850);
            deadline.check();
        }
        verify(connection).close();
    }
    @Test void aLateBorrowCannotEvenChangeJdbcTransactionState()throws Exception {
        var pool=pool();var connection=mock(Connection.class);
        when(pool.getConnection()).thenAnswer(call->{Thread.sleep(1050);return connection;});
        try(var deadline=new PostgresConnectionDeadline(pool,1)) {
            var failure=assertThrows(IllegalStateException.class,()->JdbcBatchExecutor.execute(pool,context->{fail("No SQL may run after acquisition expires");return null;},null,deadline::observe));
            assertNotEquals(UnconfirmedWriteException.class,failure.getClass());
        }
        verify(connection,never()).getAutoCommit();verify(connection,never()).setAutoCommit(anyBoolean());
        verify(connection,never()).commit();verify(connection).close();
    }
    @Test void combinedPoolLimitsAreVerifiedWithoutMutatingASharedPool()throws Exception {
        var pool=pool();when(pool.getConnectionTimeout()).thenReturn(800L);when(pool.getValidationTimeout()).thenReturn(500L);
        assertThrows(IllegalArgumentException.class,()->new PostgresConnectionDeadline(pool,1));
        verify(pool,never()).getConnection();verify(pool,never()).setConnectionTimeout(anyLong());verify(pool,never()).setValidationTimeout(anyLong());
    }
    @Test void commitBeyondTheSameDeadlineRemainsUnknownAndNeverReplays()throws Exception {
        var pool=pool();var connection=mock(Connection.class);when(pool.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);
        doAnswer(call->{Thread.sleep(1050);return null;}).when(connection).commit();
        try(var deadline=new PostgresConnectionDeadline(pool,1)) {
            assertThrows(UnconfirmedWriteException.class,()->JdbcBatchExecutor.execute(pool,context->{context.markWriteAttempted();return 1;},
                    (borrowed,value)->deadline.check(),deadline::observe));
        }
        verify(connection,times(1)).commit();verify(connection,atLeastOnce()).abort(any());verify(connection).close();
    }
    private HikariDataSource pool() {
        var pool=mock(HikariDataSource.class);when(pool.getConnectionTimeout()).thenReturn(250L);when(pool.getValidationTimeout()).thenReturn(250L);when(pool.getInitializationFailTimeout()).thenReturn(-1L);return pool;
    }
}
