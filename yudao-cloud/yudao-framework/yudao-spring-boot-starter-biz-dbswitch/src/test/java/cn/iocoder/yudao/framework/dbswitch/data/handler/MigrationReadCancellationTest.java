package cn.iocoder.yudao.framework.dbswitch.data.handler;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByDataSourceServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(15)
class MigrationReadCancellationTest {

    @Test
    void interruptCancelsTheOpenReadAndKeepsTheOriginalReadError() throws Exception {
        var properties = new MigrationRequest();
        var source = new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.POSTGRESQL);
        properties.getSource().add(source);
        properties.getTarget().setTargetProductType(ProductTypeEnum.POSTGRESQL);
        properties.getTarget().setTargetTruncateData(false);
        var table = new TableDescription();
        table.setSchemaName("public");
        table.setTableName("fixture");
        var field = new ColumnDescription();
        field.setFieldName("id");
        field.setMetadataEvidence(ColumnMetadataEvidence.unknown());
        var writer = mock(IDatabaseWriter.class);
        var operator = mock(IDatabaseOperator.class);
        var statement = mock(Statement.class);
        var rs = mock(ResultSet.class);
        var meta = mock(ResultSetMetaData.class);
        var rows = mock(StatementResultSet.class);
        when(rows.getResultset()).thenReturn(rs);
        when(rows.getStatement()).thenReturn(statement);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnType(1)).thenReturn(Types.INTEGER);
        var readEntered = new CountDownLatch(1);
        var cancelAttempted = new CountDownLatch(1);
        when(rs.next()).thenAnswer(call -> {
            readEntered.countDown();
            if (!cancelAttempted.await(5, SECONDS)) {
                throw new SQLException("timed out waiting for Statement.cancel");
            }
            throw new SQLException("original-read-error");
        });
        doAnswer(call -> {
            cancelAttempted.countDown();
            throw new SQLException("cancel-failed");
        }).when(statement).cancel();
        when(operator.nativeMigrationColumns(any(), any())).thenReturn(List.of(field));
        when(operator.queryTableData(anyString(), anyString(), anyList())).thenReturn(rows);
        when(writer.writeWithReceipt(anyList(), anyList())).thenCallRealMethod();
        try (var input = new HikariDataSource(); var output = new HikariDataSource();
             var runtime = spy(DbSwitchRuntime.standalone());
             var metadata = mockConstruction(MetaDataByDataSourceServiceImpl.class, (mock, context) ->
                     when(mock.queryTableColumnMeta(any(), any())).thenReturn(List.of(field)))) {
            when(writer.getDataSource()).thenReturn(output);
            doReturn(operator).when(runtime).reader(any(), eq(ProductTypeEnum.POSTGRESQL));
            var handler = MigrationHandler.createInstance(table, properties, 0, input, output, null, runtime);
            ReflectionTestUtils.setField(handler, "sourceProductType", ProductTypeEnum.POSTGRESQL);
            ReflectionTestUtils.setField(handler, "targetProductType", ProductTypeEnum.POSTGRESQL);
            ReflectionTestUtils.setField(handler, "sourceColumnDescriptions", List.of(field));
            ReflectionTestUtils.setField(handler, "targetColumnDescriptions", List.of(field));
            var result = new DbSwitchTableResult();
            var failure = new AtomicReference<Throwable>();
            var worker = new Thread(() -> {
                try {
                    ReflectionTestUtils.invokeMethod(handler, "doFullCoverSynchronize", writer, result);
                    failure.set(new AssertionError("read should fail"));
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            });
            worker.start();
            try {
                assertTrue(readEntered.await(5, SECONDS));
                assertDoesNotThrow(handler::interrupt);
                worker.join(5_000);
                assertFalse(worker.isAlive());
                var thrown = failure.get();
                assertInstanceOf(RuntimeException.class, thrown);
                assertInstanceOf(SQLException.class, thrown.getCause());
                assertEquals("original-read-error", thrown.getCause().getMessage());
                assertFalse(mentionsCancelFailure(thrown));
                verify(statement, times(1)).cancel();
                verify(statement, never()).setQueryTimeout(anyInt());
                handler.interrupt();
                verify(statement, times(1)).cancel();
                assertNull(result.getTransferSummary());
            } finally {
                worker.interrupt();
                worker.join(5_000);
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3))
                        .until(() -> !handler.hasActiveWriters());
            }
        }
    }

    private static boolean mentionsCancelFailure(Throwable thrown) {
        if (thrown == null) {
            return false;
        }
        if ("cancel-failed".equals(thrown.getMessage())) {
            return true;
        }
        if (mentionsCancelFailure(thrown.getCause())) {
            return true;
        }
        for (Throwable suppressed : thrown.getSuppressed()) {
            if (mentionsCancelFailure(suppressed)) {
                return true;
            }
        }
        return false;
    }
}
