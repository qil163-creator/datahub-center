package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.*;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.*;
import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.*;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class AssetEtlBatchBindingPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement static class Transactions { }
    private Connection control; private String schema; private AnnotationConfigApplicationContext app;
    private AssetEtlBatchBindingWriter writer; private AssetEtlBatchBindingMapper bindings;
    @BeforeEach void setup() throws Exception {
        schema = "asset_etl_" + UUID.randomUUID().toString().replace("-", "");
        control = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        sql("CREATE SCHEMA " + schema); sql("SET search_path TO " + schema);
        sql("CREATE TABLE dh_asset_data_asset(id bigint primary key,tenant_id bigint,catalog_revision bigint,datasource_id bigint,schema_name text,table_name text,deleted smallint default 0)");
        sql("INSERT INTO dh_asset_data_asset VALUES(10,7,3,20,'dwd','orders',0)");
        var root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/migrations/0094_asset_etl-batch-binding.sql"))) root = root.getParent();
        assertNotNull(root);
        String migration = Files.readString(root.resolve("sql/postgresql/migrations/0094_asset_etl-batch-binding.sql")).replace("\"datahub-center\"", "\"" + schema + "\"");
        sql(migration); sql(migration);
        sql(Files.readString(root.resolve("sql/postgresql/migrations/0169_datahub_remove-business-tenancy.sql"))
                .replace("datahub-center", schema));
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("asset-etl-pg", new SpringManagedTransactionFactory(), source));
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(new cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler());
        config.addMapper(DataAssetMapper.class); config.addMapper(AssetEtlBatchBindingMapper.class);
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        app = new AnnotationConfigApplicationContext(); app.register(Transactions.class);
        app.registerBean(DataAssetMapper.class, () -> sessions.getMapper(DataAssetMapper.class));
        app.registerBean(AssetEtlBatchBindingMapper.class, () -> sessions.getMapper(AssetEtlBatchBindingMapper.class));
        app.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        app.registerBean(AssetEtlBatchBindingWriter.class); app.refresh();
        writer = app.getBean(AssetEtlBatchBindingWriter.class); bindings = app.getBean(AssetEtlBatchBindingMapper.class); TenantContextHolder.clear();
    }
    @AfterEach void cleanup() throws Exception {
        TenantContextHolder.clear(); if (app != null) app.close();
        if (control != null) { try { if (schema != null && schema.matches("asset_etl_[a-f0-9]{32}")) sql("DROP SCHEMA " + schema + " CASCADE"); } finally { control.close(); } }
    }
    @Test void migrationReplayAndAppendPreserveHistoricalBatchBinding() throws Exception {
        assertEquals(0, writer.append(observed(), null, binding(9007199254740993L)));
        assertEquals(1, writer.append(observed(), 0, binding(9007199254740994L)));
        assertEquals(9007199254740994L, bindings.latest(10L).getBatchId());
        assertEquals("2", value("SELECT count(*) FROM dh_asset_etl_batch_binding"));
        assertEquals("9007199254740993", value("SELECT batch_id FROM dh_asset_etl_batch_binding WHERE revision=0"));
        assertThrows(RuntimeException.class, () -> writer.append(observed(), 0, binding(50L)));
        assertEquals("2", value("SELECT count(*) FROM dh_asset_etl_batch_binding"));
    }
    @Test void changedAssetPhysicalIdentityCannotCommitPreflightObservation() throws Exception {
        var original = observed(); sql("UPDATE dh_asset_data_asset SET table_name='changed' WHERE id=10");
        assertThrows(RuntimeException.class, () -> writer.append(original, null, binding(30L)));
        assertEquals("0", value("SELECT count(*) FROM dh_asset_etl_batch_binding"));
    }
    @Test void bindingHistoryIsObjectScopedWithoutTenantContext() {
        TenantContextHolder.clear();
        writer.append(observed(), null, binding(30L));
        assertNotNull(bindings.latest(10L));
        assertNull(bindings.latest(11L));
        assertEquals(1, bindings.history(10L, null, 21).size());
        assertTrue(bindings.history(11L, null, 21).isEmpty());
    }
    @Test void revisionCursorIsStableAcrossNewAppendsAndKeepsRevisionZero() {
        writer.append(observed(), null, binding(9007199254740993L));
        writer.append(observed(), 0, binding(31L));
        writer.append(observed(), 1, binding(32L));
        assertEquals(List.of(2, 1), bindings.history(10L, null, 2).stream().map(AssetEtlBatchBindingDO::getRevision).toList());
        writer.append(observed(), 2, binding(33L));
        var tail = bindings.history(10L, 1, 2);
        assertEquals(1, tail.size()); assertEquals(0, tail.getFirst().getRevision());
        assertEquals(9007199254740993L, tail.getFirst().getBatchId());
        assertTrue(bindings.history(10L, 0, 2).isEmpty());
        assertTrue(bindings.history(11L, null, 2).isEmpty());
    }
    @Test void concurrentBindingEditsHaveOneWinnerAndNoHistoryOverwrite() throws Exception {
        writer.append(observed(), null, binding(30L));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1); List<Future<Boolean>> results = new ArrayList<>();
            for (long batch : List.of(31L, 32L)) results.add(executor.submit(() -> {
                TenantContextHolder.clear();
                try { start.await(); writer.append(observed(), 0, binding(batch)); return true; }
                catch (cn.iocoder.yudao.framework.common.exception.ServiceException stale) { return false; }
                finally { TenantContextHolder.clear(); }
            }));
            start.countDown(); int winners = 0; for (var future : results) if (future.get(10, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners); assertEquals("2", value("SELECT count(*) FROM dh_asset_etl_batch_binding"));
        }
    }
    private DataAssetDO observed() { return DataAssetDO.builder().id(10L).catalogRevision(3L).datasourceId(20L).schemaName("dwd").tableName("orders").build(); }
    private AssetEtlBatchBindingDO binding(Long batchId) {
        var b = new AssetEtlBatchBindingDO(); b.setAssetId(10L); b.setBatchId(batchId); b.setRecordId(40L); b.setPayloadHash("a".repeat(64));
        b.setDatabaseId(20L); b.setSchemaName("dwd"); b.setTableName("orders"); b.setActorId(99L); b.setReason("显式关联测试"); return b;
    }
    private void sql(String text) throws SQLException { try (var s = control.createStatement()) { s.execute(text); } }
    private String value(String text) throws SQLException { try (var s = control.createStatement(); var r = s.executeQuery(text)) { assertTrue(r.next()); return r.getString(1); } }
}
