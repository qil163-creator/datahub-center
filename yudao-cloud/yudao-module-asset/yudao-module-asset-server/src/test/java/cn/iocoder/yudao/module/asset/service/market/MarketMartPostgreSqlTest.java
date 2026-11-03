package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.*;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.*;
import cn.iocoder.yudao.module.asset.service.common.AssetUserNameResolver;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetMaintenanceAuthorization;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.incrementer.PostgreKeyGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 在显式指定的隔离 PostgreSQL 中验证真实基线、分页、收藏、分类锁和发布原子性。 */
@EnabledIfEnvironmentVariable(named = "ASSET_MARKET_TEST_JDBC_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(90)
class MarketMartPostgreSqlTest {
    private String controlUrl;
    private String database;
    private Connection connection;
    private SqlSessionTemplate session;
    private DataSourceTransactionManager transactions;
    private DataAssetMapper assets;
    private MarketFavoriteMapper favorites;
    private MarketResourceApplyMapper applies;
    private MarketCategoryService categoryService;
    private MarketMartService mart;
    private AssetCatalogService originalPublisher;

    @BeforeAll
    void createOwnedDatabaseWithCurrentBaseline() throws Exception {
        controlUrl = System.getenv("ASSET_MARKET_TEST_JDBC_URL");
        assertThat(controlUrl).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres");
        database = "asset_mart_" + UUID.randomUUID().toString().replace("-", "");
        try (var control = DriverManager.getConnection(controlUrl, "catalog_test", ""); var sql = control.createStatement()) {
            sql.execute("CREATE DATABASE " + database);
        }
        String url = controlUrl.substring(0, controlUrl.lastIndexOf('/') + 1) + database;
        connection = DriverManager.getConnection(url, "catalog_test", "");
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        assertThat(root).isNotNull();
        sql(Files.readString(root.resolve("sql/postgresql/datahub-center.sql")));
        sql("SET search_path TO \"datahub-center\", public");
        var source = new UnpooledDataSource("org.postgresql.Driver", url + "?currentSchema=datahub-center&socketTimeout=15", "catalog_test", "");
        transactions = new DataSourceTransactionManager(source);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("asset-mart-isolated", new SpringManagedTransactionFactory(), source));
        var global = new GlobalConfig().setMetaObjectHandler(new DefaultDBFieldHandler());
        global.setDbConfig(new GlobalConfig.DbConfig().setIdType(IdType.INPUT).setKeyGenerators(List.of(new PostgreKeyGenerator())));
        GlobalConfigUtils.setGlobalConfig(config, global);
        var interceptor = new MybatisPlusInterceptor(); interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        config.addInterceptor(interceptor);
        for (var mapper : List.of(DataAssetMapper.class, MarketFavoriteMapper.class, MarketCategoryMapper.class,
                MarketListingMapper.class, MarketResourceApplyMapper.class, MarketAuditEventMapper.class)) config.addMapper(mapper);
        session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        assets = session.getMapper(DataAssetMapper.class); favorites = session.getMapper(MarketFavoriteMapper.class);
        applies = session.getMapper(MarketResourceApplyMapper.class);
    }

    @BeforeEach
    void prepareIsolatedFixtureAndTransactionalServices() throws Exception {
        sql("TRUNCATE dh_asset_data_asset,dh_asset_catalog_version,dh_asset_market_category,dh_asset_market_listing,"
                + "dh_asset_market_favorite,dh_market_resource_apply,dh_market_audit_event RESTART IDENTITY CASCADE");
        sql("INSERT INTO dh_asset_market_category(id,parent_id,name,status,sort) VALUES(1,0,'一级',0,0),(2,1,'二级',0,1),(3,0,'另一级',0,2)");
        for (int id = 1; id <= 5; id++) {
            sql("INSERT INTO dh_asset_data_asset(id,asset_code,catalog_item_urn,asset_name,asset_type,meta_table_id,resource_urn,"
                    + "published_snapshot_id,published_version,datasource_id,schema_name,table_name,status,publish_time,view_count,"
                    + "catalog_revision,published_catalog_version_id) VALUES(" + id + ",'fixture-" + id + "','urn:fixture:" + id
                    + "','草稿名称" + id + "',1," + id + ",'urn:resource:" + id + "',11,'v1',4,'public','citizen" + id + "',"
                    + (id == 3 ? 0 : id == 4 ? 2 : 1) + ",'2026-10-15 10:00:00'," + (id == 2 ? 50 : 5) + ",4," + (10 + id) + ")");
            sql("INSERT INTO dh_asset_catalog_version(id,asset_id,version_no,status,content_json) VALUES(" + (10 + id) + "," + id
                    + ",1,4,'{\"tags\":[{\"id\":30}],\"asset\":{\"categoryId\":7,\"layer\":\"ODS\",\"id\":" + id + ",\"assetName\":\"公开名称" + id + "\",\"tableName\":\"citizen" + id
                    + "\",\"description\":\"冻结描述\",\"assetType\":" + (id == 2 ? 2 : 1) + "}}')");
        }
        sql("UPDATE dh_asset_data_asset SET deleted=1 WHERE id=5");
        var categoryTarget = new MarketCategoryServiceImpl();
        ReflectionTestUtils.setField(categoryTarget, "categoryMapper", session.getMapper(MarketCategoryMapper.class));
        ReflectionTestUtils.setField(categoryTarget, "listingMapper", session.getMapper(MarketListingMapper.class));
        ReflectionTestUtils.setField(categoryTarget, "userNameResolver", mock(AssetUserNameResolver.class));
        categoryService = transactional(categoryTarget, MarketCategoryService.class);
        var target = new MarketMartServiceImpl(); var guard = mock(MarketObjectGuard.class); when(guard.requireUserId()).thenReturn(9L);
        originalPublisher = mock(AssetCatalogService.class);
        when(originalPublisher.prepareMaintenanceAuthorization()).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return mock(AssetMaintenanceAuthorization.class);
        });
        var audit = new MarketAuditRecorder(); ReflectionTestUtils.setField(audit, "auditEventMapper", session.getMapper(MarketAuditEventMapper.class));
        Map.ofEntries(Map.entry("objectGuard", guard), Map.entry("assetMapper", assets), Map.entry("favoriteMapper", favorites),
                Map.entry("applyMapper", applies), Map.entry("listingMapper", session.getMapper(MarketListingMapper.class)),
                Map.entry("categoryMapper", session.getMapper(MarketCategoryMapper.class)), Map.entry("categoryService", categoryService),
                Map.entry("catalogService", originalPublisher), Map.entry("transactionManager", transactions), Map.entry("audit", audit))
                .forEach((name, value) -> ReflectionTestUtils.setField(target, name, value));
        mart = transactional(target, MarketMartService.class);
    }

    @Test
    void frozenSearchTypesAndActorFavoritesFilterBeforeDatabasePagination() {
        var req = new MarketPageReqVO(); req.setKeyword("草稿名称");
        assertThat(assets.selectMarketPage(req, 9L, null, null).getTotal()).isZero();
        req.setKeyword("公开名称"); req.setAssetTypes(List.of(1, 2)); req.setPageSize(1);
        var page = assets.selectMarketPage(req, 9L, null, null);
        assertThat(page.getTotal()).isEqualTo(2); assertThat(page.getList()).hasSize(1);
        req.setAssetTypes(List.of(2)); assertThat(assets.selectMarketPage(req, 9L, null, null).getList())
                .extracting(DataAssetDO::getId).containsExactly(2L);
        favorites.insertIfAbsent(9L, 2L); favorites.insertIfAbsent(10L, 1L);
        req.setAssetTypes(null); req.setFavoriteOnly(true);
        assertThat(assets.selectMarketPage(req, 9L, null, null).getList()).extracting(DataAssetDO::getId).containsExactly(2L);
        req.setAuthorizedOnly(true);
        assertThat(assets.selectMarketPage(req, 9L, List.of(), null).getTotal()).isZero();
        var shared = new cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetPageReqVO();
        shared.setKeyword("公开名称"); shared.setTagId(30L);
        assertThat(assets.selectPublishedPage(shared, List.of(7L), null).getTotal()).isEqualTo(2L);
        assertThat(assets.selectPublishedFacetCounts()).anySatisfy(row -> {
            assertThat(row.get("kind")).isEqualTo("category");
            assertThat(row.get("group_key")).isEqualTo("7");
            assertThat(((Number) row.get("cnt")).longValue()).isEqualTo(2);
        });
    }

    @Test
    void listingCategoryAndMarketingSearchNeverReplaceProductionTaxonomyOrAssetId() throws Exception {
        sql("INSERT INTO dh_asset_market_listing(asset_id,market_category_id,display_name,frequency,delivery_types) VALUES(1,2,'市场展示名称','每日','1,3')");
        var req = new MarketPageReqVO(); req.setKeyword("市场展示");
        assertThat(assets.selectMarketPage(req, 9L, null, categoryService.descendants(1L)).getList())
                .extracting(DataAssetDO::getId).containsExactly(1L);
        assertThat(assets.selectMarketPage(req, 9L, null, List.of(3L)).getTotal()).isZero();
        assertThat(assets.selectById(1L).getCategoryId()).isNull();
        req.setAssetTypes(List.of(3));
        assertThat(assets.selectMarketPage(req, 9L, null, null).getList())
                .extracting(DataAssetDO::getId).containsExactly(1L);
        req.setAssetTypes(List.of(2));
        assertThat(assets.selectMarketPage(req, 9L, null, null).getTotal()).isZero();
        req.setAssetTypes(List.of(2, 3));
        assertThat(assets.selectMarketPage(req, 9L, null, null).getTotal()).isEqualTo(1L);
        req.setKeyword("' OR 1=1 --"); assertThat(assets.selectMarketPage(req, 9L, null, null).getTotal()).isZero();
    }

    @Test
    void popularAndFavoriteSortUsePersistedCountsAndIgnoreFavoriteTombstones() throws Exception {
        var req = new MarketPageReqVO(); req.setSort("POPULAR");
        assertThat(assets.selectMarketPage(req, 9L, null, null).getList()).extracting(DataAssetDO::getId).containsExactly(2L, 1L);
        favorites.insertIfAbsent(9L, 1L); favorites.insertIfAbsent(10L, 1L); favorites.insertIfAbsent(10L, 2L);
        req.setSort("FAVORITE"); assertThat(assets.selectMarketPage(req, 9L, null, null).getList())
                .extracting(DataAssetDO::getId).containsExactly(1L, 2L);
        sql("UPDATE dh_asset_market_favorite SET deleted=1 WHERE user_id=10 AND asset_id=1");
        assertThat(assets.selectMarketPage(req, 9L, null, null).getList()).extracting(DataAssetDO::getId).containsExactly(2L, 1L);
    }

    @Test
    void concurrentFavoritesAreIdempotentAndRemovalDoesNotTouchAnotherUser() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { await(start); mart.setFavorite(favorite(true)); });
            var second = executor.submit(() -> { await(start); mart.setFavorite(favorite(true)); });
            start.countDown(); first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        }
        assertThat(favorites.selectPublishedCount(9L)).isEqualTo(1);
        favorites.insertIfAbsent(10L, 1L); mart.setFavorite(favorite(false));
        assertThat(favorites.selectPublishedCount(9L)).isZero(); assertThat(favorites.selectPublishedCount(10L)).isEqualTo(1);
        mart.setFavorite(favorite(true)); assertThat(favorites.selectPublishedCount(9L)).isEqualTo(1);
    }

    @Test
    void publisherFailureRollsBackListingAndOwnRevisionRejectsStaleRetry() throws Exception {
        var req = publish(); doThrow(new IllegalStateException("受控发布失败")).when(originalPublisher).publishLatestApproved(eq(1L), any(AssetMaintenanceAuthorization.class));
        assertThatThrownBy(() -> mart.publish(req)).isInstanceOf(IllegalStateException.class);
        assertThat(scalar("SELECT COUNT(*) FROM dh_asset_market_listing")).isZero();
        doNothing().when(originalPublisher).publishLatestApproved(eq(1L), any(AssetMaintenanceAuthorization.class)); mart.publish(req);
        assertThat(scalar("SELECT revision FROM dh_asset_market_listing WHERE asset_id=1")).isEqualTo(1);
        assertThat(scalar("SELECT catalog_revision FROM dh_asset_data_asset WHERE id=1")).isEqualTo(4);
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='MARKET_LISTING_PUBLISH' AND object_id=1 AND actor_user_id=9")).isEqualTo(1L);
        assertThatThrownBy(() -> mart.publish(req)).isInstanceOf(ServiceException.class);
        assertThat(scalar("SELECT revision FROM dh_asset_market_listing WHERE asset_id=1")).isEqualTo(1);
    }

    @Test
    void personalStatisticsUsePublishedAssetsEffectiveDatabaseClockAndCurrentActor() throws Exception {
        String snapshot = "{\"assetId\":\"1\",\"assetVersion\":\"v1\",\"catalogVersionId\":\"11\",\"publishedSnapshotId\":\"11\","
                + "\"databaseId\":\"4\",\"schemaName\":\"public\",\"tableName\":\"citizen1\",\"frozenColumns\":[\"Id\"]}";
        sql("INSERT INTO dh_market_resource_apply(id,apply_no,title,asset_id,apply_user_id,status,if_auth,snapshot,apply_end_time,paused) VALUES"
                + "(1,'fixture1','已批准',1,9,2,1,'" + snapshot + "',NULL,0),"
                + "(2,'fixture2','旧批准',1,9,2,1,'" + snapshot + "',NULL,0),"
                + "(3,'fixture3','已过期',2,9,2,1,'{}',clock_timestamp() AT TIME ZONE 'Asia/Shanghai' - interval '1 day',0),"
                + "(4,'fixture4','已暂停',2,9,2,1,'{}',NULL,1),"
                + "(5,'fixture5','待审核',2,9,1,0,NULL,NULL,0),"
                + "(6,'fixture6','补充中',1,9,8,0,NULL,NULL,0),"
                + "(7,'fixture7','未发布资产申请',3,9,1,0,NULL,NULL,0),"
                + "(8,'fixture8','其他用户申请',1,10,1,0,NULL,NULL,0),"
                + "(9,'fixture9','历史下线授权',4,9,2,1,'{}',NULL,0),"
                + "(10,'fixture10','历史删除授权',5,9,2,1,'{}',NULL,0)");
        favorites.insertIfAbsent(9L, 1L); favorites.insertIfAbsent(9L, 3L); favorites.insertIfAbsent(10L, 2L);
        var access = new MarketAccessServiceImpl(); ReflectionTestUtils.setField(access, "applyMapper", applies);
        ReflectionTestUtils.setField(access, "assetMapper", assets);
        var service = new MarketMartServiceImpl(); var guard = mock(MarketObjectGuard.class); when(guard.requireUserId()).thenReturn(9L);
        Map.of("objectGuard", guard, "accessService", access, "assetMapper", assets, "favoriteMapper", favorites, "applyMapper", applies)
                .forEach((field, value) -> ReflectionTestUtils.setField(service, field, value));
        var result = service.getStatistics();
        assertThat(result.getTotalAssetCount()).isEqualTo(2L);
        assertThat(result.getAuthorizedAssetCount()).isEqualTo(1L);
        assertThat(result.getFavoriteAssetCount()).isEqualTo(1L);
        assertThat(result.getApplyingAssetCount()).isEqualTo(2L);
    }

    @Test
    void concurrentTreeMovesCannotJointlyCreateCycle() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> moveAfter(start, 1L, 3L));
            var second = executor.submit(() -> moveAfter(start, 3L, 1L));
            start.countDown(); assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(scalar("SELECT count(*) FROM dh_asset_market_category a JOIN dh_asset_market_category b ON a.parent_id=b.id WHERE b.parent_id=a.id")).isZero();
    }

    @Test
    void disablingCategoryWaitsForInFlightPublicationAndThenRejectsNextPublish() throws Exception {
        var publisherEntered = new CountDownLatch(1); var finishPublication = new CountDownLatch(1);
        doAnswer(call -> { publisherEntered.countDown(); await(finishPublication); return null; }).when(originalPublisher).publishLatestApproved(eq(1L), any(AssetMaintenanceAuthorization.class));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var publish = executor.submit(() -> mart.publish(publish()));
            assertThat(publisherEntered.await(10, TimeUnit.SECONDS)).isTrue();
            var disablingEntered = new CountDownLatch(1);
            var disable = executor.submit(() -> {
                try (var security = mockStatic(cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.class)) {
                    security.when(cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
                    disablingEntered.countDown(); categoryService.updateStatus(2L, (short)1);
                }
            });
            assertThat(disablingEntered.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> disable.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally { finishPublication.countDown(); }
            publish.get(10, TimeUnit.SECONDS); disable.get(10, TimeUnit.SECONDS);
        }
        var staleCategoryRequest = publish(); staleCategoryRequest.setExpectedListingRevision(1L);
        assertThatThrownBy(() -> mart.publish(staleCategoryRequest)).isInstanceOf(ServiceException.class);
        assertThat(scalar("SELECT revision FROM dh_asset_market_listing WHERE asset_id=1")).isEqualTo(1L);
    }

    private boolean moveAfter(CountDownLatch start, Long id, Long parent) {
        await(start); var req = new MarketCategorySaveReqVO(); req.setId(id); req.setParentId(parent);
        req.setName("分类" + id); req.setStatus((short)0); req.setSort(0);
        try (var security = mockStatic(cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.class)) {
            security.when(cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            categoryService.update(req); return true;
        } catch (ServiceException expectedConflict) { return false; }
    }

    private <T> T transactional(Object target, Class<T> type) {
        var proxy = new ProxyFactory(target); proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return type.cast(proxy.getProxy());
    }

    private static MarketFavoriteReqVO favorite(boolean value) {
        var req = new MarketFavoriteReqVO(); req.setAssetId(1L); req.setFavorite(value); return req;
    }

    private static MarketPublishReqVO publish() {
        var req = new MarketPublishReqVO(); req.setAssetId(1L); req.setMarketCategoryId(2L); req.setName("市场名");
        req.setFrequency("每日"); req.setDeliveryTypes(List.of(1,3)); req.setExpectedRevision(4L); req.setExpectedListingRevision(0L); return req;
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("并发测试等待超时"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }

    private void sql(String text) throws Exception { try (var statement = connection.createStatement()) { statement.execute(text); } }
    private long scalar(String text) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(text)) { result.next(); return result.getLong(1); }
    }

    @AfterAll
    void dropOnlyOwnedDatabase() throws Exception {
        if (connection != null) connection.close();
        if (database != null && database.matches("asset_mart_[0-9a-f]{32}")) {
            try (var control = DriverManager.getConnection(controlUrl, "catalog_test", ""); var statement = control.createStatement()) {
                statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
            }
        }
    }
}
