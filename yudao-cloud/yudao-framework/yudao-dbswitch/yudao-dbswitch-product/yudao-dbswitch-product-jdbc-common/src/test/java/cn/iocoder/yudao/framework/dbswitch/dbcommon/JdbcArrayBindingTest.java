package cn.iocoder.yudao.framework.dbswitch.dbcommon;

import cn.iocoder.yudao.framework.dbswitch.common.entity.JdbcArrayValue;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.sql.DataSource;
import java.sql.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcArrayBindingTest {
    @ParameterizedTest @ValueSource(strings={"success","bind-failure","release-failure"})
    void targetArraysAreReleasedAndNeverTurnProtocolUncertaintyIntoSuccessfulCommit(String scenario) throws Exception {
        var source=mock(DataSource.class);var connection=mock(Connection.class);var statement=mock(PreparedStatement.class);
        var first=mock(java.sql.Array.class);var second=mock(java.sql.Array.class);
        when(source.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(connection.createArrayOf(eq("text"),any(Object[].class))).thenReturn(first,second);
        when(statement.executeBatch()).thenReturn(new int[]{1,1});
        if(scenario.equals("bind-failure"))doThrow(new SQLException("sensitive-array-value")).when(statement).setArray(1,second);
        if(scenario.equals("release-failure"))doThrow(new IllegalStateException("sensitive-array-value")).when(first).free();
        var value=new JdbcArrayValue("text",Types.VARCHAR,List.of("中文"));
        Runnable execute=()->JdbcBatchExecutor.execute(source,"INSERT INTO fixture VALUES (?)",
                List.of(new Object[]{value},new Object[]{value}),new int[]{Types.ARRAY});
        if(scenario.equals("success")) {
            execute.run();var order=inOrder(statement,first,second,connection);
            order.verify(statement).executeBatch();order.verify(statement).close();
            order.verify(first).free();order.verify(second).free();order.verify(connection).commit();
        } else {
            var failure=assertThrows(RuntimeException.class,execute::run);
            assertNull(failure.getCause());assertFalse(failure.toString().contains("sensitive-array-value"));
            if(scenario.equals("bind-failure")) {
                assertFalse(failure instanceof UnconfirmedWriteException);verify(statement,never()).executeBatch();
            }else assertInstanceOf(UnconfirmedWriteException.class,failure);
            verify(first).free();verify(second).free();verify(connection,never()).commit();verify(connection).rollback();
        }
        verify(connection).close();
    }
}
