package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.uxdb.UxdbCopyWriterImpl;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** UXDB 反射 API 形状的故障模型。WRITE_COPY 仍是 DECLARED 时入口直接拒绝，协议断言先停用。 */
@Disabled("UXDB WRITE_COPY is DECLARED, not VERIFIED; see UxdbCopyWriterRefusalTest")
class UxdbCopyOutcomeTest {
    public interface CopyConnection extends Connection { CopyApi getCopyAPI() throws Exception; }
    public interface CopyApi { long copyIn(String sql, InputStream input, int bufferSize) throws Exception; }
    static final List<Object[]> ROWS=List.of(new Object[]{1},new Object[]{2});
    HikariDataSource source;
    CopyConnection connection;
    CopyApi api;
    UxdbCopyWriterImpl writer;
    @AfterEach void clearInterrupt(){Thread.interrupted();}

    @BeforeEach void setup() throws Exception {
        source=mock(HikariDataSource.class);connection=mock(CopyConnection.class);api=mock(CopyApi.class);
        when(source.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getCopyAPI()).thenReturn(api);when(api.copyIn(anyString(),any(),anyInt())).thenReturn(2L);
        writer=new UxdbCopyWriterImpl(source);
        ReflectionTestUtils.setField(writer,"columnType",Map.of("id",Types.INTEGER));
        ReflectionTestUtils.setField(writer,"schemaName","fixture");ReflectionTestUtils.setField(writer,"tableName","rows");
    }
    @Test void ownsTransactionAndCommitsOnlyAfterExactCopyReceipt() throws Exception {
        assertEquals(2,writer.write(List.of("id"),ROWS));
        var order=inOrder(connection,api);
        order.verify(connection).setAutoCommit(false);order.verify(api).copyIn(anyString(),any(),eq(65536));
        order.verify(connection).commit();order.verify(connection).setAutoCommit(true);order.verify(connection).close();
        verify(connection,never()).rollback();verify(connection,never()).prepareStatement(anyString());
    }
    @Test void copyFailureNeverReplaysAsInsert() throws Exception {
        when(api.copyIn(anyString(),any(),anyInt())).thenThrow(new SQLException("fixture-private-copy-detail"));
        assertThrows(UnconfirmedWriteException.class,()->writer.write(List.of("id"),ROWS));
        verify(source,times(1)).getConnection();verify(connection,never()).prepareStatement(anyString());
        verify(connection,never()).createStatement();verify(connection,never()).rollback();
        verify(connection).abort(any());verify(source).evictConnection(connection);
    }
    @Test void shortCopyIsUnknownAndCannotCommit() throws Exception {
        when(api.copyIn(anyString(),any(),anyInt())).thenReturn(1L);
        assertThrows(UnconfirmedWriteException.class,()->writer.write(List.of("id"),ROWS));
        verify(connection,never()).commit();verify(connection).rollback();verify(source).evictConnection(connection);
    }
    @Test void callerTransactionIsNeverCommitted() throws Exception {
        when(connection.getAutoCommit()).thenReturn(false);
        assertThrows(IllegalStateException.class,()->writer.write(List.of("id"),ROWS));
        verifyNoInteractions(api);verify(connection,never()).commit();verify(connection,never()).rollback();
    }
    @ParameterizedTest @ValueSource(strings={"copy","short","long","negative","commit","commit-batch","restore","close","abort-failed"})
    void failuresNeverReplayAndDoNotExposeDriverDetails(String fault) throws Exception {
        var detail=new SQLException("fixture-private-copy-detail");
        switch(fault){
            case "copy","abort-failed" -> when(api.copyIn(anyString(),any(),anyInt())).thenThrow(detail);
            case "short" -> when(api.copyIn(anyString(),any(),anyInt())).thenReturn(1L);
            case "long" -> when(api.copyIn(anyString(),any(),anyInt())).thenReturn(3L);
            case "negative" -> when(api.copyIn(anyString(),any(),anyInt())).thenReturn(-1L);
            case "commit" -> doThrow(detail).when(connection).commit();
            case "commit-batch" -> doThrow(new java.sql.BatchUpdateException("fixture-private-copy-detail",new int[]{1,1})).when(connection).commit();
            case "restore" -> doThrow(detail).when(connection).setAutoCommit(true);
            case "close" -> doThrow(detail).when(connection).close();
            default -> throw new AssertionError(fault);
        }
        if(fault.equals("abort-failed"))doThrow(detail).when(connection).abort(any());
        var error=assertThrows(UnconfirmedWriteException.class,()->writer.write(List.of("id"),ROWS));
        assertNull(error.getCause());assertEquals(0,error.getSuppressed().length);assertFalse(error.getMessage().contains("private"));
        verify(source,times(1)).getConnection();verify(api,times(1)).copyIn(anyString(),any(),anyInt());
        verify(connection,never()).prepareStatement(anyString());verify(connection,never()).createStatement();
        verify(source,atLeastOnce()).evictConnection(connection);verify(connection,atLeastOnce()).abort(any());verify(connection).close();
        if(List.of("copy","abort-failed").contains(fault))verify(connection,never()).rollback();
        if(!List.of("restore","close").contains(fault))verify(connection,never()).setAutoCommit(true);
        if(!List.of("commit","commit-batch","restore","close").contains(fault))verify(connection,never()).commit();
    }
    @Test void wholeBatchIsValidatedBeforeBorrowingOrCopying() {
        assertEquals(0,writer.write(List.of(),List.of()));
        assertThrows(IllegalArgumentException.class,()->writer.write(null,ROWS));
        assertThrows(IllegalArgumentException.class,()->writer.write(List.of("id"),null));
        assertThrows(IllegalArgumentException.class,()->writer.write(List.of(),ROWS));
        assertThrows(IllegalArgumentException.class,()->writer.write(List.of("id"),Arrays.asList(new Object[]{1},null)));
        assertThrows(IllegalArgumentException.class,()->writer.write(List.of("id"),List.of(new Object[]{1},new Object[]{2,3})));
        var error=assertThrows(IllegalArgumentException.class,()->writer.write(List.of("id"),List.of(new Object[]{1},new Object[]{"fixture-private-value"})));
        assertNull(error.getCause());assertFalse(error.getMessage().contains("private"));verifyNoInteractions(source,api);
    }
    @Test void interruptionBeforeWriteDoesNotBorrowAndDuringCopyStaysUnknown() throws Exception {
        Thread.currentThread().interrupt();assertThrows(IllegalStateException.class,()->writer.write(List.of("id"),ROWS));
        assertTrue(Thread.interrupted());verifyNoInteractions(source,api);
        when(api.copyIn(anyString(),any(),anyInt())).thenThrow(new InterruptedException("fixture"));
        assertThrows(UnconfirmedWriteException.class,()->writer.write(List.of("id"),ROWS));
        assertTrue(Thread.currentThread().isInterrupted());verify(connection,never()).commit();verify(source).evictConnection(connection);
    }
    @Test void interruptedAfterCopyReceiptCannotCommit() throws Exception {
        when(api.copyIn(anyString(),any(),anyInt())).thenAnswer(call->{Thread.currentThread().interrupt();return 2L;});
        assertThrows(UnconfirmedWriteException.class,()->writer.write(List.of("id"),ROWS));
        assertTrue(Thread.currentThread().isInterrupted());verify(connection,never()).commit();verify(connection).rollback();
    }
    @Test void fatalDriverErrorRetainsUnknownMarkerAndNeverFallsBack() throws Exception {
        var fatal=new AssertionError("fixture");when(api.copyIn(anyString(),any(),anyInt())).thenThrow(fatal);
        assertSame(fatal,assertThrows(AssertionError.class,()->writer.write(List.of("id"),ROWS)));
        assertTrue(Arrays.stream(fatal.getSuppressed()).anyMatch(UnconfirmedWriteException.class::isInstance));
        verify(connection,never()).prepareStatement(anyString());verify(connection,never()).commit();verify(source).evictConnection(connection);
    }
    @Test void missingCopyApiFailsBeforeSendingAndDoesNotSelectInsertImplicitly() throws Exception {
        var plain=mock(Connection.class);when(plain.getAutoCommit()).thenReturn(true);when(source.getConnection()).thenReturn(plain);
        assertThrows(IllegalStateException.class,()->writer.write(List.of("id"),ROWS));
        verifyNoInteractions(api);verify(plain,never()).prepareStatement(anyString());verify(plain,never()).commit();verify(plain).rollback();
    }
    @Test void textPayloadAndQuotedIdentifiersRetainExistingWireFormat() throws Exception {
        ReflectionTestUtils.setField(writer,"columnType",Map.of("a\"b",Types.VARCHAR,"bin",Types.BINARY,"n",Types.INTEGER));
        ReflectionTestUtils.setField(writer,"schemaName","x\"y");ReflectionTestUtils.setField(writer,"tableName","t\"r");
        when(api.copyIn(anyString(),any(),anyInt())).thenAnswer(call->{
            assertEquals("COPY \"x\"\"y\".\"t\"\"r\" (\"a\"\"b\",\"bin\",\"n\") FROM STDIN",call.getArgument(0));
            assertEquals("slash\\\\ tab\\t line\\n return\\r\\b\\f\t\\\\x000aff\t\\N\n",
                    new String(((InputStream)call.getArgument(1)).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            return 1L;
        });
        assertEquals(1,writer.write(List.of("a\"b","bin","n"),List.<Object[]>of(new Object[]{"slash\\ tab\t line\n return\r\b\f",new byte[]{0,10,(byte)255},null})));
    }
    @Test void factoryStillSelectsExplicitInsertWithoutImplicitFallback() {
        assertInstanceOf(UxdbCopyWriterImpl.class,cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().writer(source, cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.UXDB, cn.iocoder.yudao.framework.dbswitch.spi.WriteMode.COPY));
        assertInstanceOf(cn.iocoder.yudao.framework.dbswitch.dbwriter.uxdb.UxdbInsertWriterImpl.class,
                cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().writer(source, cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.UXDB, cn.iocoder.yudao.framework.dbswitch.spi.WriteMode.INSERT));
    }
}
