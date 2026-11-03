package cn.iocoder.yudao.framework.dbswitch.dbwriter.olap;

import cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksJdbcSupport;
import cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksStreamLoadProfile;
import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.PartialBatchWriteException;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StarrocksNativeWriteOutcomeTest {
    private final DataSource source=mock(DataSource.class);
    private final Connection connection=mock(Connection.class);
    private final Statement control=mock(Statement.class);
    private final PreparedStatement statement=mock(PreparedStatement.class);
    private final List<StarrocksJdbcSupport.Column> columns=List.of(new StarrocksJdbcSupport.Column("id","bigint",Types.BIGINT,19,0,false,""));
    private final List<Object[]> rows=List.of(new Object[]{1L},new Object[]{2L},new Object[]{3L});
    private void setup()throws Exception{when(source.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);when(connection.createStatement()).thenReturn(control);when(connection.prepareStatement(anyString())).thenReturn(statement);}
    private long write(){return StarrocksJdbcSupport.write(source,"INSERT INTO `db`.`table` (`id`) VALUES (?)",rows,columns,5,new BatchLimits(10,1024,512,1,1000));}
    @Test void lostSecondRequestKeepsFirstReceiptAndNeverSubmitsThird()throws Exception {
        setup();when(statement.executeUpdate()).thenReturn(1).thenThrow(new SQLException("secret-row-marker","08006"));
        var failure=assertThrows(PartialBatchWriteException.class,this::write);
        assertEquals(1,failure.acknowledgedRows());assertEquals(1,failure.unknownRows());assertEquals(1,failure.rejectedRows());assertFalse(failure.terminationConfirmed());
        assertNull(failure.getCause());assertFalse(failure.toString().contains("secret-row-marker"));verify(statement,times(2)).executeUpdate();
        verify(connection,never()).commit();verify(connection,never()).rollback();verify(connection,never()).setAutoCommit(anyBoolean());
    }
    @Test void acknowledgementSurvivesStatementCloseFailure()throws Exception {
        setup();when(statement.executeUpdate()).thenReturn(1);doThrow(new SQLException("close marker")).when(statement).close();
        var failure=assertThrows(PartialBatchWriteException.class,this::write);
        assertEquals(1,failure.acknowledgedRows());assertEquals(0,failure.unknownRows());assertEquals(2,failure.rejectedRows());assertFalse(failure.terminationConfirmed());
        verify(statement,times(1)).executeUpdate();
    }
    @Test void rejectedStrictModeNeverSendsData()throws Exception {
        setup();when(control.execute(anyString())).thenThrow(new SQLException("strict denied"));
        assertThrows(IllegalStateException.class,this::write);verify(connection,never()).prepareStatement(anyString());verify(connection,never()).commit();
    }
    @Test void binaryHeaderGrammarIsClosedBoundedAndCollisionFree()throws Exception {
        var streamLoad=new StarrocksStreamLoadProfile();
        var headers=streamLoad.binaryColumnHeaders(List.of("__dbswitch_binary_0","value","normal"),Set.of("value"));
        assertTrue(headers.get("columns").contains("to_binary(__dbswitch_binary_1,'encode64')"));
        assertTrue(streamLoad.binaryColumnHeaders(List.of("__DBSWITCH_BINARY_0","value"),Set.of("value")).get("columns").contains("to_binary(__dbswitch_binary_1,'encode64')"));
        assertFalse(headers.get("columns").contains("to_binary(normal"));assertEquals("true",headers.get("strict_mode"));
        assertThrows(IllegalArgumentException.class,()->streamLoad.binaryColumnHeaders(List.of("bad\nname"),Set.of("bad\nname")));
        assertThrows(IllegalArgumentException.class,()->streamLoad.binaryColumnHeaders(List.of("value"),Set.of("not_present")));
        var huge=new ArrayList<String>();for(int i=0;i<1025;i++)huge.add("column_"+i);
        assertThrows(IllegalArgumentException.class,()->streamLoad.binaryColumnHeaders(huge,Set.of("column_0")));
    }
}
