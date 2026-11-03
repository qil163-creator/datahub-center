package cn.iocoder.yudao.module.governance.service.metadata;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableIngestReqDTO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaIngestEventMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaColumnMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static cn.iocoder.yudao.module.governance.enums.MetadataIngestErrorCodes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** 实际 PostgreSQL、生产收据 Mapper、租户拦截器和 Spring 事务；目标写入用 JDBC 测试投影模拟。 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(30)
class MetaPhysicalTableIngestPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private JdbcTemplate jdbc;
    private MetaPhysicalTableIngestService service;
    private MetaImportService imports;

    @BeforeEach
    void setup() throws Exception {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP TABLE IF EXISTS dh_meta_ingest_event");
        jdbc.execute("DROP TABLE IF EXISTS ingest_projection");
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        assertNotNull(root);
        String baseline = Files.readString(root.resolve("sql/postgresql/datahub-center.sql")).replace("\r\n", "\n");
        var matcher = Pattern.compile("CREATE TABLE (?:IF NOT EXISTS )?\"datahub-center\"[.]\"dh_meta_ingest_event\" \\(.*?\n\\)\\s*;", Pattern.DOTALL).matcher(baseline);
        assertTrue(matcher.find(), "receipt table must be delivered in baseline");
        jdbc.execute(matcher.group().replace("\"datahub-center\".", "")
                .replaceAll(" DEFAULT nextval\\([^\n]*?\\)", ""));
        jdbc.execute("CREATE UNIQUE INDEX test_ingest_event ON dh_meta_ingest_event(source,event_id)");
        jdbc.execute("CREATE UNIQUE INDEX test_ingest_revision ON dh_meta_ingest_event(source,target_key,revision)");
        jdbc.execute("CREATE TABLE ingest_projection(name varchar(128),field_name varchar(128),PRIMARY KEY(name))");
        cn.iocoder.yudao.module.governance.support.SingleTenantPostgreSqlFixture.upgrade(jdbc, "public");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("metadata-ingest-test", new SpringManagedTransactionFactory(), source));
        GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(new DefaultDBFieldHandler());
        var plugins = new MybatisPlusInterceptor();
        config.addInterceptor(plugins);
        config.addMapper(MetaIngestEventMapper.class);
        var mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config)).getMapper(MetaIngestEventMapper.class);
        imports = mock(MetaImportService.class);
        when(imports.prepareRegistration(anyLong())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "source RPC must be outside transaction");
            return new MetaImportService.RegistrationSource(call.getArgument(0), "测试源", "POSTGRESQL");
        });
        when(imports.registerTablePrepared(any(), any())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            var registration = (cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaTableRegisterReqVO) call.getArgument(0);
            jdbc.update("INSERT INTO ingest_projection VALUES (?,?) ON CONFLICT (name) DO UPDATE SET field_name=EXCLUDED.field_name",
                    registration.getTableName(), registration.getColumns().getFirst().getColumnName());
            return 100L;
        });
        service = new MetaPhysicalTableIngestService();
        ReflectionTestUtils.setField(service, "eventMapper", mapper);
        ReflectionTestUtils.setField(service, "importService", imports);
        ReflectionTestUtils.setField(service, "tableMapper", mock(MetaTableMapper.class));
        ReflectionTestUtils.setField(service, "columnMapper", mock(MetaColumnMapper.class));
        ReflectionTestUtils.setField(service, "transactionTemplate", new TransactionTemplate(new DataSourceTransactionManager(source)));
        TenantContextHolder.clear();
    }

    @AfterEach void cleanup() { TenantContextHolder.clear(); }

    @Test void lateOldSnapshotCannotOverwriteNewerRevisionAndRetryDoesNotWriteAgain() {
        assertEquals(100L, service.ingest(request("new", 20, "new_field")));
        assertEquals(100L, service.ingest(request("old", 10, "old_field")));
        assertEquals(100L, service.ingest(request("old", 10, "old_field")));
        assertEquals("new_field", jdbc.queryForObject("SELECT field_name FROM ingest_projection", String.class));
        assertEquals("SUPERSEDED", jdbc.queryForObject("SELECT status FROM dh_meta_ingest_event WHERE event_id='old'", String.class));
        verify(imports, times(1)).registerTablePrepared(any(), any());
    }

    @Test void sameEventWithDifferentPayloadAndSameRevisionWithDifferentEventAreRejected() {
        service.ingest(request("event", 10, "field"));
        assertEquals(META_INGEST_EVENT_CONFLICT.getCode(), assertThrows(ServiceException.class,
                () -> service.ingest(request("event", 10, "changed"))).getCode());
        assertEquals(META_INGEST_REVISION_CONFLICT.getCode(), assertThrows(ServiceException.class,
                () -> service.ingest(request("another", 10, "field"))).getCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM dh_meta_ingest_event", Integer.class));
    }

    @Test void targetAndReceiptRollbackTogetherWhenRegistrationFails() {
        doAnswer(call -> {
            jdbc.update("INSERT INTO ingest_projection VALUES ('dwd_case','incomplete')");
            throw new IllegalStateException("column update failed");
        }).when(imports).registerTablePrepared(any(), any());
        assertThrows(IllegalStateException.class, () -> service.ingest(request("event", 10, "field")));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ingest_projection", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dh_meta_ingest_event", Integer.class));
    }

    @Test void eventIdentityIsGlobalAndRejectsConflictingPayload() {
        service.ingest(request("same", 10, "first_field"));
        TenantContextHolder.clear();
        assertThrows(ServiceException.class, () -> service.ingest(request("same", 10, "changed_field")));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM dh_meta_ingest_event", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ingest_projection", Integer.class));
    }

    @Test void concurrentArrivalAlwaysKeepsHigherRevision() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        when(imports.prepareRegistration(anyLong())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            ready.countDown();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            return new MetaImportService.RegistrationSource(1L, "测试源", "POSTGRESQL");
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Long> older = pool.submit(() -> ingestOnTenant(request("old", 10, "old_field")));
            Future<Long> newer = pool.submit(() -> ingestOnTenant(request("new", 20, "new_field")));
            assertEquals(100L, older.get(15, TimeUnit.SECONDS));
            assertEquals(100L, newer.get(15, TimeUnit.SECONDS));
        }
        assertEquals("new_field", jdbc.queryForObject("SELECT field_name FROM ingest_projection", String.class));
    }

    private Long ingestOnTenant(MetadataTableIngestReqDTO request) {
        TenantContextHolder.clear();
        try { return service.ingest(request); } finally { TenantContextHolder.clear(); }
    }

    private MetadataTableIngestReqDTO request(String eventId, long revision, String field) {
        var request = new MetadataTableIngestReqDTO();
        request.setEventId(eventId); request.setSource("MODEL_DEPLOY"); request.setRevision(revision);
        request.setSchemaVersion(1); request.setOccurredAt(LocalDateTime.of(2026, 10, 3, 8, 0));
        request.setDatasourceId(1L); request.setDbName("public"); request.setTableName("dwd_case");
        var column = new MetadataTableIngestReqDTO.Column();
        column.setColumnName(field); column.setDataType("varchar"); request.setColumns(List.of(column));
        return request;
    }
}
