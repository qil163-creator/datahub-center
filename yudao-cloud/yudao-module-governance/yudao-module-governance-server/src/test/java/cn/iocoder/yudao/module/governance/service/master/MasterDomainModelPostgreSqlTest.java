package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDomainPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDomainSaveReqVO;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDomainMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterModelMapper;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.*;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.MASTER_DOMAIN_HAS_MODEL;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 仅允许私有本机测试库，真实 Mapper、租户插件、事务代理与独立数据库会话。 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
@Timeout(30)
class MasterDomainModelPostgreSqlTest {
    private Connection control;
    private String schema;
    private MasterDomainMapper domains;
    private MasterModelMapper models;
    private MasterDomainService service;
    private DataSourceTransactionManager manager;

    @BeforeEach
    void setup() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
        control = DriverManager.getConnection(url, "codex_test", "");
        schema = "masterdomain_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
        sql("SET search_path TO " + schema);
        String audit = "creator varchar(64) DEFAULT '', updater varchar(64) DEFAULT '', "
                + "create_time timestamp DEFAULT CURRENT_TIMESTAMP, update_time timestamp DEFAULT CURRENT_TIMESTAMP, deleted smallint DEFAULT 0";
        sql("CREATE TABLE dh_governance_master_domain(id bigint PRIMARY KEY, code varchar(100), name varchar(100), description varchar(500), remark varchar(500), leader_user_id bigint, leader_phone varchar(20), icon varchar(100), sort int DEFAULT 99, status int DEFAULT 0, " + audit + ", UNIQUE(id), UNIQUE(code), UNIQUE(name))");
        sql("CREATE TABLE dh_governance_master_model(id bigint PRIMARY KEY, catalog_id bigint, domain_id bigint NOT NULL, database_id bigint, table_name varchar(100), table_cn varchar(100), description varchar(200), status int DEFAULT 0, version int DEFAULT 1, approval_enabled boolean DEFAULT false, approval_process_key varchar(100), publish_state varchar(16) DEFAULT 'READY', publish_message varchar(500), release_time timestamp, release_user_id bigint, org_id bigint, " + audit + ", FOREIGN KEY(domain_id) REFERENCES dh_governance_master_domain(id))");
        sql("INSERT INTO dh_governance_master_domain(id,code,name) VALUES(10,'person','person'),(20,'organization','organization'),(30,'empty','empty')");
        sql("INSERT INTO dh_governance_master_model(id,domain_id,database_id,table_name,table_cn,status) VALUES(100,10,1,'md_person','person',1),(200,20,2,'md_person','person',1)");
        var source = new DriverManagerDataSource(url + "?currentSchema=" + schema, "codex_test", "");
        manager = new DataSourceTransactionManager(source);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("master-domain-test", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        config.addInterceptor(interceptor);
        config.addMapper(MasterDomainMapper.class); config.addMapper(MasterModelMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        domains = session.getMapper(MasterDomainMapper.class); models = session.getMapper(MasterModelMapper.class);
        MasterDomainServiceImpl target = new MasterDomainServiceImpl();
        ReflectionTestUtils.setField(target, "domainMapper", domains);
        ReflectionTestUtils.setField(target, "modelMapper", models);
        ReflectionTestUtils.setField(target, "transactionManager", manager);
        AdminUserApi users = mock(AdminUserApi.class);
        AdminUserRespDTO leader = new AdminUserRespDTO(); leader.setId(77L); leader.setNickname("leader"); leader.setMobile("00000000000");
        when(users.validateUser(77L)).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()); return leader;
        });
        when(users.getUserMap(anyCollection())).thenReturn(java.util.Map.of(77L, leader));
        ReflectionTestUtils.setField(target, "adminUserApi", users);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        service = (MasterDomainService) proxy.getProxy();
        clearTenantContext();
    }

    @AfterEach
    void cleanup() throws Exception {
        TenantContextHolder.clear();
        if (control != null) {
            try {
                if (schema != null && schema.matches("masterdomain_[a-f0-9]{32}")) sql("DROP SCHEMA " + schema + " CASCADE");
            } finally { control.close(); }
        }
    }

    @Test
    void lookupsPaginationAndDomainStatisticsUseGlobalModels() {
        assertNotNull(domains.selectById(20L));
        assertNotNull(models.selectById(200L));
        MasterModelPageReqVO req = new MasterModelPageReqVO(); req.setName("person"); req.setPageNo(1); req.setPageSize(10);
        assertEquals(2L, models.selectPage(req).getTotal());
        assertEquals(1L, models.selectStatisticsCount(20L, null));
        assertEquals(1L, models.selectCountByDomainId(10L));
        TenantContextHolder.clear();
        assertEquals(2L, models.selectPage(req).getTotal());
    }

    @Test
    void domainMetadataClearsOptionalFieldsAndCodePaginationMatchesDataDomain() throws Exception {
        sql("UPDATE dh_governance_master_domain SET icon='legacy', sort=8, description='old', remark='old', leader_phone='old' WHERE id=10");
        MasterDomainSaveReqVO req = new MasterDomainSaveReqVO(); req.setId(10L); req.setCode("001-P"); req.setName("person"); req.setLeaderUserId(77L);
        service.updateDomain(req);
        var stored = domains.selectById(10L);
        assertEquals(77L, stored.getLeaderUserId()); assertEquals("00000000000", stored.getLeaderPhone());
        assertNull(stored.getDescription()); assertNull(stored.getRemark()); assertEquals("legacy", stored.getIcon()); assertEquals(8, stored.getSort());
        MasterDomainPageReqVO page = new MasterDomainPageReqVO(); page.setCode("01-P"); page.setPageNo(1); page.setPageSize(10);
        var result = service.getDomainPage(page);
        assertEquals(1L, result.getTotal()); assertEquals(10L, result.getList().getFirst().getId());
        assertEquals("leader", result.getList().getFirst().getLeaderUserName()); assertEquals(1L, result.getList().getFirst().getModelCount());
        page.setCode(null); assertEquals(30L, service.getDomainPage(page).getList().getFirst().getId());
    }

    @Test
    void deletionWaitsForConcurrentCreationThenRejectsNewReference() throws Exception {
        CountDownLatch domainLocked = new CountDownLatch(1);
        CountDownLatch allowCreate = new CountDownLatch(1);
        CountDownLatch deletionEntered = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> create = pool.submit(() -> {
                clearTenantContext();
                try {
                    new TransactionTemplate(manager).executeWithoutResult(status -> {
                        assertNotNull(domains.selectForUpdate(30L));
                        domainLocked.countDown();
                        try { assertTrue(allowCreate.await(5, TimeUnit.SECONDS)); }
                        catch (InterruptedException ex) { throw new IllegalStateException(ex); }
                        models.insert(MasterModelDO.builder().id(300L).domainId(30L).databaseId(3L)
                                .tableName("md_empty").tableCn("empty").status(0).version(1)
                                .publishState("READY").build());
                    });
                } finally { TenantContextHolder.clear(); }
            });
            assertTrue(domainLocked.await(5, TimeUnit.SECONDS));
            Future<?> delete = pool.submit(() -> {
                clearTenantContext();
                try { deletionEntered.countDown(); service.deleteDomain(30L); }
                finally { TenantContextHolder.clear(); }
            });
            assertTrue(deletionEntered.await(5, TimeUnit.SECONDS));
            try { assertThrows(TimeoutException.class, () -> delete.get(200, TimeUnit.MILLISECONDS)); }
            finally { allowCreate.countDown(); }
            create.get(5, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> delete.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ServiceException.class, failure.getCause());
            assertEquals(MASTER_DOMAIN_HAS_MODEL.getCode(), ((ServiceException) failure.getCause()).getCode());
            assertNotNull(domains.selectById(30L));
            assertEquals(1L, models.selectCountByDomainId(30L));
        }
    }

    @Test
    void publishClaimIsExclusiveRejectsStaleModelAndMissingId() throws Exception {
        sql("UPDATE dh_governance_master_model SET status=0 WHERE id=100");
        MasterModelDO stale = models.selectById(100L);
        sql("UPDATE dh_governance_master_model SET update_time=update_time+interval '1 second' WHERE id=100");
        assertEquals(0, models.claimPublish(stale));
        MasterModelDO current = models.selectById(100L);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> claim = () -> {
                clearTenantContext();
                try { assertTrue(start.await(5, TimeUnit.SECONDS)); return models.claimPublish(current); }
                finally { TenantContextHolder.clear(); }
            };
            Future<Integer> first = pool.submit(claim); Future<Integer> second = pool.submit(claim); start.countDown();
            assertEquals(1, first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS));
        }
        assertEquals("APPLYING", models.selectById(100L).getPublishState());
        clearTenantContext();
        assertEquals(0, models.markPublishUnknown(9999L));
        clearTenantContext();
        assertEquals(1, models.markPublishUnknown(100L));
        assertEquals("UNKNOWN", models.selectById(100L).getPublishState());
    }

    private static void clearTenantContext() { TenantContextHolder.clear(); TenantContextHolder.setIgnore(false); }
    private void sql(String query) throws Exception { try (var statement = control.createStatement()) { statement.execute(query); } }
}
