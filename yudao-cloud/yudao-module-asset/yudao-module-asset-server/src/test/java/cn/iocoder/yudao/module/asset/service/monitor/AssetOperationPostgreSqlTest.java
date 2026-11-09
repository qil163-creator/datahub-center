package cn.iocoder.yudao.module.asset.service.monitor;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.module.asset.controller.admin.monitor.vo.AssetInventoryRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.monitor.vo.AssetOperationPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.monitor.vo.AssetOperationRespVO;
import cn.iocoder.yudao.module.asset.dal.mysql.categorydataasset.CategoryDataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.monitor.AssetOperationMetricMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.monitor.AssetOperationSourceMapper;
import cn.iocoder.yudao.module.supply.api.dataapi.SupplyDataApiApi;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyApiCallStatReqDTO;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyApiCallStatRespDTO;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.*;
import java.util.List;
import java.util.UUID;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.ASSET_OPERATION_REFRESHING;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 在导入完整基线的独立库上验证运营指标计算、快照替换、互斥、盘点报告与数据服务降级。 */
@EnabledIfEnvironmentVariable(named = "ASSET_MARKET_TEST_JDBC_URL", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class AssetOperationPostgreSqlTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);

    private String controlUrl;
    private String database;
    private Connection connection;
    private DriverManagerDataSource source;
    private AssetOperationMetricMapper metrics;
    private SupplyDataApiApi supply;
    private AssetOperationServiceImpl service;

    @BeforeAll
    void importBaseline() throws Exception {
        controlUrl = System.getenv("ASSET_MARKET_TEST_JDBC_URL");
        database = "asset_operation_" + UUID.randomUUID().toString().replace("-", "");
        try (var control = DriverManager.getConnection(controlUrl, "catalog_test", ""); var sql = control.createStatement()) {
            sql.execute("CREATE DATABASE " + database);
        }
        String url = controlUrl.substring(0, controlUrl.lastIndexOf('/') + 1) + database;
        connection = DriverManager.getConnection(url, "catalog_test", "");
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        try (var sql = connection.createStatement()) {
            sql.execute(Files.readString(root.resolve("sql/postgresql/datahub-center.sql")));
            sql.execute("SET search_path TO \"datahub-center\"");
        }
        source = new DriverManagerDataSource(url + "?currentSchema=datahub-center", "catalog_test", "");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        GlobalConfigUtils.getGlobalConfig(config).getDbConfig().setIdType(IdType.ASSIGN_ID);
        GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(new DefaultDBFieldHandler());
        config.setEnvironment(new Environment("asset-operation-real-pg", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        config.addInterceptor(interceptor);
        config.addMapper(DataAssetMapper.class);
        config.addMapper(CategoryDataAssetMapper.class);
        config.addMapper(AssetOperationMetricMapper.class);
        config.addMapper(AssetOperationSourceMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        metrics = session.getMapper(AssetOperationMetricMapper.class);
        supply = mock(SupplyDataApiApi.class);
        service = new AssetOperationServiceImpl(session.getMapper(DataAssetMapper.class),
                session.getMapper(CategoryDataAssetMapper.class), metrics,
                session.getMapper(AssetOperationSourceMapper.class), supply,
                new TransactionTemplate(new DataSourceTransactionManager(source)),
                Clock.fixed(TODAY.atTime(10, 0).atZone(ZONE).toInstant(), ZONE));
    }

    @AfterAll
    void dropDatabase() throws Exception {
        if (connection != null) connection.close();
        if (database != null) {
            assertTrue(database.matches("asset_operation_[a-f0-9]{32}"));
            try (var control = DriverManager.getConnection(controlUrl, "catalog_test", ""); var sql = control.createStatement()) {
                sql.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            }
        }
    }

    @BeforeEach
    void fixture() throws Exception {
        reset(supply);
        sql("TRUNCATE dh_asset_operation_metric, dh_asset_usage_log, dh_asset_market_favorite, dh_market_resource_apply,"
                + " dh_market_resource_comment, dh_asset_data_asset, dh_asset_category_data_asset CASCADE");
        sql("INSERT INTO dh_asset_category_data_asset(id,category_name,category_code) VALUES (31,'交通','TRAFFIC')");
        // 1：热门已发布库表资产；2：接口资产（经授权申请关联 API 900）；3：发布很久从未使用；4：未发布草稿。
        sql("INSERT INTO dh_asset_data_asset(id,asset_code,catalog_item_urn,asset_name,asset_type,status,category_id,"
                + "description,tech_leader_user_id,business_leader_user_id,owner_dept_id,update_cycle,share_attr,"
                + "subject_domain_id,health_score,publish_time,create_time) VALUES"
                + " (1,'A1','urn:1','订单明细',1,1,31,'订单',2,3,4,'DAILY',1,5,90,'2026-01-01','2026-01-01'),"
                + " (2,'A2','urn:2','订单接口',2,1,31,'接口',2,NULL,4,'DAILY',1,NULL,NULL,'2026-03-01','2026-03-01'),"
                + " (3,'A3','urn:3','历史档案',1,1,NULL,NULL,NULL,NULL,NULL,NULL,0,NULL,40,'2026-02-01','2026-02-01'),"
                + " (4,'A4','urn:4','草稿表',1,0,NULL,NULL,NULL,NULL,NULL,NULL,0,NULL,NULL,NULL,'2026-10-01')");
        sql("INSERT INTO dh_asset_usage_log(asset_id,action,user_id,create_time) VALUES"
                + " (1,1,10,'2026-11-08 09:00'),(1,1,11,'2026-11-08 10:00'),(1,1,10,'2026-11-09 08:00'),"
                + " (1,3,10,'2026-11-07 09:00'),(1,3,12,'2026-11-09 09:00'),(1,2,12,'2026-11-05 09:00'),"
                + " (1,3,10,'2026-08-01 09:00')," // 窗口外：不计次数，但仍是最近使用之前的历史
                + " (3,1,10,'2026-11-01 09:00')"); // 资产 3 只有浏览，不算使用
        sql("INSERT INTO dh_asset_market_favorite(user_id,asset_id) VALUES (10,1),(11,1)");
        sql("INSERT INTO dh_market_resource_apply(apply_no,title,asset_id,api_id,apply_dept_id,apply_user_id,status,apply_end_time) VALUES"
                + " ('P1','订单明细申请',1,NULL,100,12,2,NULL),"
                + " ('P2','订单明细申请',1,NULL,101,13,2,'2027-01-01'),"
                + " ('P3','过期授权',1,NULL,102,14,2,'2026-10-01'),"
                + " ('P4','接口申请',2,900,100,12,2,NULL)");
        sql("INSERT INTO dh_market_resource_comment(asset_id,comment_str,comment_kind,create_time) VALUES"
                + " (1,'字段缺失',2,'2026-11-02'),(1,'很好用',1,'2026-11-02')");
    }

    @Test
    void refreshDerivesUsageScoresAndReplacesTheSameDay() throws Exception {
        when(supply.listApiCallStatistics(any())).thenReturn(CommonResult.success(List.of(
                new SupplyApiCallStatRespDTO(900L, 2500L, 2400L, 3L, LocalDateTime.of(2026, 11, 8, 12, 0)))));
        assertEquals(4, service.refresh(TODAY));
        assertEquals(4, service.refresh(TODAY)); // 同日重算整体替换，不产生重复行
        assertEquals(4, count("SELECT count(*) FROM dh_asset_operation_metric WHERE stat_date='2026-11-09'"));

        var hot = metric(1L);
        assertEquals(3, hot.getBrowseCount());
        assertEquals(2, hot.getBrowseUserCount());
        assertEquals(2, hot.getUsageCount());
        assertEquals(1, hot.getApplyCount());
        assertEquals(2, hot.getFavoriteCount());
        assertEquals(2, hot.getActiveGrantCount()); // 过期授权不计
        assertEquals(2, hot.getGrantDeptCount());
        assertEquals(1, hot.getFeedbackCount());
        assertEquals(LocalDateTime.of(2026, 11, 9, 9, 0), hot.getLastUsedTime());
        assertEquals(0, hot.getIdleDays());
        assertEquals("交通", hot.getCategoryName());
        assertEquals(new BigDecimal("100.0"), hot.getCompletenessScore());
        assertEquals(new BigDecimal("90.0"), hot.getQualityScore());

        var api = metric(2L);
        assertEquals(2500, api.getApiCallCount());
        assertEquals(3, api.getApiAppCount());
        assertEquals(LocalDateTime.of(2026, 11, 8, 12, 0), api.getLastUsedTime());
        assertTrue(api.getApiStatsAvailable());
        assertNull(api.getQualityScore());

        var idle = metric(3L);
        assertNull(idle.getLastUsedTime()); // 只有浏览
        assertEquals(281, idle.getIdleDays());
        assertEquals(AssetOperationScoring.LEVEL_IDLE, idle.getValueLevel());
        assertEquals(AssetOperationScoring.SUGGEST_RETIRE_REVIEW, idle.getSuggestion());

        var draft = metric(4L);
        assertNotEquals(AssetOperationScoring.LEVEL_IDLE, draft.getValueLevel());

        var request = org.mockito.ArgumentCaptor.forClass(SupplyApiCallStatReqDTO.class);
        verify(supply, times(2)).listApiCallStatistics(request.capture());
        assertEquals(List.of(900L), request.getValue().getApiIds());
        assertEquals(LocalDateTime.of(2026, 10, 11, 0, 0), request.getValue().getSince());

        var page = new AssetOperationPageReqVO();
        page.setSortField("heatScore");
        page.setSortOrder("desc");
        page.setValueLevel("IDLE");
        assertEquals(List.of(3L), service.getPage(page).getList().stream().map(AssetOperationRespVO::getAssetId).toList());
    }

    @Test
    void supplyOutageKeepsOtherSignalsAndMarksTheSnapshot() {
        when(supply.listApiCallStatistics(any())).thenThrow(new IllegalStateException("supply down"));
        assertEquals(4, service.refresh(TODAY));
        assertFalse(metric(2L).getApiStatsAvailable());
        assertEquals(0, metric(2L).getApiCallCount());
        assertEquals(2, metric(1L).getUsageCount());
        AssetInventoryRespVO report = service.getInventory(null);
        assertFalse(report.getApiStatsAvailable());
    }

    @Test
    void inventoryAggregatesTheLatestSnapshot() {
        when(supply.listApiCallStatistics(any())).thenReturn(CommonResult.success(List.of()));
        assertNull(service.getInventory(null).getStatDate()); // 尚无快照
        service.refresh(TODAY);
        AssetInventoryRespVO report = service.getInventory(null);
        assertEquals(TODAY, report.getStatDate());
        assertEquals(4, report.getTotalCount());
        assertEquals(3, report.getPublishedCount());
        assertEquals(1, report.getUnpublishedCount());
        assertEquals(1, report.getUsedCount());
        assertEquals(3, report.getActiveUserCount());
        assertEquals(4, report.getBrowseTotal());
        assertEquals(new BigDecimal("50.0"), report.getDescriptionRate());
        assertEquals(new BigDecimal("50.0"), report.getOwnerRate());
        assertEquals(new BigDecimal("50.0"), report.getQualityAssessedRate());
        assertEquals(1L, report.getLevelDistribution().get(AssetOperationScoring.LEVEL_IDLE));
        assertEquals(3L, report.getTypeDistribution().get(1));
        assertEquals("交通", report.getCategoryDistribution().getFirst().getCategoryName());
        assertEquals(2, report.getCategoryDistribution().getFirst().getCount());
        assertEquals("未归类", report.getCategoryDistribution().get(1).getCategoryName());
        assertEquals(1L, report.getTopHot().getFirst().getAssetId());
        assertEquals(3L, report.getLongestIdle().getFirst().getAssetId());
        assertTrue(report.getLongestIdle().stream().noneMatch(row -> row.getAssetId() == 4L)); // 未发布不列入闲置
    }

    @Test
    void trendFillsEveryDayAndKeepsSnapshotScores() {
        when(supply.listApiCallStatistics(any())).thenReturn(CommonResult.success(List.of()));
        service.refresh(TODAY);
        var trend = service.getTrend(1L, 7);
        assertEquals(7, trend.getPoints().size());
        assertEquals(LocalDate.of(2026, 11, 3), trend.getPoints().getFirst().getDate());
        var last = trend.getPoints().getLast();
        assertEquals(TODAY, last.getDate());
        assertEquals(1, last.getBrowseCount());
        assertEquals(1, last.getUsageCount());
        assertNotNull(last.getHeatScore());
        assertNull(trend.getPoints().getFirst().getHeatScore());
        assertThrows(ServiceException.class, () -> service.getTrend(404L, 7));
    }

    @Test
    void concurrentRefreshIsRejectedWhileAnotherNodeHoldsTheLock() throws Exception {
        when(supply.listApiCallStatistics(any())).thenReturn(CommonResult.success(List.of()));
        try (var holder = DriverManager.getConnection(controlUrl.substring(0, controlUrl.lastIndexOf('/') + 1) + database,
                "catalog_test", "")) {
            holder.setAutoCommit(false);
            try (var sql = holder.createStatement()) {
                sql.execute("SELECT pg_advisory_xact_lock(" + AssetOperationServiceImpl.REFRESH_LOCK_KEY + ")");
            }
            ServiceException busy = assertThrows(ServiceException.class, () -> service.refresh(TODAY));
            assertEquals(ASSET_OPERATION_REFRESHING.getCode(), busy.getCode());
            holder.rollback();
        }
        assertEquals(4, service.refresh(TODAY));
        // 数据库约束兜底：同日同资产不能出现两行有效快照。
        assertThrows(java.sql.SQLException.class, () -> sql("INSERT INTO dh_asset_operation_metric(stat_date,asset_id,"
                + "value_level,suggestion) VALUES ('2026-11-09',1,'LOW','KEEP')"));
    }

    private AssetOperationRespVO metric(Long assetId) {
        var page = new AssetOperationPageReqVO();
        page.setPageSize(100);
        return service.getPage(page).getList().stream().filter(row -> row.getAssetId().equals(assetId)).findFirst()
                .orElseThrow();
    }

    private long count(String query) throws Exception {
        try (var sql = connection.createStatement(); var rows = sql.executeQuery(query)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private void sql(String statement) throws Exception {
        try (var sql = connection.createStatement()) {
            sql.execute(statement);
        }
    }

}
