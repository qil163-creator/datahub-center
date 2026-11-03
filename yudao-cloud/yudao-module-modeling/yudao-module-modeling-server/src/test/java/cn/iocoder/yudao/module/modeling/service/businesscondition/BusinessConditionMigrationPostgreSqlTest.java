package cn.iocoder.yudao.module.modeling.service.businesscondition;

import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.module.modeling.controller.admin.businesscondition.vo.BusinessConditionPageReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.IndicatorModelPageReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businesscondition.BusinessConditionDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.businesscondition.BusinessConditionMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.indicator.IndicatorModelMapper;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.*;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.incrementer.PostgreKeyGenerator;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 使用任务专用本机 PostgreSQL 验证新增迁移、实际 Mapper 和并发引用保护。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BusinessConditionMigrationPostgreSqlTest {
    private String controlUrl;
    private String user;
    private String database;
    private String url;
    private JdbcTemplate jdbc;
    private SqlSessionFactory sessions;
    private String migration;
    private long menuParent;
    private long testRole;

    @BeforeAll
    void initialize() throws Exception {
        controlUrl=System.getenv("MODEL_OVERVIEW_TEST_JDBC_URL");
        assumeTrue(controlUrl!=null&&!controlUrl.isBlank(),"需要显式提供隔离 PostgreSQL 测试环境");
        user=System.getenv().getOrDefault("MODEL_OVERVIEW_TEST_DB_USER","overview_test");
        if(!controlUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres")||!"overview_test".equals(user))
            throw new IllegalStateException("仅接受本机任务专用数据库和测试角色");
        database="business_condition_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(controlUrl,user,"");var s=c.createStatement()){s.execute("CREATE DATABASE "+database);}
        url=controlUrl.substring(0,controlUrl.lastIndexOf('/')+1)+database;
        jdbc=new JdbcTemplate(new DriverManagerDataSource(url+"?currentSchema=datahub-center",user,""));
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(root!=null&&!Files.exists(root.resolve("sql/postgresql/datahub-center.sql")))root=root.getParent();
        assertNotNull(root);
        String baseline=Files.readString(root.resolve("sql/postgresql/datahub-center.sql"));
        jdbc.execute(baseline.substring(0,baseline.indexOf("-- Migration: 0185 ")));
        menuParent=jdbc.queryForObject("SELECT id FROM system_menu WHERE component='modeling/indicator-model/index'",Long.class);
        testRole=jdbc.queryForObject("SELECT coalesce(max(role_id),0)+1 FROM system_role_menu",Long.class);
        jdbc.update("INSERT INTO system_role_menu(id,role_id,menu_id,tenant_id,deleted) SELECT max(id)+1,?,?,1,0 FROM system_role_menu",testRole,menuParent);
        migration=Files.readString(root.resolve("sql/postgresql/migrations/0185_modeling_metric-pages-business-condition.sql"));
        jdbc.execute(migration);
        var properties=new Properties();properties.setProperty("user",user);properties.setProperty("password","");properties.setProperty("currentSchema","datahub-center");
        var datasource=new UnpooledDataSource("org.postgresql.Driver",url,properties);
        var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("business-condition-test",new JdbcTransactionFactory(),datasource));
        var global=GlobalConfigUtils.getGlobalConfig(configuration);global.setMetaObjectHandler(new DefaultDBFieldHandler());
        global.getDbConfig().setIdType(IdType.INPUT).setLogicDeleteValue("1").setLogicNotDeleteValue("0").setKeyGenerators(List.of(new PostgreKeyGenerator()));
        configuration.addMapper(BusinessConditionMapper.class);configuration.addMapper(IndicatorModelMapper.class);
        sessions=new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void migrationReplaysAndPreservesNavigationWithoutGrantingNewBusinessPermissions() {
        jdbc.execute(migration);jdbc.execute(migration);
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM system_menu WHERE id=? AND type=1 AND permission=''",Long.class,menuParent));
        assertEquals(3L,jdbc.queryForObject("SELECT count(*) FROM system_menu WHERE parent_id=? AND type=2 AND deleted=0",Long.class,menuParent));
        assertEquals(2L,jdbc.queryForObject("SELECT count(*) FROM system_role_menu r JOIN system_menu m ON r.menu_id=m.id WHERE r.role_id=? AND r.deleted=0 AND m.permission='modeling:indicator-model:query'",Long.class,testRole));
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM system_role_menu r JOIN system_menu m ON r.menu_id=m.id WHERE r.role_id=? AND r.deleted=0 AND m.permission LIKE 'modeling:business-condition:%'",Long.class,testRole));
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='datahub-center' AND table_name='dh_modeling_business_condition' AND column_name='tenant_id'",Long.class));
    }

    @Test
    void sourceDeletionAndSnapshotMutationAreProtectedUntilConditionIsDeleted() {
        long domain=domain();long[] source=source(domain);long id=condition(domain,source,"source_guard");
        long otherDomain=domain();
        assertThrows(DataIntegrityViolationException.class,()->condition(otherDomain,source,"forged_"+token()));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE dh_modeling_logic_model SET data_domain_id=? WHERE id=?",otherDomain,source[0]));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE dh_modeling_logic_model SET enabled=0 WHERE id=?",source[0]));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE dh_modeling_logic_model_version SET status='REVISING' WHERE id=?",source[1]));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE dh_modeling_logic_model_version SET fields='[]'::jsonb WHERE id=?",source[1]));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE dh_modeling_logic_model_version SET deleted=1 WHERE id=?",source[1]));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("DELETE FROM dh_modeling_logic_model WHERE id=?",source[0]));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE dh_modeling_data_domain SET deleted=1 WHERE id=?",domain));
        jdbc.update("UPDATE dh_modeling_business_condition SET deleted=1 WHERE id=?",id);
        assertEquals(1,jdbc.update("UPDATE dh_modeling_logic_model SET enabled=0 WHERE id=?",source[0]));
    }

    @Test
    void definitionConstraintsAndPartialCodeUniquenessAreEnforced() {
        long domain=domain();long[] source=source(domain);String code="unique_"+token();long id=condition(domain,source,code);
        assertThrows(DataIntegrityViolationException.class,()->condition(domain,source,code.toUpperCase(Locale.ROOT)));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE dh_modeling_business_condition SET config_type='COMPOSITE' WHERE id=?",id));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE dh_modeling_business_condition SET expression='  ' WHERE id=?",id));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("UPDATE dh_modeling_business_condition SET source_version_id=-1 WHERE id=?",id));
        jdbc.update("UPDATE dh_modeling_business_condition SET deleted=1 WHERE id=?",id);
        assertTrue(condition(domain,source,code)>0);
    }

    @Test
    void realMapperFiltersSourceAndReturnsGroupedDerivedCounts() {
        long domain=domain();long[] source=source(domain);String code="mapper_"+token();
        try(var session=sessions.openSession(true)) {
            var mapper=session.getMapper(BusinessConditionMapper.class);
            // 此测试冻结0185历史结构；写入历史输入用SQL，读取仍走当前Mapper。
            long storedId=condition(domain,source,code);
            var row=mapper.selectById(storedId);
            assertNotNull(row.getId());assertEquals("status = 'SUCCESS'",mapper.selectById(row.getId()).getExpression());
            var page=new BusinessConditionPageReqVO();page.setKeyword(code);page.setSourceModelId(source[0]);page.setPageNo(1);page.setPageSize(10);
            assertEquals(1,mapper.selectPage(page).getList().size());page.setSourceModelId(-1L);assertTrue(mapper.selectPage(page).getList().isEmpty());
            long atomic=jdbc.queryForObject("INSERT INTO dh_modeling_indicator_model(name,code,indicator_type,data_domain_id,source_model_id,source_version_id,value_field_uid,time_field_uid,aggregation,result_data_type,status) VALUES(?,?,'ATOMIC',?,?,?,'amount','time','SUM','DECIMAL','ACTIVE') RETURNING id",Long.class,token(),token(),domain,source[0],source[1]);
            jdbc.update("INSERT INTO dh_modeling_indicator_model(name,code,indicator_type,data_domain_id,base_indicator_id,time_period,business_condition) VALUES(?,?,'DERIVED',?,?,'DAY','有效业务')",token(),token(),domain,atomic);
            var indicators=session.getMapper(IndicatorModelMapper.class);var counts=indicators.selectDerivedReferenceCounts(List.of(atomic));
            assertEquals(1,counts.size());assertEquals(1L,counts.getFirst().getTotal());
            var filter=new IndicatorModelPageReqVO();filter.setBaseIndicatorId(atomic);filter.setPageNo(1);filter.setPageSize(10);
            assertEquals(1,indicators.selectPage(filter).getList().size());filter.setSourceModelId(source[0]);assertEquals(1,indicators.selectPage(filter).getList().size());
            filter.setSourceModelId(-1L);assertTrue(indicators.selectPage(filter).getList().isEmpty());
        }
    }

    @Test
    void concurrentConditionCreationAndSourceDisableCannotLeaveBrokenReference() throws Exception {
        long domain=domain();long[] source=source(domain);
        try(var first=DriverManager.getConnection(url+"?currentSchema=datahub-center",user,"")) {
            first.setAutoCommit(false);
            try(var statement=first.prepareStatement("INSERT INTO dh_modeling_business_condition(code,name,data_domain_id,source_model_id,source_version_id,expression) VALUES(?,?,?,?,?,'status=1')")) {
                statement.setString(1,token());statement.setString(2,"并发限定");statement.setLong(3,domain);statement.setLong(4,source[0]);statement.setLong(5,source[1]);statement.executeUpdate();
            }
            try(var executor=Executors.newSingleThreadExecutor()) {
                var started=new CountDownLatch(1);
                var backendPid=new AtomicInteger();
                Future<Boolean> disabled=executor.submit(()->{
                    try(var c=DriverManager.getConnection(url+"?currentSchema=datahub-center",user,"");var s=c.createStatement()) {
                        s.execute("SET statement_timeout='10s'");
                        try(var pid=s.executeQuery("SELECT pg_backend_pid()")){pid.next();backendPid.set(pid.getInt(1));}
                        started.countDown();
                        try {s.executeUpdate("UPDATE dh_modeling_logic_model SET enabled=0 WHERE id="+source[0]);return true;}
                        catch(java.sql.SQLException failure){assertEquals("23514",failure.getSQLState());return false;}
                    }
                });
                assertTrue(started.await(5,TimeUnit.SECONDS));
                boolean waiting=false;
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(System.nanoTime()<deadline&&!disabled.isDone()) {
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock' AND wait_event='advisory')",Boolean.class,backendPid.get()));
                    if(waiting) break;
                    Thread.sleep(20);
                }
                assertTrue(waiting,"并发停用必须实际等待未提交限定持有的引用锁");
                first.commit();assertFalse(disabled.get(10,TimeUnit.SECONDS));
            }
        }
    }

    private long domain(){String t=token();return jdbc.queryForObject("INSERT INTO dh_modeling_data_domain(name,code,status) VALUES(?,?,0) RETURNING id",Long.class,t,t);}
    private long[] source(long domain){String t=token();long m=jdbc.queryForObject("INSERT INTO dh_modeling_logic_model(name,code,data_domain_id,status,enabled) VALUES(?,?,?,'PUBLISHED',1) RETURNING id",Long.class,t,t,domain);
        long v=jdbc.queryForObject("INSERT INTO dh_modeling_logic_model_version(model_id,version,status,fields) VALUES(?,1,'PUBLISHED','[{\"fieldUid\":\"amount\",\"fieldName\":\"amount\",\"dataType\":\"DECIMAL\"},{\"fieldUid\":\"time\",\"fieldName\":\"time\",\"dataType\":\"TIMESTAMP\"}]'::jsonb) RETURNING id",Long.class,m);return new long[]{m,v};}
    private long condition(long domain,long[] source,String code){return jdbc.queryForObject("INSERT INTO dh_modeling_business_condition(code,name,data_domain_id,source_model_id,source_version_id,expression) VALUES(?,?,?,?,?,'status=1') RETURNING id",Long.class,code,code,domain,source[0],source[1]);}
    private String token(){return "t"+UUID.randomUUID().toString().replace("-","");}
    @AfterAll
    void cleanup() throws Exception {
        if(database!=null&&database.matches("business_condition_[0-9a-f]{32}"))
            try(var c=DriverManager.getConnection(controlUrl,user,"");var s=c.createStatement()){s.execute("DROP DATABASE "+database);}
    }
}
