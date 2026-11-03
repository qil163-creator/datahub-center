package cn.iocoder.yudao.module.dataforge.service.database;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.datapermission.core.db.DataPermissionRuleHandler;
import cn.iocoder.yudao.framework.datapermission.core.rule.DataPermissionRuleFactoryImpl;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseMonitorVO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseAlarmRecordMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseAlarmRuleMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMonitorMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMonitorSampleMapper;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.incrementer.PostgreKeyGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATABASE_MONITOR_ALARM_CONFLICT;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATABASE_MONITOR_RULE_NOT_EXISTS;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATABASE_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real PostgreSQL, single-platform object scope and Spring transactions; no live platform or external source is used. */
@Testcontainers
@Timeout(90)
class DatabaseMonitorPostgreSqlTest {
    private static final String MIGRATION = "sql/postgresql/migrations/0156_dataforge_database-monitor.sql";
    private static final String SCHEMA_PATTERN = "monitor_test_[a-f0-9]{32}";
    private static final String BASELINE_MARKER = "-- Migration: 0156 ";
    private static final LocalDateTime REVISION = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final List<String> MONITOR_TABLES = List.of("dh_dataforge_database_sample",
            "dh_dataforge_database_alarm_rule", "dh_dataforge_database_alarm_record");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new cn.iocoder.yudao.module.dataforge.support.DataforgePostgreSqlContainer();

    private String schema;
    private String baseline;
    private String migration;
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private AnnotationConfigApplicationContext app;
    private DatabaseMapper databases;
    private DatabaseMonitorMapper monitor;
    private DatabaseAlarmRuleMapper rules;
    private DatabaseMonitorSampleMapper samples;
    private DatabaseAlarmRecordMapper alarms;
    private DatabaseMonitorService service;
    private DatabaseMonitorSampleService sampleService;
    private final AtomicInteger transactionalLocks = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        Path root = root();
        baseline = Files.readString(root.resolve("sql/postgresql/datahub-center.sql")).replace("\r\n", "\n");
        migration = cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.readStructure(root.resolve(MIGRATION)).replace("\r\n", "\n");
        schema = "monitor_test_" + UUID.randomUUID().toString().replace("-", "");
        admin = jdbc(source(null));
        admin.execute("CREATE SCHEMA " + schema);
        DataSource source = source(schema);
        db = jdbc(source);
        assertEquals(schema, db.queryForObject("SELECT current_schema()", String.class));
        createBaselineDependencies(db);
        migrate(schema, migration);
        runtime(source);
        identity( 99);
        seedDatabase(10);
        seedDatabase(20);
        seedDatabase(30);
    }

    private void createBaselineDependencies(JdbcTemplate target) {
        for (String table : List.of("dh_dataforge_database", "dh_dataforge_access_task", "dh_dataforge_dev_record")) {
            var ddl = Pattern.compile("CREATE TABLE \"datahub-center\"[.]\"" + table + "\" \\(.*?\\n\\)\\s*;", Pattern.DOTALL)
                    .matcher(baseline);
            assertTrue(ddl.find(), "Baseline table missing: " + table);
            target.execute(ddl.group().replace("\"datahub-center\".", "")
                    .replaceAll(" DEFAULT nextval\\([^\\n]*?::regclass\\)", ""));
            target.execute("ALTER TABLE " + table + " ADD PRIMARY KEY(id)");
        }
        // The frozen execution columns are part of the baseline's inline run-protocol upgrade.
        var runColumns = Pattern.compile("ALTER TABLE dh_dataforge_access_task\\s+ADD COLUMN IF NOT EXISTS protocol_version.*?;", Pattern.DOTALL)
                .matcher(baseline);
        assertTrue(runColumns.find(), "Baseline frozen execution configuration columns");
        target.execute(runColumns.group());
    }

    @AfterEach
    void tearDown() {
        clearIdentity();
        try {
            if (app != null) app.close();
        } finally {
            if (admin != null && schema != null && schema.matches(SCHEMA_PATTERN)) {
                admin.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void migrationReplaysWithoutChangingDataAndFreshBaselineHasMatchingTables() throws Exception {
        Long rule = service.createRule(rule(10));
        Long sample = monitor.claim( 10L, REVISION, REVISION, UUID.randomUUID().toString());
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(db, schema);
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(db, schema);
        assertEquals(rule, service.rule(10L, rule).getId());
        assertNotNull(samples.selectById(sample));
        String fresh = "monitor_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + fresh);
        try {
            createBaselineDependencies(jdbc(source(fresh)));
            int start = baseline.indexOf(BASELINE_MARKER);
            assertTrue(start >= 0, "Fresh baseline must contain the new monitoring table definitions");
            int end = baseline.indexOf("\nCOMMIT;", start);
            assertTrue(end > start, "Monitoring baseline increment must have its transaction end");
            migrate(fresh, baseline.substring(start, end + "\nCOMMIT;".length()));
            cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(jdbc(source(fresh)), fresh);
            assertEquals(structure(schema), structure(fresh));
            for (String table : MONITOR_TABLES) {
                assertEquals("smallint", db.queryForObject("SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema=? AND table_name=? AND column_name='deleted'", String.class, schema, table));
            }
        } finally {
            assertTrue(fresh.matches(SCHEMA_PATTERN));
            admin.execute("DROP SCHEMA " + fresh + " CASCADE");
        }
    }

    @Test
    void frozenSourceAndTargetBindingsKeepObjectScopeAndLosslessCounts() {
        LocalDateTime day = LocalDate.now().atStartOfDay();
        long large = 9_007_199_254_740_993L;
        seedAccess(101, "{\"sourceDatabaseId\":10,\"targetDatabaseId\":20}", large, 3, day, 0);
        seedAccess(102, "{\"sourceDatabaseId\":\"20\",\"targetDatabaseId\":\"10\"}", 7, 3, day, 0);
        seedAccess(103, "{\"sourceDatabaseId\":20,\"targetDatabaseId\":20}", 999, 3, day, 0);
        seedAccess(104, null, 999, 3, day, 0);
        seedAccess(105, "{\"sourceDatabaseId\":10,\"targetDatabaseId\":20}", 999, 3, day, 0);
        seedAccess(106, "{\"sourceDatabaseId\":10,\"targetDatabaseId\":20}", 999, 3, day, 1);
        seedAccess(107, "{\"sourceDatabaseId\":10,\"targetDatabaseId\":20}", 999, 4, day, 0);
        db.update("INSERT INTO dh_dataforge_dev_record(id,task_id,task_name,database_id,status,affected_rows,start_time,end_time,duration_ms) VALUES(201,800,'SQL summary',10,'SUCCESS',?, ?, ?,1000)", large, day.plusHours(1), day.plusHours(1).plusSeconds(1));
        db.update("INSERT INTO dh_dataforge_dev_record(id,task_id,database_id,status) VALUES(202,800,10,'SUCCESS')");

        var result = service.logs(page(10));
        assertEquals(6L, result.getTotal());
        var byId = result.getList().stream().collect(java.util.stream.Collectors.toMap(DatabaseMonitorVO.ReadWriteLog::getId, value -> value));
        assertEquals(Long.toString(large), byId.get("ACCESS:101").getOutputRows());
        assertNull(byId.get("ACCESS:101").getInputRows());
        assertEquals("7", byId.get("ACCESS:102").getInputRows());
        assertNull(byId.get("DEV:201").getInputRows());
        assertNull(byId.get("DEV:201").getOutputRows(), "SQL affected rows do not prove read/write direction");
        assertEquals(1000L, byId.get("ACCESS:101").getDurationMs());
        var summary = service.overview(10L);
        assertEquals(Long.toString(large + 999), summary.getTodayOutputRows());
        assertEquals("7", summary.getTodayInputRows());
        assertEquals("9007199254741999", summary.getTotalTransferRows());
        assertEquals(7.0, summary.getWriteSpeed());
        var today = service.trend(10L, 7).getPoints().getLast();
        assertEquals(Long.toString(large + 999), today.getOutputRows());
        assertEquals("7", today.getInputRows());
        assertEquals(7.0, today.getWriteSpeed());
        assertEquals("1006", service.overview(20L).getTodayOutputRows());
        identity( 98);
        assertEquals(6L, monitor.logCount(10L), "Changing the actor does not create a separate data namespace");
        assertEquals(6L, service.logs(page(10)).getTotal());
        assertThrows(ServiceException.class, () -> service.logs(page(999)), "Missing database IDs remain invalid");
        assertEquals(0L, service.logs(page(30)).getTotal());
    }

    @Test
    void concurrentMinuteClaimsHaveOneOwnerAndExpiredLeaseRejectsTheOldToken() throws Exception {
        var slot = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        String firstToken = UUID.randomUUID().toString();
        String secondToken = UUID.randomUUID().toString();
        List<Long> claims = concurrently(
                () -> monitor.claim( 10L, slot, slot, firstToken),
                () -> monitor.claim( 10L, slot, slot, secondToken));
        assertEquals(1L, claims.stream().filter(java.util.Objects::nonNull).count());
        Long id = claims.stream().filter(java.util.Objects::nonNull).findFirst().orElseThrow();
        String oldToken = db.queryForObject("SELECT lease_token FROM dh_dataforge_database_sample WHERE id=?", String.class, id);
        String replacement = UUID.randomUUID().toString();
        assertNull(monitor.claim( 10L, slot, slot.plusSeconds(30), replacement));
        assertEquals(id, monitor.claim( 10L, slot, slot.plusMinutes(3), replacement));
        assertEquals(0, completeMapper(10, id, oldToken, slot));
        assertEquals(0, completeMapper(20, id, replacement, slot));
        assertEquals(1, completeMapper(10, id, replacement, slot));
        assertEquals(0, completeMapper(10, id, replacement, slot));
        assertNull(monitor.claim( 10L, slot, slot.plusMinutes(10), UUID.randomUUID().toString()));
        assertNotNull(monitor.claim( 30L, slot, slot, UUID.randomUUID().toString()));
    }

    @Test
    void proxiedCompletionGeneratesOnePendingAlarmAndDoesNotReplayDuplicateOrOlderSamples() {
        Long rule = service.createRule(rule(10));
        LocalDateTime first = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).minusMinutes(1);
        String token = UUID.randomUUID().toString();
        Long firstId = monitor.claim( 10L, first, first, token);
        complete(firstId, token, first, 200L);
        complete(firstId, token, first, 200L);
        assertEquals(1L, service.alarms(page(10)).getTotal());
        assertEquals(1, service.alarms(page(10)).getList().getFirst().getOccurrences());
        String nextToken = UUID.randomUUID().toString();
        Long nextId = monitor.claim( 10L, first.plusMinutes(1), first.plusMinutes(1), nextToken);
        complete(nextId, nextToken, first.plusMinutes(1), 300L);
        var alarm = service.alarms(page(10)).getList().getFirst();
        assertEquals(rule, alarm.getRuleId());
        assertEquals(2, alarm.getOccurrences());
        assertEquals("300", alarm.getCurrentValue());
        assertEquals("Fixture response limit", alarm.getRuleName());
        String lateToken = UUID.randomUUID().toString();
        Long lateId = monitor.claim( 10L, first.minusMinutes(1), first.minusMinutes(1), lateToken);
        complete(lateId, lateToken, first.minusMinutes(1), 900L);
        assertEquals(2, service.alarms(page(10)).getList().getFirst().getOccurrences());
        assertEquals(300L, service.overview(10L).getResponseTimeMs());
        assertEquals("9007199254740993", service.overview(10L).getCapacityUsed());
        assertTrue(transactionalLocks.get() >= 5, "Every service lock must execute inside the real transaction proxy");
    }

    @Test
    void alarmWriteFailureRollsBackSamplePublicationAndChangedDatabaseRevisionDiscardsIt() {
        service.createRule(rule(10));
        var slot = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        String token = UUID.randomUUID().toString();
        Long id = monitor.claim( 10L, slot, slot, token);
        db.execute("CREATE FUNCTION reject_monitor_alarm() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "RAISE EXCEPTION 'injected alarm write failure'; END $$");
        db.execute("CREATE TRIGGER reject_monitor_alarm BEFORE INSERT ON dh_dataforge_database_alarm_record "
                + "FOR EACH ROW EXECUTE FUNCTION reject_monitor_alarm()");
        assertThrows(RuntimeException.class, () -> complete(id, token, slot, 200L));
        assertEquals("COLLECTING", samples.selectById(id).getState(), "Alarm failure must roll back the completed sample too");
        assertEquals(0L, service.alarms(page(10)).getTotal());
        db.execute("DROP TRIGGER reject_monitor_alarm ON dh_dataforge_database_alarm_record");
        db.update("UPDATE dh_dataforge_database SET update_time=? WHERE id=10", REVISION.plusSeconds(1));
        complete(id, token, slot, 200L);
        assertEquals("DISCARDED", samples.selectById(id).getState());
        assertEquals(0L, service.alarms(page(10)).getTotal());
    }

    @Test
    void unsupportedCollectionStaysUnknownAndOnlyProvenOfflineSamplesTriggerConnectionAlarms() {
        var connectionRule = rule(10);
        connectionRule.setMetric("CONNECTION_STATUS");
        connectionRule.setOperator("EQ");
        connectionRule.setThreshold("0");
        service.createRule(connectionRule);
        var slot = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        String unsupportedToken = UUID.randomUUID().toString();
        Long unsupported = monitor.claim( 10L, slot, slot, unsupportedToken);
        sampleService.complete(10L, unsupported, unsupportedToken, REVISION, slot, 0, null, null,
                "UNSUPPORTED", "Fixture unsupported probe");
        var unknown = service.overview(10L);
        assertEquals(0, unknown.getConnectionStatus());
        assertEquals("UNSUPPORTED", unknown.getCollectionStatus());
        assertNull(unknown.getResponseTimeMs());
        assertNull(unknown.getAverageResponseTimeMs());
        assertNull(service.trend(10L, 7).getPoints().getLast().getOnlineRate());
        assertEquals(0L, service.alarms(page(10)).getTotal());
        String offlineToken = UUID.randomUUID().toString();
        Long offline = monitor.claim( 10L, slot.plusMinutes(1), slot.plusMinutes(1), offlineToken);
        sampleService.complete(10L, offline, offlineToken, REVISION, slot.plusMinutes(1), 2, null, null,
                "FAILED", "Fixture connection refused");
        assertEquals(1L, service.alarms(page(10)).getTotal());
        assertEquals("0", service.alarms(page(10)).getList().getFirst().getCurrentValue());
        assertEquals(2, service.overview(10L).getConnectionStatus());
    }

    @Test
    void concurrentDispositionHasOneWinnerAndDatabaseGuardsRejectForeignObjects() throws Exception {
        Long rule = service.createRule(rule(10));
        var slot = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        String token = UUID.randomUUID().toString();
        Long sample = monitor.claim( 10L, slot, slot, token);
        complete(sample, token, slot, 200L);
        Long alarm = service.alarms(page(10)).getList().getFirst().getId();
        var outcomes = concurrently(() -> handle(alarm, "HANDLE", 101), () -> handle(alarm, "IGNORE", 102));
        assertEquals(1L, outcomes.stream().filter(code -> code == 0).count());
        assertEquals(1L, outcomes.stream().filter(code -> code.equals(DATABASE_MONITOR_ALARM_CONFLICT.getCode())).count());
        var persisted = service.alarm(10L, alarm);
        assertTrue(List.of("HANDLED", "IGNORED").contains(persisted.getStatus()));
        assertTrue(List.of("101", "102").contains(persisted.getHandledBy()));
        assertNotNull(persisted.getHandledTime());
        assertEquals("Verified disposition", persisted.getHandleRemark());
        assertEquals(0L, service.overview(10L).getPendingAlarmCount());

        identity( 98);
        assertNotNull(databases.selectById(10L));
        assertNotNull(rules.selectById(rule));
        assertNotNull(alarms.selectById(alarm));
        assertNotNull(samples.selectById(sample));
        assertEquals(alarm, service.alarm(10L, alarm).getId());
        assertThrows(ServiceException.class, () -> service.alarm(20L, alarm));
        var wrongDatabase = handleRequest(alarm, "HANDLE");
        wrongDatabase.setDatabaseId(20L);
        assertThrows(ServiceException.class, () -> service.handleAlarm(wrongDatabase));
        assertEquals(DATABASE_MONITOR_RULE_NOT_EXISTS.getCode(), assertThrows(ServiceException.class,
                () -> service.rule(30L, rule)).getCode());
        sampleService.complete(10L, sample, token, REVISION, slot, 1, 999L, null, "SUCCESS", "Fixture sample");
        identity( 99);
        assertEquals(200L, samples.selectById(sample).getResponseTimeMs());
        assertEquals(persisted.getHandledBy(), service.alarm(10L, alarm).getHandledBy());
    }

    private void runtime(DataSource source) throws Exception {
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setDefaultStatementTimeout(10);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
        configuration.setEnvironment(new Environment("database-monitor-test", new SpringManagedTransactionFactory(), source));
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(source);
        var global = GlobalConfigUtils.getGlobalConfig(configuration);
        global.setMetaObjectHandler(new DefaultDBFieldHandler());
        global.getDbConfig().setIdType(IdType.INPUT).setLogicDeleteValue("1").setLogicNotDeleteValue("0")
                .setKeyGenerators(List.of(new PostgreKeyGenerator()));
        var interceptors = new MybatisPlusInterceptor();
        // 与实际应用一致：空数据权限规则也会解析 SQL，须覆盖 INSERT RETURNING 的采样认领。
        interceptors.addInnerInterceptor(new DataPermissionInterceptor(
                new DataPermissionRuleHandler(new DataPermissionRuleFactoryImpl(List.of()))));
        interceptors.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        configuration.addInterceptor(interceptors);
        for (Class<?> mapper : List.of(DatabaseMapper.class, DatabaseMonitorMapper.class, DatabaseMonitorSampleMapper.class,
                DatabaseAlarmRuleMapper.class, DatabaseAlarmRecordMapper.class)) configuration.addMapper(mapper);
        String resource = "mapper/database/DatabaseMonitorMapper.xml";
        try (var xml = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(xml, "Production monitoring mapper XML");
            new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
        }
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        databases = sessions.getMapper(DatabaseMapper.class);
        var delegate = sessions.getMapper(DatabaseMonitorMapper.class);
        monitor = (DatabaseMonitorMapper) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DatabaseMonitorMapper.class},
                (_, method, arguments) -> {
                    if (method.getName().equals("lockDatabase")) {
                        assertTrue(TransactionSynchronizationManager.isActualTransactionActive(), "Row locks require a real Spring transaction");
                        transactionalLocks.incrementAndGet();
                    }
                    try { return method.invoke(delegate, arguments); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
        samples = sessions.getMapper(DatabaseMonitorSampleMapper.class);
        rules = sessions.getMapper(DatabaseAlarmRuleMapper.class);
        alarms = sessions.getMapper(DatabaseAlarmRecordMapper.class);
        app = new AnnotationConfigApplicationContext();
        app.register(Transactions.class);
        app.registerBean(DataSource.class, () -> source);
        app.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        app.registerBean(DatabaseMapper.class, () -> databases);
        app.registerBean(DatabaseMonitorMapper.class, () -> monitor);
        app.registerBean(DatabaseMonitorSampleMapper.class, () -> samples);
        app.registerBean(DatabaseAlarmRuleMapper.class, () -> rules);
        app.registerBean(DatabaseAlarmRecordMapper.class, () -> alarms);
        app.getBeanFactory().registerSingleton("accessDatabaseResolver", mock(AccessDatabaseResolver.class));
        app.getBeanFactory().registerSingleton("dbSwitchRuntime", mock(DbSwitchRuntime.class));
        app.registerBean(DatabaseMonitorSampleService.class);
        app.registerBean(DatabaseMonitorService.class);
        app.refresh();
        sampleService = app.getBean(DatabaseMonitorSampleService.class);
        service = app.getBean(DatabaseMonitorService.class);
        assertTrue(AopUtils.isAopProxy(sampleService));
        assertTrue(AopUtils.isAopProxy(service));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions { }

    private void seedDatabase(long id) {
        db.update("INSERT INTO dh_dataforge_database(id,name,database_type,database_ip,database_port,database_name,update_time) VALUES(?,'Fixture database',1,'fixture.invalid','1','fixture',?)", id, REVISION);
    }

    private void seedAccess(long id, String snapshot, long rows, int status, LocalDateTime day, int deleted) {
        db.update("INSERT INTO dh_dataforge_access_task(id,data_access_id,task_name,config_json,data_count,run_status,start_time,end_time,deleted) VALUES(?,700,'Frozen sync',CAST(? AS jsonb),?,?,?, ?,?)",
                id, snapshot, rows, status, day.plusHours(1), day.plusHours(1).plusSeconds(1), deleted);
    }

    private int completeMapper(long database, long id, String token, LocalDateTime collected) {
        return monitor.complete(database, id, token, collected, 1, 15L, "10", "SUCCESS", "Fixture sample");
    }

    private void complete(Long id, String token, LocalDateTime collected, Long response) {
        sampleService.complete(10L, id, token, REVISION, collected, 1, response, "9007199254740993", "SUCCESS", "Fixture sample");
    }

    private int handle(Long alarm, String action, long actor) {
        identity( actor);
        try { service.handleAlarm(handleRequest(alarm, action)); return 0; }
        catch (ServiceException conflict) { return conflict.getCode(); }
        finally { clearIdentity(); }
    }

    private static DatabaseMonitorVO.AlarmHandle handleRequest(Long alarm, String action) {
        var request = new DatabaseMonitorVO.AlarmHandle();
        request.setDatabaseId(10L); request.setId(alarm); request.setAction(action); request.setRemark("Verified disposition");
        return request;
    }

    private static DatabaseMonitorVO.RuleSave rule(long database) {
        var result = new DatabaseMonitorVO.RuleSave();
        result.setDatabaseId(database); result.setName("Fixture response limit"); result.setDescription("Fixture description");
        result.setMetric("RESPONSE_TIME"); result.setOperator("GT"); result.setThreshold("100");
        result.setLevel("SERIOUS"); result.setStatus(0); result.setOwner("Fixture owner");
        return result;
    }

    private static DatabaseMonitorVO.PageReq page(long database) {
        var request = new DatabaseMonitorVO.PageReq();
        request.setDatabaseId(database); request.setPageNo(1); request.setPageSize(100);
        return request;
    }

    private <T> List<T> concurrently(Callable<T> first, Callable<T> second) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<java.util.concurrent.Future<T>>();
            for (var action : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    identity( 99); ready.countDown();
                    try { assertTrue(start.await(10, TimeUnit.SECONDS)); return action.call(); }
                    finally { clearIdentity(); }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            var result = new ArrayList<T>();
            for (var future : futures) result.add(future.get(20, TimeUnit.SECONDS));
            return result;
        }
    }

    private List<String> structure(String name) {
        var result = new ArrayList<String>();
        for (String table : MONITOR_TABLES) {
            List<Map<String, Object>> columns = admin.queryForList("SELECT column_name,udt_name,is_nullable,column_default "
                    + "FROM information_schema.columns WHERE table_schema=? AND table_name=? ORDER BY ordinal_position", name, table);
            result.add(table + columns.toString().replace(name, "SCHEMA"));
            result.addAll(admin.queryForList("SELECT c.conname || ':' || pg_get_constraintdef(c.oid) FROM pg_constraint c "
                    + "JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace "
                    + "WHERE n.nspname=? AND t.relname=? ORDER BY c.conname", String.class, name, table));
            result.addAll(admin.queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname=? AND tablename=? ORDER BY indexname", String.class, name, table)
                    .stream().map(value -> value.replace(name, "SCHEMA")).toList());
        }
        return result;
    }

    private void migrate(String name, String sql) {
        assertTrue(name.matches(SCHEMA_PATTERN));
        admin.execute(sql.replace("\"datahub-center\"", name).replace("'datahub-center'", "'" + name + "'"));
    }

    private DriverManagerDataSource source(String name) {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var properties = new Properties();
        properties.setProperty("connectTimeout", "5"); properties.setProperty("socketTimeout", "15");
        properties.setProperty("options", "-c statement_timeout=10000 -c lock_timeout=5000");
        if (name != null) properties.setProperty("currentSchema", name);
        source.setConnectionProperties(properties);
        return source;
    }

    private static JdbcTemplate jdbc(DataSource source) {
        var result = new JdbcTemplate(source); result.setQueryTimeout(12); return result;
    }

    private static Path root() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        assertNotNull(root, "Repository root containing monitoring migration");
        return root;
    }

    private static void identity( long actor) {

        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(new LoginUser().setId(actor), null));
    }

    private static void clearIdentity() {
        SecurityContextHolder.clearContext();
    }
}
