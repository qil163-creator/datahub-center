package cn.iocoder.yudao.framework.dbswitch.data.handler;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.DatabaseAwareUtils;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeFidelity;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeMappingOptions;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeMappingRejectedException;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByDataSourceServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.IDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.MYSQL;
import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Control-boundary regression with actual registered PostgreSQL/MySQL type mappers and catalog-shaped
 * source facts. Metadata/operators/writer are observed fixtures, not native MySQL integration proof.
 */
class MigrationExistingTargetTypeDecisionTest {

    @ParameterizedTest(name = "{0} / {1} with no explicit decision")
    @CsvSource({"real,FULL_TRUNCATE", "uuid,FULL_TRUNCATE", "real,PK_DIFF", "uuid,PK_DIFF"})
    void existingNonSqliteTargetsRejectLossOrUnsupportedTypesBeforeAnyMutation(String type, String operation) {
        var properties = properties(operation);
        assertTrue(properties.getTypeMappingAcknowledgements().isEmpty());
        var columns = List.of(pgColumn("id", "integer", Types.INTEGER, false),
                pgColumn("value", type, type.equals("real") ? Types.REAL : Types.OTHER, true));
        var sourceOperator = mock(IDatabaseOperator.class);
        var targetOperator = mock(IDatabaseOperator.class);
        var writer = mock(IDatabaseWriter.class);

        try (var source = new HikariDataSource(); var target = new HikariDataSource();
             var runtime = spy(DbSwitchRuntime.standalone());
             var metadata = mockConstruction(MetaDataByDataSourceServiceImpl.class, (database, context) -> {
                 when(database.queryTableColumnMeta(anyString(), anyString())).thenReturn(columns);
                 when(database.queryTableColumnMetaWithNativeEvidence(anyString(), anyString())).thenReturn(columns);
                 when(database.queryTablePrimaryKeys(anyString(), anyString())).thenReturn(List.of("id"));
                 when(database.queryTableList(anyString())).thenReturn(List.of(table()));
                 when(database.queryTableColumnName(anyString(), anyString())).thenReturn(List.of("id", "value"));
             });
             var engines = mockStatic(DatabaseAwareUtils.class)) {
            doReturn(sourceOperator).when(runtime).reader(same(source), eq(POSTGRESQL));
            doReturn(targetOperator).when(runtime).reader(same(target), eq(MYSQL));
            doReturn(writer).when(runtime).writer(same(target), eq(MYSQL), any());
            when(writer.getDataSource()).thenReturn(target);
            when(sourceOperator.nativeMigrationColumns("public", "fixture")).thenReturn(List.of());
            when(targetOperator.nativeMigrationColumns("public", "fixture")).thenReturn(List.of());
            engines.when(() -> DatabaseAwareUtils.isMysqlInnodbStorageEngine("public", "fixture", target)).thenReturn(true);

            // Establish the exact loss/blocker before invoking the handler; UNKNOWN metadata cannot stand in for LOSSY.
            var expected = GenerateSqlUtils.planCrossProductColumns(runtime.products().metadata(MYSQL),
                    columns, List.of("id"), TypeMappingOptions.of(runtime.products().typeMappings()));
            assertEquals(TypeFidelity.EXACT, expected.column("id").fidelity());
            assertEquals(type.equals("real") ? TypeFidelity.LOSSY : TypeFidelity.UNSUPPORTED,
                    expected.column("value").fidelity());
            if (type.equals("real")) {
                assertEquals(Set.of("FLOAT32_PRECISION_NOT_PRESERVED", "NEGATIVE_ZERO_NOT_PRESERVED"),
                        expected.column("value").lossCodes());
            } else {
                assertEquals("UUID_TYPE_UNAVAILABLE", expected.column("value").reasonCode());
            }

            var handler = MigrationHandler.createInstance(table(), properties, 0, source, target, null, runtime);
            var failure = assertThrows(RuntimeException.class, handler::get);
            assertAll(
                    () -> assertInstanceOf(TypeMappingRejectedException.class, failure),
                    () -> assertNotNull(handler.partialResult().getTypeMappingPlan()),
                    () -> verifyNoInteractions(writer),
                    () -> verify(targetOperator, never()).dropTable(anyString(), anyString()),
                    () -> verify(targetOperator, never()).truncateTableData(anyString(), anyString()),
                    () -> verify(runtime, never()).writer(any(), any(ProductTypeEnum.class), any()),
                    () -> metadata.constructed().forEach(database -> {
                        verify(database, never()).addNoExistColumnsByTarget(anyString(), anyString(), anyList());
                    }));
            var rejected = ((TypeMappingRejectedException) failure).plan();
            assertFalse(rejected.accepted());
            assertEquals(expected.column("value").fingerprint(), rejected.column("value").fingerprint());
            assertEquals(rejected, handler.partialResult().getTypeMappingPlan());
            verify(sourceOperator).nativeMigrationColumns("public", "fixture");
            verifyNoMoreInteractions(sourceOperator);
        }
    }

    private static MigrationRequest properties(String operation) {
        var properties = new MigrationRequest();
        var source = new SourceDataSourceProperties();
        source.setSourceProductType(POSTGRESQL); source.setSourceSchema("public");
        properties.setSource(List.of(source));
        var target = properties.getTarget();
        target.setTargetProductType(MYSQL); target.setTargetSchema("public");
        target.setTargetDrop(false); target.setSyncExist(true);
        target.setTargetTruncateData(operation.equals("FULL_TRUNCATE"));
        target.setChangeDataSync(operation.equals("PK_DIFF"));
        if (operation.equals("PK_DIFF")) target.setChangeDataSyncType("PK_ORDER_COMPARE");
        return properties;
    }

    private static TableDescription table() {
        var table = new TableDescription(); table.setSchemaName("public"); table.setTableName("fixture"); return table;
    }

    /** Same non-character PG catalog shape exercised by the existing mapper golden cases. */
    private static ColumnDescription pgColumn(String name, String type, int jdbcType, boolean nullable) {
        var column = new ColumnDescription();
        column.setFieldName(name); column.setLabelName(name); column.setDbType(POSTGRESQL);
        column.setFieldType(jdbcType); column.setNullable(nullable);
        column.setMetadataEvidence(new ColumnMetadataEvidence(
                new MetadataProvenance(POSTGRESQL, "PostgreSQL", "16.15", "NATIVE_CATALOG_V1", Instant.EPOCH),
                MetadataValue.value(type), MetadataValue.absent(), MetadataValue.value(nullable),
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(),
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent()));
        return column;
    }
}
