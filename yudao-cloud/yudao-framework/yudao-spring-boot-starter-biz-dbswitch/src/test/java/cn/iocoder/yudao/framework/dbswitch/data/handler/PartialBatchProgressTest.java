package cn.iocoder.yudao.framework.dbswitch.data.handler;

import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByDataSourceServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.PartialBatchWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PartialBatchProgressTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"success", "partial", "unknown", "closing", "invalid", "short"})
    void differenceBatchesKeepAcceptedRejectedUnknownAndDoNotInventCommitReceipts(String scenario) {
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.POSTGRESQL); request.getSource().add(source);
        request.getTarget().setTargetProductType(ProductTypeEnum.POSTGRESQL);
        request.getTarget().setTargetTable("fixture");
        var table = new TableDescription(); table.setSchemaName("public"); table.setTableName("fixture");
        try (var input = new HikariDataSource(); var output = new HikariDataSource();
                var runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), DbSwitchOptions.defaults(), null)) {
            var handler = MigrationHandler.createInstance(table, request, 0, input, output, null, runtime);
            var result = new DbSwitchTableResult(); ReflectionTestUtils.setField(handler, "progressTarget", result);
            var collector = (cn.iocoder.yudao.framework.dbswitch.data.domain.TableBatchProgress.Collector)
                    ReflectionTestUtils.getField(handler, "batchProgress");
            var rows = List.<Object[]>of(new Object[]{1}, new Object[]{2}, new Object[]{3});
            for (Object[] row : rows) {
                long bytes = cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter.estimatedRowBytes(row);
                collector.read(bytes); collector.reserve(bytes);
            }
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            java.util.function.ToLongFunction<List<Object[]>> write = batch -> {
                calls.incrementAndGet();
                return switch (scenario) {
                    case "success" -> 3;
                    case "short" -> 1;
                    case "partial" -> throw new PartialBatchWriteException(1, 2, 0, true);
                    case "unknown" -> throw new PartialBatchWriteException(1, 1, 1, true);
                    case "closing" -> throw new PartialBatchWriteException(3, 0, 0, false);
                    default -> throw new PartialBatchWriteException(1, 0, 1, true);
                };
            };
            if (scenario.equals("success")) assertEquals(3L,
                    ReflectionTestUtils.<Long>invokeMethod(handler, "executeDifferenceBatch", rows, write));
            else {
                var failure = assertThrows(RuntimeException.class,
                        () -> ReflectionTestUtils.invokeMethod(handler, "executeDifferenceBatch", rows, write));
                if (scenario.equals("invalid") || scenario.equals("short")) assertInstanceOf(UnconfirmedWriteException.class, failure);
            }
            assertEquals(1, calls.get()); assertFalse(handler.hasActiveWriters());
            var progress = result.getBatchProgress();
            assertEquals(3, progress.submittedRows()); assertEquals(0, progress.bufferedBytes());
            assertEquals(0, progress.commitWitnessedInputRows());
            assertEquals(scenario.equals("success") ? 1 : 0, progress.acknowledgedBatches());
            assertEquals(scenario.equals("success") || scenario.equals("closing") ? 3
                    : scenario.equals("partial") || scenario.equals("unknown") ? 1 : 0, progress.acknowledgedRows());
            assertEquals(scenario.equals("partial") ? 2 : scenario.equals("unknown") ? 1 : 0, progress.rejectedRows());
            assertEquals(scenario.equals("invalid") || scenario.equals("short") ? 3 : scenario.equals("unknown") ? 1 : 0, progress.unknownRows());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,known", "true,known", "false,unknown", "true,unknown",
            "false,closing", "true,closing", "false,invalid-count", "true,invalid-count"})
    void pipelineAndTaskKeepTheActualPartitionWithoutReplaying(String asynchronous, String scenario) throws Exception {
        boolean async = Boolean.parseBoolean(asynchronous);
        var options = new DbSwitchOptions(128L * 1024 * 1024, async, true, 3600, 0, 300_000, 30_000, 0);
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.POSTGRESQL); request.getSource().add(source);
        request.getTarget().setTargetProductType(ProductTypeEnum.POSTGRESQL);
        request.getTarget().setTargetTable("fixture");
        var table = new TableDescription(); table.setSchemaName("public"); table.setTableName("fixture");
        var column = new ColumnDescription(); column.setFieldName("id");
        var writer = mock(IDatabaseWriter.class); var operator = mock(IDatabaseOperator.class);
        var rows = mock(ResultSet.class); var types = mock(ResultSetMetaData.class); var scope = mock(StatementResultSet.class);
        when(scope.getResultset()).thenReturn(rows); when(rows.getMetaData()).thenReturn(types);
        when(types.getColumnType(1)).thenReturn(Types.INTEGER);
        when(rows.next()).thenReturn(true, true, true, false);
        when(rows.getObject(1)).thenReturn(1, 2, 3);
        when(operator.queryTableData(anyString(), anyString(), anyList())).thenReturn(scope);
        var supplied = switch (scenario) {
            case "known" -> new PartialBatchWriteException(1, 2, 0, true);
            case "unknown" -> new PartialBatchWriteException(1, 1, 1, true);
            case "closing" -> new PartialBatchWriteException(3, 0, 0, false);
            case "invalid-count" -> new PartialBatchWriteException(1, 0, 1, true);
            default -> throw new AssertionError(scenario);
        };
        when(writer.writeWithReceipt(anyList(), anyList())).thenThrow(supplied);
        try (var input = new HikariDataSource(); var output = new HikariDataSource();
             var runtime = spy(new DbSwitchRuntime(DbSwitchProductRegistry.discover(), options, null))) {
            var metadata = mock(MetaDataByDataSourceServiceImpl.class);
            when(metadata.queryTableColumnMeta(any(), any())).thenReturn(List.of(column));
            when(writer.getDataSource()).thenReturn(output);
            doReturn(metadata).when(runtime).metadata(output, ProductTypeEnum.POSTGRESQL);
            doReturn(operator).when(runtime).reader(input, ProductTypeEnum.POSTGRESQL);
            var handler = MigrationHandler.createInstance(table, request, 0, input, output, null, runtime);
            ReflectionTestUtils.setField(handler, "sourceProductType", ProductTypeEnum.POSTGRESQL);
            ReflectionTestUtils.setField(handler, "targetProductType", ProductTypeEnum.POSTGRESQL);
            ReflectionTestUtils.setField(handler, "sourceColumnDescriptions", List.of(column));
            ReflectionTestUtils.setField(handler, "targetColumnDescriptions", List.of(column));
            var result = new DbSwitchTableResult();
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> ReflectionTestUtils.invokeMethod(handler, "doFullCoverSynchronize", writer, result));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> !handler.hasActiveWriters());
            var progress = result.getBatchProgress();
            assertNotNull(progress); assertEquals(3, progress.submittedRows()); assertEquals(3, progress.readRows());
            if (scenario.equals("invalid-count")) {
                assertInstanceOf(UnconfirmedWriteException.class, failure);
                assertEquals(0, progress.acknowledgedRows()); assertEquals(0, progress.rejectedRows());
                assertEquals(3, progress.unknownRows());
            } else {
                assertSame(supplied, failure);
                assertEquals(supplied.acknowledgedRows(), progress.acknowledgedRows());
                assertEquals(supplied.rejectedRows(), progress.rejectedRows());
                assertEquals(supplied.unknownRows(), progress.unknownRows());
            }
            assertEquals(0, progress.acknowledgedBatches()); assertEquals(0, progress.commitWitnessedInputRows());
            assertEquals(0, progress.bufferedBytes()); assertNull(result.getTransferSummary());
            verify(writer, times(1)).writeWithReceipt(anyList(), anyList()); verify(writer, never()).write(anyList(), anyList());
            var service = new MigrationService(request, ignored -> {}, null, runtime);
            ReflectionTestUtils.invokeMethod(service, "recordUnconfirmedWrite", failure);
            assertEquals(!scenario.equals("known"), ReflectionTestUtils.getField(service, "externalWriteUnconfirmed"));
        }
    }
}
