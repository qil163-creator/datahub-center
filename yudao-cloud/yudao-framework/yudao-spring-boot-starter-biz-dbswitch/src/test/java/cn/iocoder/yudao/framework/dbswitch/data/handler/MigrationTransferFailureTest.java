package cn.iocoder.yudao.framework.dbswitch.data.handler;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByDataSourceServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.domain.TableTransferSummary.Operation;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MigrationTransferFailureTest {
    @ParameterizedTest @ValueSource(strings = {"default-read", "null-flag-read", "strict-read", "strict-copy-read",
            "create-new-read", "close-failure", "sql-query"})
    void recordsActualReadOutcomeOnlyAfterSuccessfulClose(String scenario) throws Exception {
        var p = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.POSTGRESQL); source.setSourceSql("SELECT id FROM fixture");
        if (scenario.equals("sql-query")) source.setSourceType(2);
        p.getSource().add(source); p.getTarget().setTargetProductType(ProductTypeEnum.POSTGRESQL);
        p.getTarget().setTargetTable("fixture");
        p.getTarget().setTargetTruncateData(false); p.getTarget().setFailOnFieldReadError(scenario.equals("strict-read"));
        if (scenario.equals("null-flag-read")) p.getTarget().setFailOnFieldReadError(null);
        if (scenario.equals("strict-copy-read")) p.getTarget().setWriteMode("STRICT_COPY");
        if (scenario.equals("create-new-read")) p.getTarget().setWriteMode("CREATE_NEW");
        var table = new TableDescription(); table.setSchemaName("public"); table.setTableName("fixture");
        var field = new ColumnDescription(); field.setFieldName("id");
        var writer = mock(IDatabaseWriter.class); var operator = mock(IDatabaseOperator.class);
        var rs = mock(ResultSet.class); var meta = mock(ResultSetMetaData.class); var srs = mock(StatementResultSet.class);
        when(srs.getResultset()).thenReturn(rs); when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnType(1)).thenReturn(Types.INTEGER); when(rs.next()).thenReturn(true, false);
        boolean failedRead = scenario.endsWith("-read");
        if (failedRead) {
            when(rs.next()).thenReturn(true, true, false);
            when(rs.getObject(1)).thenReturn(7).thenThrow(new SQLException("synthetic-sensitive-field-value"));
        } else { when(rs.getObject(1)).thenReturn(7); }
        if (scenario.equals("close-failure")) doThrow(new SQLException("synthetic close failure")).when(rs).close();
        when(operator.queryTableData(anyString(), anyString(), anyList())).thenReturn(srs);
        when(operator.queryTableData(anyString(), anyList())).thenReturn(srs);
        when(writer.write(anyList(), anyList())).thenReturn(1L);
        when(writer.writeWithReceipt(anyList(), anyList())).thenCallRealMethod();
        try (var input = new HikariDataSource(); var output = new HikariDataSource();
             var runtime = spy(DbSwitchRuntime.standalone());
             var metadata = mockConstruction(MetaDataByDataSourceServiceImpl.class, (mock, context) ->
                     when(mock.queryTableColumnMeta(any(), any())).thenReturn(List.of(field)))) {
            when(writer.getDataSource()).thenReturn(output);
            doReturn(operator).when(runtime).reader(input, ProductTypeEnum.POSTGRESQL);
            var handler = MigrationHandler.createInstance(table, p, 0, input, output, null, runtime);
            ReflectionTestUtils.setField(handler, "sourceProductType", ProductTypeEnum.POSTGRESQL);
            ReflectionTestUtils.setField(handler, "targetProductType", ProductTypeEnum.POSTGRESQL);
            ReflectionTestUtils.setField(handler, "sourceColumnDescriptions", List.of(field));
            ReflectionTestUtils.setField(handler, "targetColumnDescriptions", List.of(field));
            var result = new DbSwitchTableResult();
            try {
                if (failedRead || scenario.equals("close-failure")) {
                    var failure = assertThrows(RuntimeException.class, () -> ReflectionTestUtils.invokeMethod(handler, "doFullCoverSynchronize", writer, result));
                    assertNull(result.getTransferSummary());
                    if (failedRead) {
                        assertInstanceOf(BatchRejectedException.class, failure);
                        assertNull(failure.getCause());
                        assertFalse(failure.toString().contains("synthetic-sensitive-field-value"));
                        assertEquals(1, result.getBatchProgress().readRows());
                        assertEquals(1, result.getBatchProgress().rejectedRows());
                        assertEquals(0, result.getBatchProgress().submittedRows());
                        assertEquals(0, result.getBatchProgress().acknowledgedRows());
                        verify(writer, never()).write(anyList(), anyList());
                        verify(writer, never()).writeWithReceipt(anyList(), anyList());
                    }
                } else {
                    ReflectionTestUtils.invokeMethod(handler, "doFullCoverSynchronize", writer, result);
                    assertEquals(scenario.equals("sql-query") ? Operation.SQL_QUERY : Operation.FULL_SCAN_TABLE,
                            result.getTransferSummary().operation());
                    assertEquals(0, result.getTransferSummary().substitutedNullValues());
                    assertEquals(1, result.getTransferSummary().acknowledgedRows());
                }
            } finally {
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).until(() -> !handler.hasActiveWriters());
            }
        }
    }
}
