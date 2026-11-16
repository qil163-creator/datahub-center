package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityAuditLogDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityAuditLogMapper;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.incrementer.PostgreKeyGenerator;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
 * 迁移 0293 与表行数波动历史基准查询在真实 PostgreSQL 上的行为：环比取最近一次、同比取窗口内最接近参考点的一次，
 * 按任务、检查对象、评估/规则、指标键与口径隔离，排除执行出错与已删除的结果；迁移可重复执行。
 */
@Testcontainers
@Timeout(120)
class QualityMetricBaselinePostgreSqlTest {

    private static final String MIGRATION = "sql/postgresql/migrations/0293_governance_quality-row-count-fluctuation.sql";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 2, 0);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate db;
    private SqlSession session;
    private QualityAuditLogMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        var admin = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        admin.execute("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE");
        admin.execute("CREATE SCHEMA \"datahub-center\"");
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=datahub-center",
                POSTGRES.getUsername(), POSTGRES.getPassword());
        db = new JdbcTemplate(source);
        db.execute("""
                CREATE SEQUENCE dh_governance_quality_audit_log_seq;
                CREATE TABLE dh_governance_quality_audit_log (
                  id int8 NOT NULL DEFAULT nextval('dh_governance_quality_audit_log_seq'::regclass) PRIMARY KEY,
                  instance_id int8 NOT NULL, task_id int8 NOT NULL, rule_id int8, rule_name varchar(128), rule_type varchar(32),
                  check_object_id int8, table_name varchar(128), check_field varchar(128), group_name varchar(64),
                  check_time timestamp(6), total_count int8 NOT NULL DEFAULT 0, fail_count int8 NOT NULL DEFAULT 0,
                  pass_rate numeric(10,2) NOT NULL DEFAULT 0, result_type varchar(32) NOT NULL DEFAULT 'PASS',
                  index_value varchar(128), cost_ms int8, result_sql text, error_detail text, creator varchar(64),
                  create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP, updater varchar(64),
                  update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP, deleted int2 NOT NULL DEFAULT 0,
                  read_evidence text, evaluation_id bigint);
                """);
        Path migration = root().resolve(MIGRATION);
        db.execute(Files.readString(migration));
        db.execute(Files.readString(migration));

        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("metric-baseline", new JdbcTransactionFactory(), source));
        var global = GlobalConfigUtils.getGlobalConfig(configuration);
        global.setMetaObjectHandler(new DefaultDBFieldHandler());
        global.getDbConfig().setIdType(IdType.INPUT).setLogicDeleteValue("1").setLogicNotDeleteValue("0")
                .setKeyGenerators(List.of(new PostgreKeyGenerator()));
        configuration.addMapper(QualityAuditLogMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession(true);
        mapper = session.getMapper(QualityAuditLogMapper.class);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void migrationAddsColumnsAndPartialIndex() {
        assertEquals(3, db.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='datahub-center' "
                + "AND table_name='dh_governance_quality_audit_log' AND column_name IN ('metric_key','metric_value','metric_scope')",
                Integer.class));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname='datahub-center' "
                + "AND indexname='idx_quality_audit_metric_history' AND indexdef LIKE '%WHERE%'", Integer.class));
    }

    @Test
    void previousModeTakesLatestEarlierResultIncludingSkippedButNotErrors() {
        insert(1, 10L, null, "s", NOW.minusDays(2), "PASS", 900, 0);
        insert(1, 10L, null, "s", NOW.minusDays(1), "SKIPPED", 1000, 0);
        insert(1, 10L, null, "s", NOW.minusHours(1), "ERROR", 5, 0);
        insert(1, 10L, null, "s", NOW.minusMinutes(30), "PASS", 7, 1);
        insert(1, 10L, null, "s", NOW.plusMinutes(1), "PASS", 8, 0);

        QualityAuditLogDO baseline = mapper.selectMetricBaseline(1L, 100L, "ROW_COUNT", "s", NOW,
                null, null, null);

        assertNotNull(baseline);
        assertEquals(0, baseline.getMetricValue().compareTo(java.math.BigDecimal.valueOf(1000)));
        assertEquals(NOW.minusDays(1), baseline.getCheckTime());
    }

    @Test
    void periodicModeTakesClosestInsideWindowAcrossRecreatedEvaluationsButIsolatesScopeAndTask() {
        LocalDateTime target = NOW.minusWeeks(1);
        insert(1, 10L, 55L, "s", target.minusHours(3), "PASS", 500, 0);
        insert(1, 10L, 55L, "s", target.plusMinutes(20), "FAIL", 600, 0);
        insert(1, 10L, 55L, "s", target.minusHours(13), "PASS", 700, 0);
        insert(1, 10L, 55L, "other-scope", target, "PASS", 800, 0);
        insert(1, 11L, 56L, "s", target.plusMinutes(5), "PASS", 900, 0);
        insert(2, 10L, 55L, "s", target, "PASS", 950, 0);

        QualityAuditLogDO baseline = mapper.selectMetricBaseline(1L, 100L, "ROW_COUNT", "s", NOW,
                target, target.minusHours(12), target.plusHours(12));
        assertNotNull(baseline);
        assertEquals(0, baseline.getMetricValue().compareTo(java.math.BigDecimal.valueOf(900)),
                "保存任务会重建评测：同一任务、对象与口径的结果属于同一序列，取最接近参考点的一次");

        assertNull(mapper.selectMetricBaseline(1L, 100L, "ROW_COUNT", "s", NOW,
                target.minusDays(30), target.minusDays(30).minusHours(12), target.minusDays(30).plusHours(12)),
                "窗口内没有结果时没有基准");
        assertNull(mapper.selectMetricBaseline(1L, 100L, "ROW_COUNT", "missing-scope", NOW,
                target, target.minusHours(12), target.plusHours(12)), "其他口径的结果不能作为基准");

    }

    @Test
    void historyReturnsLatestFirstAndCanFilterSameWeekday() {
        // NOW 是 2026-11-16（周一）
        for (int day = 1; day <= 15; day++) {
            insert(1, 10L, null, "s", NOW.minusDays(day), day == 3 ? "ERROR" : "PASS", 1000 + day, 0);
        }
        List<java.math.BigDecimal> latest = mapper.selectMetricHistory(1L, 100L, "ROW_COUNT", "s", NOW,
                null, 4);
        assertEquals(List.of(1001L, 1002L, 1004L, 1005L), latest.stream().map(java.math.BigDecimal::longValue).toList(),
                "执行出错的结果不进入历史");
        List<java.math.BigDecimal> mondays = mapper.selectMetricHistory(1L, 100L, "ROW_COUNT", "s", NOW,
                1, 10);
        assertEquals(List.of(1007L, 1014L), mondays.stream().map(java.math.BigDecimal::longValue).toList());
    }

    @Test
    void trendReturnsSameIdentityUpToAnchorInTimeOrder() {
        for (int day = 5; day >= 0; day--) {
            insert(1, 10L, 55L, "s", NOW.minusDays(day), "PASS", 100 + day, 0);
        }
        insert(1, 10L, 55L, "other", NOW.minusDays(1), "PASS", 9, 0);
        insert(1, 10L, 55L, "s", NOW.plusDays(1), "PASS", 7, 0);
        db.update("UPDATE dh_governance_quality_audit_log SET metric_lower=90, metric_upper=110.5, biz_date=check_time::date - 1");
        Long anchorId = db.queryForObject("SELECT id FROM dh_governance_quality_audit_log WHERE check_time=? "
                + "AND metric_scope='s'", Long.class, NOW);
        QualityAuditLogDO anchor = mapper.selectById(anchorId);
        assertEquals(java.time.LocalDate.of(2026, 11, 15), anchor.getBizDate());

        List<QualityAuditLogDO> trend = mapper.selectMetricTrend(anchor, 3);
        assertEquals(List.of(102L, 101L, 100L), trend.stream().map(row -> row.getMetricValue().longValue()).toList(),
                "最近 3 次、按时间升序，不含锚点之后与其他口径");
        assertEquals(0, new java.math.BigDecimal("110.5").compareTo(trend.getLast().getMetricUpper()));
    }

    private void insert(long taskId, Long ruleId, Long evaluationId, String scope, LocalDateTime checkTime,
                        String result, long rows, int deleted) {
        db.update("INSERT INTO dh_governance_quality_audit_log(instance_id,task_id,rule_id,evaluation_id,check_object_id,"
                        + "check_time,result_type,index_value,metric_key,metric_value,metric_scope,deleted) "
                        + "VALUES (1,?,?,?,100,?,?,?,'ROW_COUNT',?,?,?)",
                taskId, ruleId, evaluationId, checkTime, result, String.valueOf(rows), rows, scope, deleted);
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
