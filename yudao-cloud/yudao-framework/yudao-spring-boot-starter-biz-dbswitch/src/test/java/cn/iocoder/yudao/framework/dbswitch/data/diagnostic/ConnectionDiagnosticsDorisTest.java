package cn.iocoder.yudao.framework.dbswitch.data.diagnostic;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticOptions;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 Doris 完整公开入口，裸 URL 不携带数据库，所有对象只存在于随机测试库。 */
@Timeout(90)
@EnabledIfSystemProperty(named = "datahub.test.doris.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class ConnectionDiagnosticsDorisTest {
    @Test
    void nativeTemporaryWritesUseTargetNamespaceNativeTransactionsAndReleaseAllBudgets() throws Exception {
        String url = System.getProperty("datahub.test.doris.url");
        String user = System.getenv().getOrDefault("DORIS_DIAGNOSTIC_USER", "root");
        String password = System.getenv().getOrDefault("DORIS_DIAGNOSTIC_PASSWORD", "");
        String schema = "dbswitch_diag_" + UUID.randomUUID().toString().replace("-", "");
        try (var control = DriverManager.getConnection(url, user, password);
             var statement = control.createStatement()) {
            statement.setQueryTimeout(10);
            statement.execute("CREATE DATABASE `" + schema + "`");
            try {
                try (var runtime = DbSwitchRuntime.standalone()) {
                    var result = runtime.diagnoseConnection(ProductTypeEnum.DORIS, null, url, user, password,
                            new ConnectionDiagnosticOptions(schema, true, 5000));
                    assertTrue(result.connectionSuccessful(), result.toString());
                    for (String key : List.of("TEMP_CREATE", "INSERT", "BATCH_INSERT", "UPDATE", "UPSERT",
                            "MERGE", "DELETE", "TRUNCATE", "TRANSACTION", "DROP_TEMP", "WRITE_CLEANUP")) {
                        assertEquals("PASS", ConnectionDiagnosticsTest.check(result, key).status(), key + ": " + result);
                    }
                    for (String key : List.of("ALTER_TEMP", "RENAME_TEMP")) {
                        var check = ConnectionDiagnosticsTest.check(result, key);
                        assertEquals("SKIPPED", check.status(), key + ": " + result);
                        assertEquals("NOT_APPLICABLE", check.reasonCode(), key + ": " + result);
                    }
                    var budget = runtime.drivers().runtimes().connections().snapshot();
                    assertEquals(0, budget.metadata(), budget.toString());
                    assertEquals(0, budget.workload(), budget.toString());
                }
                try (var rows = statement.executeQuery("SELECT COUNT(TABLE_ID) FROM internal.information_schema.metadata_name_ids "
                        + "WHERE DATABASE_NAME='" + schema + "'")) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1), "独立会话不得残留物理临时对象");
                }
            } finally {
                statement.execute("DROP DATABASE `" + schema + "` FORCE");
            }
        }
    }
}
