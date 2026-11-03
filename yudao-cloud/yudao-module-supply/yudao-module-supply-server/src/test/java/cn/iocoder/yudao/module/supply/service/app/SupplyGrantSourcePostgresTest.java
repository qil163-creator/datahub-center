package cn.iocoder.yudao.module.supply.service.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class SupplyGrantSourcePostgresTest extends SharingPostgresSupport {
    private static final String MIGRATION = "0041_supply_grant-source-protocol.sql";

    @Test
    void replayAndAggregateUniquenessIncludingRevokedRows() throws Exception {
        migrate(MIGRATION);
        migrate(MIGRATION);
        migrate("0169_datahub_remove-business-tenancy.sql");
        migrate("0169_datahub_remove-business-tenancy.sql");
        sql("INSERT INTO dh_supply_app_api(id,app_id,api_id,grant_status) VALUES(1,1,2,3)");
        assertThatThrownBy(() -> sql("INSERT INTO dh_supply_app_api(id,app_id,api_id) VALUES(2,1,2)"))
                .isInstanceOf(SQLException.class);
        sql("INSERT INTO dh_supply_app_api(id,app_id,api_id) VALUES(3,2,2)");
        assertThat(scalar("SELECT count(*) FROM dh_supply_app_api")).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM information_schema.columns WHERE table_schema='datahub-center' "
                + "AND table_name LIKE 'dh_supply_%' AND column_name='tenant_id'")).isZero();
    }

    @Test
    void refusesDuplicateLegacyAggregatesWithoutDeletingHistory() throws Exception {
        sql("INSERT INTO dh_supply_app_api(id,app_id,api_id) VALUES(1,1,2),(2,1,2)");
        assertThatThrownBy(() -> migrate(MIGRATION)).isInstanceOf(SQLException.class).hasMessageContaining("重复");
        assertThat(scalar("SELECT count(*) FROM dh_supply_app_api")).isEqualTo(2);
    }

    @Test
    void refusesMissingPrerequisiteAndDuplicateLegacyOperations() throws Exception {
        sql("INSERT INTO dh_supply_grant_op(id,source_system,operation_id) VALUES(1,'asset','op'),(2,'asset','op')");
        assertThatThrownBy(() -> migrate(MIGRATION)).isInstanceOf(SQLException.class).hasMessageContaining("重复");
        sql("DROP TABLE dh_supply_grant_op");
        assertThatThrownBy(() -> migrate(MIGRATION)).isInstanceOf(SQLException.class).hasMessageContaining("前置");
    }

    @Test
    void tombstoneRetainsGlobalSourceIdentityAndActiveNeedsFields() throws Exception {
        migrate(MIGRATION);
        migrate("0169_datahub_remove-business-tenancy.sql");
        sql(sourceInsert(1, 1, "REVOKED", "NULL"));
        assertThatThrownBy(() -> sql(sourceInsert(2, 2, "REVOKED", "NULL"))).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> sql("INSERT INTO dh_supply_grant_source(source_system,source_key,app_id,api_id,source_revision,source_status,payload_hash,scope_json) "
                + "VALUES('asset','empty',1,2,1,'ACTIVE','hash','{}')")).isInstanceOf(SQLException.class);
        assertThat(scalar("SELECT count(*) FROM dh_supply_grant_source")).isEqualTo(1);
    }

    @Test
    void independentConnectionsCannotCreateSameSourceTwice() throws Exception {
        migrate(MIGRATION);
        migrate("0169_datahub_remove-business-tenancy.sql");
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> insertConcurrentSource(start, 10));
            var second = workers.submit(() -> insertConcurrentSource(start, 11));
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS) + second.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        }
    }

    private int insertConcurrentSource(CountDownLatch start, long id) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try (var c = open(); var statement = c.createStatement()) {
            statement.execute(sourceInsert(id, 1, "REVOKED", "NULL"));
            return 1;
        } catch (SQLException ex) {
            if (!"23505".equals(ex.getSQLState())) throw ex;
            return 0;
        }
    }

    private static String sourceInsert(long id, long appId, String status, String scope) {
        return "INSERT INTO dh_supply_grant_source(id,source_system,source_key,app_id,api_id,source_revision,source_status,payload_hash,scope_json) VALUES("
                + id + ",'asset','source'," + appId + ",2,1,'" + status + "','hash'," + scope + ")";
    }
}
