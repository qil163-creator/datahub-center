package cn.iocoder.yudao.framework.quality.core.audit;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseStarrocksImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AuditStarrocksKeySnapshotTest {
    private final Connection c=mock(Connection.class);
    private final Statement s=mock(Statement.class);
    private final AuditRuleContext context=new AuditRuleContext(spy(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover()));
    private final AuditSqlGenerator generator=new AuditSqlRegistry().getGenerator("NOT_NULL_VALIDATION");
    private ResultSet rows(boolean nativeWitness, int secondType) throws Exception {
        var r=mock(ResultSet.class); var m=mock(ResultSetMetaData.class); when(r.getMetaData()).thenReturn(m);
        when(r.next()).thenReturn(true,true,false); when(r.getLong(1)).thenReturn(1L); when(r.getLong(2)).thenReturn(0L);
        when(m.isSigned(anyInt())).thenReturn(true);
        when(m.getColumnType(4)).thenReturn(nativeWitness?Types.INTEGER:Types.BIGINT);
        when(m.getColumnTypeName(4)).thenReturn(nativeWitness?"INT":"BIGINT");
        when(m.getColumnType(6)).thenReturn(nativeWitness?Types.INTEGER:secondType);
        when(m.getColumnTypeName(6)).thenReturn(nativeWitness?"INT":secondType==Types.BIGINT?"BIGINT":"VARCHAR");
        when(r.getInt(4)).thenReturn(Types.BIGINT); when(r.getInt(6)).thenReturn(secondType);
        when(r.getBytes(3)).thenReturn(bytes("2"),bytes("1"));
        when(r.getBytes(5)).thenReturn(bytes("ab"),bytes("ba"));
        return r;
    }
    private AuditKeySnapshot read(int max) throws Exception {
        when(c.createStatement()).thenReturn(s);
        context.setProductType(ProductTypeEnum.STARROCKS);context.setTableName("sample");context.setColumn("amount");context.setConfig(Map.of());
        var database=mock(DatabaseStarrocksImpl.class,CALLS_REAL_METHODS);
        {
            doReturn(database).when(context.products()).metadata(ProductTypeEnum.STARROCKS);
            return readComposite(c,generator,context,List.of("part","id"),max,5);
        }
    }
    @Test void rowWitnessHasAnIndependentGoldenFrameAndDiscardsOldCounts() throws Exception {
        var first=rows(false,Types.VARCHAR);when(first.getLong(1)).thenReturn(7L);
        var second=rows(true,Types.VARCHAR);when(s.executeQuery(anyString())).thenReturn(first,second);
        var read=read(2);assertTrue(read.complete());assertEquals(2,read.total());
        assertEquals(STARROCKS_TYPED_COMPOSITE_ALGORITHM,read.algorithm());
        assertEquals("8e1932cc1e30d22763d1f7cdc6d66c88beabef78fba4ef8d78f80fa85d644c83",read.keyHash());
        verify(first,never()).getBytes(anyInt());verify(first).close();verify(second).close();ownership(2);
    }
    @Test void originalIntegerGoldenFrameDoesNotChange() throws Exception {
        var r=rows(false,Types.BIGINT);when(r.getBytes(5)).thenReturn(bytes("12"),bytes("11"));
        when(s.executeQuery(anyString())).thenReturn(r);
        var read=read(2);assertTrue(read.complete());assertEquals(STARROCKS_COMPOSITE_ALGORITHM,read.algorithm());
        assertEquals("18df28f53467d7609fc4766b5cb6b79656ba79faeec6b1ae88e77335b0c44465",read.keyHash());
        assertFalse(read.sql().contains("typeof("));ownership(1);
    }
    @Test void changedRowWitnessFailsInsteadOfMixingTypes() throws Exception {
        var first=rows(false,Types.VARCHAR);var second=rows(true,Types.VARCHAR);
        when(second.getInt(6)).thenReturn(Types.VARCHAR,Types.INTEGER);
        when(s.executeQuery(anyString())).thenReturn(first,second);
        assertThrows(SQLException.class,()->read(2));verify(second).close();ownership(2);
    }
    @Test void unknownWitnessNeverReadsPayload() throws Exception {
        var first=rows(false,Types.VARCHAR);var second=rows(true,0);
        when(s.executeQuery(anyString())).thenReturn(first,second);
        assertEquals("KEY_TYPE_UNSUPPORTED",read(2).reasonCode());verify(second,never()).getBytes(anyInt());ownership(2);
    }
    @Test void invalidUtf8CannotBecomeAReplacementCharacterIdentity() throws Exception {
        var first=rows(false,Types.VARCHAR);var second=rows(true,Types.VARCHAR);
        when(second.getBytes(5)).thenReturn(new byte[]{(byte)255});
        when(s.executeQuery(anyString())).thenReturn(first,second);
        assertThrows(SQLException.class,()->read(2));verify(second).close();ownership(2);
    }
    @Test void limitsNeverTriggerAnotherScan() throws Exception {
        var first=rows(false,Types.VARCHAR); when(s.executeQuery(anyString())).thenReturn(first);
        assertEquals("KEY_LIMIT_EXCEEDED",read(1).reasonCode());ownership(1);
    }
    @Test void fallbackTimeoutCannotReturnOldCounts() throws Exception {
        var first=rows(false,Types.VARCHAR);
        when(s.executeQuery(anyString())).thenReturn(first).thenThrow(new SQLTimeoutException("synthetic"));
        assertThrows(SQLTimeoutException.class,()->read(2));ownership(2);
    }
    private void ownership(int count) throws SQLException {
        verify(s,times(count)).close();verify(c,never()).close();verify(c,never()).commit();verify(c,never()).rollback();
    }
    private byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
}
