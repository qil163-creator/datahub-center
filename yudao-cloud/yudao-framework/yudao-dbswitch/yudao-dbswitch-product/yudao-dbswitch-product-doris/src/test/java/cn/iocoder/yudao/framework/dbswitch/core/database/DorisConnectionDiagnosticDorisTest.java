package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDorisImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 使用显式启用的本地 Doris 验证诊断目录，不接触已有业务对象。 */
@Timeout(60)
@EnabledIfSystemProperty(named = "datahub.test.doris.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class DorisConnectionDiagnosticDorisTest {
    @Test void actualNamespacePrivilegesAndComments() throws Exception {
        var product = new DatabaseDorisImpl();
        String schema = "diagnostic_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.doris.url"),
                System.getenv().getOrDefault("DORIS_DIAGNOSTIC_USER", "root"),
                System.getenv().getOrDefault("DORIS_DIAGNOSTIC_PASSWORD", ""));
             var statement = connection.createStatement()) {
            statement.setQueryTimeout(20);
            statement.execute("CREATE DATABASE `" + schema + "`");
            try {
                statement.execute("CREATE TABLE `" + schema + "`.`sample` (id BIGINT COMMENT '编号') "
                        + "ENGINE=OLAP DUPLICATE KEY(id) COMMENT '诊断专用' DISTRIBUTED BY HASH(id) BUCKETS 1 "
                        + "PROPERTIES('replication_num'='1')");
                var catalog = product.queryCatalogTableList(connection, schema, 10, 10);
                assertEquals(1, catalog.size());
                assertEquals("sample", catalog.getFirst().getTableName());
                assertEquals(schema, catalog.getFirst().getSchemaName());
                assertEquals("TABLE", catalog.getFirst().getTableType());
                assertEquals(1, product.queryTableColumnMeta(connection, schema, "sample").size());
                try (var runtime = cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime.standalone()) {
                    var result = runtime.diagnoseConnection(cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.DORIS,
                            null, System.getProperty("datahub.test.doris.url"),
                            System.getenv().getOrDefault("DORIS_DIAGNOSTIC_USER", "root"),
                            System.getenv().getOrDefault("DORIS_DIAGNOSTIC_PASSWORD", ""),
                            new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticOptions(schema, false, 5000));
                    for (String key : new String[]{"CONFIG", "AUTH", "VALIDATION", "SCHEMA", "TABLES", "COLUMNS", "PRIMARY_KEYS", "EXTENDED_PROPERTIES", "ZERO_ROW_READ"}) {
                        var check = result.checks().stream().filter(item -> key.equals(item.key())).findFirst().orElseThrow();
                        assertEquals("PASS", check.status(), key + ": " + check.message());
                    }
                    assertEquals("NOT_AUTHORIZED", result.checks().stream()
                            .filter(item -> "TEMP_CREATE".equals(item.key())).findFirst().orElseThrow().reasonCode());
                    assertEquals(0, runtime.drivers().runtimes().connections().snapshot().metadata());
                }
                statement.execute("CREATE TABLE `" + schema + "`.`second` LIKE `" + schema + "`.`sample`");
                assertThrows(SQLException.class, () -> product.queryCatalogTableList(connection, schema, 1, 10));
                product.probeNamespace(connection, schema, 10);
                product.probeAccountPrivileges(connection, schema, 10);
                var evidence = product.probeExtendedProperties(connection, schema, "sample", 10);
                assertEquals(1, evidence.columnsRead());
                assertTrue(evidence.tableCommentPresent());
                assertEquals(1, evidence.columnCommentsPresent());
                assertThrows(SQLException.class, () -> product.probeNamespace(connection, schema + "_missing", 10));
                assertNotNull(product.probeCdcReadiness(connection, 10));
                assertFalse(connection.isClosed());
            } finally {
                statement.execute("DROP DATABASE `" + schema + "`");
            }
        }
    }
}
