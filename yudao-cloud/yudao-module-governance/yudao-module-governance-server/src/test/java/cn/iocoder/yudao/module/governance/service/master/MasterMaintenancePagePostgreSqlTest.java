package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelPageReqVO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.*;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用本机隔离 PostgreSQL，验证维护视图范围、组合筛选和成功维护时间聚合。 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[a-z][a-z0-9_]*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(30)
class MasterMaintenancePagePostgreSqlTest {
    private Connection control;
    private String schema;
    private MasterModelServiceImpl service;
    private MasterDataChangeMapper changes;

    @BeforeAll
    void setup() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        String user = System.getProperty("datahub.test.postgres.user", "codex_test");
        control = DriverManager.getConnection(url, user, "");
        schema = "maintenance_page_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
        sql("SET search_path TO " + schema);
        String audit = "creator varchar(64) DEFAULT '', updater varchar(64) DEFAULT '', "
                + "create_time timestamp DEFAULT CURRENT_TIMESTAMP, update_time timestamp DEFAULT CURRENT_TIMESTAMP, deleted smallint DEFAULT 0";
        sql("CREATE TABLE dh_governance_master_model(id bigint PRIMARY KEY, catalog_id bigint, domain_id bigint, "
                + "database_id bigint, table_name varchar(100), table_cn varchar(100), description varchar(200), "
                + "status int DEFAULT 0, version int DEFAULT 1, approval_enabled boolean DEFAULT false, approval_process_key varchar(100), "
                + "publish_state varchar(16) DEFAULT 'READY', publish_message varchar(500), release_time timestamp, release_user_id bigint, org_id bigint, " + audit + ")");
        sql("CREATE TABLE dh_governance_master_column(id bigint PRIMARY KEY, master_model_id bigint, " + audit + ")");
        sql("CREATE TABLE dh_governance_master_data_change(id bigint PRIMARY KEY, model_id bigint, status varchar(32), applied_at timestamp, " + audit + ")");
        sql("INSERT INTO dh_governance_master_model(id,domain_id,database_id,table_name,table_cn,status,publish_state,create_time,deleted) VALUES"
                + "(100,10,20,'river_section','河段',1,'READY','2026-10-01 10:00:00',0),"
                + "(101,10,20,'river_draft','河段草稿',0,'READY','2026-10-01 10:00:00',0),"
                + "(102,10,20,'river_offline','河段下线',2,'READY','2026-10-01 10:00:00',0),"
                + "(103,10,20,'river_unknown','河段待核查',1,'UNKNOWN','2026-10-01 10:00:00',0),"
                + "(104,10,20,'river_applying','河段发布中',1,'APPLYING','2026-10-01 10:00:00',0),"
                + "(105,10,20,'river_deleted','河段已删除',1,'READY','2026-10-01 10:00:00',1),"
                + "(200,20,20,'river_other','河段其他类目',1,'READY','2026-10-01 10:00:00',0),"
                + "(201,10,20,'river_recent','河段新建',1,'READY','2026-10-15 10:00:00',0)");
        sql("INSERT INTO dh_governance_master_column(id,master_model_id,deleted) VALUES(1,100,0),(2,100,0),(3,100,1),(4,200,0)");
        sql("INSERT INTO dh_governance_master_data_change(id,model_id,status,applied_at,deleted) VALUES"
                + "(1,100,'APPLIED','2026-10-01 12:00:00',0),(2,100,'APPLIED','2026-10-02 12:00:00',0),"
                + "(3,100,'PENDING','2026-10-15 12:00:00',0),(4,100,'UNKNOWN','2026-10-16 12:00:00',0),"
                + "(5,100,'APPLIED','2026-10-20 12:00:00',1),(6,200,'APPLIED','2026-10-21 12:00:00',0),"
                + "(7,100,'APPLIED',NULL,0)");
        var datasource = new DriverManagerDataSource(url + "?currentSchema=" + schema, user, "");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("maintenance-page-test", new SpringManagedTransactionFactory(), datasource));
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        configuration.addInterceptor(interceptor);
        configuration.addMapper(MasterModelMapper.class);
        configuration.addMapper(MasterColumnMapper.class);
        configuration.addMapper(MasterDataChangeMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        changes = session.getMapper(MasterDataChangeMapper.class);
        service = new MasterModelServiceImpl();
        ReflectionTestUtils.setField(service, "modelMapper", session.getMapper(MasterModelMapper.class));
        ReflectionTestUtils.setField(service, "columnMapper", session.getMapper(MasterColumnMapper.class));
        ReflectionTestUtils.setField(service, "dataChangeMapper", changes);
        ReflectionTestUtils.setField(service, "domainMapper", mock(MasterDomainMapper.class));
        ReflectionTestUtils.setField(service, "codeRuleMapper", mock(MasterCodeRuleMapper.class));
        ReflectionTestUtils.setField(service, "adminUserApi", mock(AdminUserApi.class));
        var databases = mock(DatabaseApi.class);
        when(databases.getDatabaseList(null)).thenReturn(CommonResult.success(List.of()));
        ReflectionTestUtils.setField(service, "databaseApi", databases);
    }

    @AfterAll
    void cleanup() throws Exception {
        if (control != null) {
            try {
                if (schema != null && schema.matches("maintenance_page_[a-f0-9]{32}")) {
                    sql("DROP SCHEMA " + schema + " CASCADE");
                }
            } finally { control.close(); }
        }
    }

    @Test
    void onlyReadyPublishedModelsAreVisibleAndStatusCannotOverrideThatScope() {
        var req = new MasterModelPageReqVO();
        var result = service.getMaintenanceModelPage(req);
        assertEquals(3L, result.getTotal());
        assertEquals(List.of(201L, 200L, 100L), result.getList().stream().map(row -> row.getId()).toList());
        req.setStatus(0);
        assertEquals(0L, service.getMaintenanceModelPage(req).getTotal());
        req.setStatus(2);
        assertEquals(0L, service.getMaintenanceModelPage(req).getTotal());
    }

    @Test
    void nameCodeDomainDateAndPaginationFiltersApplyTogether() {
        var req = new MasterModelPageReqVO();
        req.setName("河段"); req.setTableName("river_"); req.setDomainId(10L); req.setStatus(1);
        req.setCreateTime(new LocalDateTime[]{LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 10, 2, 23, 59)});
        var result = service.getMaintenanceModelPage(req);
        assertEquals(1L, result.getTotal());
        assertEquals(100L, result.getList().getFirst().getId());
        assertEquals(2L, result.getList().getFirst().getFieldCount());
        req.setCreateTime(null); req.setPageSize(1); req.setPageNo(2);
        result = service.getMaintenanceModelPage(req);
        assertEquals(2L, result.getTotal()); assertEquals(100L, result.getList().getFirst().getId());
        req.setTableName("' OR 1=1 --");
        assertEquals(0L, service.getMaintenanceModelPage(req).getTotal());
    }

    @Test
    void latestMaintenanceIncludesOnlyVisibleAppliedRowsOfRequestedModels() {
        var rows = changes.selectLatestAppliedByModels(List.of(100L, 201L));
        assertEquals(1, rows.size());
        assertEquals(100L, rows.getFirst().getModelId());
        assertEquals(LocalDateTime.of(2026, 10, 2, 12, 0), rows.getFirst().getAppliedAt());
        var page = service.getMaintenanceModelPage(new MasterModelPageReqVO());
        assertEquals(rows.getFirst().getAppliedAt(), page.getList().getLast().getLastMaintenanceTime());
        assertNull(page.getList().getFirst().getLastMaintenanceTime());
        assertTrue(changes.selectLatestAppliedByModels(List.of()).isEmpty());
    }

    private void sql(String statement) throws Exception {
        try (var query = control.createStatement()) { query.setQueryTimeout(15); query.execute(statement); }
    }
}
