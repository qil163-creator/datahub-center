package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfig;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfigCodec;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfigMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AccessHookConfigTest {
    @Test void oldV1DefinitionsRemainWithoutHooksAndNoNewNullFieldsAreSerialized() {
        var config = AccessTaskConfigCodec.read("{\"schemaVersion\":1,\"source\":[{}],\"target\":{}}");
        var runtime = AccessTaskConfigMapper.toRuntime(config, 20L);
        assertNull(runtime.getSource().getFirst().getBeforeSqlScripts()); assertNull(runtime.getSource().getFirst().getAfterSqlScripts());
        assertNull(runtime.getTarget().getBeforeSqlScripts()); assertNull(runtime.getTarget().getAfterSqlScripts());
        assertFalse(AccessTaskConfigCodec.write(config).contains("SqlScripts"));
    }

    @Test void allFourHooksSurviveJsonMappingAndAnIndependentRunSnapshot() {
        var config = new AccessTaskConfig(); config.setSource(List.of(new AccessTaskConfig.Source()));
        var source = config.getSource().getFirst(); var target = config.getTarget();
        source.setBeforeSqlScripts("SELECT 1"); source.setAfterSqlScripts("SELECT 2");
        target.setBeforeSqlScripts("SELECT 3"); target.setAfterSqlScripts("SELECT 4");
        var definition = new DataAccessDO(); definition.setDataAccessJson(config);
        String frozen = AccessRunSnapshot.freeze(definition);
        source.setBeforeSqlScripts("SELECT 5"); target.setAfterSqlScripts("SELECT 6");
        var restored = AccessRunSnapshot.restore(frozen).getDataAccessJson();
        var runtime = AccessTaskConfigMapper.toRuntime(restored, 20L);
        assertEquals("SELECT 1", runtime.getSource().getFirst().getBeforeSqlScripts());
        assertEquals("SELECT 2", runtime.getSource().getFirst().getAfterSqlScripts());
        assertEquals("SELECT 3", runtime.getTarget().getBeforeSqlScripts());
        assertEquals("SELECT 4", runtime.getTarget().getAfterSqlScripts());
        assertEquals(20L, runtime.getTarget().getDatabaseId());
        assertFalse(restored.getSource().getFirst().toString().contains("SELECT"));
        assertFalse(restored.getTarget().toString().contains("SELECT"));
    }

    @ParameterizedTest
    @CsvSource({"source,beforeSqlScripts", "source,afterSqlScripts", "target,beforeSqlScripts", "target,afterSqlScripts"})
    void eachFieldRejectsCoercionOversizeAndNul(String scope, String field) {
        for (String value : List.of("1", "true", "{}", "[]", "\"" + "x".repeat(AccessTaskConfig.MAX_HOOK_SQL_LENGTH + 1) + "\"", "\"\\u0000\"")) {
            String node = "{\"" + field + "\":" + value + "}";
            String json = "{\"schemaVersion\":1,\"source\":[" + (scope.equals("source") ? node : "{}")
                    + "],\"target\":" + (scope.equals("target") ? node : "{}") + "}";
            assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.read(json));
        }
    }
}

