package cn.iocoder.yudao.framework.etl.core.compile;

import org.junit.jupiter.api.Test;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class EtlNativeTdengineCompilerTest {
    @Test void tmqIsExplicitlyContinuousAndDoesNotContainInlineCredentials() {
        var plan = compile(Map.of("topic", "sensor_topic", "groupId", "sensor_group", "offsetReset", "latest"), binding());
        assertTrue(plan.table().continuousSource()); assertEquals(List.of("ts", "reading", "station"), new ArrayList<>(plan.table().columns().keySet()));
        assertTrue(plan.initializationSql().contains("'connector'='datahub-tdengine-tmq'"));
        assertTrue(plan.initializationSql().contains("'auto.offset.reset'='latest'"));
        assertTrue(plan.initializationSql().contains("'credential-ref'='tdengine-test'"));
        assertFalse(plan.initializationSql().toLowerCase(Locale.ROOT).contains("password"));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeTdengineCompiler.compile(EtlNativeSqlCompiler.Engine.FLINK, false, "task:node", Map.of(), binding(), Set.of("tdengine-test")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeTdengineCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, true, "task:node", Map.of(), binding(), Set.of("tdengine-test")));
    }
    @Test void resourcesGroupsColumnsAndSecretReferencesCannotBeOverriddenByTheNode() {
        assertThrows(IllegalArgumentException.class, () -> compile(Map.of("topic", "other", "groupId", "sensor_group"), binding()));
        assertThrows(IllegalArgumentException.class, () -> compile(Map.of("topic", "sensor_topic", "groupId", "other"), binding()));
        assertThrows(IllegalArgumentException.class, () -> compile(Map.of("topic", "sensor_topic", "groupId", "sensor_group", "password", "ignored"), binding()));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeTdengineCompiler.compile(EtlNativeSqlCompiler.Engine.FLINK, true, "task:node", Map.of("topic", "sensor_topic", "groupId", "sensor_group"), binding(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> compile(Map.of("topic", "sensor_topic", "groupId", "sensor_group", "columns", List.of(Map.of("name", "reading", "type", "String"))), binding()));
        assertThrows(IllegalArgumentException.class, () -> compile(Map.of("topic", "sensor_topic", "groupId", "sensor_group", "columns", List.of(Map.of("name", "missing", "type", "String"))), binding()));
    }
    @Test void selectedFieldsUseTheOrderedAuthorizedPhysicalSchema() {
        var plan = compile(Map.of("topic", "sensor_topic", "groupId", "sensor_group", "columns", List.of(Map.of("name", "station", "type", "String"), Map.of("name", "reading", "type", "Double"))), binding());
        assertEquals(List.of("station", "reading"), new ArrayList<>(plan.table().columns().keySet()));
        assertTrue(plan.initializationSql().contains("`ts` TIMESTAMP(3), `reading` DOUBLE, `station` STRING"));
        assertTrue(plan.initializationSql().contains("AS SELECT `station`, `reading` FROM"));
    }
    @Test void writesActualCompilerGeneratedDdlForTheRuntimeFixture() throws Exception {
        var plan = compile(Map.of("databaseId", "901", "topic", "sensor_topic", "groupId", "sensor_group", "offsetReset", "earliest"), binding());
        Path file = Path.of("target", "native-sql", "tdengine-source.sql"); Files.createDirectories(file.getParent()); Files.writeString(file, plan.initializationSql());
    }
    @Test void metadataUsesNativeCatalogTimestampEvidenceAndExactPhysicalOrder() {
        var timestamp = nativeColumn("ts", "TIMESTAMP", MetadataValue.value(3));
        var reading = nativeColumn("reading", "DOUBLE", MetadataValue.unknown());
        var station = nativeColumn("station", "NCHAR(64)", MetadataValue.unknown());
        var actual = List.of(timestamp, reading, station);
        assertDoesNotThrow(() -> EtlNativeTdengineCompiler.validateMetadata(binding().columns(), actual));
        timestamp.setScaleSize(9);
        assertDoesNotThrow(() -> EtlNativeTdengineCompiler.validateMetadata(binding().columns(), actual));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeTdengineCompiler.validateMetadata(binding().columns(), List.of(reading, timestamp, station)));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeTdengineCompiler.validateMetadata(Map.of("ts", "TIMESTAMP(3)"), actual));
        timestamp.setMetadataEvidence(ColumnMetadataEvidence.unknown());
        assertThrows(IllegalArgumentException.class, () -> EtlNativeTdengineCompiler.validateMetadata(binding().columns(), actual));
        timestamp.setMetadataEvidence(nativeColumn("ts", "TIMESTAMP", MetadataValue.absent()).getMetadataEvidence());
        assertThrows(IllegalArgumentException.class, () -> EtlNativeTdengineCompiler.validateMetadata(binding().columns(), actual));
        timestamp.setMetadataEvidence(nativeColumn("ts", "TIMESTAMP", MetadataValue.value(6)).getMetadataEvidence());
        assertThrows(IllegalArgumentException.class, () -> EtlNativeTdengineCompiler.validateMetadata(binding().columns(), actual));
    }
    private static ColumnDescription nativeColumn(String name, String type, MetadataValue<Integer> scale) {
        var column = new ColumnDescription(); column.setFieldName(name); column.setDbType(ProductTypeEnum.TDENGINE);
        column.setMetadataEvidence(new ColumnMetadataEvidence(new MetadataProvenance(ProductTypeEnum.TDENGINE, "TDengine", "3.3.8.8", "TDENGINE_CATALOG_V1", Instant.EPOCH),
                MetadataValue.value(type), MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(), scale,
                MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown()));
        return column;
    }
    private static EtlNativeConnectorCompiler.TablePlan compile(Map<String, Object> config, EtlNativeTdengineCompiler.Binding binding) { return EtlNativeTdengineCompiler.compile(EtlNativeSqlCompiler.Engine.FLINK, true, "task:node", config, binding, Set.of("tdengine-test")); }
    private static EtlNativeTdengineCompiler.Binding binding() {
        var fields = new LinkedHashMap<String, String>(); fields.put("ts", "TIMESTAMP(3)"); fields.put("reading", "DOUBLE"); fields.put("station", "STRING");
        return new EtlNativeTdengineCompiler.Binding("datahub-native-tdengine:6041", Set.of("sensor_topic"), Set.of("sensor_group"), "tdengine-test", fields, 500, false, true);
    }
}
