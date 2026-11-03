package cn.iocoder.yudao.module.dataforge.service.access;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfig;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfigMapper;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AccessRunSnapshotTest {
    @Test void frozenBusinessDefinitionCannotAcquireRuntimeConnections() {
        var source = new AccessTaskConfig.Source();
        source.setSourceIncludes("customers"); source.setIncludeOrExclude(1);
        var config = new AccessTaskConfig(); config.setSource(List.of(source));
        var runtime = AccessTaskConfigMapper.toRuntime(config, 20L);
        runtime.getSource().getFirst().setPassword("runtime-source-secret");
        runtime.getTarget().setUrl("jdbc:runtime-target");
        var task = DataAccessDO.builder().id(7L).engineType(1).sourceDatabaseId(10L).targetDatabaseId(20L).dataAccessJson(config).build();
        String frozen = AccessRunSnapshot.freeze(task);
        assertFalse(frozen.contains("runtime-source-secret")); assertFalse(frozen.contains("jdbc:runtime-target"));
        source.setSourceIncludes("changed-after-freeze");
        DataAccessDO restored = AccessRunSnapshot.restore(frozen);
        assertEquals(10L, restored.getSourceDatabaseId()); assertEquals(20L, restored.getTargetDatabaseId());
        assertEquals(1, restored.getDataAccessJson().getSchemaVersion());
        assertEquals("customers", restored.getDataAccessJson().getSource().getFirst().getSourceIncludes());
    }

    @Test void snapshotCannotSilentlyAcceptUnknownCredentialFieldsOrUnversionedConfig() {
        assertThrows(IllegalArgumentException.class, () -> AccessRunSnapshot.restore(
                "{\"dataAccessJson\":{\"source\":[{}],\"target\":{}}}"));
        assertThrows(IllegalArgumentException.class, () -> AccessRunSnapshot.restore(
                "{\"dataAccessJson\":{\"schemaVersion\":1,\"source\":[{\"password\":\"forbidden\"}],\"target\":{}}}"));
    }

    @Test void apiSnapshotFreezesIndependentConfigurationAndMappings() {
        var task = DataAccessDO.builder().id(7L).engineType(3).targetDatabaseId(20L)
                .apiAccessJson("{\"mappings\":[{\"apiConfig\":{\"requestUrl\":\"https://example.invalid/orders\"},\"targetTable\":\"orders\"}]}").build();
        String frozen = ReflectionTestUtils.invokeMethod(snapshotType(), "freeze", task);
        assertNotNull(frozen); assertEquals(20L, JSONUtil.parseObj(frozen).getLong("targetDatabaseId"));
        task.setApiAccessJson("{}");
        DataAccessDO restored = ReflectionTestUtils.invokeMethod(snapshotType(), "restore", frozen);
        assertTrue(restored.getApiAccessJson().contains("orders"));
        assertTrue(restored.getApiAccessJson().contains("https://example.invalid/orders"));
    }

    @Test void nativeJobSnapshotsKeepOriginalFormatAndContentWithoutImposingPlatformBindings() {
        for (String format : List.of("json", "hocon")) {
            String original = format.equals("json")
                    ? "{\"env\": {\"parallelism\":2}, \"source\": [{\"plugin_name\":\"Kafka\"}], \"sink\":[{\"plugin_name\":\"Console\"}]}"
                    : "# 原生作业\nenv { parallelism = 2 }\nsource { Kafka {} }\nsink { Console {} }\n";
            var task = DataAccessDO.builder().id(7L).engineType(2).configFormat(format)
                    .sourceDatabaseId(999L).targetDatabaseId(998L).seatunnelConfig(original).build();
            String frozen = AccessRunSnapshot.freeze(task);
            task.setSeatunnelConfig("changed-after-acceptance");
            var restored = AccessRunSnapshot.restore(frozen);
            assertEquals(original, restored.getSeatunnelConfig());
            assertEquals(format, restored.getConfigFormat());
            assertNull(restored.getSourceDatabaseId()); assertNull(restored.getTargetDatabaseId());
        }
    }

    @Test void switchingEngineDiscardsHistoricalRemoteConfigWithoutBlockingOfflineRun() {
        for (int engine : List.of(1, 3)) {
            var task = DataAccessDO.builder().id(7L).engineType(engine).configFormat("hocon")
                    .seatunnelConfig("password=historical-secret").build();
            String frozen = AccessRunSnapshot.freeze(task);
            assertFalse(frozen.contains("historical-secret"));
            assertFalse(frozen.contains("seatunnel"));
            assertNull(AccessRunSnapshot.restore(frozen).getConfigFormat());
        }
    }

    private Class<?> snapshotType() {
        try { return Class.forName("cn.iocoder.yudao.module.dataforge.service.access.AccessRunSnapshot"); }
        catch (ClassNotFoundException missing) { return fail("ACCESS has no immutable credential-free snapshot", missing); }
    }
}
