package cn.iocoder.yudao.module.dataforge.service.batchfile;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.module.dataforge.controller.admin.batchfile.vo.BatchFileModels.*;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 可由 CI 或开发环境提供独立 MySQL；未配置时必须报告跳过。 */
@EnabledIfEnvironmentVariable(named = "BATCH_FILE_MYSQL_URL", matches = ".+")
class BatchFileMysqlTest {
    @Test void createsAllTypesAppendsInOrderRejectsMismatchAndNeverTruncates() throws Exception {
        String url = System.getenv("BATCH_FILE_MYSQL_URL"), user = System.getenv().getOrDefault("BATCH_FILE_MYSQL_USER", "root"),
                password = System.getenv().getOrDefault("BATCH_FILE_MYSQL_PASSWORD", "");
        String schema = "batch_file_test", tableName = "bf_" + UUID.randomUUID().toString().replace("-", "");
        try (DbSwitchRuntime runtime = DbSwitchRuntime.standalone(); Connection jdbc = runtime.openConnection(ProductTypeEnum.MYSQL, null, url, user, password)) {
            AccessDatabaseResolver resolver = mock(AccessDatabaseResolver.class); var info = new AccessDatabaseResolver.ConnectionInfo();
            info.setDatabaseId(1L); info.setProductType(ProductTypeEnum.MYSQL); info.setJdbcUrl(url); info.setUsername(user); info.setPassword(password); info.setSchema(schema);
            info.setConfigurationRevision("test-revision");
            when(resolver.resolve(eq(1L), any())).thenReturn(info);
            BatchFileTargetAdapter adapter = new BatchFileTargetAdapter();
            ReflectionTestUtils.setField(adapter, "runtime", runtime); ReflectionTestUtils.setField(adapter, "resolver", resolver);
            var access = mock(ExecutionAccessService.class);
            ReflectionTestUtils.setField(adapter, "access", access);
            ReflectionTestUtils.setField(adapter, "authorization", new BatchFileTargetAuthorization());
            Task task = new Task(); task.setTargetDatabaseId(1L);
            File file = new File(); file.setName("sample.csv"); file.setTargetTable(tableName);
            List<String> types = BatchFileRules.TYPES;
            List<Field> fields = new ArrayList<>();
            for (int i = 0; i < types.size(); i++) fields.add(BatchFileRulesTest.field(i, "列" + i, types.get(i)));
            file.setFields(fields);
            String header = String.join(",", fields.stream().map(Field::getName).toList());
            String record = "中文,9223372036854775807,2147483647,12345678901234567890.123456789012345678,1.25,2026-10-16,2026-10-16 12:30:00.123456,true";
            byte[] data = (header + "\n" + record + "\n").getBytes(StandardCharsets.UTF_8);
            try {
                var first = adapter.append(task, file, data, 1L, () -> {}); assertEquals("SUCCESS", first.status(), first.errorMessage()); assertEquals(1, first.writtenRows());
                byte[] secondData = (header + "\n" + record.replace("中文", "第二个文件") + "\n").getBytes(StandardCharsets.UTF_8);
                var second = adapter.append(task, file, secondData, 1L, () -> {}); assertEquals("SUCCESS", second.status(), second.errorMessage());
                try (var statement = jdbc.createStatement(); var result = statement.executeQuery("SELECT * FROM `" + schema + "`.`" + tableName + "`")) {
                    assertTrue(result.next()); assertEquals("中文", result.getString(1)); assertEquals(Long.MAX_VALUE, result.getLong(2));
                    assertEquals(Integer.MAX_VALUE, result.getInt(3)); assertEquals("12345678901234567890.123456789012345678", result.getBigDecimal(4).toPlainString());
                    assertEquals(1.25, result.getDouble(5)); assertEquals("2026-10-16", result.getDate(6).toString());
                    assertEquals(123456000, result.getTimestamp(7).getNanos()); assertTrue(result.getBoolean(8));
                    assertTrue(result.next()); assertEquals("第二个文件", result.getString(1)); assertFalse(result.next());
                }
                try (var statement = jdbc.createStatement(); var result = statement.executeQuery("SELECT count(*) FROM `" + schema + "`.`" + tableName + "`")) { assertTrue(result.next()); assertEquals(2, result.getInt(1)); }
                verify(access, atLeastOnce()).requireUserPermission(1L, "dataforge:batch-file:execute");
                doThrow(new IllegalStateException("denied")).when(access).requireUserPermission(1L, "dataforge:batch-file:execute");
                assertEquals("FAILED", adapter.append(task, file, data, 1L, () -> {}).status(), "操作权限撤销后不得追加");
                doNothing().when(access).requireUserPermission(1L, "dataforge:batch-file:execute");
                try (var statement = jdbc.createStatement()) { statement.execute("ALTER TABLE `" + schema + "`.`" + tableName + "` ADD CONSTRAINT `bf_limit` CHECK (`列7` = 1)"); }
                byte[] rejectedData = (header + "\n" + record.replace(",true", ",false") + "\n").getBytes(StandardCharsets.UTF_8);
                assertEquals("UNKNOWN", adapter.append(task, file, rejectedData, 1L, () -> {}).status(), "既有表无引擎证据时失败回执不可宣称未写入");
                try (var statement = jdbc.createStatement()) { statement.execute("ALTER TABLE `" + schema + "`.`" + tableName + "` DROP CHECK `bf_limit`"); }
                try (var statement = jdbc.createStatement()) { statement.execute("ALTER TABLE `" + schema + "`.`" + tableName + "` MODIFY `列6` DATETIME(0) NULL"); }
                assertEquals("FAILED", adapter.append(task, file, data, 1L, () -> {}).status(), "低精度既有表必须拒绝追加");
                file.setFields(List.of(fields.getFirst()));
                assertEquals("FAILED", adapter.append(task, file, data, 1L, () -> {}).status());
                try (var statement = jdbc.createStatement(); var result = statement.executeQuery("SELECT count(*) FROM `" + schema + "`.`" + tableName + "`")) { assertTrue(result.next()); assertEquals(2, result.getInt(1)); }
            } finally { try (var statement = jdbc.createStatement()) { statement.execute("DROP TABLE IF EXISTS `" + schema + "`.`" + tableName + "`"); } }
        }
    }
}

