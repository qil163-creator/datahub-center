package cn.iocoder.yudao.framework.dbswitch.product.doris;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.PartialBatchWriteException;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static cn.iocoder.yudao.framework.dbswitch.product.doris.DorisJdbcSupport.*;

class DorisNativeWriteOutcomeTest {
    @Test void preparedPublicWriterAndSyncRejectInvalidBatchBeforeConnecting() throws Exception {
        for (boolean writerMode : List.of(true, false)) {
            var fixture = new Fixture();
            var evidence = new Evidence(DorisNativeSchema.parse("CREATE TABLE t(id BIGINT NOT NULL,note VARCHAR(40)) ENGINE=OLAP UNIQUE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('enable_unique_key_merge_on_write'='true','replication_num'='1')"), "a".repeat(64), "1:2:3");
            var writer = new cn.iocoder.yudao.framework.dbswitch.dbwriter.doris.DorisWriterImpl(fixture.source);
            writer.configure(jdbcOnly());
            var sync = new cn.iocoder.yudao.framework.dbswitch.dbsynch.doris.DorisDatabaseSyncImpl(fixture.source);
            try (var capture = mockStatic(DorisJdbcSupport.class, call -> call.getMethod().getName().equals("inspect") ? evidence : call.callRealMethod())) {
                if (writerMode) writer.prepareWrite("db", "t", List.of("id", "note"));
                else sync.prepare("db", "t", List.of("id", "note"), List.of("id"));
                clearInvocations(fixture.source);
                var owner = Thread.currentThread();
                var clob = mock(Clob.class);
                when(clob.getCharacterStream()).thenAnswer(call -> { assertSame(owner, Thread.currentThread()); return new java.io.StringReader("first"); });
                var unsupported = mock(Struct.class);
                var records = List.of(new Object[]{1L, clob}, new Object[]{2L, unsupported});
                var failure = assertThrows(IllegalArgumentException.class, () -> {
                    if (writerMode) writer.write(List.of("id", "note"), records); else sync.executeInsert(records);
                });
                assertNull(failure.getCause()); assertSame(clob, records.getFirst()[1]); assertSame(unsupported, records.getLast()[1]);
                verify(clob).free(); verifyNoInteractions(fixture.source);
            }
        }
    }
    @Test void nativeMultiValuesUsesParameterBudgetTimeoutAndNoJdbcTransaction()throws Exception {var fixture=new Fixture();when(fixture.write.executeUpdate()).thenReturn(2,1);
        try(var capture=fixture.capture()){assertEquals(3,fixture.write(rows(3),new BatchLimits(10,10000,1000,4,1000)));}
        verify(fixture.write,times(2)).setQueryTimeout(7);verify(fixture.connection).prepareStatement(contains("VALUES (?,?),(?,?)"));verify(fixture.connection,never()).setAutoCommit(anyBoolean());verify(fixture.connection,never()).commit();verify(fixture.connection,never()).rollback();verify(fixture.control).execute(contains("group_commit='async_mode'"));}
    @Test void lostSecondResponsePreservesAcknowledgedPrefixAndUnknownOnlySentRows()throws Exception {var fixture=new Fixture();when(fixture.write.executeUpdate()).thenReturn(2).thenThrow(new SQLException("secret-vendor-value"));
        try(var capture=fixture.capture()){var failure=assertThrows(PartialBatchWriteException.class,()->fixture.write(rows(5),new BatchLimits(10,10000,1000,4,1000)));assertEquals(2,failure.acknowledgedRows());assertEquals(2,failure.unknownRows());assertEquals(1,failure.rejectedRows());assertFalse(failure.terminationConfirmed());assertNull(failure.getCause());assertFalse(failure.toString().contains("secret"));}verify(fixture.connection,never()).rollback();}
    @Test void nativePreflightFailureIsKnownBeforeAnyRequest()throws Exception {var fixture=new Fixture();when(fixture.connection.getAutoCommit()).thenReturn(false);try(var capture=fixture.capture()){assertThrows(IllegalStateException.class,()->fixture.write(rows(1),BatchLimits.defaults()));}verify(fixture.connection,never()).prepareStatement(anyString());}
    @Test void entireBatchConversionFailureNeverBorrowsAConnection()throws Exception {var fixture=new Fixture();try(var capture=fixture.capture()){assertThrows(IllegalArgumentException.class,()->fixture.write(Arrays.asList(new Object[]{1L,"ok"},new Object[]{2L,"x".repeat(41)}),BatchLimits.defaults()));}verify(fixture.source,never()).getConnection();}
    @Test void closeFailureRetainsUnknownTerminationAfterAcknowledgement()throws Exception {var fixture=new Fixture();when(fixture.write.executeUpdate()).thenReturn(1);doThrow(new SQLException("secret-close")).when(fixture.connection).close();try(var capture=fixture.capture()){var failure=assertThrows(PartialBatchWriteException.class,()->fixture.write(rows(1),BatchLimits.defaults()));assertEquals(1,failure.acknowledgedRows());assertEquals(0,failure.unknownRows());assertFalse(failure.terminationConfirmed());}}
    @Test void failedSessionRestorationAbortsConnectionAndCannotBecomeSuccess()throws Exception {var fixture=new Fixture();when(fixture.write.executeUpdate()).thenReturn(1);doThrow(new SQLException("secret-session")).when(fixture.control).execute(contains("group_commit='async_mode'"));try(var capture=fixture.capture()){var failure=assertThrows(PartialBatchWriteException.class,()->fixture.write(rows(1),BatchLimits.defaults()));assertEquals(1,failure.acknowledgedRows());assertFalse(failure.terminationConfirmed());}verify(fixture.connection).abort(any());}
    @Test void bothTransportsShareNativePrecedencePreflight()throws Exception {var fixture=new Fixture();var legacy=new Evidence(DorisNativeSchema.parse("CREATE TABLE t(id BIGINT NOT NULL,note VARCHAR(40)) ENGINE=OLAP UNIQUE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('enable_unique_key_merge_on_write'='false','replication_num'='1')"),"a".repeat(64),"1:2:3");try(var capture=mockStatic(DorisJdbcSupport.class,call->call.getMethod().getName().equals("inspect")?legacy:call.callRealMethod())){var writer=new cn.iocoder.yudao.framework.dbswitch.dbwriter.doris.DorisWriterImpl(fixture.source);writer.configure(jdbcOnly());assertThrows(IllegalStateException.class,()->writer.prepareWrite("db","t",List.of("id","note")));}verify(fixture.connection,never()).prepareStatement(anyString());verify(fixture.connection,never()).createStatement();}
    private static List<Object[]> rows(int count){var rows=new ArrayList<Object[]>();for(int i=0;i<count;i++)rows.add(new Object[]{(long)i,"v"});return rows;}
    private static DbSwitchOptions jdbcOnly(){var defaults=DbSwitchOptions.defaults();return new DbSwitchOptions(defaults.maxCacheBytes(),defaults.pipelineWriteEnabled(),false,defaults.queryTimeoutSeconds(),defaults.poolMinimumIdle(),defaults.poolIdleTimeoutMs(),defaults.poolValidationTimeoutMs(),defaults.poolLeakDetectionThresholdMs(),defaults.diffSort());}
    private static final class Fixture {
        final DataSource source=mock(DataSource.class);final Connection connection=mock(Connection.class);final Statement control=mock(Statement.class);final PreparedStatement write=mock(PreparedStatement.class);
        final Evidence evidence=new Evidence(DorisNativeSchema.parse("CREATE TABLE t(id BIGINT NOT NULL,note VARCHAR(40) NULL) ENGINE=OLAP DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')"),"a".repeat(64),"1:2:3");
        Fixture()throws Exception {when(source.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);when(connection.createStatement()).thenReturn(control);when(connection.prepareStatement(anyString())).thenReturn(write);var settings=mock(ResultSet.class);when(control.executeQuery(anyString())).thenReturn(settings);when(settings.next()).thenReturn(true,false);when(settings.getBoolean(1)).thenReturn(false);when(settings.getBoolean(2)).thenReturn(true);when(settings.getString(3)).thenReturn("async_mode");var mode=mock(ResultSet.class);when(control.executeQuery("SELECT @@sql_mode")).thenReturn(mode);when(mode.next()).thenReturn(true,false);when(mode.getString(1)).thenReturn("NO_BACKSLASH_ESCAPES");}
        org.mockito.MockedStatic<DorisJdbcSupport> capture(){return mockStatic(DorisJdbcSupport.class,invocation->invocation.getMethod().getName().equals("inspect")?evidence:invocation.callRealMethod());}
        long write(List<Object[]> rows,BatchLimits limits){return DorisJdbcSupport.write(source,"db","t",evidence,List.of("id","note"),rows,Operation.INSERT,7,limits);}
    }
}
