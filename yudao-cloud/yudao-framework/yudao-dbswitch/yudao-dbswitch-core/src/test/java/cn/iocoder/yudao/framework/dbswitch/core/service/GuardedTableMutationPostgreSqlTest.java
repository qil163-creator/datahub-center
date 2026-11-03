package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static cn.iocoder.yudao.framework.dbswitch.core.service.GuardedMutationResult.Status.*;
import static cn.iocoder.yudao.framework.dbswitch.core.service.GuardedMutationResult.Failure.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
@Timeout(30)
class GuardedTableMutationPostgreSqlTest {
    private Connection control;
    private DriverManagerDataSource source;
    private String schema;
    private final GuardedTableMutationExecutor executor = new GuardedTableMutationExecutor();
    @BeforeEach void setup() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
        source = new DriverManagerDataSource(url, "codex_test", ""); control = source.getConnection();
        schema = "guardmutation_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
        sql("CREATE TABLE " + schema + ".rows(id bigint PRIMARY KEY, value varchar(100))");
        sql("INSERT INTO " + schema + ".rows VALUES(1,'old'),(2,NULL)");
    }
    @AfterEach void cleanup() throws Exception {
        if (control != null) try { if (schema != null && schema.matches("guardmutation_[a-f0-9]{32}")) sql("DROP SCHEMA " + schema + " CASCADE"); }
        finally { control.close(); }
    }
    @Test void competingOriginalSnapshotsCannotOverwriteEachOther() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<GuardedMutationResult> first = () -> { start.await(); return update(List.of(row(1, "old")), List.of(row(1, "first"))); };
            Callable<GuardedMutationResult> second = () -> { start.await(); return update(List.of(row(1, "old")), List.of(row(1, "second"))); };
            Future<GuardedMutationResult> a = pool.submit(first); Future<GuardedMutationResult> b = pool.submit(second); start.countDown();
            List<GuardedMutationResult> results = List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(r -> r.status() == APPLIED).count());
            assertEquals(1, results.stream().filter(r -> r.status() == NOT_APPLIED && r.failure() == CONFLICT).count());
            assertTrue(Set.of("first", "second").contains(value(1)));
        }
    }
    @Test void laterConflictRollsBackAllPreviousRows() throws Exception {
        var result = update(List.of(row(1, "old"), row(2, "stale")), List.of(row(1, "new"), row(2, "new")));
        assertEquals(NOT_APPLIED, result.status()); assertEquals(CONFLICT, result.failure());
        assertEquals("old", value(1)); assertNull(value(2));
    }
    @Test void nullSnapshotWorksAndDeleteIsAlsoGuarded() throws Exception {
        assertEquals(APPLIED, update(List.of(row(2, null)), List.of(row(2, "filled"))).status());
        var mismatch = new GuardedTableMutation(schema, "rows", List.of("id", "value"), List.of("id"), GuardedTableMutation.Operation.DELETE,
                List.of(row(2, null)), List.of(Map.of("id", 2L)));
        assertEquals(CONFLICT, executor.mutate(source, ProductTypeEnum.POSTGRESQL, mismatch).failure());
        var valid = new GuardedTableMutation(schema, "rows", List.of("id", "value"), List.of("id"), GuardedTableMutation.Operation.DELETE,
                List.of(row(2, "filled")), List.of(Map.of("id", 2L)));
        assertEquals(APPLIED, executor.mutate(source, ProductTypeEnum.POSTGRESQL, valid).status());
        assertNull(value(2));
    }
    @Test void duplicateInsertRollsBackEntireBatchAndUnsupportedProductDoesNotWrite() throws Exception {
        var insert = new GuardedTableMutation(schema, "rows", List.of("id", "value"), List.of("id"), GuardedTableMutation.Operation.INSERT,
                Arrays.asList(null, null), List.of(row(3, "new"), row(1, "duplicate")));
        assertEquals(CONFLICT, executor.mutate(source, ProductTypeEnum.POSTGRESQL, insert).failure());
        assertNull(value(3)); assertEquals("old", value(1));
        assertEquals(UNSUPPORTED, executor.mutate(source, ProductTypeEnum.HIVE, insert).failure()); assertNull(value(3));
    }
    private GuardedMutationResult update(List<Map<String, Object>> before, List<Map<String, Object>> after) {
        return executor.mutate(source, ProductTypeEnum.POSTGRESQL, new GuardedTableMutation(schema, "rows", List.of("id", "value"),
                List.of("id"), GuardedTableMutation.Operation.UPDATE, before, after));
    }
    private static Map<String, Object> row(long id, Object value) { return GuardedTableMutationTest.row(id, value); }
    private void sql(String query) throws Exception { try (Statement statement = control.createStatement()) { statement.execute(query); } }
    private String value(long id) throws Exception {
        try (Statement statement = control.createStatement(); ResultSet rows = statement.executeQuery("SELECT value FROM " + schema + ".rows WHERE id=" + id)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }
}
