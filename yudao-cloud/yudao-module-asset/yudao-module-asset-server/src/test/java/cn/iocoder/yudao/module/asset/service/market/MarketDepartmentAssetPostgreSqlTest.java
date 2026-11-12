package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDepartmentAssetPageReqVO;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDepartmentAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDepartmentAssetRow;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetSupplyStopMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 PostgreSQL 的部门隔离、来源绑定、过滤排序及实时身份变更，不使用 H2 推断 JSONB。 */
@EnabledIfEnvironmentVariable(named = "ASSET_MARKET_TEST_JDBC_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class MarketDepartmentAssetPostgreSqlTest {
    private String controlUrl;
    private String database;
    private String user;
    private Connection connection;
    private MarketDepartmentAssetMapper mapper;
    private MarketResourceApplyMapper applications;
    private AssetSupplyStopMapper stops;

    @BeforeAll
    void createOnlyOwnedDatabase() throws Exception {
        controlUrl = System.getenv("ASSET_MARKET_TEST_JDBC_URL");
        assertThat(controlUrl).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres").doesNotContain(":5433/");
        user = System.getenv().getOrDefault("ASSET_MARKET_TEST_JDBC_USER", "catalog_test");
        database = "department_assets_" + UUID.randomUUID().toString().replace("-", "");
        try (var control = DriverManager.getConnection(controlUrl, user, ""); var sql = control.createStatement()) {
            sql.execute("CREATE DATABASE " + database);
        }
        String url = controlUrl.substring(0, controlUrl.lastIndexOf('/') + 1) + database;
        connection = DriverManager.getConnection(url, user, "");
        sql("""
                CREATE TABLE dh_asset_data_asset(id bigint PRIMARY KEY,external_code varchar(256),deleted smallint DEFAULT 0,status int DEFAULT 2,stop_supply_user_id bigint DEFAULT 9);
                CREATE TABLE dh_asset_catalog_version(id bigint PRIMARY KEY,asset_id bigint,status int,content_json text,deleted smallint DEFAULT 0);
                CREATE TABLE dh_asset_market_category(id bigint PRIMARY KEY,parent_id bigint,name varchar(256),deleted smallint DEFAULT 0);
                CREATE TABLE dh_asset_market_listing(asset_id bigint PRIMARY KEY,market_category_id bigint,display_name varchar(256),frequency varchar(64),delivery_types varchar(32),deleted smallint DEFAULT 0);
                CREATE TABLE dh_market_approval_work_order(id bigint PRIMARY KEY,source_system varchar(64),source_record_id varchar(64),mart_source_id varchar(64),mart_name varchar(256),detail_snapshot jsonb,deleted smallint DEFAULT 0);
                CREATE TABLE dh_market_resource_apply(id bigint PRIMARY KEY,asset_id bigint,asset_type int,api_id bigint,apply_user_id bigint,apply_dept_id bigint,
                    source_work_order_id bigint UNIQUE REFERENCES dh_market_approval_work_order(id),catalog_version_id bigint,title varchar(256),status int,if_auth int,app_id bigint,
                    create_time timestamp,apply_start_time timestamp,apply_end_time timestamp,apply_note text,purpose text,deleted smallint DEFAULT 0);
                CREATE TABLE dh_market_delivery_operation(id bigint PRIMARY KEY,apply_id bigint,source_revision bigint,status varchar(32),op_type varchar(32),resolution_status varchar(32),deleted smallint DEFAULT 0);
                CREATE TABLE dh_asset_grant_source(apply_id bigint,asset_id bigint,revoked int,source_status varchar(32),deleted smallint DEFAULT 0);
                """);
        var source = new UnpooledDataSource("org.postgresql.Driver", url + "?socketTimeout=15", user, "");
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setEnvironment(new Environment("department-assets-isolated", new JdbcTransactionFactory(), source));
        configuration.addMapper(MarketDepartmentAssetMapper.class);
        configuration.addMapper(MarketResourceApplyMapper.class);
        configuration.addMapper(AssetSupplyStopMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        mapper = session.getMapper(MarketDepartmentAssetMapper.class);
        applications = session.getMapper(MarketResourceApplyMapper.class);
        stops = session.getMapper(AssetSupplyStopMapper.class);
    }

    @BeforeEach
    void fixtures() throws Exception {
        sql("TRUNCATE dh_market_resource_apply,dh_market_approval_work_order,dh_asset_market_listing,dh_asset_market_category,dh_asset_catalog_version,dh_asset_data_asset");
        sql("""
                INSERT INTO dh_asset_data_asset(id,external_code,deleted) VALUES(16,'qdata:market:22',0),(2,'local:2',0);
                INSERT INTO dh_asset_catalog_version VALUES(2,2,4,'{"format":"1","asset":{"id":2,"assetName":"冻结资产"}}',0);
                INSERT INTO dh_asset_market_category VALUES(10,0,'父类目',0),(11,10,'来源类目',0),(20,0,'本地类目',0);
                INSERT INTO dh_asset_market_listing VALUES(16,11,'河流', 'REAL_TIME','1,2',0),(2,20,'本地名称','DAILY','1',0);
                INSERT INTO dh_market_approval_work_order VALUES
                  (1,'QDATA','21','22','河流维表','{"resource":{"sourceRecordId":"22","assetName":"来源河流","deliveryTypes":[1,2]},"columns":[{"columnName":"STCD"}],"preview":{"total":"3","columns":[],"rows":[{"ID":"1"},{"ID":"2"},{"ID":"3"}],"truncated":false}}',0),
                  (2,'QDATA','20','22','河流维表','{"resource":{"sourceRecordId":"22","deliveryTypes":[1,2]}}',0),
                  (3,'QDATA','9007199254740993','22','河流维表','{"resource":{"sourceRecordId":"22","deliveryTypes":[1,2]}}',0),
                  (4,'QDATA','99','22','已删除来源','{}',1);
                INSERT INTO dh_market_resource_apply(id,asset_id,asset_type,apply_user_id,apply_dept_id,source_work_order_id,status,if_auth,create_time) VALUES
                  (1,16,1,NULL,100,1,2,0,'2026-10-22 22:25'),(5,16,1,NULL,101,2,2,0,'2026-10-22 18:53'),
                  (8,16,1,NULL,100,3,2,0,'2026-10-21'),(9,16,1,NULL,100,4,2,0,'2026-10-25'),
                  (4,2,1,13,100,NULL,1,0,'2026-10-25');
                INSERT INTO dh_market_resource_apply(id,asset_id,asset_type,apply_user_id,apply_dept_id,catalog_version_id,status,if_auth,create_time)
                  SELECT 100+n,2,1,CASE WHEN n%2=0 THEN 9 ELSE 12 END,999,2,
                         (ARRAY[0,1,2,3,4,5,6,8])[n+1],0,'2026-10-01'::timestamp+n*INTERVAL '1 day' FROM generate_series(0,7) n;
                """);
    }

    @AfterEach
    void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test
    void exactLiveMemberScopeAndExplicitSourceDepartmentAreIndependentOfHistoricalOwnerDepartment() {
        var req = new MarketDepartmentAssetPageReqVO(); req.setPageSize(100);
        assertThat(mapper.count(req, 100L, List.of(9L, 12L), null)).isEqualTo(10);
        assertThat(mapper.selectPage(req, 100L, List.of(9L, 12L), null, 0)).extracting(MarketDepartmentAssetRow::getStatus)
                .contains(0, 1, 2, 3, 4, 5, 6, 8);
        assertThat(mapper.count(req, 100L, List.of(), null)).isEqualTo(2);
        assertThat(mapper.count(req, 101L, List.of(13L), null)).isEqualTo(2);
        assertThat(mapper.selectInDepartment(4L, 100L, List.of(9L, 12L))).isNull();
        assertThat(mapper.selectInDepartment(1L, 101L, List.of(13L))).isNull();
        assertThat(mapper.selectInDepartment(5L, 100L, List.of(9L, 12L))).isNull();
        assertThat(mapper.selectInDepartment(9L, 100L, List.of(9L, 12L))).isNull();
        assertThat(mapper.selectInDepartment(21L, 100L, List.of(9L, 12L))).isNull();
    }

    @Test
    void keywordDeliveryAndCategoryFiltersRunBeforePagingAndSourceNumberSortIsLossless() {
        var req = new MarketDepartmentAssetPageReqVO(); req.setPageSize(1); req.setSortField("applyId");
        var first = mapper.selectPage(req, 100L, List.of(9L, 12L), null, 0);
        assertThat(first).extracting(MarketDepartmentAssetRow::getSourceApplyId).containsExactly("9007199254740993");
        req.setSortOrder("asc");
        assertThat(mapper.selectPage(req, 100L, List.of(9L, 12L), null, 0)).extracting(MarketDepartmentAssetRow::getId).containsExactly(1L);
        req.setKeyword("河流"); req.setDeliveryType(2);
        assertThat(mapper.count(req, 100L, List.of(9L, 12L), List.of(11L))).isEqualTo(2);
        assertThat(mapper.count(req, 100L, List.of(9L, 12L), List.of(20L))).isZero();
        req.setKeyword("' OR 1=1 --");
        assertThat(mapper.count(req, 100L, List.of(9L, 12L), null)).isZero();
        assertThat(mapper.selectCategories(100L, List.of())).extracting(item -> item.getId()).containsExactly(10L, 11L);
    }

    @Test
    void realMapperAndRealtimeUserServiceRejectTransferredUserBeforeReadingAnotherDepartmentsSourceData() {
        var users = mock(AdminUserApi.class); var liveDelivery = mock(MarketMyAssetService.class);
        var actor = user(9, 100); when(users.getUser(9L)).thenAnswer(call -> CommonResult.success(actor));
        when(users.getUserListByDeptIds(List.of(100L))).thenReturn(CommonResult.success(List.of(actor, user(12, 100))));
        when(users.getUserListByDeptIds(List.of(101L))).thenReturn(CommonResult.success(List.of(actor, user(13, 101))));
        var scope = new MarketDepartmentScope(); ReflectionTestUtils.setField(scope, "adminUserApi", users);
        var service = new MarketDepartmentAssetServiceImpl(); ReflectionTestUtils.setField(service, "departmentScope", scope);
        ReflectionTestUtils.setField(service, "departmentAssetMapper", mapper); ReflectionTestUtils.setField(service, "snapshotMasks", org.mockito.Mockito.mock(MarketSnapshotMaskSupport.class)); ReflectionTestUtils.setField(service, "myAssetService", liveDelivery);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new LoginUser().setId(9L), null, List.of()));
        assertThat(service.get(1L).getPreview().getRows()).hasSize(3);
        assertThat(service.get(1L).getCanInvoke()).isFalse();
        actor.setDeptId(101L);
        assertThatThrownBy(() -> service.get(1L)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.preview(1L, 20)).isInstanceOf(ServiceException.class);
        assertThat(service.get(5L).getAsset().getSourceApplyId()).isEqualTo("20");
        verifyNoInteractions(liveDelivery);
    }

    @Test
    void copiedApprovalsNeverEnterExpiryStopScanningOrBlockTheNativeStopWorkflow() throws Exception {
        sql("UPDATE dh_market_resource_apply SET apply_end_time='2000-01-01' WHERE status=2");
        assertThat(applications.selectExpiredDue(100)).extracting(item -> item.getId()).containsExactly(102L);
        assertThat(stops.due()).containsExactly(102L);
        assertThat(stops.openApplications(16)).isZero();
        assertThat(stops.pending(16)).isZero();
        assertThat(stops.openApplications(2)).isEqualTo(5);
        assertThat(stops.pending(2)).isEqualTo(1);
    }

    @AfterAll
    void destroyOnlyOwnedDatabase() throws Exception {
        if (connection != null) connection.close();
        if (database != null) {
            assertThat(database).matches("department_assets_[0-9a-f]{32}");
            try (var control = DriverManager.getConnection(controlUrl, user, ""); var statement = control.createStatement()) {
                statement.execute("DROP DATABASE " + database + " WITH (FORCE)");
            }
        }
    }

    private void sql(String value) throws Exception { try (var statement = connection.createStatement()) { statement.execute(value); } }
    private static AdminUserRespDTO user(long id, long dept) {
        var result = new AdminUserRespDTO(); result.setId(id); result.setDeptId(dept); result.setStatus(0); return result;
    }
}
