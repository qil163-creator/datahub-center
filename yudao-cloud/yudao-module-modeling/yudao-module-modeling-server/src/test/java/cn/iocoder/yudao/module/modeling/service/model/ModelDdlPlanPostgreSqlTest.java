package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

class ModelDdlPlanPostgreSqlTest {
    @Test void unsupportedProductIsRejectedDuringPreflightWithoutOpeningAConnection() {
        var runtime = mock(DbSwitchRuntime.class);
        var executor = new ModelDdlExecutor();
        ReflectionTestUtils.setField(executor, "dbSwitchRuntime", runtime);
        var info = new ModelingDatabaseResolver.ConnectionInfo();
        info.setProductType(ProductTypeEnum.ORACLE);
        assertThrows(UnsupportedOperationException.class, () -> executor.validateDdl(info,
                List.of("CREATE TABLE sample (id number)")));
        verifyNoInteractions(runtime);
    }

    @Test void frozenNativeObservationIsCheckedInsideTheOwnedDdlTransaction() throws Exception {
        var info = fixture();
        var table = "ddl_fresh_" + UUID.randomUUID().toString().replace("-", "");
        try (var runtime = DbSwitchRuntime.standalone();
             var connection = DriverManager.getConnection(info.getJdbcUrl(), info.getUsername(), "")) {
            var executor = new ModelDdlExecutor();
            ReflectionTestUtils.setField(executor, "dbSwitchRuntime", runtime);
            try (var sql = connection.createStatement()) {
                sql.execute("CREATE TABLE public." + table + " (note text DEFAULT 'a  b')");
                try {
                    var frozen = executor.observeTable(info, 1L, table);
                    assertNotNull(frozen.nativePrecondition());
                    sql.execute("ALTER TABLE public." + table + " ALTER COLUMN note SET DEFAULT 'a b'");
                    var callbacks = new java.util.concurrent.atomic.AtomicInteger();
                    var failed = assertThrows(IllegalStateException.class, () -> executor.executeDdl(info,
                            List.of("ALTER TABLE public." + table + " ADD COLUMN extra int"), frozen,
                            ignored -> callbacks.incrementAndGet()));
                    assertTrue(failed.getMessage().contains("ROLLED_BACK"));
                    assertEquals(0, callbacks.get());
                    assertEquals(1, executor.observeTable(info, 1L, table).fields().size());
                } finally { sql.execute("DROP TABLE public." + table); }
            }
        }
    }

    @Test void frozenCreatePlanRollsBackAsOneTransactionWhenItsLaterStepFails() throws Exception {
        var info = fixture();
        var table = "ddl_fresh_" + UUID.randomUUID().toString().replace("-", "");
        try (var runtime = DbSwitchRuntime.standalone()) {
            var executor = new ModelDdlExecutor();
            ReflectionTestUtils.setField(executor, "dbSwitchRuntime", runtime);
            var frozen = executor.observeTable(info, 1L, table);
            assertFalse(frozen.tableExists());
            assertThrows(IllegalStateException.class, () -> executor.executeDdl(info,
                    List.of("CREATE TABLE public." + table + " (id bigint)",
                            "ALTER TABLE public." + table + " ADD COLUMN id bigint"), frozen, ignored -> { }));
            assertFalse(executor.observeTable(info, 1L, table).tableExists());
        }
    }

    @Test void invalidTailAndDmlAreRejectedBeforeOpeningAnyConnection() {
        var runtime = mock(DbSwitchRuntime.class);
        var executor = new ModelDdlExecutor();
        ReflectionTestUtils.setField(executor, "dbSwitchRuntime", runtime);
        var connection = new ModelingDatabaseResolver.ConnectionInfo();
        connection.setProductType(ProductTypeEnum.POSTGRESQL);
        assertThrows(IllegalArgumentException.class, () -> executor.executeDdl(connection,
                List.of("CREATE TABLE sample (id int)", "this is not valid SQL")));
        assertThrows(IllegalArgumentException.class, () -> executor.executeDdl(connection,
                List.of("CREATE TABLE sample (id int)", "DELETE FROM sample")));
        verifyNoInteractions(runtime);
    }

    @Test void postgresRollsBackTheWholePlanWhenLaterDdlFails() throws Exception {
        var info = fixture();
        var table = "ddl_plan_" + UUID.randomUUID().toString().replace("-", "");
        try (var runtime = DbSwitchRuntime.standalone();
             var connection = DriverManager.getConnection(info.getJdbcUrl(), info.getUsername(), "")) {
            var executor = new ModelDdlExecutor();
            ReflectionTestUtils.setField(executor, "dbSwitchRuntime", runtime);
            var failure = assertThrows(IllegalStateException.class, () -> executor.executeDdl(info,
                    List.of("CREATE TABLE public." + table + " (id bigint)",
                            "ALTER TABLE public." + table + " ADD COLUMN id bigint")));
            assertTrue(failure.getMessage().contains("ROLLED_BACK"));
            try (var statement = connection.prepareStatement("SELECT to_regclass(?)")) {
                statement.setString(1, "public." + table);
                try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertNull(rows.getString(1)); }
            }
        }
    }

    @Test void postgresExecutesIdentityAndQuotedCommentsInTheSamePlan() throws Exception {
        var info = fixture();
        var table = "ddl_plan_" + UUID.randomUUID().toString().replace("-", "");
        try (var runtime = DbSwitchRuntime.standalone();
             var connection = DriverManager.getConnection(info.getJdbcUrl(), info.getUsername(), "")) {
            var executor = new ModelDdlExecutor();
            ReflectionTestUtils.setField(executor, "dbSwitchRuntime", runtime);
            try {
                executor.executeDdl(info, List.of("CREATE TABLE public." + table
                        + " (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, amount numeric(200,40))",
                        "COMMENT ON TABLE public." + table + " IS 'keep  double spaces; and ''quotes'''"));
                try (var statement = connection.prepareStatement("SELECT obj_description(to_regclass(?), 'pg_class')")) {
                    statement.setString(1, "public." + table);
                    try (var rows = statement.executeQuery()) {
                        assertTrue(rows.next()); assertEquals("keep  double spaces; and 'quotes'", rows.getString(1));
                    }
                }
            } finally { try (var statement = connection.createStatement()) { statement.execute("DROP TABLE IF EXISTS public." + table); } }
        }
    }

    private ModelingDatabaseResolver.ConnectionInfo fixture() {
        var url = System.getProperty("datahub.test.postgres.url");
        assumeTrue(url != null && !url.isBlank(), "Real PostgreSQL fixture is required");
        var info = new ModelingDatabaseResolver.ConnectionInfo();
        info.setProductType(ProductTypeEnum.POSTGRESQL); info.setJdbcUrl(url);
        info.setUsername(System.getProperty("datahub.test.postgres.user", "dataforge_test"));
        info.setPassword(""); info.setSchema("public");
        return info;
    }
}
