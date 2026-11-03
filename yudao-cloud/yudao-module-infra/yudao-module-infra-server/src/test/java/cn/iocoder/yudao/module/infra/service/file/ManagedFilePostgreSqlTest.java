package cn.iocoder.yudao.module.infra.service.file;

import cn.iocoder.yudao.module.infra.api.file.dto.ManagedFileCreateReqDTO;
import cn.iocoder.yudao.module.infra.dal.dataobject.file.FileDO;
import cn.iocoder.yudao.module.infra.dal.mysql.file.FileMapper;
import cn.iocoder.yudao.module.infra.dal.mysql.file.ManagedFileMapper;
import cn.iocoder.yudao.module.infra.framework.file.core.client.FileClient;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 PostgreSQL 验证操作唯一键、两阶段补偿与并发回执提交。 */
@Timeout(45)
class ManagedFilePostgreSqlTest {
    private final String schema = "managed_file_" + UUID.randomUUID().toString().replace("-", "");
    private String url, user, password;
    private boolean created;
    private FileServiceImpl service;
    private FileClient client;
    private FileMapper files;
    private ManagedFileMapper operations;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final AtomicInteger uploads = new AtomicInteger();
    private final byte[] content = "name,value\nalpha,123\n".getBytes(StandardCharsets.UTF_8);

    @BeforeEach void setup() throws Exception {
        url = System.getProperty("datahub.test.postgres.url");
        assumeTrue(url != null && !url.isBlank(), "需要显式隔离 PostgreSQL 地址");
        assertTrue(url.matches("jdbc:postgresql://(?:127[.]0[.]0[.]1|localhost):[0-9]{1,5}/[A-Za-z0-9_]+"));
        user = System.getProperty("datahub.test.postgres.user", "postgres"); password = System.getProperty("datahub.test.postgres.password", "");
        try (var c = DriverManager.getConnection(url, user, password); var st = c.createStatement()) {
            st.execute("CREATE SCHEMA " + schema); created = true;
            st.execute("SET search_path TO " + schema);
            st.execute("CREATE TABLE infra_file (LIKE \"datahub-center\".infra_file INCLUDING ALL)");
            st.execute("CREATE TABLE infra_managed_file (LIKE \"datahub-center\".infra_managed_file INCLUDING ALL)");
        }
        var source = new UnpooledDataSource("org.postgresql.Driver", url + "?currentSchema=" + schema + "&connectTimeout=5&socketTimeout=10", user, password);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("managed-file", new SpringManagedTransactionFactory(), source));
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(new cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler());
        config.addMapper(FileMapper.class); config.addMapper(ManagedFileMapper.class);
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        files = spy(sessions.getMapper(FileMapper.class)); operations = sessions.getMapper(ManagedFileMapper.class);
        client = mock(FileClient.class);
        when(client.getId()).thenReturn(1L);
        when(client.upload(any(), anyString(), anyString())).thenAnswer(invocation -> {
            objects.put(invocation.getArgument(1), ((byte[]) invocation.getArgument(0)).clone()); uploads.incrementAndGet(); return "internal-object-url";
        });
        when(client.getContent(anyString(), anyLong())).thenAnswer(invocation -> objects.get(invocation.getArgument(0)));
        doAnswer(invocation -> { objects.remove(invocation.getArgument(0)); return null; }).when(client).delete(anyString());
        var configs = mock(FileConfigService.class); when(configs.getMasterFileClient()).thenReturn(client); when(configs.getFileClient(1L)).thenReturn(client);
        service = new FileServiceImpl();
        ReflectionTestUtils.setField(service, "fileConfigService", configs);
        ReflectionTestUtils.setField(service, "fileMapper", files);
        ReflectionTestUtils.setField(service, "managedFileMapper", operations);
        ReflectionTestUtils.setField(service, "transactionManager", new DataSourceTransactionManager(source));
    }

    @AfterEach void cleanup() throws Exception {
        if (!created) return;
        assertTrue(schema.matches("managed_file_[a-f0-9]{32}"));
        try (var c = DriverManager.getConnection(url, user, password); var st = c.createStatement()) { st.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }

    @Test void sameKeySameBytesReturnsSameReceiptAndOtherOwnerCannotReadOrDelete() throws Exception {
        var first = service.createManaged("dataforge", request("operation_same_12345"));
        var second = service.createManaged("dataforge", request("operation_same_12345"));
        assertEquals(first.getId(), second.getId()); assertEquals("READY", first.getState()); assertEquals(1, uploads.get());
        assertEquals("dataforge", operations.find("dataforge", "operation_same_12345").getCreator());
        assertArrayEquals(content, service.readManaged("dataforge", first.getId(), "operation_same_12345"));
        assertThrows(RuntimeException.class, () -> service.readManaged("governance", first.getId(), "operation_same_12345"));
        assertThrows(RuntimeException.class, () -> service.deleteManaged("governance", first.getId(), "operation_same_12345"));
        assertThrows(RuntimeException.class, () -> service.createManaged("dataforge", request("operation_same_12345").setContent("different".getBytes(StandardCharsets.UTF_8))));
    }

    @Test void metadataFailureCleansObjectAndSameKeyCanRetryOnlyAfterCleanup() throws Exception {
        doThrow(new IllegalStateException("模拟元数据登记失败")).doCallRealMethod().when(files).insert(any(FileDO.class));
        assertThrows(RuntimeException.class, () -> service.createManaged("dataforge", request("operation_failure_123")));
        assertTrue(objects.isEmpty());
        assertEquals("FAILED", operations.find("dataforge", "operation_failure_123").getState());
        assertEquals("READY", service.createManaged("dataforge", request("operation_failure_123")).getState());
    }

    @Test void uploadCommittedButReplyUnknownReconcilesWithoutUploadingAgain() throws Exception {
        var result = service.createManaged("dataforge", request("operation_unknown_123"));
        assertEquals("READY", service.getManaged("dataforge", "operation_unknown_123").getState());
        assertEquals(result.getId(), service.createManaged("dataforge", request("operation_unknown_123")).getId());
        assertEquals(1, uploads.get());
        // 模拟上传完成但元数据事务尚未提交的进程中断。
        var operation = operations.find("dataforge", "operation_unknown_123");
        files.deleteById(operation.getFileId());
        assertEquals(1, operations.transition(operation.getId(), "READY", "UPLOADING"));
        assertEquals("READY", service.getManaged("dataforge", "operation_unknown_123").getState());
        assertEquals(1, uploads.get());
    }

    @Test void deletionKeepsTombstoneAndRetriesPhysicalCleanupAfterFailure() throws Exception {
        var first = service.createManaged("dataforge", request("operation_delete_1234"));
        doThrow(new IllegalStateException("模拟存储暂不可用")).doAnswer(invocation -> { objects.remove(invocation.getArgument(0)); return null; }).when(client).delete(anyString());
        assertThrows(Exception.class, () -> service.deleteManaged("dataforge", first.getId(), "operation_delete_1234"));
        assertEquals("DELETED", service.getManaged("dataforge", "operation_delete_1234").getState());
        assertThrows(RuntimeException.class, () -> service.createManaged("dataforge", request("operation_delete_1234")));
        service.deleteManaged("dataforge", first.getId(), "operation_delete_1234");
        service.deleteManaged("dataforge", first.getId(), "operation_delete_1234");
        assertTrue(objects.isEmpty());
    }

    @Test void concurrentDuplicateCreationUploadsOnlyOnceAndKeepsOneOperation() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var latch = new CountDownLatch(1);
            var futures = List.of(executor.submit(() -> { latch.await(); return service.createManaged("dataforge", request("operation_race_123456")); }),
                    executor.submit(() -> { latch.await(); return service.createManaged("dataforge", request("operation_race_123456")); }));
            latch.countDown();
            assertEquals(futures.get(0).get(15, TimeUnit.SECONDS).getId(), futures.get(1).get(15, TimeUnit.SECONDS).getId());
        }
        assertEquals(1, uploads.get());
        assertEquals("READY", service.getManaged("dataforge", "operation_race_123456").getState());
    }

    @Test void legacyPublicFileRoutesCannotReadOrIssueGrantsForManagedObjects() throws Exception {
        service.createManaged("dataforge", request("operation_protected_123"));
        var operation = operations.find("dataforge", "operation_protected_123");
        assertThrows(RuntimeException.class, () -> service.getFileContent(operation.getConfigId(), operation.getPath()));
        assertThrows(RuntimeException.class, () -> service.getFileAccessUrl(operation.getFileId(), 60));
        assertThrows(RuntimeException.class, () -> service.presignGetUrl("https://storage.example.test/" + operation.getPath(), 60));
        assertThrows(RuntimeException.class, () -> service.presignPutUrl("source.csv", "managed/object"));
    }

    @Test void boundedCleanupReplaysTombstonesAfterLateUploadWithoutStarvingNextPage() throws Exception {
        for (int i = 0; i < 3; i++) {
            String key = "operation_late_upload_" + i;
            var receipt = service.createManaged("dataforge", request(key));
            var operation = operations.find("dataforge", key);
            service.deleteManaged("dataforge", receipt.getId(), key);
            // 模拟对象存储在取消回执成功以后才完成旧上传。
            objects.put(operation.getPath(), content);
        }
        try (var c = DriverManager.getConnection(url, user, password); var st = c.createStatement()) {
            st.execute("UPDATE " + schema + ".infra_managed_file SET update_time=CURRENT_TIMESTAMP-INTERVAL '10 minutes'");
        }
        var first = service.cleanupManagedFiles(1, 2);
        assertEquals(2, first.attempted()); assertEquals(2, first.succeeded()); assertEquals(0, first.failed());
        assertEquals(1, objects.size());
        var second = service.cleanupManagedFiles(1, 2);
        assertEquals(1, second.attempted()); assertEquals(1, second.succeeded()); assertTrue(objects.isEmpty());
        assertEquals("DELETED", operations.find("dataforge", "operation_late_upload_0").getState());
    }

    private ManagedFileCreateReqDTO request(String key) { return new ManagedFileCreateReqDTO().setOperationKey(key).setName("source.csv").setContent(content); }
}
