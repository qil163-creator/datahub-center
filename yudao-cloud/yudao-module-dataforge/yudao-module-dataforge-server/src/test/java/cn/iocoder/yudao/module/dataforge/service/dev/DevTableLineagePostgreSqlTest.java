package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 JSONB 和冻结版本往返验证，不在业务库建表或修改任务。 */
@Testcontainers(disabledWithoutDocker = true)
class DevTableLineagePostgreSqlTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void jsonbStorageAndTaskSnapshotPreserveTheTypedLineageWithoutIdentifierLoss() throws Exception {
        var config = DevTableLineageTest.config(DevTableLineageTest.reference(9_007_199_254_740_993L,"table_a"));
        config.getTableLineage().getUpstream().getFirst().setSchemaName("public");
        var json = JsonUtils.toJsonString(config);
        var snapshot = new DevTaskSnapshot(); snapshot.setTask(DevTaskDO.builder().id(1L).taskType("SQL").executionConfigJson(json).build());
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE lineage_roundtrip(execution_config_json jsonb,snapshot_json jsonb)");
            try (var insert = connection.prepareStatement("INSERT INTO lineage_roundtrip VALUES(?,?)")) {
                insert.setObject(1,json,Types.OTHER); insert.setObject(2,snapshot.write(),Types.OTHER); insert.executeUpdate();
            }
            try (var result = statement.executeQuery("SELECT execution_config_json::text,snapshot_json::text,execution_config_json#>>'{tableLineage,upstream,0,databaseId}' FROM lineage_roundtrip")) {
                assertTrue(result.next()); assertEquals("9007199254740993",result.getString(3));
                assertEquals(config,DevTaskDefinition.readExecutionConfig(result.getString(1)));
                var restored = DevTaskSnapshot.read(result.getString(2));
                assertEquals(config,DevTaskDefinition.readExecutionConfig(restored.getTask().getExecutionConfigJson()));
            }
        }
    }
}
