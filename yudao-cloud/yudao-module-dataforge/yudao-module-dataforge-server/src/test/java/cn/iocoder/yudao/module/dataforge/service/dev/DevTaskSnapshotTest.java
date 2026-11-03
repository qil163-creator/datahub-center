package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DEV_TASK_SNAPSHOT_INVALID;
import static org.junit.jupiter.api.Assertions.*;

class DevTaskSnapshotTest {

    @Test
    void legacyCurrentPointerIsReadWithoutLosingExactIdsAndNewJsonHasOnlyCurrentField() {
        String json = "{\"task\":{\"id\":\"9007199254740993\",\"publishedVersionId\":\"9007199254740995\"}}";
        var snapshot = DevTaskSnapshot.read(json);
        assertEquals(9_007_199_254_740_995L, snapshot.getTask().getCurrentVersionId());
        var written = JsonUtils.parseTree(snapshot.write()).path("task");
        assertFalse(written.has("publishedVersionId"));
        assertEquals("9007199254740995", written.path("currentVersionId").asText());
    }

    @Test
    void legacySnapshotWithoutVersionRetainsFrozenConfigurationAndDependency() {
        DevTaskSnapshot snapshot = DevTaskSnapshot.read("""
                {"task":{"id":10,"taskType":"FLINK_SQL","content":"SELECT 1","dsDatasourceId":7,
                 "timeoutSeconds":300,"maxRows":25},"workerGroup":"isolated","tenantCode":"datahub",
                 "dependencyVersions":{"20":90}}
                """);
        assertEquals(1, snapshot.getSchemaVersion());
        assertEquals("FLINK_SQL", snapshot.getTask().getTaskType());
        assertEquals(7, snapshot.getTask().getDsDatasourceId());
        assertEquals(25, snapshot.getTask().getMaxRows());
        assertEquals("isolated", snapshot.getWorkerGroup());
        assertEquals("datahub", snapshot.getTenantCode());
        assertEquals(Map.of(20L, 90L), snapshot.getDependencyVersions());
    }

    @Test
    void newSnapshotWritesVersionAndReadsWithoutLosingExactIdentifiers() {
        DevTaskSnapshot snapshot = new DevTaskSnapshot();
        snapshot.setTask(DevTaskDO.builder().id(9_007_199_254_740_993L).taskType("SPARK_SQL")
                .content("SELECT 1").dsDatasourceId(8).timeoutSeconds(900).build());
        snapshot.setDependencyVersions(Map.of(30L, 9_007_199_254_740_995L));
        String json = snapshot.write();
        assertEquals(1, JsonUtils.parseTree(json).path("schemaVersion").intValue());
        DevTaskSnapshot restored = DevTaskSnapshot.read(json);
        assertEquals(snapshot.getTask().getId(), restored.getTask().getId());
        assertEquals(snapshot.getDependencyVersions(), restored.getDependencyVersions());
        assertEquals(900, restored.getTask().getTimeoutSeconds());
    }

    @Test
    void unknownNullCoercedOrOverflowingVersionIsNeverAcceptedAsCurrent() {
        for (String version : new String[]{"2", "0", "-1", "null", "\"1\"", "1.0", "true", "4294967297"}) {
            assertInvalid("{\"schemaVersion\":" + version + ",\"task\":{}}", version);
        }
    }

    @Test
    void incompleteMalformedOrTrailingPayloadIsRejectedWithoutLeakingContent() {
        for (String json : new String[]{"", "null", "[]", "{}", "{\"task\":null}",
                "{\"task\":{}} {\"privateScript\":\"sensitive-fixture\"}",
                "{\"task\":{\"content\":\"sensitive-fixture\"}",
                "{\"task\":{},\"dependencyVersions\":null}",
                "{\"task\":{},\"dependencyVersions\":{\"20\":null}}",
                "{\"task\":{},\"dependencyVersions\":{\"0\":90}}"}) {
            ServiceException error = assertInvalid(json, json);
            assertFalse(error.getMessage().contains("sensitive-fixture"));
        }
    }

    @Test
    void writeAlsoRejectsUnsupportedOrIncompleteSnapshot() {
        DevTaskSnapshot snapshot = new DevTaskSnapshot();
        assertThrows(ServiceException.class, snapshot::write);
        snapshot.setTask(DevTaskDO.builder().id(1L).build());
        snapshot.setSchemaVersion(2);
        assertThrows(ServiceException.class, snapshot::write);
    }

    private static ServiceException assertInvalid(String json, String description) {
        ServiceException error = assertThrows(ServiceException.class, () -> DevTaskSnapshot.read(json), description);
        assertEquals(DEV_TASK_SNAPSHOT_INVALID.getCode(), error.getCode());
        return error;
    }
}
