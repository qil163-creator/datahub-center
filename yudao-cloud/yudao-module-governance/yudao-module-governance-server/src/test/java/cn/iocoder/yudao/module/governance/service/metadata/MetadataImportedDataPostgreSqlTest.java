package cn.iocoder.yudao.module.governance.service.metadata;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.MetaFinalizedDetailRespVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

/** 在已执行导入迁移的独立回环测试库上验证真实冻结数据，不访问源系统。 */
@EnabledIfEnvironmentVariable(named = "QDATA_IMPORT_TEST_JDBC_URL",
        matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[a-zA-Z0-9_]+")
class MetadataImportedDataPostgreSqlTest {
    @Test
    void allFrozenImportedViewsDeserializeAndPreserveTheirOwnFields() throws Exception {
        try (var connection = DriverManager.getConnection(System.getenv("QDATA_IMPORT_TEST_JDBC_URL"),
                System.getenv("QDATA_IMPORT_TEST_JDBC_USER"), "");
             var query = connection.createStatement();
             var rows = query.executeQuery("SELECT id,finalized_snapshot::text FROM \"datahub-center\".dh_governance_meta_table "
                     + "WHERE display_snapshot->>'bundleId'='qdata-metadata-20261022' AND finalized_snapshot IS NOT NULL")) {
            int count = 0;
            while (rows.next()) {
                var frozen = JsonUtils.parseObject(rows.getString(2), MetaFinalizedDetailRespVO.class);
                assertEquals(rows.getLong(1), frozen.getTable().getId());
                assertTrue(frozen.getTable().getReadOnly());
                assertNotNull(frozen.getTable().getTableName());
                assertNotNull(frozen.getTable().getFinalizedVersion());
                assertNotNull(frozen.getColumns());
                assertEquals(frozen.getColumns().size(), frozen.getTable().getColumnCount());
                for (var column : frozen.getColumns()) {
                    assertNotNull(column.getColumnName()); assertTrue(column.getReadOnly());
                }
                count++;
            }
            assertEquals(38, count);
        }
    }
}
