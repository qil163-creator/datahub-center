package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketMyAssetPageReqVO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.*;
import cn.iocoder.yudao.module.asset.service.monitor.AssetMonitorService;
import cn.iocoder.yudao.module.asset.service.security.AssetMetadataIdentityGuard;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
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
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 隔离 PostgreSQL 的真实基线、SQL 分页、JSON 白名单、本人授权与消费入口回归。 */
@EnabledIfEnvironmentVariable(named = "ASSET_MARKET_TEST_JDBC_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(90)
class MarketMyAssetPostgreSqlTest {
    private String controlUrl;
    private String database;
    private Connection connection;
    private SqlSessionTemplate session;
    private DataSourceTransactionManager transactions;
    private MarketMyAssetService service;
    private MarketMyAssetMapper mapper;
    private DatabaseApi dataApi;

    @BeforeAll
    void createOwnedDatabaseAndLoadCurrentBaseline() throws Exception {
        controlUrl = System.getenv("ASSET_MARKET_TEST_JDBC_URL");
        assertThat(controlUrl).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres");
        database = "asset_my_" + UUID.randomUUID().toString().replace("-", "");
        try (var control = DriverManager.getConnection(controlUrl, "catalog_test", ""); var statement = control.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = controlUrl.substring(0, controlUrl.lastIndexOf('/') + 1) + database;
        connection = DriverManager.getConnection(url, "catalog_test", "");
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        assertThat(root).isNotNull();
        sql(Files.readString(root.resolve("sql/postgresql/datahub-center.sql")));
        sql("SET search_path TO \"datahub-center\",public");
        var source = new UnpooledDataSource("org.postgresql.Driver", url + "?currentSchema=datahub-center&socketTimeout=15", "catalog_test", "");
        transactions = new DataSourceTransactionManager(source);
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("my-assets-isolated", new SpringManagedTransactionFactory(), source));
        for (var type : List.of(MarketMyAssetMapper.class, MarketResourceApplyMapper.class, DataAssetMapper.class,
                MarketCategoryMapper.class, MarketDeliveryOperationMapper.class, MarketApprovalEvidenceMapper.class,
                MarketCheckProcessMapper.class)) config.addMapper(type);
        session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        mapper = session.getMapper(MarketMyAssetMapper.class);
    }

    @BeforeEach
    void prepareFixtures() throws Exception {
        login(9L);
        sql("TRUNCATE dh_asset_data_asset,dh_asset_catalog_version,dh_asset_market_category,dh_asset_market_listing,"
                + "dh_market_resource_apply,dh_market_audit_event,dh_market_delivery_operation,dh_market_check_process RESTART IDENTITY CASCADE");
        sql("INSERT INTO dh_asset_market_category(id,parent_id,name,status,sort) VALUES(1,0,'一级集市',0,0),(2,1,'二级集市',0,1),(3,0,'另一级',0,2)");
        sql("""
                INSERT INTO dh_asset_data_asset(id,asset_code,catalog_item_urn,asset_name,asset_type,meta_table_id,resource_urn,
                  published_snapshot_id,published_version,datasource_id,schema_name,table_name,status,catalog_revision,published_catalog_version_id)
                VALUES(5,'draft-code','urn:fixture:5','未审核草稿秘密名称',1,15,'urn:resource:5',11,'v1',4,'public','citizen',1,4,12)
                """);
        sql("""
                INSERT INTO dh_asset_catalog_version(id,asset_id,version_no,status,content_json,tech_snapshot_id)
                VALUES(12,5,1,4,'{"format":1,"asset":{"id":"5","assetName":"已批准目录名称","assetCode":"approved-code",
                "description":"已批准目录描述","updateCycle":"每周"}}',11)
                """);
        sql("INSERT INTO dh_asset_market_listing(asset_id,market_category_id,display_name,frequency,delivery_types) VALUES(5,2,'市场展示名称','每日','1,2,3')");
        for (long id = 7; id <= 14; id++) insertApply(id, id == 13 ? 10 : 9, id == 10 ? 6 : id == 12 ? 0 : 2);
        sql("UPDATE dh_market_resource_apply SET paused=1 WHERE id=8");
        sql("UPDATE dh_market_resource_apply SET apply_end_time='2000-01-01' WHERE id=9");
        sql("UPDATE dh_market_resource_apply SET apply_start_time='2099-01-01' WHERE id=11");
        sql("UPDATE dh_market_resource_apply SET deleted=1 WHERE id=14");
        var access = new MarketAccessServiceImpl();
        dataApi = mock(DatabaseApi.class);
        Map.ofEntries(Map.entry("applyMapper", session.getMapper(MarketResourceApplyMapper.class)),
                Map.entry("assetMapper", session.getMapper(DataAssetMapper.class)),
                Map.entry("transactionManager", transactions), Map.entry("databaseApi", dataApi),
                Map.entry("assetMonitorService", mock(AssetMonitorService.class)),
                Map.entry("metadataIdentityGuard", mock(AssetMetadataIdentityGuard.class)))
                .forEach((name, value) -> ReflectionTestUtils.setField(access, name, value));
        var delivery = new MarketDeliveryService();
        ReflectionTestUtils.setField(delivery, "approvalEvidenceMapper", session.getMapper(MarketApprovalEvidenceMapper.class));
        var target = new MarketMyAssetServiceImpl();
        var categories = new MarketCategoryServiceImpl();
        ReflectionTestUtils.setField(categories, "categoryMapper", session.getMapper(MarketCategoryMapper.class));
        Map.ofEntries(Map.entry("myAssetMapper", mapper), Map.entry("applyMapper", session.getMapper(MarketResourceApplyMapper.class)),
                Map.entry("assetMapper", session.getMapper(DataAssetMapper.class)), Map.entry("categoryService", categories),
                Map.entry("deliveryMapper", session.getMapper(MarketDeliveryOperationMapper.class)),
                Map.entry("approvalMapper", session.getMapper(MarketApprovalEvidenceMapper.class)),
                Map.entry("processMapper", session.getMapper(MarketCheckProcessMapper.class)),
                Map.entry("deliveryService", delivery), Map.entry("accessService", access))
                .forEach((name, value) -> ReflectionTestUtils.setField(target, name, value));
        var factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (MarketMyAssetService) factory.getProxy();
    }

    @AfterEach
    void clearLogin() { SecurityContextHolder.clearContext(); }

    @Test
    void sqlOwnScopeAndPaginationPreserveMultipleSourcesForOneAsset() {
        var req = new MarketMyAssetPageReqVO(); req.setPageSize(2);
        var page = service.getPage(req);
        assertThat(page.getTotal()).isEqualTo(5);
        assertThat(page.getList()).extracting(item -> item.getApplyId()).containsExactly(11L, 10L);
        req.setPageNo(2);
        assertThat(service.getPage(req).getList()).extracting(item -> item.getApplyId()).containsExactly(9L, 8L);
        req.setPageNo(3);
        assertThat(service.getPage(req).getList()).singleElement().satisfies(item -> {
            assertThat(item.getApplyId()).isEqualTo(7L); assertThat(item.getAssetId()).isEqualTo(5L);
            assertThat(item.getAssetCode()).isEqualTo("approved-code");
        });
        login(10L);
        assertThat(service.getPage(new MarketMyAssetPageReqVO()).getList()).extracting(item -> item.getApplyId()).containsExactly(13L);
        verifyNoInteractions(dataApi);
    }

    @Test
    void searchAndCategoryApplyBeforePaginationAndNeverUseDraftFields() {
        var req = new MarketMyAssetPageReqVO(); req.setKeyword("未审核草稿");
        assertThat(service.getPage(req).getTotal()).isZero();
        req.setKeyword("市场展示"); req.setMarketCategoryId(1L); req.setPageSize(1);
        assertThat(service.getPage(req).getTotal()).isEqualTo(5);
        req.setMarketCategoryId(3L); assertThat(service.getPage(req).getTotal()).isZero();
        req.setMarketCategoryId(null); req.setKeyword("' OR 1=1 --");
        assertThat(service.getPage(req).getTotal()).isZero();
    }

    @Test
    void deliveryFilterUsesApprovedRequestInsteadOfListingDeclarations() throws Exception {
        insertApply(15, 9, 2); insertApply(16, 9, 2);
        sql("UPDATE dh_market_resource_apply SET api_id=21,app_id=22 WHERE id=15");
        sql("UPDATE dh_market_resource_apply SET asset_type=3 WHERE id=16");
        var req = new MarketMyAssetPageReqVO(); req.setDeliveryType(2);
        assertThat(service.getPage(req).getList()).extracting(item -> item.getApplyId()).containsExactly(15L);
        req.setDeliveryType(3);
        assertThat(service.getPage(req).getList()).singleElement().satisfies(item -> {
            assertThat(item.getApplyId()).isEqualTo(16L); assertThat(item.getCanDownload()).isFalse();
            assertThat(item.getAccessStatus()).isEqualTo("UNAVAILABLE");
        });
    }

    @Test
    void expiredAndRevokedHistoryRemainsReadableButForeignAndDraftAreDenied() {
        assertThat(service.get(9L).getAccessStatus()).isEqualTo("EXPIRED");
        assertThat(service.get(10L).getAccessStatus()).isEqualTo("REVOKED");
        assertThat(service.get(7L).getApprovedColumns()).containsExactly("Id");
        assertThat(JsonUtils.toJsonString(service.get(7L))).contains("已批准目录描述").doesNotContain("未审核草稿", "snapshot", "datasourceId");
        assertThatThrownBy(() -> service.get(13L)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.get(12L)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.get(14L)).isInstanceOf(ServiceException.class);
    }

    @Test
    void missingOrForeignCatalogVersionNeverFallsBackToCurrentDraft() throws Exception {
        sql("UPDATE dh_market_resource_apply SET catalog_version_id=99 WHERE id=7");
        var missing = service.get(7L);
        assertThat(missing.getAssetName()).isEqualTo("历史目录 #5");
        assertThat(missing.getAccessStatus()).isEqualTo("UNAVAILABLE");
        assertThat(missing.getCanPreview()).isFalse();
        sql("UPDATE dh_market_resource_apply SET catalog_version_id=12 WHERE id=7");
        sql("UPDATE dh_asset_catalog_version SET asset_id=99 WHERE id=12");
        assertThat(service.get(7L).getAssetName()).isEqualTo("历史目录 #5");
    }

    @Test
    void invalidOrNonObjectJsonDoesNotBreakThePageOrSearchDraft() throws Exception {
        for (String content : List.of("broken-json", "[]", "null", "123")) {
            try (var update = connection.prepareStatement("UPDATE dh_asset_catalog_version SET content_json=? WHERE id=12")) {
                update.setString(1, content); update.executeUpdate();
            }
            var req = new MarketMyAssetPageReqVO();
            assertThat(service.getPage(req).getList()).hasSize(5).allSatisfy(item -> {
                assertThat(item.getAssetName()).isEqualTo("历史目录 #5"); assertThat(item.getUsable()).isFalse();
            });
            req.setKeyword("未审核草稿"); assertThat(service.getPage(req).getTotal()).isZero();
            assertThat(service.get(7L).getDescription()).isNull();
        }
    }

    @Test
    void requestedExpiredOrRevokedSourceCannotUseAnotherEffectiveGrant() throws Exception {
        assertThatThrownBy(() -> service.preview(9L, 20)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.preview(10L, 20)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.preview(13L, 20)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(dataApi);
        var result = new DatabasePreviewRespDTO(); result.setColumns(List.of(new DatabasePreviewRespDTO.Column("Id", "编号", "VARCHAR")));
        result.setRows(List.of(Map.of("Id", "1")));
        when(dataApi.previewTable(any())).thenReturn(CommonResult.success(result));
        assertThat(service.preview(7L, 20).getRows()).containsExactly(Map.of("Id", "1"));
        verify(dataApi).previewTable(argThat(request -> request.getColumns().equals(List.of("Id"))
                && request.getDatabaseId() == 4L && request.getSchemaName().equals("public") && request.getLimit() == 20));
    }

    @Test
    void revokeDuringPreviewDiscardsRowsAfterTheRemoteRead() {
        when(dataApi.previewTable(any())).thenAnswer(call -> {
            sql("UPDATE dh_market_resource_apply SET status=6,if_auth=0,source_revision=3 WHERE id=7");
            var result = new DatabasePreviewRespDTO(); result.setColumns(List.of(new DatabasePreviewRespDTO.Column("Id", "编号", "VARCHAR")));
            result.setRows(List.of(Map.of("Id", "must-not-return"))); return CommonResult.success(result);
        });
        assertThatThrownBy(() -> service.preview(7L, 20)).isInstanceOf(ServiceException.class);
    }

    private void insertApply(long id, long userId, int status) throws Exception {
        try (var statement = connection.prepareStatement("""
                INSERT INTO dh_market_resource_apply(id,apply_no,title,asset_id,asset_type,apply_user_id,status,if_auth,
                  revision,source_revision,catalog_version_id,create_time,snapshot)
                VALUES(?,?,'批准资产申请',5,1,?,?,1,1,2,12,'2026-01-01'::timestamp + ? * interval '1 second',CAST(? AS jsonb))
                """)) {
            statement.setLong(1, id); statement.setString(2, "fixture-" + id); statement.setLong(3, userId);
            statement.setInt(4, status); statement.setLong(5, id); statement.setString(6, """
                    {"assetId":"5","assetVersion":"v1","catalogVersionId":"12","publishedSnapshotId":"11",
                    "databaseId":"4","schemaName":"public","tableName":"citizen","frozenColumns":["Id"],
                    "appId":"","apiId":"","dutyMatter":"","applyUseType":"0","applyUseTimes":"0"}
                    """); statement.executeUpdate();
        }
    }

    private void sql(String value) throws Exception { try (var statement = connection.createStatement()) { statement.execute(value); } }
    private void login(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(new LoginUser().setId(userId), null, List.of()));
    }

    @AfterAll
    void removeOwnedDatabase() throws Exception {
        if (connection != null) connection.close();
        if (database != null && database.matches("asset_my_[0-9a-f]{32}")) {
            try (var control = DriverManager.getConnection(controlUrl, "catalog_test", ""); var statement = control.createStatement()) {
                statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
            }
        }
    }
}
