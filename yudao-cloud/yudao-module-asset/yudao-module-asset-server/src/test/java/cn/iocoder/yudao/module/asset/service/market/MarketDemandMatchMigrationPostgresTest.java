package cn.iocoder.yudao.module.asset.service.market;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/** 真实0169转换、重放、全局匹配唯一性与失败回滚。 */
@Timeout(30)
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketDemandMatchMigrationPostgresTest extends SharingPostgresSupport {
    private static final String MIGRATION = "0169_datahub_remove-business-tenancy.sql";
    private static final String INDEX = "uk_market_demand_match_active";

    @BeforeEach
    void prepare() throws Exception {
        sql("""
                CREATE TABLE dh_market_demand_match (
                  id bigint PRIMARY KEY, tenant_id bigint NOT NULL DEFAULT 1, demand_id bigint NOT NULL,
                  asset_id bigint NOT NULL, status smallint NOT NULL DEFAULT 0, apply_id bigint,
                  deleted smallint NOT NULL DEFAULT 0);
                CREATE UNIQUE INDEX uk_market_demand_match_active
                  ON dh_market_demand_match(tenant_id,demand_id,asset_id) WHERE deleted=0;
                """);
    }

    @Test
    void firstExecutionAndReplayPreserveExistingConfirmedApplication() throws Exception {
        sql("INSERT INTO dh_market_demand_match VALUES(1,1,10,20,1,99,0)");
        migrate(MIGRATION);
        long oid = scalar("SELECT '" + INDEX + "'::regclass::oid");
        migrate(MIGRATION);
        assertThat(scalar("SELECT '" + INDEX + "'::regclass::oid")).isEqualTo(oid);
        assertThat(tenantColumnCount()).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE id=1 AND status=1 AND apply_id=99"))
                .isEqualTo(1);
        assertDuplicate("INSERT INTO dh_market_demand_match VALUES(2,10,20,0,NULL,0)");
    }

    static Stream<String> historicalPairs() {
        return Stream.of(0, 1, 2).flatMap(first -> Stream.of(0, 1, 2).map(second -> first + "," + second));
    }

    @ParameterizedTest
    @MethodSource("historicalPairs")
    void crossTenantDuplicatesFailBeforeRemovingIdentityOrMergingRecords(String pair) throws Exception {
        String[] statuses = pair.split(",");
        sql("INSERT INTO dh_market_demand_match VALUES(1,1,10,20," + statuses[0] + ",98,0),"
                + "(2,2,10,20," + statuses[1] + ",99,0)");
        assertThatThrownBy(() -> migrate(MIGRATION)).isInstanceOf(SQLException.class)
                .hasMessageContaining("全局唯一键");
        assertThat(tenantColumnCount()).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE deleted=0 AND apply_id IN(98,99)"))
                .isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM pg_indexes WHERE schemaname='datahub-center'"
                + " AND indexname='" + INDEX + "'")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void allLiveStatusesRejectDuplicateWhileDemandAndAssetRemainSeparate(int status) throws Exception {
        migrate(MIGRATION);
        sql("INSERT INTO dh_market_demand_match VALUES(1,10,20," + status + ",NULL,0),"
                + "(2,11,20,0,NULL,0),(3,10,21,0,NULL,0)");
        assertDuplicate("INSERT INTO dh_market_demand_match VALUES(4,10,20,0,NULL,0)");
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match")).isEqualTo(3);
    }

    @Test
    void deletedHistoryIsKeptAndCannotBeRestoredOverNewLiveMatch() throws Exception {
        sql("INSERT INTO dh_market_demand_match VALUES(1,1,10,20,1,98,1),(2,2,10,20,2,99,1)");
        migrate(MIGRATION);
        sql("INSERT INTO dh_market_demand_match VALUES(3,10,20,0,NULL,0)");
        assertDuplicate("UPDATE dh_market_demand_match SET deleted=0 WHERE id=1");
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE deleted=1")).isEqualTo(2);
        sql("UPDATE dh_market_demand_match SET deleted=1 WHERE id=3");
        sql("INSERT INTO dh_market_demand_match VALUES(4,10,20,0,NULL,0)");
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE deleted=0")).isEqualTo(1);
    }

    @Test
    void baselineMatchSliceUpgradesToTheSameGlobalConstraint() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("sql/postgresql/migrations"))) root = root.getParent();
        assertThat(root).isNotNull();
        String baseline = Files.readString(root.resolve("sql/postgresql/datahub-center.sql"));
        int sequenceStart = baseline.indexOf("CREATE SEQUENCE \"datahub-center\".\"dh_market_demand_match_seq\"");
        assertThat(sequenceStart).isGreaterThan(0);
        sql(baseline.substring(sequenceStart, baseline.indexOf(';', sequenceStart) + 1));
        int tableStart = baseline.indexOf("CREATE TABLE \"datahub-center\".\"dh_market_demand_match\"");
        int tableEnd = baseline.indexOf("-- ----------------------------", tableStart);
        sql("DROP TABLE dh_market_demand_match");
        sql(baseline.substring(tableStart, tableEnd));
        migrate(MIGRATION);
        assertThat(tenantColumnCount()).isZero();
        sql("INSERT INTO dh_market_demand_match(demand_id,asset_id) VALUES(10,20)");
        assertDuplicate("INSERT INTO dh_market_demand_match(demand_id,asset_id) VALUES(10,20)");
    }

    @Test
    void busyTableTimesOutWithoutPartialUpgradeOrChangingOtherWriters() throws Exception {
        try (var writer = open()) {
            writer.setAutoCommit(false);
            try (var statement = writer.createStatement()) {
                statement.executeUpdate("INSERT INTO dh_market_demand_match VALUES(1,1,10,20,1,99,0)");
            }
            try {
                assertThatThrownBy(() -> migrate(MIGRATION)).isInstanceOfSatisfying(SQLException.class,
                        error -> assertThat(error.getSQLState()).isEqualTo("55P03"));
            } finally {
                writer.commit();
            }
        }
        assertThat(tenantColumnCount()).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE id=1 AND apply_id=99")).isEqualTo(1);
        migrate(MIGRATION);
        assertThat(tenantColumnCount()).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_demand_match WHERE id=1 AND apply_id=99")).isEqualTo(1);
    }

    private long tenantColumnCount() throws SQLException {
        return scalar("SELECT count(*) FROM information_schema.columns WHERE table_schema='datahub-center'"
                + " AND table_name='dh_market_demand_match' AND column_name='tenant_id'");
    }

    private void assertDuplicate(String statement) {
        assertThatThrownBy(() -> sql(statement)).isInstanceOfSatisfying(SQLException.class,
                error -> assertThat(error.getSQLState()).isEqualTo("23505"));
    }
}
