package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelPageReqVO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterCodeRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDomainMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterModelMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.MASTER_MODEL_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 PostgreSQL 验证计数、超时、租户过滤与字段聚合；没有 Docker 时明确跳过。 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(30)
class MasterModelListPostgreSqlTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private MasterModelServiceImpl service;
    private MasterDatabaseResolver resolver;
    private DbSwitchRuntime runtime;
    private Connection control;
    private String schema;

    @BeforeEach
    void setup() throws Exception {
        schema = "masterlist_" + UUID.randomUUID().toString().replace("-", "");
        control = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        sql("CREATE SCHEMA " + schema);
        sql("SET search_path TO " + schema);
        String audit = "creator varchar(64) DEFAULT '', updater varchar(64) DEFAULT '', "
                + "create_time timestamp DEFAULT CURRENT_TIMESTAMP, update_time timestamp DEFAULT CURRENT_TIMESTAMP, deleted smallint DEFAULT 0";
        sql("CREATE TABLE dh_governance_master_model(id bigint PRIMARY KEY, catalog_id bigint, domain_id bigint, "
                + "database_id bigint, table_name varchar(100), table_cn varchar(100), description varchar(200), "
                + "status int DEFAULT 0, version int DEFAULT 1, approval_enabled boolean DEFAULT false, approval_process_key varchar(100), "
                + "publish_state varchar(16) DEFAULT 'READY', publish_message varchar(500), release_time timestamp, release_user_id bigint, org_id bigint, " + audit + ")");
        sql("CREATE TABLE dh_governance_master_column(id bigint PRIMARY KEY, master_model_id bigint, " + audit + ")");
        sql("INSERT INTO dh_governance_master_model(id,database_id,table_name,table_cn,status) VALUES(100,20,'md Person','人员',1),(200,30,'other_person','其他模型',1)");
        sql("INSERT INTO dh_governance_master_column(id,master_model_id) VALUES(1,100),(2,100),(3,100)");
        sql("CREATE TABLE \"md Person\"(id bigint PRIMARY KEY)");
        sql("INSERT INTO \"md Person\" VALUES(1),(2),(3)");
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl()
                + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("master-list-test", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        configuration.addInterceptor(interceptor);
        configuration.addMapper(MasterModelMapper.class); configuration.addMapper(MasterColumnMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        service = new MasterModelServiceImpl();
        ReflectionTestUtils.setField(service, "modelMapper", session.getMapper(MasterModelMapper.class));
        ReflectionTestUtils.setField(service, "columnMapper", session.getMapper(MasterColumnMapper.class));
        ReflectionTestUtils.setField(service, "domainMapper", mock(MasterDomainMapper.class));
        ReflectionTestUtils.setField(service, "codeRuleMapper", mock(MasterCodeRuleMapper.class));
        AdminUserApi users = mock(AdminUserApi.class);
        when(users.getUserMap(anyCollection())).thenReturn(Map.of());
        ReflectionTestUtils.setField(service, "adminUserApi", users);
        DatabaseApi databaseApi = mock(DatabaseApi.class);
        when(databaseApi.getDatabaseList(null)).thenReturn(CommonResult.success(List.of()));
        ReflectionTestUtils.setField(service, "databaseApi", databaseApi);
        resolver = spy(new MasterDatabaseResolver());
        var connection = new MasterDatabaseResolver.ConnectionInfo();
        connection.setProductType(ProductTypeEnum.POSTGRESQL); connection.setSchema(schema);
        connection.setJdbcUrl(POSTGRES.getJdbcUrl()); connection.setUsername(POSTGRES.getUsername());
        connection.setPassword(POSTGRES.getPassword());
        doReturn(connection).when(resolver).resolve(eq(20L), any());
        ReflectionTestUtils.setField(service, "databaseResolver", resolver);
        runtime = DbSwitchRuntime.standalone();
        ReflectionTestUtils.setField(service, "dbSwitchRuntime", runtime);
        TenantContextHolder.clear();
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(99L), new MockHttpServletRequest());
    }

    @AfterEach
    void cleanup() throws Exception {
        TenantContextHolder.clear(); SecurityContextHolder.clearContext();
        if (runtime != null) runtime.close();
        if (control != null) {
            try { if (schema != null && schema.matches("masterlist_[a-f0-9]{32}")) sql("DROP SCHEMA " + schema + " CASCADE"); }
            finally { control.close(); }
        }
    }

    @Test
    void countAndFieldAggregationUseTheRequestedModelWithoutTenantContext() {
        var page = service.getModelPage(new MasterModelPageReqVO());
        assertEquals(2L, page.getTotal());
        assertEquals(3L, page.getList().stream().filter(row -> row.getId().equals(100L)).findFirst().orElseThrow().getFieldCount());
        var response = service.getDataCount(100L);
        assertTrue(response.isAvailable()); assertEquals(3L, response.getDataCount());
        ServiceException denied = assertThrows(ServiceException.class, () -> service.getDataCount(9999L));
        assertEquals(MASTER_MODEL_NOT_EXISTS.getCode(), denied.getCode());
        verify(resolver, never()).resolve(eq(30L), any());
    }

    @Test
    void missingPhysicalTableIsUnknownAndDoesNotBreakModelPagination() throws Exception {
        sql("DROP TABLE \"md Person\"");
        var response = service.getDataCount(100L);
        assertFalse(response.isAvailable()); assertNull(response.getDataCount());
        assertNotNull(response.getMessage());
        assertEquals(2L, service.getModelPage(new MasterModelPageReqVO()).getTotal());
    }

    @Test
    void lockedTableCountIsCancelledWithinItsBudget() throws Exception {
        try (var lock = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            lock.setAutoCommit(false);
            try (var statement = lock.createStatement()) { statement.execute("LOCK TABLE " + schema + ".\"md Person\" IN ACCESS EXCLUSIVE MODE"); }
            long start = System.nanoTime();
            var response = service.getDataCount(100L);
            assertFalse(response.isAvailable()); assertNull(response.getDataCount());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(12)) < 0);
            lock.rollback();
        }
    }

    private void sql(String sql) throws Exception {
        try (var statement = control.createStatement()) { statement.execute(sql); }
    }
}
