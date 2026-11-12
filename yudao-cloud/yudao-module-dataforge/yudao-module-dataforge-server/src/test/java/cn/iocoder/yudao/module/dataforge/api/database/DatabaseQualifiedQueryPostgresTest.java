package cn.iocoder.yudao.module.dataforge.api.database;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryReqDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryColumnsReqDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryTableRespDTO;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 提供方实际解析、JDBC 执行与受限投影；仅使用显式指定的本机隔离 PostgreSQL。 */
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class DatabaseQualifiedQueryPostgresTest {
    private String controlUrl;
    private String databaseName;
    private DatabaseApiImpl api;
    private cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime runtime;
    private AccessDatabaseResolver.ConnectionInfo info;

    @BeforeEach
    void setUp() throws Exception {
        controlUrl = System.getenv("SHARING_TEST_JDBC_URL");
        if (!controlUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres")) {
            throw new IllegalStateException("Explicit local isolated SHARING_TEST_JDBC_URL is required");
        }
        databaseName = "sharing_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(controlUrl, "sharing_test", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
        }
        String jdbc = controlUrl.substring(0, controlUrl.lastIndexOf('/') + 1) + databaseName;
        try (var connection = DriverManager.getConnection(jdbc, "sharing_test", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA other");
            statement.execute("CREATE TABLE public.citizen(id bigint)");
            statement.execute("INSERT INTO public.citizen VALUES(1)");
            statement.execute("CREATE TABLE other.citizen(id bigint)");
            statement.execute("INSERT INTO other.citizen VALUES(99)");
            statement.execute("CREATE SCHEMA \"Gov.Schema\"");
            statement.execute("CREATE TABLE \"Gov.Schema\".\"Citizen\"\". One\"(\"Id\"\".Code\" bigint, secret text)");
            statement.execute("INSERT INTO \"Gov.Schema\".\"Citizen\"\". One\" VALUES(7,'hidden')");
        }
        info = new AccessDatabaseResolver.ConnectionInfo();
        info.setDatabaseId(4L);
        info.setProductType(ProductTypeEnum.POSTGRESQL);
        info.setSchema("public");
        info.setJdbcUrl(jdbc + "?currentSchema=other");
        info.setUsername("sharing_test");
        info.setPassword("");
        var resolver = mock(AccessDatabaseResolver.class);
        when(resolver.resolve(eq(4L), any())).thenReturn(info);
        api = new DatabaseApiImpl();
        runtime = cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime.standalone();
        ReflectionTestUtils.setField(api, "dbSwitchRuntime", runtime);
        ReflectionTestUtils.setField(api, "databaseResolver", resolver);
        ReflectionTestUtils.setField(api, "dynamicMaskSupport",
                cn.iocoder.yudao.module.dataforge.service.database.DatabaseDynamicMaskSupportTest.passThrough());
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (runtime != null) runtime.close();
        if (databaseName != null && databaseName.matches("sharing_test_[0-9a-f]{32}")) {
            try (var connection = DriverManager.getConnection(controlUrl, "sharing_test", "");
                 var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + databaseName);
            }
        }
    }

    private DatabaseQueryReqDTO request(String sql) {
        var request = new DatabaseQueryReqDTO();
        request.setDatabaseId(4L);
        request.setSql(sql);
        return request;
    }

    @Test
    void parseAndExecuteUseConfiguredSchemaDespiteAnotherConnectionSearchPath() {
        var request = request("select ID from Citizen");
        var plan = api.parseRestrictedSelect(request).getCheckedData();
        assertThat(plan.getSchemaName()).isEqualTo("public");
        assertThat(plan.getTableName()).isEqualTo("citizen");
        request.setSql(plan.getNormalizedSql());
        assertThat(api.executeQuery(request).getCheckedData().getRows().getFirst().get("id")).isEqualTo(1L);
    }

    @Test
    void explicitSchemaAndConfigurationChangeDoNotRedirectTheBoundSql() {
        var request = request("select id from other.citizen");
        var plan = api.parseRestrictedSelect(request).getCheckedData();
        assertThat(plan.getSchemaName()).isEqualTo("other");
        info.setSchema("public");
        request.setSql(plan.getNormalizedSql());
        assertThat(api.executeQuery(request).getCheckedData().getRows().getFirst().get("id")).isEqualTo(99L);
    }

    @Test
    void quotedIdentityAndApprovedColumnsSurviveTheActualProviderBoundary() {
        var request = request("select * from \"Gov.Schema\".\"Citizen\"\". One\"");
        var plan = api.parseRestrictedSelect(request).getCheckedData();
        assertThat(plan.getTableName()).isEqualTo("Citizen\". One");
        request.setSql(plan.getNormalizedSql());
        request.setExpectedSchema(plan.getSchemaName());
        request.setExpectedTable(plan.getTableName());
        request.setAllowedColumns(List.of("Id\".Code"));
        var result = api.executeQuery(request).getCheckedData();
        assertThat(result.getRows().getFirst()).containsOnlyKeys("Id\".Code");
        assertThat(result.getRows().getFirst().get("Id\".Code")).isEqualTo(7L);
    }

    @Test
    void mismatchedSchemaCannotBeRetargetedToAnApprovedTable() {
        var request = request("select id from other.citizen");
        request.setSql(api.parseRestrictedSelect(request).getCheckedData().getNormalizedSql());
        request.setExpectedSchema("public");
        request.setExpectedTable("citizen");
        request.setAllowedColumns(List.of("id"));
        assertThatThrownBy(() -> api.executeQuery(request)).isInstanceOf(ServiceException.class);
    }

    @Test
    void missingDefaultSchemaDoesNotUseUnprovenSearchPathResolution() {
        info.setSchema(null);
        assertThatThrownBy(() -> api.parseRestrictedSelect(request("select * from citizen")))
                .isInstanceOf(ServiceException.class);
        assertThat(api.parseRestrictedSelect(request("select * from public.citizen")).getCheckedData().getSchemaName())
                .isEqualTo("public");
    }

    @Test
    void metadataListIsBoundToConfiguredSchemaNotTheConnectionSearchPath() {
        var tables = api.getQueryTableList(4L).getCheckedData();
        assertThat(tables).extracting(DatabaseQueryTableRespDTO::getTableReference)
                .containsExactly("\"public\".\"citizen\"");
        assertThat(tables.getFirst().getSchemaName()).isEqualTo("public");
        assertThat(tables.getFirst().getTableName()).isEqualTo("citizen");
        assertThat(api.resolveQueryTable(4L, "Citizen").getCheckedData()).isEqualTo(tables.getFirst());
        assertThat(api.resolveQueryTable(4L, "other.citizen").getCheckedData().getSchemaName()).isEqualTo("other");
    }

    @Test
    void listedQuotedReferenceRoundTripsToOnlyApprovedPhysicalColumns() {
        info.setSchema("Gov.Schema");
        var listed = api.getQueryTableList(4L).getCheckedData().getFirst();
        assertThat(listed.getTableReference()).isEqualTo("\"Gov.Schema\".\"Citizen\"\". One\"");
        var resolved = api.resolveQueryTable(4L, listed.getTableReference()).getCheckedData();
        assertThat(resolved).isEqualTo(listed);
        var columns = api.getQueryTableColumns(columns(resolved, List.of("Id\".Code"))).getCheckedData();
        assertThat(columns).extracting("columnName").containsExactly("Id\".Code");
        assertThat(columns.getFirst().getTypeName()).isEqualTo("int8");
    }

    @Test
    void resolvedMetadataCannotBeRedirectedByLaterDatasourceSchemaChanges() {
        var resolved = api.resolveQueryTable(4L, "\"Gov.Schema\".\"Citizen\"\". One\"").getCheckedData();
        info.setSchema("other");
        assertThat(api.getQueryTableColumns(columns(resolved, List.of("Id\".Code"))).getCheckedData())
                .extracting("columnName").containsExactly("Id\".Code");
    }

    @Test
    void invalidReferencesAndUnknownDefaultSchemaNeverUseLegacyResolution() {
        for (String reference : List.of("citizen c", "citizen where 1=1", "citizen; select 1",
                "catalog.public.citizen", "citizen join other.citizen on true", "a".repeat(64))) {
            assertThatThrownBy(() -> api.resolveQueryTable(4L, reference)).isInstanceOf(ServiceException.class);
        }
        info.setSchema(null);
        assertThatThrownBy(() -> api.resolveQueryTable(4L, "citizen")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> api.getQueryTableList(4L)).isInstanceOf(ServiceException.class);
        assertThat(api.resolveQueryTable(4L, "public.citizen").getCheckedData().getSchemaName()).isEqualTo("public");
    }

    @Test
    void missingOrEmptyProjectionNeverFallsBackToAllColumns() {
        var resolved = api.resolveQueryTable(4L, "public.citizen").getCheckedData();
        assertThatThrownBy(() -> api.getQueryTableColumns(columns(resolved, List.of())))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> api.getQueryTableColumns(columns(resolved, List.of("missing"))))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> api.getQueryTableColumns(columns(resolved, List.of("id", "id"))))
                .isInstanceOf(ServiceException.class);
        resolved.setSchemaName("missing_schema");
        assertThatThrownBy(() -> api.getQueryTableColumns(columns(resolved, List.of("id"))))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void unverifiedProductDoesNotConnectOrUseLegacyMetadata() {
        // MySQL/MariaDB 已由各自原生专测开放精确接口，不能继续把它们当未适配产品。
        // Doris 仅证明受限投影，parseBound/精确元数据链仍关闭。
        info.setProductType(ProductTypeEnum.DORIS);
        info.setJdbcUrl("invalid-jdbc-url");
        assertThatThrownBy(() -> api.getQueryTableList(4L)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> api.resolveQueryTable(4L, "citizen")).isInstanceOf(ServiceException.class);
        var request = new DatabaseQueryColumnsReqDTO();
        request.setDatabaseId(4L);
        request.setSchemaName("public");
        request.setTableName("citizen");
        assertThatThrownBy(() -> api.getQueryTableColumns(request)).isInstanceOf(ServiceException.class);
    }

    private static DatabaseQueryColumnsReqDTO columns(DatabaseQueryTableRespDTO table, List<String> projection) {
        var request = new DatabaseQueryColumnsReqDTO();
        request.setDatabaseId(4L);
        request.setSchemaName(table.getSchemaName());
        request.setTableName(table.getTableName());
        request.setColumns(projection);
        return request;
    }
}
