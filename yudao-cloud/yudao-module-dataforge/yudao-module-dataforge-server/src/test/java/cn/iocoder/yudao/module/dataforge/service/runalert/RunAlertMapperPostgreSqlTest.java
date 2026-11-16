package cn.iocoder.yudao.module.dataforge.service.runalert;

import cn.iocoder.yudao.framework.datapermission.core.db.DataPermissionRuleHandler;
import cn.iocoder.yudao.framework.datapermission.core.rule.DataPermissionRuleFactoryImpl;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.runalert.RunAlertCandidate;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.runalert.RunAlertDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.runalert.RunAlertMapper;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.incrementer.PostgreKeyGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 迁移 0292 与失败通知 SQL 在真实 PostgreSQL 上的行为：迁移可重复执行、扫描范围与去重、租约认领与状态迁移、约束与清理。
 *
 * <p>运行表只建扫描用到的列，结构与基线中同名列一致；MyBatis 配置与应用一致地挂载数据权限拦截器，确保语句能被解析。</p>
 */
@Testcontainers
@Timeout(120)
class RunAlertMapperPostgreSqlTest {

    private static final String MIGRATION = "sql/postgresql/migrations/0292_dataforge_run-failure-alert.sql";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new cn.iocoder.yudao.module.dataforge.support.DataforgePostgreSqlContainer();

    private JdbcTemplate db;
    private SqlSession session;
    private RunAlertMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        var admin = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        admin.execute("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE");
        admin.execute("CREATE SCHEMA \"datahub-center\"");
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=datahub-center";
        var source = new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        db = new JdbcTemplate(source);
        assertEquals("datahub-center", db.queryForObject("SELECT current_schema()", String.class));
        createDependencies();

        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setDefaultStatementTimeout(10);
        configuration.setEnvironment(new Environment("run-alert-test", new JdbcTransactionFactory(), source));
        var global = GlobalConfigUtils.getGlobalConfig(configuration);
        global.setMetaObjectHandler(new DefaultDBFieldHandler());
        global.getDbConfig().setIdType(IdType.INPUT).setLogicDeleteValue("1").setLogicNotDeleteValue("0")
                .setKeyGenerators(List.of(new PostgreKeyGenerator()));
        var interceptors = new MybatisPlusInterceptor();
        interceptors.addInnerInterceptor(new DataPermissionInterceptor(
                new DataPermissionRuleHandler(new DataPermissionRuleFactoryImpl(List.of()))));
        interceptors.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        configuration.addInterceptor(interceptors);
        configuration.addMapper(RunAlertMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true);
        mapper = session.getMapper(RunAlertMapper.class);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void migrationIsRepeatableAndAddsTemplatesOnceChannelsExist() throws Exception {
        migrate();
        assertEquals(1, count("system_notify_template WHERE code='dataforge_run_failed' AND status=0"));
        assertEquals(0, count("system_mail_template WHERE code='dataforge_run_failed_mail'"));

        db.update("INSERT INTO system_mail_account(id) VALUES (7)");
        db.update("INSERT INTO system_sms_channel(id, code) VALUES (3, 'ALIYUN')");
        migrate();
        assertEquals(1, count("system_notify_template WHERE code='dataforge_run_failed'"));
        assertEquals(1, count("system_mail_template WHERE code='dataforge_run_failed_mail' AND status=1 AND account_id=7"));
        assertEquals(1, count("system_sms_template WHERE code='dataforge_run_failed_sms' AND status=1 AND channel_code='ALIYUN'"));
        assertEquals(5, count("pg_indexes WHERE schemaname='datahub-center' AND indexname LIKE 'idx_%_run_alert'"));

        db.update("UPDATE system_notify_template SET content='被改过' WHERE code='dataforge_run_failed'");
        assertThrows(Exception.class, this::migrate, "内容不一致时必须中止而不是静默覆盖");
    }

    @Test
    void scanCoversFivePlatformKindsWithinWindowAndSkipsRegisteredOrImportedRuns() throws Exception {
        migrate();
        LocalDateTime now = LocalDateTime.of(2026, 11, 15, 10, 0);
        seedRuns(now);

        List<RunAlertCandidate> runs = mapper.selectFailedRuns(now.minusHours(24), 100);
        assertEquals(List.of("DEVELOPMENT", "DATA_ACCESS", "ETL", "ETL", "INTEGRATION", "JOB"),
                runs.stream().map(RunAlertCandidate::getSourceKind).toList());
        RunAlertCandidate dev = runs.getFirst();
        assertEquals(101L, dev.getRunId());
        assertEquals(11L, dev.getOwnerId());
        assertEquals(12L, dev.getExecutionUserId());
        assertEquals(13L, dev.getCreatorId());
        assertEquals("日汇总-101", dev.getRunName());
        assertNull(runs.get(1).getCreatorId(), "非数字 creator 不作为接收人");
        assertEquals(21L, runs.get(1).getReleaseUserId());
        assertEquals("客户主题集成", runs.get(2).getIntegrationName());
        assertEquals(41L, runs.get(2).getOwnerId());
        assertNull(runs.get(3).getIntegrationName());
        assertEquals(42L, runs.get(4).getExecutionUserId(), "受管集成实例以创建人作为执行身份");
        assertEquals(51L, runs.get(5).getOwnerId());

        assertEquals(1, mapper.selectFailedRuns(now.minusHours(24), 1).size());
        assertEquals(0, mapper.selectFailedRuns(now.plusHours(1), 100).size());

        RunAlertDO alert = alert("DEVELOPMENT", 101L, 11L, RunAlertDO.CHANNEL_NOTIFY);
        assertEquals(1, mapper.insertIgnore(alert));
        assertEquals(0, mapper.insertIgnore(alert), "同一运行、渠道、接收人只登记一次");
        assertEquals(5, mapper.selectFailedRuns(now.minusHours(24), 100).size());
    }

    @Test
    void leaseClaimAndStateTransitionsUseDatabaseClock() throws Exception {
        migrate();
        mapper.insertIgnore(alert("JOB", 1L, 5L, RunAlertDO.CHANNEL_NOTIFY));
        mapper.insertIgnore(alert("JOB", 1L, 5L, RunAlertDO.CHANNEL_SMS));
        long notify = id(RunAlertDO.CHANNEL_NOTIFY);
        long sms = id(RunAlertDO.CHANNEL_SMS);
        assertEquals(2, mapper.selectDue(10).size());

        assertEquals(1, mapper.claim(notify, "a", 120, 5));
        assertEquals(0, mapper.claim(notify, "b", 120, 5), "有效租约不可被接管");
        assertEquals(0, mapper.delivered(notify, "b", 9L), "令牌不符不能记回执");
        RunAlertDO claimed = mapper.selectById(notify);
        assertEquals(RunAlertDO.STATUS_SENDING, claimed.getStatus());
        assertTrue(claimed.getTemplateParams().contains("kindName"));

        db.update("UPDATE dh_dataforge_run_alert SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", notify);
        assertEquals(1, mapper.claim(notify, "b", 120, 5), "站内信过期租约可接管");
        assertEquals(1, mapper.delivered(notify, "b", 901L));
        assertEquals(RunAlertDO.STATUS_SENT, mapper.selectById(notify).getStatus());

        assertEquals(1, mapper.claim(sms, "s", 120, 5));
        db.update("UPDATE dh_dataforge_run_alert SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", sms);
        assertEquals(0, mapper.claim(sms, "t", 120, 5), "短信过期租约不可接管");
        assertEquals(1, mapper.expireUnconfirmed(sms));
        assertEquals(RunAlertDO.STATUS_UNKNOWN, mapper.selectById(sms).getStatus());
        assertEquals(1, mapper.countAttention());

        assertEquals(1, mapper.dispose(sms, "RETRY", "确认未收到", "xxl-job:1:2"));
        RunAlertDO retried = mapper.selectById(sms);
        assertEquals(RunAlertDO.STATUS_PENDING, retried.getStatus());
        assertEquals(0, retried.getAttempts());
        assertEquals("xxl-job:1:2", retried.getDispositionCommand());
    }

    @Test
    void retryBackoffExhaustionDeadLetterAndCleanup() throws Exception {
        migrate();
        mapper.insertIgnore(alert("ETL", 2L, 6L, RunAlertDO.CHANNEL_NOTIFY));
        long id = id(RunAlertDO.CHANNEL_NOTIFY);

        assertEquals(1, mapper.claim(id, "a", 120, 2));
        assertEquals(1, mapper.retryLater(id, "a", "REMOTE_REJECTED", 2, 60));
        RunAlertDO waiting = mapper.selectById(id);
        assertEquals(RunAlertDO.STATUS_PENDING, waiting.getStatus());
        assertEquals("REMOTE_REJECTED", waiting.getLastError());
        assertTrue(mapper.selectDue(10).isEmpty(), "退避期间不可认领");

        db.update("UPDATE dh_dataforge_run_alert SET available_at=clock_timestamp()-interval '1 second' WHERE id=?", id);
        assertEquals(1, mapper.claim(id, "b", 120, 2));
        assertEquals(1, mapper.retryLater(id, "b", "REMOTE_REJECTED", 2, 60));
        assertEquals(RunAlertDO.STATUS_DEAD, mapper.selectById(id).getStatus(), "次数用尽转死信");

        mapper.insertIgnore(alert("ETL", 3L, 6L, RunAlertDO.CHANNEL_NOTIFY));
        long exhausted = db.queryForObject("SELECT id FROM dh_dataforge_run_alert WHERE run_id=3", Long.class);
        db.update("UPDATE dh_dataforge_run_alert SET attempts=5 WHERE id=?", exhausted);
        assertEquals(0, mapper.claim(exhausted, "c", 120, 5));
        assertEquals(1, mapper.exhaust(exhausted, 5));

        assertEquals(1, mapper.dispose(id, "ACKNOWLEDGE", "已电话通知", "xxl-job:1:3"));
        assertEquals(RunAlertDO.STATUS_ACKNOWLEDGED, mapper.selectById(id).getStatus());
        assertEquals(0, mapper.deleteHandled(30, 100));
        db.update("UPDATE dh_dataforge_run_alert SET handled_at=clock_timestamp()-interval '31 days' WHERE id=?", id);
        assertEquals(1, mapper.deleteHandled(30, 100));
        assertNull(mapper.selectById(id));
        assertNotNull(mapper.selectById(exhausted), "死信保留到人工处置");
    }

    @Test
    void constraintsRejectPendingRowsWithoutReceiverAndRetryOfThem() throws Exception {
        migrate();
        RunAlertDO orphan = alert("DATA_ACCESS", 4L, 0L, RunAlertDO.CHANNEL_NOTIFY);
        assertThrows(Exception.class, () -> mapper.insertIgnore(orphan));
        orphan.setStatus(RunAlertDO.STATUS_DEAD);
        orphan.setLastError("NO_RECEIVER");
        assertEquals(1, mapper.insertIgnore(orphan));
        long id = db.queryForObject("SELECT id FROM dh_dataforge_run_alert WHERE run_id=4", Long.class);
        assertEquals(0, mapper.dispose(id, "RETRY", "重发", "xxl-job:1:4"));
        assertThrows(DataIntegrityViolationException.class, () -> db.update(
                "INSERT INTO dh_dataforge_run_alert(source_kind,run_id,channel,receiver_user_id) VALUES('OTHER',1,1,1)"));
    }

    private RunAlertDO alert(String kind, Long runId, Long receiver, int channel) {
        return RunAlertDO.builder().sourceKind(kind).runId(runId).taskId(1L).taskName("任务").runName("实例")
                .eventType("FAILURE").occurredTime(LocalDateTime.of(2026, 11, 15, 9, 0)).receiverUserId(receiver)
                .channel(channel).templateParams("{\"kindName\":\"作业编排\"}").status(RunAlertDO.STATUS_PENDING).build();
    }

    private long id(int channel) {
        return db.queryForObject("SELECT id FROM dh_dataforge_run_alert WHERE channel=? ORDER BY id LIMIT 1", Long.class, channel);
    }

    private int count(String fromWhere) {
        return db.queryForObject("SELECT count(*) FROM " + fromWhere, Integer.class);
    }

    private void migrate() throws Exception {
        db.execute(Files.readString(root().resolve(MIGRATION)));
    }

    private void seedRuns(LocalDateTime now) {
        LocalDateTime failed = now.minusHours(1);
        LocalDateTime old = now.minusHours(30);
        db.update("INSERT INTO dh_dataforge_dev_task(id,name,owner_id,creator) VALUES (1,'日汇总',11,'13')");
        db.update("INSERT INTO dh_dataforge_dev_record(id,task_id,task_name,status,end_time,update_time,execution_user_id) VALUES "
                + "(101,1,'日汇总','FAILED',?,?,12),(102,1,'日汇总','SUCCESS',?,?,12),(103,1,'日汇总','FAILED',?,?,12)",
                failed, failed, failed, failed, old, old);
        db.update("INSERT INTO dh_dataforge_access(id,task_name,release_user_id,creator) VALUES (2,'订单同步',21,'admin')");
        db.update("INSERT INTO dh_dataforge_access_task(id,data_access_id,task_name,run_status,end_time,update_time,execution_user_id) "
                + "VALUES (201,2,'订单同步',4,NULL,?,22),(202,2,'订单同步',5,?,?,22)", failed.plusMinutes(1), failed, failed);
        db.update("INSERT INTO dh_dataforge_etl_pipeline(id,name,release_user_id,creator) VALUES (3,'客户清洗',31,'32'),(4,'独立ETL',33,'')");
        db.update("INSERT INTO dh_dataforge_integration_task(id,name,owner_id,pipeline_id,creator) VALUES "
                + "(40,'客户主题集成',41,3,'41'),(43,'受管集成',44,NULL,'45')");
        db.update("INSERT INTO dh_dataforge_etl_record(id,pipeline_id,name,run_status,end_time,update_time,execution_user_id) VALUES "
                + "(301,3,'客户清洗-1',4,?,?,34),(302,4,'独立ETL-1',4,?,?,35)",
                failed.plusMinutes(2), failed, failed.plusMinutes(3), failed);
        db.update("INSERT INTO dh_dataforge_integration_record(id,task_id,instance_name,status,end_time,update_time,ds_project_code,creator) "
                + "VALUES (401,43,'受管集成-1','FAILURE',?,?,9001,'42'),(402,43,'迁入快照','FAILURE',?,?,NULL,'42')",
                failed.plusMinutes(4), failed, failed.plusMinutes(4), failed);
        db.update("INSERT INTO dh_dataforge_job(id,name,owner_id,creator) VALUES (5,'夜间作业',51,'52')");
        db.update("INSERT INTO dh_dataforge_job_record(id,job_id,origin,instance_name,status,end_time,update_time,execution_user_id) "
                + "VALUES (501,5,'PLATFORM','夜间作业-1','FAILURE',?,?,53),(502,5,'SOURCE','迁入','FAILURE',?,?,53)",
                failed.plusMinutes(5), failed, failed.plusMinutes(5), failed);
    }

    /** 迁移依赖的 system 模板表与五类运行表（只含扫描用到的列，类型与基线一致） */
    private void createDependencies() {
        db.execute("""
                CREATE SEQUENCE system_notify_template_seq START WITH 100;
                CREATE TABLE system_notify_template(id int8 PRIMARY KEY, name varchar(63) NOT NULL, code varchar(64) NOT NULL,
                  nickname varchar(255) NOT NULL, content varchar(1024) NOT NULL, type int2 NOT NULL, params varchar(255),
                  status int2 NOT NULL, remark varchar(255), creator varchar(64) DEFAULT '', create_time timestamp NOT NULL DEFAULT now(),
                  updater varchar(64) DEFAULT '', update_time timestamp NOT NULL DEFAULT now(), deleted int2 NOT NULL DEFAULT 0);
                CREATE TABLE system_mail_account(id int8 PRIMARY KEY, deleted int2 NOT NULL DEFAULT 0);
                CREATE SEQUENCE system_mail_template_seq;
                CREATE TABLE system_mail_template(id int8 PRIMARY KEY, name varchar(63), code varchar(63), account_id int8, nickname varchar(255),
                  title varchar(255), content varchar(10240), params varchar(255), status int2, remark varchar(255), creator varchar(64),
                  create_time timestamp, updater varchar(64), update_time timestamp, deleted int2 NOT NULL DEFAULT 0);
                CREATE TABLE system_sms_channel(id int8 PRIMARY KEY, code varchar(63), deleted int2 NOT NULL DEFAULT 0);
                CREATE SEQUENCE system_sms_template_seq;
                CREATE TABLE system_sms_template(id int8 PRIMARY KEY, type int2, status int2, code varchar(63), name varchar(63),
                  content varchar(255), params varchar(255), remark varchar(255), api_template_id varchar(63), channel_id int8,
                  channel_code varchar(63), creator varchar(64), create_time timestamp, updater varchar(64), update_time timestamp,
                  deleted int2 NOT NULL DEFAULT 0);
                CREATE TABLE dh_dataforge_dev_task(id int8 PRIMARY KEY, name varchar(100) NOT NULL, owner_id int8,
                  creator varchar(64) DEFAULT '', deleted int2 NOT NULL DEFAULT 0);
                CREATE TABLE dh_dataforge_dev_record(id int8 PRIMARY KEY, task_id int8, task_name varchar(100), status varchar(32),
                  end_time timestamp(6), update_time timestamp(6) NOT NULL DEFAULT now(), execution_user_id bigint, deleted int2 NOT NULL DEFAULT 0);
                CREATE TABLE dh_dataforge_access(id int8 PRIMARY KEY, task_name varchar(255), release_user_id int8,
                  creator varchar(64) DEFAULT '', deleted int2 NOT NULL DEFAULT 0);
                CREATE TABLE dh_dataforge_access_task(id int8 PRIMARY KEY, data_access_id int8, task_name varchar(255), run_status int2,
                  end_time timestamp(6), update_time timestamp(6) NOT NULL DEFAULT now(), execution_user_id bigint, deleted int2 NOT NULL DEFAULT 0);
                CREATE TABLE dh_dataforge_etl_pipeline(id int8 PRIMARY KEY, name varchar(50) NOT NULL, release_user_id int8,
                  creator varchar(64) DEFAULT '', deleted int2 NOT NULL DEFAULT 0);
                CREATE TABLE dh_dataforge_etl_record(id int8 PRIMARY KEY, pipeline_id int8, name varchar(255), run_status int2,
                  end_time timestamp(6), update_time timestamp(6) NOT NULL DEFAULT now(), execution_user_id bigint, deleted int2 NOT NULL DEFAULT 0);
                CREATE TABLE dh_dataforge_integration_task(id bigint PRIMARY KEY, name varchar(200) NOT NULL, owner_id bigint,
                  pipeline_id bigint, creator varchar(64) NOT NULL DEFAULT '', deleted smallint NOT NULL DEFAULT 0);
                CREATE TABLE dh_dataforge_integration_record(id bigint PRIMARY KEY, task_id bigint NOT NULL, instance_name varchar(300),
                  status varchar(16) NOT NULL, end_time timestamp, update_time timestamp NOT NULL DEFAULT current_timestamp,
                  ds_project_code bigint, creator varchar(64) NOT NULL DEFAULT '', deleted smallint NOT NULL DEFAULT 0);
                CREATE TABLE dh_dataforge_job(id bigint PRIMARY KEY, name varchar(200) NOT NULL, owner_id bigint,
                  creator varchar(64) NOT NULL DEFAULT '', deleted smallint NOT NULL DEFAULT 0);
                CREATE TABLE dh_dataforge_job_record(id bigint PRIMARY KEY, job_id bigint NOT NULL, origin varchar(16) NOT NULL DEFAULT 'PLATFORM',
                  instance_name varchar(512) NOT NULL, status varchar(16) NOT NULL, end_time timestamp, update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  execution_user_id bigint, deleted smallint NOT NULL DEFAULT 0);
                """);
    }

    private static Path root() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) {
            root = root.getParent();
        }
        assertNotNull(root, "仓库根目录");
        return root;
    }

}
