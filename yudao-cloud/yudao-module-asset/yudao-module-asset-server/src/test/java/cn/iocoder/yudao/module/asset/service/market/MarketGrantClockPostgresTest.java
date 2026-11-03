package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
@Timeout(30)
class MarketGrantClockPostgresTest extends SharingPostgresSupport {

    @Test
    void clockAdvancesInsideRepeatableReadWithDefaultSessionCache() {
        withClock((mapper, db) -> {
            LocalDateTime first = mapper.databaseNow();
            LocalDateTime observed = rawClock(db);
            assertTrue(observed.isAfter(first), "database wall clock must have advanced before the second read");
            assertTrue(mapper.databaseNow().isAfter(first),
                    "permission clock must not reuse MyBatis SESSION cache or transaction-start time");
        });
    }

    @Test
    void clockUsesShanghaiRegardlessOfConnectionTimezone() {
        withClock((mapper, db) -> {
            for (String zone : new String[]{"UTC", "America/New_York"}) {
                try (var statement = db.createStatement()) {
                    statement.execute("SET LOCAL TIME ZONE '" + zone + "'");
                }
                LocalDateTime before = rawClock(db);
                LocalDateTime actual = mapper.databaseNow();
                LocalDateTime after = rawClock(db);
                assertTrue(!actual.isBefore(before) && !actual.isAfter(after),
                        "permission clock must use the platform timezone, not session timezone");
            }
        });
    }

    private void withClock(ClockAssertion assertion) {
        withTransaction(TransactionDefinition.ISOLATION_REPEATABLE_READ, true, assertion);
    }

    @Test
    void ownedGrantLockMustBeReacquiredAfterSavepointRollback() throws Exception {
        sql("ALTER TABLE dh_market_resource_apply ADD apply_user_id bigint, ADD source_revision bigint");
        sql("INSERT INTO dh_market_resource_apply(id,apply_user_id,source_revision) VALUES(9,11,2)");
        withTransaction(TransactionDefinition.ISOLATION_READ_COMMITTED, false, (mapper, db) -> {
            var savepoint = db.setSavepoint();
            assertEquals(2L, mapper.selectOwnedForRenewal( 9L, 11L).getSourceRevision());
            db.rollback(savepoint); // 回滚释放行锁，但 JDBC 保存点不负责清理 MyBatis SESSION 缓存。
            db.releaseSavepoint(savepoint);
            sql("UPDATE dh_market_resource_apply SET source_revision=3 WHERE id=9");
            assertEquals(3L, mapper.selectOwnedForRenewal( 9L, 11L).getSourceRevision());
        });
    }

    private void withTransaction(int isolation, boolean readOnly, ClockAssertion assertion) {
        var source = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center&socketTimeout=10", "sharing_test", "");
        var configuration = singleTenantConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("grant-clock",
                new SpringManagedTransactionFactory(), source));
        configuration.addMapper(MarketResourceApplyMapper.class);
        var mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration))
                .getMapper(MarketResourceApplyMapper.class);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setIsolationLevel(isolation);
        transaction.setReadOnly(readOnly);
        transaction.executeWithoutResult(status -> {
            Connection db = DataSourceUtils.getConnection(source);
            try {
                assertion.check(mapper, db);
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            } finally {
                DataSourceUtils.releaseConnection(db, source);
            }
        });
    }

    private static LocalDateTime rawClock(Connection db) throws SQLException {
        try (var statement = db.createStatement();
             var result = statement.executeQuery("SELECT clock_timestamp() AT TIME ZONE 'Asia/Shanghai'")) {
            result.next();
            return result.getObject(1, LocalDateTime.class);
        }
    }

    @FunctionalInterface
    private interface ClockAssertion {
        void check(MarketResourceApplyMapper mapper, Connection db) throws SQLException;
    }
}
