package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.dbswitch.common.type.CaseConvertEnum;
import cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.AccessTaskConfigTypeHandler;
import cn.iocoder.yudao.module.dataforge.service.access.config.*;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccessTaskConfigTest {
    @Test void identityDeclarationsDefaultFalseAndSurviveFrozenJsonAndExplicitMapping() {
        var legacy=AccessTaskConfigCodec.read("{\"schemaVersion\":1,\"source\":[{}],\"target\":{}}");
        var oldRuntime=AccessTaskConfigMapper.toRuntime(legacy,42L);
        assertFalse(oldRuntime.getSource().getFirst().getIdentitySourceQuiesced());
        assertFalse(oldRuntime.getTarget().getPreserveIdentityValues());
        assertFalse(oldRuntime.getTarget().getExclusiveTargetOwnership());
        var definition=definition();var access=new cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO();
        access.setDataAccessJson(definition);String before=AccessRunSnapshot.freeze(access);
        definition.getSource().getFirst().setIdentitySourceQuiesced(true);
        definition.getTarget().setPreserveIdentityValues(true);definition.getTarget().setExclusiveTargetOwnership(true);
        String frozen=AccessRunSnapshot.freeze(access);
        assertNotEquals(cn.hutool.crypto.digest.DigestUtil.sha256Hex(before),cn.hutool.crypto.digest.DigestUtil.sha256Hex(frozen));
        var restored=AccessRunSnapshot.restore(frozen).getDataAccessJson();
        assertTrue(restored.getSource().getFirst().getIdentitySourceQuiesced());
        assertTrue(restored.getTarget().isPreserveIdentityValues());assertTrue(restored.getTarget().isExclusiveTargetOwnership());
        var runtime=AccessTaskConfigMapper.toRuntime(restored,42L);
        assertTrue(runtime.getSource().getFirst().getIdentitySourceQuiesced());
        assertTrue(runtime.getTarget().getPreserveIdentityValues());assertTrue(runtime.getTarget().getExclusiveTargetOwnership());
        definition.getSource().getFirst().setIdentitySourceQuiesced(false);
        assertTrue(AccessRunSnapshot.restore(frozen).getDataAccessJson().getSource().getFirst().getIdentitySourceQuiesced());
    }

    @Test void identityDeclarationsRejectCoercionMissingOwnershipAndIncrementalMode() {
        for(String value:List.of("null","\"true\"","1"))assertThrows(IllegalArgumentException.class,()->AccessTaskConfigCodec.read(
                "{\"schemaVersion\":1,\"source\":[{\"identitySourceQuiesced\":"+value+"}],\"target\":{}}"));
        for(String property:List.of("preserveIdentityValues","exclusiveTargetOwnership")) {
            assertThrows(IllegalArgumentException.class,()->AccessTaskConfigCodec.read(
                    "{\"schemaVersion\":1,\"source\":[{}],\"target\":{\""+property+"\":true}}"));
            assertThrows(IllegalArgumentException.class,()->AccessTaskConfigCodec.read(
                    "{\"schemaVersion\":1,\"source\":[{}],\"target\":{\""+property+"\":null}}"));
        }
        assertThrows(IllegalArgumentException.class,()->AccessTaskConfigCodec.read(
                "{\"schemaVersion\":1,\"source\":[{}],\"target\":{\"identitySourceQuiesced\":true}}"));
        var config=definition();config.getTarget().setPreserveIdentityValues(true);config.getTarget().setExclusiveTargetOwnership(true);
        config.getTarget().setChangeDataSync(true);config.getTarget().setChangeDataSyncType("PK_ORDER_COMPARE");
        assertThrows(IllegalArgumentException.class,()->AccessTaskConfigCodec.write(config));
        config.getTarget().setChangeDataSync(false);
        // The business DTO does not know the resolved product: PostgreSQL does not require the SQL Server source declaration.
        assertFalse(AccessTaskConfigMapper.toRuntime(config,42L).getSource().getFirst().getIdentitySourceQuiesced());
    }
    @Test void runtimeMappingPreservesOptionsAndNeverMutatesSavedRules() {
        var definition = definition();
        var source = definition.getSource().getFirst();
        source.setMapperType("SINGLE");
        source.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1");
        source.setCursorBootstrapAllowed(true);
        source.setTableReadMode("ONLY");
        var table = new AccessTableMapping();
        table.setRegexColumnMapper(new ArrayList<>(List.of(new AccessNameMapping("^old$", "new"))));
        table.setSourcePrimaryKeys(new ArrayList<>(List.of("tenant", "id")));
        table.setIncreaseColumnName("modified_at"); table.setTimestampColumn("event_time");
        source.setConfigMap(Map.of("orders", table));
        var target = definition.getTarget();
        target.setTargetDrop(true); target.setSyncExist(false); target.setOnlyCreate(true);
        target.setTargetTruncateData(true); target.setIndexCreate(true); target.setSyncDefaultVal(true);
        target.setCreateTableAutoIncrement(true); target.setLowercase(true);
        target.setChangeDataSync(true); target.setChangeDataSyncType("PK_ORDER_COMPARE");
        target.setTargetSyncOption("INSERT_UPDATE"); target.setTablePrefix("ods_");
        String before = AccessTaskConfigCodec.write(definition);
        var first = AccessTaskConfigMapper.toRuntime(definition, 42L);
        var second = AccessTaskConfigMapper.toRuntime(definition, 42L);
        assertEquals(42L, first.getTarget().getDatabaseId());
        assertTrue(first.getTarget().getTargetDrop()); assertFalse(first.getTarget().getSyncExist());
        assertTrue(first.getTarget().getOnlyCreate()); assertTrue(first.getTarget().getTargetTruncateData());
        assertTrue(first.getTarget().getIndexCreate()); assertTrue(first.getTarget().getSyncDefaultVal());
        assertTrue(first.getTarget().getCreateTableAutoIncrement()); assertTrue(first.getTarget().getLowercase());
        assertEquals("INSERT_UPDATE", first.getTarget().getTargetSyncOption().name());
        assertTrue(first.getSource().getFirst().getCursorBootstrapAllowed());
        assertEquals("ONLY", first.getSource().getFirst().getTableReadMode().name());
        var runtimeTable = first.getSource().getFirst().getConfigMap().get("orders");
        assertEquals(List.of("tenant", "id"), runtimeTable.getSourcePrimaryKeys());
        runtimeTable.getRegexColumnMapper().getFirst().setToValue("runtime-only");
        runtimeTable.getSourcePrimaryKeys().clear();
        runtimeTable.setIncreaseStartVal("runtime-watermark");
        first.getSource().getFirst().setPassword("ephemeral-only");
        assertEquals(before, AccessTaskConfigCodec.write(definition));
        assertEquals("new", second.getSource().getFirst().getConfigMap().get("orders").getRegexColumnMapper().getFirst().getToValue());
        assertNull(second.getSource().getFirst().getPassword());
    }

    @Test void nameCasesSurviveTheCodecFrozenRunAndReachTheRuntimeTargetIndependently() {
        var definition = definition();
        definition.getTarget().setTableNameCase("SNAKE"); definition.getTarget().setColumnNameCase("CAMEL");
        definition.getTarget().setUppercase(true);
        String json = AccessTaskConfigCodec.write(definition);
        assertTrue(json.contains("\"tableNameCase\":\"SNAKE\"")); assertTrue(json.contains("\"columnNameCase\":\"CAMEL\""));
        var access = new cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO(); access.setDataAccessJson(definition);
        var restored = AccessRunSnapshot.restore(AccessRunSnapshot.freeze(access)).getDataAccessJson();
        assertEquals("SNAKE", restored.getTarget().getTableNameCase()); assertEquals("CAMEL", restored.getTarget().getColumnNameCase());
        var target = AccessTaskConfigMapper.toRuntime(restored, 42L).getTarget();
        assertEquals(CaseConvertEnum.SNAKE, target.getTableNameCase()); assertEquals(CaseConvertEnum.CAMEL, target.getColumnNameCase());
        assertTrue(target.getUppercase());
        definition.getTarget().setColumnNameCase(null);
        var tableOnly = AccessTaskConfigMapper.toRuntime(AccessTaskConfigCodec.read(AccessTaskConfigCodec.write(definition)), 42L).getTarget();
        assertEquals(CaseConvertEnum.SNAKE, tableOnly.getTableNameCase()); assertNull(tableOnly.getColumnNameCase());
    }

    @Test void legacyDefinitionsWithoutNameCasesKeepTheirFlagsAndAreNotRewrittenWithNewKeys() {
        String saved = "{\"schemaVersion\":1,\"source\":[{}],\"target\":{\"lowercase\":true}}";
        var legacy = AccessTaskConfigCodec.read(saved);
        assertNull(legacy.getTarget().getTableNameCase()); assertNull(legacy.getTarget().getColumnNameCase());
        assertFalse(AccessTaskConfigCodec.write(legacy).contains("NameCase"));
        var target = AccessTaskConfigMapper.toRuntime(legacy, 42L).getTarget();
        assertTrue(target.getLowercase()); assertFalse(target.getUppercase());
        assertNull(target.getTableNameCase()); assertNull(target.getColumnNameCase());
        var explicitNull = AccessTaskConfigCodec.read(
                "{\"schemaVersion\":1,\"source\":[{}],\"target\":{\"tableNameCase\":null,\"columnNameCase\":null}}");
        assertNull(explicitNull.getTarget().getTableNameCase()); assertNull(explicitNull.getTarget().getColumnNameCase());
    }

    @Test void nameCasesAcceptOnlyTheVersionOneWhitelistAndBothLegacyFlagsStayRejected() {
        for (String property : List.of("tableNameCase", "columnNameCase")) {
            for (String value : List.of("NONE", "UPPER", "LOWER", "SNAKE", "CAMEL")) assertEquals(value, property.startsWith("table")
                    ? AccessTaskConfigCodec.read(target(property, "\"" + value + "\"")).getTarget().getTableNameCase()
                    : AccessTaskConfigCodec.read(target(property, "\"" + value + "\"")).getTarget().getColumnNameCase());
            for (String value : List.of("\"snake\"", "\"KEBAB\"", "\"\"", "\" SNAKE\"", "1", "true", "{}", "[]"))
                assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.read(target(property, value)), property + "=" + value);
        }
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.read("{\"schemaVersion\":1,\"source\":[{}],\"target\":"
                + "{\"lowercase\":true,\"uppercase\":true,\"tableNameCase\":\"NONE\",\"columnNameCase\":\"NONE\"}}"));
        var config = definition(); config.getTarget().setColumnNameCase("snake");
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.write(config));
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigMapper.toRuntime(config, 42L));
    }

    @Test void persistedAndHttpModelsContainNoComponentTypesAndNoImplicitVersionUpgrade() {
        String json = AccessTaskConfigCodec.write(definition());
        assertFalse(json.contains("driverPath")); assertFalse(json.contains("password"));
        assertEquals(1, AccessTaskConfigCodec.read(json).getSchemaVersion());
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.read(json.replace("\"schemaVersion\":1,", "")));
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.read(json.replace("\"schemaVersion\":1", "\"schemaVersion\":2")));
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.read(json + " {}"));
        for (var type : List.of(AccessTaskConfig.class, AccessTaskConfig.Source.class, AccessTaskConfig.Target.class,
                AccessTableMapping.class, AccessNameMapping.class,
                cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessSaveReqVO.class,
                cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessRespVO.class,
                cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessPreviewReqVO.class)) {
            for (var field : type.getDeclaredFields()) assertFalse(field.getGenericType().getTypeName().contains("framework.dbswitch"));
        }
    }

    @Test void unknownCredentialsCallbacksAndRuntimeWatermarksAreRejectedWithoutLeakingValues() {
        for (String fragment : List.of("\"password\":\"do-not-print\"", "\"driverPath\":\"do-not-print\"",
                "\"increaseColumn\":{}", "\"increaseStartVal\":\"do-not-print\"")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.read(
                    "{\"schemaVersion\":1,\"source\":[{" + fragment + "}],\"target\":{}}"));
            assertFalse(failure.toString().contains("do-not-print")); assertNull(failure.getCause());
        }
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.read(
                "{\"schemaVersion\":1,\"source\":[{\"configMap\":{\"orders\":{\"increaseEndVal\":\"9\"}}}],\"target\":{}}"));
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.read(
                "{\"schemaVersion\":1,\"source\":[{}],\"target\":{\"cursorRunBindings\":{}}}"));
    }

    @Test void typeHandlerBindsJsonbAndValidatesBeforeTouchingStatement() throws Exception {
        var statement = mock(PreparedStatement.class);
        var handler = new AccessTaskConfigTypeHandler();
        var definition = definition();
        handler.setNonNullParameter(statement, 3, definition, JdbcType.OTHER);
        verify(statement).setObject(3, AccessTaskConfigCodec.write(definition), Types.OTHER);
        clearInvocations(statement);
        definition.setSchemaVersion(9);
        assertThrows(java.sql.SQLException.class, () -> handler.setNonNullParameter(statement, 3, definition, JdbcType.OTHER));
        verifyNoInteractions(statement);
    }

    private static AccessTaskConfig definition() {
        var config = new AccessTaskConfig();
        var source = new AccessTaskConfig.Source(); source.setSourceIncludes("orders");
        config.setSource(new ArrayList<>(List.of(source)));
        return config;
    }

    private static String target(String property, String json) {
        return "{\"schemaVersion\":1,\"source\":[{}],\"target\":{\"" + property + "\":" + json + "}}";
    }
}
