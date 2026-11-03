package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.module.dataforge.service.access.config.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AccessTypeMappingConfigTest {
    @Test void oldVersionOneTasksKeepRejectingLosses() {
        var old = AccessTaskConfigCodec.read("{\"schemaVersion\":1,\"source\":[{}],\"target\":{}}");
        assertTrue(old.getTypeMappingAcknowledgements().isEmpty());
        assertTrue(AccessTaskConfigMapper.toRuntime(old, 2L).snapshot().getTypeMappingAcknowledgements().isEmpty());
    }

    @Test void decisionsFreezeInTheExistingRunAndNeverAcquireRuntimeConnectionMaterial() {
        var config = config();
        var decisions = new ArrayList<>(List.of(decision()));
        config.setTypeMappingAcknowledgements(decisions);
        var definition = DataAccessDO.builder().id(9007199254740993L).engineType(1)
                .sourceDatabaseId(1L).targetDatabaseId(2L).dataAccessJson(config).build();
        String frozen = AccessRunSnapshot.freeze(definition);
        decisions.clear(); config.getSource().getFirst().setSourceIncludes("different");
        var restored = AccessRunSnapshot.restore(frozen);
        assertEquals(9007199254740993L, restored.getId());
        assertEquals(List.of(decision()), restored.getDataAccessJson().getTypeMappingAcknowledgements());
        assertEquals("orders", restored.getDataAccessJson().getSource().getFirst().getSourceIncludes());
        var runtime = AccessTaskConfigMapper.toRuntime(restored.getDataAccessJson(), 2L).snapshot();
        var accepted = runtime.getTypeMappingAcknowledgements().getFirst();
        assertEquals("migration", accepted.targetSchema()); assertEquals("ods_orders", accepted.targetTable());
        assertEquals("note", accepted.column()); assertEquals("a".repeat(64), accepted.mappingFingerprint());
        assertEquals(java.util.Set.of("DECLARED_TYPE_AFFINITY_NOT_STRICT"), accepted.lossCodes());
        assertThrows(UnsupportedOperationException.class, () -> accepted.lossCodes().clear());
        assertNull(runtime.getSource().getFirst().getPassword()); assertNull(runtime.getTarget().getUrl());
        assertFalse(frozen.contains("jdbc:")); assertFalse(frozen.contains("password"));
    }

    @Test void nullUnknownProductDuplicateScopeAndRuntimeFieldsFailWithoutEchoingInput() {
        var config = config(); config.setTypeMappingAcknowledgements(List.of(decision(), decision()));
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.write(config));
        config.setTypeMappingAcknowledgements(null);
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.write(config));
        config.setTypeMappingAcknowledgements(List.of(new AccessTypeMappingAcknowledgement("UNKNOWN", "migration", "ods_orders",
                "note", "a".repeat(64), List.of("DECLARED_TYPE_AFFINITY_NOT_STRICT"))));
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.write(config));
        config.setTypeMappingAcknowledgements(List.of(decision()));
        String json = AccessTaskConfigCodec.write(config);
        var failure = assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.read(
                json.replace("\"mappingFingerprint\":", "\"password\":\"do-not-print\",\"mappingFingerprint\":")));
        assertFalse(failure.toString().contains("do-not-print")); assertNull(failure.getCause());
        assertThrows(IllegalArgumentException.class, () -> new AccessTypeMappingAcknowledgement("POSTGRESQL", "migration", "ods_orders",
                "note", "a".repeat(64), List.of("LOSS", "LOSS")));
    }

    @Test void acknowledgementsDoNotWidenSqlOrTypedCursorModes() {
        var config = config(); config.setTypeMappingAcknowledgements(List.of(decision()));
        config.getSource().getFirst().setSourceType(2);
        config.getSource().getFirst().setSourceSql("SELECT 1"); config.getTarget().setTargetTable("ods_orders");
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.write(config));
        config.getSource().getFirst().setSourceType(1);
        config.getTarget().setChangeDataSync(true); config.getTarget().setChangeDataSyncType("INCREASE_COLUMN");
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.write(config));
        config.getTarget().setChangeDataSyncType("PK_ORDER_COMPARE");
        assertEquals(List.of(decision()), AccessTaskConfigCodec.copy(config).getTypeMappingAcknowledgements());
    }

    static AccessTaskConfig config() {
        var config = new AccessTaskConfig(); var source = new AccessTaskConfig.Source(); source.setSourceIncludes("orders");
        config.setSource(List.of(source)); config.getTarget().setTablePrefix("ods_"); return config;
    }

    static AccessTypeMappingAcknowledgement decision() {
        return new AccessTypeMappingAcknowledgement("POSTGRESQL", "migration", "ods_orders", "note", "a".repeat(64),
                List.of("DECLARED_TYPE_AFFINITY_NOT_STRICT"));
    }
}
