package cn.iocoder.yudao.framework.dbswitch.data.handler;

import cn.iocoder.yudao.framework.dbswitch.common.constant.MapperType;
import cn.iocoder.yudao.framework.dbswitch.common.entity.MapperConfig;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.CaseConvertEnum;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByDataSourceServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Handler control contract only: these fixtures do not prove native time-series JDBC behavior. */
class MigrationTimestampMappingTest {
    @ParameterizedTest
    @CsvSource({"TDENGINE,NONE,Event_Time", "TDENGINE,UPPER,EVENT_TIME", "TDENGINE,LOWER,event_time",
            "TDENGINE,SNAKE,event_time", "TDENGINE,CAMEL,eventTime", "INFLUXDB3,NONE,Event_Time",
            "INFLUXDB3,UPPER,EVENT_TIME", "INFLUXDB3,LOWER,event_time", "INFLUXDB3,SNAKE,event_time",
            "INFLUXDB3,CAMEL,eventTime"})
    void timestampUsesTheSameRegexAndModeAsThePreparedTargetColumns(
            ProductTypeEnum product, CaseConvertEnum mode, String expected) throws Exception {
        var request = request(product); request.getTarget().setColumnNameCase(mode);
        request.getTarget().setLowercase(true); request.getTarget().setTableNameCase(CaseConvertEnum.UPPER);
        try (var fixture = new Fixture(request)) {
            fixture.handler.sequenceTablePlan();
            assertEquals(expected, ReflectionTestUtils.invokeMethod(fixture.handler, "getTimestampColumn"));
            @SuppressWarnings("unchecked")
            var columns = (List<ColumnDescription>) ReflectionTestUtils.getField(fixture.handler, "targetColumnDescriptions");
            List<ColumnDescription> ordered = ReflectionTestUtils.invokeMethod(fixture.handler, "applyTimestampColumn", columns);
            assertEquals(expected, ordered.getFirst().getFieldName());
            assertEquals(List.of("src_id", "src_Event_Time"), fixture.sourceColumns.stream().map(ColumnDescription::getFieldName).toList());
            verify(fixture.output, never()).getConnection();
        }
    }

    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, names = {"TDENGINE", "INFLUXDB3"})
    void perTableTimestampAndColumnRulesAreUsedTogether(ProductTypeEnum product) {
        var request = request(product); var source = request.getSource().getFirst();
        source.setMapperType(MapperType.SINGLE.name()); source.setTimestampColumn("wrong_global");
        source.setRegexColumnMapper(List.of(new PatternMapper(".*", "wrong_global")));
        var selected = new MapperConfig(); selected.setTimestampColumn("src_Event_Time");
        selected.setRegexColumnMapper(List.of(new PatternMapper("^src_Event_Time$", "recorded_at")));
        source.setConfigMap(Map.of("events", selected));
        request.getTarget().setColumnNameCase(CaseConvertEnum.CAMEL);
        try (var fixture = new Fixture(request)) {
            fixture.handler.sequenceTablePlan();
            assertEquals("recordedAt", ReflectionTestUtils.invokeMethod(fixture.handler, "getTimestampColumn"));
        }
    }

    @ParameterizedTest
    @CsvSource({"TDENGINE,missing", "TDENGINE,excluded", "TDENGINE,target-name",
            "INFLUXDB3,missing", "INFLUXDB3,excluded", "INFLUXDB3,target-name"})
    void aTimestampMustResolveToARetainedSourceColumnBeforeAnyTargetAccess(ProductTypeEnum product, String scenario)
            throws Exception {
        var request = request(product); var source = request.getSource().getFirst();
        request.getTarget().setColumnNameCase(CaseConvertEnum.SNAKE);
        if (scenario.equals("excluded")) source.setRegexColumnMapper(List.of(new PatternMapper("^src_Event_Time$", "")));
        else source.setTimestampColumn(scenario.equals("missing") ? "missing" : "event_time");
        try (var fixture = new Fixture(request)) {
            assertThrows(IllegalArgumentException.class, fixture.handler::sequenceTablePlan);
            verify(fixture.output, never()).getConnection();
            verify(fixture.runtime, never()).writer(any(), any(), any());
        }
    }

    private static MigrationRequest request(ProductTypeEnum product) {
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(product); source.setSourceSchema("fixture");
        source.setTimestampColumn("src_Event_Time");
        source.setRegexColumnMapper(List.of(new PatternMapper("^src_", "")));
        request.setSource(List.of(source)); request.getTarget().setTargetProductType(product);
        request.getTarget().setTargetSchema("fixture");
        return request;
    }

    private static ColumnDescription column(String name) {
        var column = new ColumnDescription(); column.setFieldName(name); column.setLabelName(name); return column;
    }

    private static final class Fixture implements AutoCloseable {
        final HikariDataSource input = mock(HikariDataSource.class);
        final HikariDataSource output = mock(HikariDataSource.class);
        final DbSwitchRuntime runtime = spy(DbSwitchRuntime.standalone());
        final List<ColumnDescription> sourceColumns = List.of(column("src_id"), column("src_Event_Time"));
        final MigrationHandler handler;

        Fixture(MigrationRequest request) {
            var product = request.getSource().getFirst().getSourceProductType();
            var metadata = mock(MetaDataByDataSourceServiceImpl.class);
            var reader = mock(IDatabaseOperator.class);
            when(reader.nativeMigrationColumns("fixture", "events")).thenReturn(sourceColumns);
            when(metadata.queryTablePrimaryKeys("fixture", "events")).thenReturn(List.of("src_id"));
            doReturn(metadata).when(runtime).metadata(input, product);
            doReturn(reader).when(runtime).reader(input, product);
            var table = new TableDescription(); table.setSchemaName("fixture"); table.setTableName("events");
            handler = MigrationHandler.createInstance(table, request, 0, input, output, null, runtime);
        }

        @Override public void close() { runtime.close(); input.close(); output.close(); }
    }
}
