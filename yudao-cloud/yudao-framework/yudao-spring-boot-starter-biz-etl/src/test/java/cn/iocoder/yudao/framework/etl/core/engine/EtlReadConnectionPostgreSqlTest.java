package cn.iocoder.yudao.framework.etl.core.engine;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.connection.*;
import cn.iocoder.yudao.framework.etl.core.executor.*;
import cn.iocoder.yudao.framework.etl.core.model.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The SPI observes the actual SELECT connection and leaves its transaction/close lifetime with DB Switch. */
class EtlReadConnectionPostgreSqlTest {
    private static PostgreSQLContainer<?> container;
    private static String url, user, password;
    private Connection control;
    private String schema;
    private EtlConnectionInfo info;
    private DbSwitchRuntime runtime;
    private final List<Map<String, Object>> received = new CopyOnWriteArrayList<>();
    private final List<EtlReadPolicyPort.ReadConnectionStage> stages = new CopyOnWriteArrayList<>();
    private final AtomicReference<Connection> actual = new AtomicReference<>();
    private final AtomicInteger filters = new AtomicInteger();

    @BeforeAll static void database() {
        url = System.getProperty("datahub.test.postgres.url");
        if (url == null || url.isBlank()) {
            container = new PostgreSQLContainer<>("postgres:16-alpine"); container.start();
            url = container.getJdbcUrl(); user = container.getUsername(); password = container.getPassword();
        } else { user = System.getProperty("datahub.test.postgres.user"); password = System.getProperty("datahub.test.postgres.password", ""); }
    }
    @AfterAll static void stop() { if (container != null) container.stop(); }
    @BeforeEach void setup() throws Exception {
        control = DriverManager.getConnection(url, user, password);
        schema = "etl_read_connection_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema); sql("CREATE TABLE " + schema + ".rows(id integer)");
        info = new EtlConnectionInfo(); info.setDatabaseId(1L); info.setSchema(schema);
        info.setProductType(ProductTypeEnum.POSTGRESQL); info.setJdbcUrl(url); info.setUsername(user); info.setPassword(password);
        runtime = DbSwitchRuntime.standalone();
    }
    @AfterEach void close() throws Exception {
        try { if (control != null) { sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); control.close(); } }
        finally { if (runtime != null) runtime.close(); }
    }

    @Test void bothStagesUseTheSameLiveSelectTransactionBeforeAndAfterAuthorizedRows() throws Exception {
        sql("INSERT INTO " + schema + ".rows VALUES(1),(2),(3)");
        var result = execute(policy(false)); assertTrue(result.isSuccess(), result.getFirstError());
        assertEquals(3, received.size()); assertEquals(1, filters.get());
        assertEquals(List.of(EtlReadPolicyPort.ReadConnectionStage.OPENED, EtlReadPolicyPort.ReadConnectionStage.DRAINED), stages);
        assertTrue(actual.get().isClosed(), "observer cannot extend the reader connection lifetime");
        assertEquals(0, runtime.queries().activeOperations());
    }

    @Test void aRealEmptyQueryStillOpensAndDrainsTheSameConnection() throws Exception {
        var result = execute(policy(false)); assertTrue(result.isSuccess(), result.getFirstError());
        assertTrue(received.isEmpty()); assertEquals(0, filters.get());
        assertEquals(List.of(EtlReadPolicyPort.ReadConnectionStage.OPENED, EtlReadPolicyPort.ReadConnectionStage.DRAINED), stages);
        assertTrue(actual.get().isClosed());
    }

    @Test void rejectedOpenedConnectionFailsBeforeEmissionAndClosesThroughTheOriginalReaderChain() throws Exception {
        sql("INSERT INTO " + schema + ".rows VALUES(1)");
        var result = execute(policy(true)); assertFalse(result.isSuccess());
        assertTrue(received.isEmpty()); assertEquals(0, filters.get());
        assertEquals(List.of(EtlReadPolicyPort.ReadConnectionStage.OPENED), stages);
        assertTrue(actual.get().isClosed()); assertEquals(0, runtime.queries().activeOperations());
    }

    private EtlReadPolicyPort policy(boolean reject) {
        return new EtlReadPolicyPort() {
            public String rewrite(String node, Long database, String sql) { return sql; }
            public List<Map<String, Object>> filter(String node, Long database, String sql, List<Map<String, Object>> rows) {
                assertEquals(List.of(ReadConnectionStage.OPENED), stages); assertFalse(reject);
                filters.incrementAndGet(); return rows;
            }
            public void validateReadConnection(String node, Long database, String sql, Connection connection, ReadConnectionStage stage) throws SQLException {
                assertEquals("source", node); assertEquals(1L, database); assertEquals("SELECT id FROM " + schema + ".rows", sql);
                assertFalse(connection.isClosed()); assertFalse(connection.getAutoCommit());
                if (stage == ReadConnectionStage.OPENED) { assertNull(actual.get()); actual.set(connection); }
                else assertSame(actual.get(), connection);
                stages.add(stage);
                if (reject) throw new SQLException("fixture read connection rejected");
            }
        };
    }
    private cn.iocoder.yudao.framework.etl.core.runtime.EtlRunResult execute(EtlReadPolicyPort policy) {
        var registry = new EtlExecutorRegistry();
        registry.register("fixture-collect", (node, context) -> new AbstractEtlNodeExecutor(node, context) {
            protected void execute() throws Exception { Map<String, Object> row; while ((row = readNextRow()) != null) received.add(row); }
        });
        var source = node("source", "read-db", 1, Map.of("databaseId", 1, "ifSql", 1, "sql", "SELECT id FROM " + schema + ".rows"));
        var sink = node("sink", "fixture-collect", 3, Map.of()); var edge = new EtlEdgeDef(); edge.setSourceNo("source"); edge.setTargetNo("sink");
        try (var engine = new EtlPipelineEngine(registry, runtime)) {
            return engine.execute(EtlDag.build(List.of(source, sink), List.of(edge)),
                    EtlRunOptions.builder().readPolicyPort(policy).connectionResolver(id -> info).build());
        }
    }
    private static EtlNodeDef node(String no, String tool, int type, Map<String, Object> config) {
        var node = new EtlNodeDef(); node.setNo(no); node.setName(no); node.setToolCode(tool); node.setToolType(type); node.setNodeJson(JSONUtil.toJsonStr(config)); return node;
    }
    private void sql(String sql) throws SQLException { try (var statement = control.createStatement()) { statement.execute(sql); } }
}
