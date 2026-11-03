package cn.iocoder.yudao.framework.dbswitch.data.handler;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.DatabaseAwareUtils;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByDataSourceServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MigrationFallbackSafetyTest {
    @Test void destructiveDefaultsAreDisabled() {
        var target = new MigrationRequest().getTarget();
        assertFalse(target.getTargetDrop()); assertFalse(target.getTargetTruncateData());
    }

    @ParameterizedTest @ValueSource(strings = {"drop", "truncate"})
    void incrementalDestructiveConflictIsRejectedBeforeMetadata(String destructive) {
        var properties = properties(ProductTypeEnum.POSTGRESQL);
        if (destructive.equals("drop")) properties.getTarget().setTargetDrop(true);
        else properties.getTarget().setTargetTruncateData(true);
        try (var source = new HikariDataSource(); var target = new HikariDataSource();
             var runtime = spy(DbSwitchRuntime.standalone());
             var metadata = mockConstruction(MetaDataByDataSourceServiceImpl.class)) {
            var handler = MigrationHandler.createInstance(table(), properties, 0, source, target, null, runtime);
            assertThrows(IllegalArgumentException.class, handler::get);
            assertTrue(metadata.constructed().isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"missing-source-pk", "mismatched-pk", "absent-target", "myisam", "engine-probe-failure"})
    void cannotFallBackToFullCopyOrMutateTargetWhenIncrementalPreconditionsFail(String scenario) throws Exception {
        ProductTypeEnum product = scenario.startsWith("myisam") || scenario.startsWith("engine")
                ? ProductTypeEnum.MYSQL : ProductTypeEnum.POSTGRESQL;
        var properties = properties(product);
        // These two cases isolate the MySQL storage-engine gate, not cross-product type mapping.
        if (product == ProductTypeEnum.MYSQL) properties.getSource().getFirst().setSourceProductType(product);
        var field = new ColumnDescription(); field.setFieldName("id"); field.setLabelName("id");
        var writer = mock(IDatabaseWriter.class);
        var sourceOperator = mock(IDatabaseOperator.class);
        var targetOperator = mock(IDatabaseOperator.class);
        try (var source = new HikariDataSource(); var target = new HikariDataSource();
             var runtime = spy(DbSwitchRuntime.standalone());
             var metadata = mockConstruction(MetaDataByDataSourceServiceImpl.class, (mock, context) -> {
                 boolean input = context.arguments().getFirst() == source;
                 when(mock.queryTableColumnMeta(anyString(), anyString())).thenReturn(List.of(field));
                 when(mock.queryTableColumnMetaWithNativeEvidence(anyString(), anyString())).thenReturn(List.of(field));
                 when(mock.queryTablePrimaryKeys(anyString(), anyString())).thenReturn(
                         input && scenario.equals("missing-source-pk") ? List.of()
                                 : !input && scenario.equals("mismatched-pk") ? List.of("other_id") : List.of("id"));
                 when(mock.queryTableList(anyString())).thenReturn(scenario.equals("absent-target") ? List.of() : List.of(table()));
                 when(mock.queryTableColumnName(anyString(), anyString())).thenReturn(List.of("id"));
             });
             var engines = mockStatic(DatabaseAwareUtils.class)) {
            doReturn(writer).when(runtime).writer(same(target), eq(product), any());
            doReturn(sourceOperator).when(runtime).reader(same(source), eq(properties.getSource().getFirst().getSourceProductType()));
            doReturn(targetOperator).when(runtime).reader(same(target), eq(product));
            when(sourceOperator.nativeMigrationColumns("public", "fixture")).thenReturn(List.of());
            if (scenario.equals("engine-probe-failure")) {
                engines.when(() -> DatabaseAwareUtils.isMysqlInnodbStorageEngine(anyString(), anyString(), same(target)))
                        .thenThrow(new IllegalStateException("fixture connection error"));
            } else {
                engines.when(() -> DatabaseAwareUtils.isMysqlInnodbStorageEngine(anyString(), anyString(), same(target)))
                        .thenReturn(false);
            }
            var handler = MigrationHandler.createInstance(table(), properties, 0, source, target, null, runtime);
            var failure = assertThrows(IllegalStateException.class, handler::get);
            assertTrue(failure.getMessage().contains(switch (scenario) {
                case "missing-source-pk" -> "必须指定互不重复的匹配键";
                case "mismatched-pk" -> "映射键必须与目标原生键一致";
                case "absent-target" -> "目标表不存在";
                default -> "无法确认 MySQL 目标表的 InnoDB 能力";
            }));
            // Factories may expose the read-only native projection. No row query or DDL is allowed.
            verify(sourceOperator).nativeMigrationColumns("public", "fixture");
            verify(targetOperator).supportsNativeTableDefinition();
            verifyNoMoreInteractions(sourceOperator, targetOperator);
            if (scenario.equals("myisam") || scenario.equals("engine-probe-failure"))
                engines.verify(() -> DatabaseAwareUtils.isMysqlInnodbStorageEngine("public", "fixture", target));
            verifyNoInteractions(writer);
            for (var database : metadata.constructed()) {
                verify(database, never()).addNoExistColumnsByTarget(anyString(), anyString(), anyList());
            }
        }
    }

    private static MigrationRequest properties(ProductTypeEnum targetProduct) {
        var p = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.POSTGRESQL); source.setSourceSchema("public");
        p.setSource(List.of(source)); p.getTarget().setTargetProductType(targetProduct);
        p.getTarget().setTargetSchema("public"); p.getTarget().setChangeDataSync(true);
        return p;
    }

    private static TableDescription table() {
        var table = new TableDescription(); table.setSchemaName("public"); table.setTableName("fixture"); return table;
    }
}
