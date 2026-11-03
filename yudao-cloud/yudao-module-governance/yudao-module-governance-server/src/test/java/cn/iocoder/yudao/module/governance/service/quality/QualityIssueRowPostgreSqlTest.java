package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityIssueRowStateMapper;
import cn.iocoder.yudao.module.governance.support.SingleTenantPostgreSqlFixture;
import com.baomidou.mybatisplus.core.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 PostgreSQL 验证处置状态与审计同事务、批量回滚以及跨线程并发胜者。 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
@Timeout(30)
class QualityIssueRowPostgreSqlTest {
    private Connection control;
    private String schema;
    private QualityIssueRowStateMapper states;
    private TransactionTemplate transaction;
    private static final String KEY = "a".repeat(64);
    private static final String SECOND = "b".repeat(64);

    @BeforeEach void setup() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
        String user = System.getProperty("datahub.test.postgres.user", "dataforge_test");
        control = DriverManager.getConnection(url, user, "");
        schema = "qualityrows_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema); sql("SET search_path TO " + schema);
        SingleTenantPostgreSqlFixture.copyCurrentTables(control, schema,
                List.of("dh_governance_quality_issue_row_state", "dh_governance_quality_issue_row_audit"));
        var source = new DriverManagerDataSource(url + "?currentSchema=" + schema, user, "");
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setEnvironment(new Environment("quality-rows", new SpringManagedTransactionFactory(), source));
        configuration.addMapper(QualityIssueRowStateMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        states = session.getMapper(QualityIssueRowStateMapper.class);
    }
    @AfterEach void cleanup() throws Exception {
        if (control != null) try {
            if (schema != null && schema.matches("qualityrows_[a-f0-9]{32}")) sql("DROP SCHEMA " + schema + " CASCADE");
        } finally { control.close(); }
    }
    private void sql(String statement) throws Exception { try (var s = control.createStatement()) { s.execute(statement); } }
    private long number(String query) throws Exception {
        try (var s = control.createStatement(); var result = s.executeQuery(query)) { assertTrue(result.next()); return result.getLong(1); }
    }
    private boolean ignore(String key, int revision) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            states.ensureState(1L, key, "11");
            if (states.compareAndSet(1L, key, revision, "IGNORE", "", "11") != 1) { status.setRollbackOnly(); return false; }
            states.recordAction(1L, key, 11L, "IGNORE", "UNPROCESSED", revision, KEY, UUID.randomUUID().toString());
            return true;
        }));
    }
    @Test void twoConcurrentFirstActionsHaveOneWinnerAndOneAudit() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = pool.submit(() -> { start.await(); return ignore(KEY, 0); });
            var second = pool.submit(() -> { start.await(); return ignore(KEY, 0); });
            start.countDown(); assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, number("SELECT revision FROM dh_governance_quality_issue_row_state"));
        assertEquals(1, number("SELECT count(*) FROM dh_governance_quality_issue_row_audit WHERE before_state='UNPROCESSED' AND after_state='IGNORED' AND actor_id=11"));
    }
    @Test void auditConstraintFailureRollsBackStateAndNewRow() throws Exception {
        assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(status -> {
            states.ensureState(1L, KEY, "11");
            assertEquals(1, states.compareAndSet(1L, KEY, 0, "IGNORE", "", "11"));
            states.recordAction(1L, KEY, 11L, "IGNORE", "UNPROCESSED", 0, "invalid-digest", "batch");
        }));
        assertEquals(0, number("SELECT count(*) FROM dh_governance_quality_issue_row_state"));
        assertEquals(0, number("SELECT count(*) FROM dh_governance_quality_issue_row_audit"));
    }
    @Test void laterStaleRowRollsBackEarlierBatchActionAndDeletedStateCannotBeRevived() throws Exception {
        assertTrue(ignore(SECOND, 0));
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            states.ensureState(1L, KEY, "11");
            assertEquals(1, states.compareAndSet(1L, KEY, 0, "IGNORE", "", "11"));
            states.recordAction(1L, KEY, 11L, "IGNORE", "UNPROCESSED", 0, KEY, "batch");
            if (states.compareAndSet(1L, SECOND, 0, "IGNORE", "", "11") != 1) throw new IllegalStateException("陈旧修订号");
        }));
        assertEquals(1, number("SELECT count(*) FROM dh_governance_quality_issue_row_state"));
        assertEquals(1, number("SELECT count(*) FROM dh_governance_quality_issue_row_audit"));
        sql("UPDATE dh_governance_quality_issue_row_state SET deleted=1");
        assertFalse(ignore(SECOND, 1));
        assertEquals(1, number("SELECT revision FROM dh_governance_quality_issue_row_state"));
    }
}
