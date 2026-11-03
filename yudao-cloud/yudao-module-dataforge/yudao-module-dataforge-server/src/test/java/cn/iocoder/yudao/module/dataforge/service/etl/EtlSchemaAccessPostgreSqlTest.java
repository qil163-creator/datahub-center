package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import cn.iocoder.yudao.framework.etl.core.schema.EtlSchemaService;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlSchemaReqVO;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL verifies that design metadata never executes rejected writes or switches schemas. */
class EtlSchemaAccessPostgreSqlTest {
    private static PostgreSQLContainer<?> container;
    private static String url, user, password;
    @BeforeAll static void postgres() {
        url = System.getProperty("datahub.test.postgres.url");
        if (url == null || url.isBlank()) {
            container = new cn.iocoder.yudao.module.dataforge.support.DataforgePostgreSqlContainer(); container.start();
            url = container.getJdbcUrl(); user = container.getUsername(); password = container.getPassword();
        } else {
            if (!url.startsWith("jdbc:postgresql://127.0.0.1:") && !url.startsWith("jdbc:postgresql://localhost:"))
                throw new IllegalArgumentException("This fixture requires an isolated local PostgreSQL database");
            user = System.getProperty("datahub.test.postgres.user");
            password = System.getProperty("datahub.test.postgres.password", "");
        }
    }
    @AfterAll static void stop() { if (container != null) container.stop(); }
    private final EtlPipelineServiceImpl service = new EtlPipelineServiceImpl();
    private final EtlNodeAccessService access = new EtlNodeAccessService();
    private final ExecutionAccessService databases = mock(ExecutionAccessService.class);
    private final EtlConnectionResolverAdapter connections = mock(EtlConnectionResolverAdapter.class);
    private final EtlEnvironmentService environments = mock(EtlEnvironmentService.class);
    private Connection control;
    private DbSwitchRuntime runtime;
    private String schema;

    @BeforeEach void setup() throws Exception {
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(7L), new MockHttpServletRequest());
        control = DriverManager.getConnection(url, user, password);
        schema = "etl_schema_access_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
        sql("CREATE TABLE " + schema + ".source_rows(id integer PRIMARY KEY, secret text)");
        sql("INSERT INTO " + schema + ".source_rows VALUES (1,'isolated-fixture'),(2,'isolated-fixture')");
        var info = new EtlConnectionInfo(); info.setDatabaseId(42L); info.setProductType(ProductTypeEnum.POSTGRESQL);
        info.setJdbcUrl(url); info.setUsername(user); info.setPassword(password); info.setSchema(schema);
        when(connections.resolve(42L)).thenReturn(info);
        when(environments.freeze(any(), anyList(), any())).thenAnswer(call ->
                new EtlEnvironmentService.Resolved(call.getArgument(1), Map.of()));
        runtime = DbSwitchRuntime.standalone();
        ReflectionTestUtils.setField(access, "executionAccessService", databases);
        ReflectionTestUtils.setField(access, "connectionResolverAdapter", connections);
        ReflectionTestUtils.setField(service, "nodeAccessService", access);
        ReflectionTestUtils.setField(service, "executionAccessService", databases);
        ReflectionTestUtils.setField(service, "connectionResolverAdapter", connections);
        ReflectionTestUtils.setField(service, "environmentService", environments);
        ReflectionTestUtils.setField(service, "schemaService", new EtlSchemaService(runtime));
    }
    @AfterEach void cleanup() throws Exception {
        SecurityContextHolder.clearContext();
        if (runtime != null) runtime.close();
        if (control != null) { try { sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); } finally { control.close(); } }
    }

    @Test void restrictedProjectionUsesPhysicalSchemaAndReturnsOnlyRequestedColumns() {
        var result = service.resolveSchema(request(Map.of("databaseId", 42, "ifSql", 1, "sql", "select id from source_rows")));
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        assertEquals(List.of("id"), result.getNodes().get("source").getOutputFields().stream().map(field -> field.getName()).toList());
    }

    @Test void rejectedMultiStatementAndModifyingCteLeaveActualTableUnchanged() throws Exception {
        for (String text : List.of(
                "DELETE FROM " + schema + ".source_rows; SELECT id FROM " + schema + ".source_rows",
                "WITH removed AS (DELETE FROM " + schema + ".source_rows RETURNING id) SELECT id FROM removed",
                "SELECT id INTO " + schema + ".unexpected_copy FROM " + schema + ".source_rows",
                "SELECT pg_sleep(60) FROM " + schema + ".source_rows")) {
            var failure = assertThrows(ServiceException.class, () -> service.resolveSchema(request(
                    Map.of("databaseId", 42, "ifSql", 1, "sql", text))));
            assertEquals(ETL_PIPELINE_GRAPH_INVALID.getCode(), failure.getCode());
            assertFalse(failure.getMessage().contains(text));
            assertEquals(2, countRows());
        }
        try (var statement = control.createStatement(); var result = statement.executeQuery(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema='" + schema + "' AND table_name='unexpected_copy'")) {
            assertTrue(result.next()); assertEquals(0, result.getInt(1));
        }
    }

    @Test void tableAndSqlWildcardsReturnAllActualDatabaseFields() {
        for (Map<String, Object> config : List.of(
                Map.<String, Object>of("databaseId", 42, "tableName", "source_rows"),
                Map.<String, Object>of("databaseId", 42, "ifSql", 1, "sql", "SELECT * FROM source_rows"))) {
            var result = service.resolveSchema(request(config));
            assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
            var fields = result.getNodes().get("source").getOutputFields();
            assertEquals(List.of("id", "secret"), fields.stream().map(field -> field.getName()).toList());
            assertNull(fields.getFirst().getNullable());
            assertNull(fields.getFirst().getPrecision()); assertNull(fields.getFirst().getScale());
        }
    }

    private int countRows() throws Exception {
        try (var statement = control.createStatement(); var result = statement.executeQuery("SELECT count(*) FROM " + schema + ".source_rows")) {
            assertTrue(result.next()); return result.getInt(1);
        }
    }
    private void sql(String text) throws Exception { try (var statement = control.createStatement()) { statement.execute(text); } }
    private static EtlSchemaReqVO request(Map<String, Object> config) {
        var req = new EtlSchemaReqVO(); req.setNodes(List.of(Map.of("id", "source", "properties",
                Map.of("toolCode", "read-db", "toolType", 1, "nodeJson", config)))); req.setEdges(List.of()); return req;
    }
}
