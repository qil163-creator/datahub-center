package cn.iocoder.yudao.module.modeling.service.model;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 数据库并发守卫回归：不能在“分层已引用”和“分层被停用”之间留下竞态窗口。 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(30)
class ModelingLayerReferencePostgreSqlTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeEach
    void setup() throws Exception {
        try (Connection connection = open()) {
            ModelingMigrationTestSupport.resetBaselineTables(connection);
            ModelingMigrationTestSupport.migrate(connection);
            sql(connection, """
                    INSERT INTO "datahub-center".dh_modeling_dw_layer
                      (id,level_no,layer_name,layer_code,node_type,table_prefix,status,tenant_id)
                    VALUES (1,2,'明细层','DWD','LAYER','dwd',0,1),
                           (2,1,'公共数仓',NULL,'CATEGORY',NULL,0,1),
                           (3,2,'外租户明细层','DWD','LAYER','dwd',0,2)
                    """);
        }
    }

    @Test
    void newConceptDoesNotRequireRemovedThemeAndRejectsCategoryOrOtherTenantLayer() throws Exception {
        try (Connection connection = open()) {
            sql(connection, conceptInsert(10, 1)); // 不再填写已从 DO 中移除的 theme_id。
            assertThat(checkViolation(connection, conceptInsert(11, 2))).isEqualTo("23514");
            assertThat(checkViolation(connection, conceptInsert(12, 3))).isEqualTo("23514");
            assertThat(checkViolation(connection, "UPDATE \"datahub-center\".dh_modeling_dw_layer SET table_prefix='other' WHERE id=1"))
                    .isEqualTo("23514");
            assertThat(checkViolation(connection, "DELETE FROM \"datahub-center\".dh_modeling_dw_layer WHERE id=1"))
                    .isEqualTo("23514");
        }
    }

    @Test
    void committedReferencePreventsConcurrentDisableAfterWaitingOnLayerLock() throws Exception {
        try (Connection holder = open(); ExecutorService pool = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            sql(holder, conceptInsert(10, 1));
            CountDownLatch attempted = new CountDownLatch(1);
            Future<String> disable = pool.submit(() -> {
                try (Connection contender = open()) {
                    attempted.countDown();
                    return checkViolation(contender, "UPDATE \"datahub-center\".dh_modeling_dw_layer SET status=1 WHERE id=1");
                }
            });
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                assertThrows(TimeoutException.class, () -> disable.get(200, TimeUnit.MILLISECONDS));
            } finally {
                holder.commit();
            }
            assertThat(disable.get(5, TimeUnit.SECONDS)).isEqualTo("23514");
            try (Statement statement = holder.createStatement();
                 ResultSet result = statement.executeQuery("SELECT status FROM \"datahub-center\".dh_modeling_dw_layer WHERE id=1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            }
        }
    }

    @Test
    void concurrentReferenceCannotAdoptLayerThatWasDisabledWhileWaiting() throws Exception {
        try (Connection holder = open(); ExecutorService pool = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            sql(holder, "UPDATE \"datahub-center\".dh_modeling_dw_layer SET status=1 WHERE id=1");
            CountDownLatch attempted = new CountDownLatch(1);
            Future<String> reference = pool.submit(() -> {
                try (Connection contender = open()) {
                    attempted.countDown();
                    return checkViolation(contender, conceptInsert(10, 1));
                }
            });
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                assertThrows(TimeoutException.class, () -> reference.get(200, TimeUnit.MILLISECONDS));
            } finally {
                holder.commit();
            }
            assertThat(reference.get(5, TimeUnit.SECONDS)).isEqualTo("23514");
        }
    }

    private String conceptInsert(int id, int layer) {
        return "INSERT INTO \"datahub-center\".dh_modeling_concept_model(id,name,layer_id,tenant_id) VALUES ("
                + id + ",'办件受理'," + layer + ",1)";
    }

    private static String checkViolation(Connection connection, String query) throws SQLException {
        try {
            sql(connection, query);
            return "SUCCEEDED";
        } catch (SQLException failure) {
            return failure.getSQLState();
        }
    }

    private static void sql(Connection connection, String query) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            statement.execute(query);
        }
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
