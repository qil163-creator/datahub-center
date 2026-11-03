package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiExecuteRespVO;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiSaveReqVO;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiTestReqVO;
import cn.iocoder.yudao.module.supply.dal.mysql.apilog.ApiLogMapper;
import cn.iocoder.yudao.module.supply.framework.config.SupplyDataApiProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 真实本地 Doris 草稿试调；只使用随机临时库，凭据沿用显式诊断环境变量。 */
@Timeout(60)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "datahub.test.doris.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class DataApiReadonlyDorisTest {

    private static final long DATABASE_ID = 9_007_199_254_740_993L;
    private static final String SPECIAL_PAYLOAD = "测站' ; DROP TABLE sample; -- \\";

    private final String schema = "supply_readonly_" + UUID.randomUUID().toString().replace("-", "");
    private final String user = System.getenv().getOrDefault("DORIS_DIAGNOSTIC_USER", "root");
    private final String password = System.getenv().getOrDefault("DORIS_DIAGNOSTIC_PASSWORD", "");
    private Connection fixture;
    private DbSwitchRuntime runtime;
    private SupplyDatabaseResolver.ConnectionInfo connection;
    private DataApiServiceImpl service;

    @BeforeAll
    void createIsolatedDatabase() throws Exception {
        fixture = DriverManager.getConnection(System.getProperty("datahub.test.doris.url"), user, password);
        try (var statement = fixture.createStatement()) {
            statement.setQueryTimeout(20);
            try (var result = statement.executeQuery("SHOW VARIABLES LIKE 'version_comment'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(2)).contains("doris-4.0.8");
            }
            statement.execute("CREATE DATABASE `" + schema + "`");
            statement.execute("CREATE TABLE " + table() + " (id BIGINT NOT NULL, region VARCHAR(40), payload VARCHAR(200)) "
                    + "ENGINE=OLAP DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 "
                    + "PROPERTIES('replication_num'='1')");
        }
        try (var insert = fixture.prepareStatement("INSERT INTO " + table() + " VALUES (?, ?, ?)")) {
            insert.setQueryTimeout(20);
            for (int id = 1; id <= 5; id++) {
                insert.setLong(1, id);
                insert.setString(2, id <= 4 ? "北区" : "南区");
                insert.setString(3, id == 3 ? SPECIAL_PAYLOAD : "测站" + id);
                insert.executeUpdate();
            }
        }
    }

    @AfterAll
    void dropIsolatedDatabase() throws Exception {
        if (fixture == null) return;
        try (Connection closing = fixture; var statement = closing.createStatement()) {
            statement.setQueryTimeout(20);
            statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
        }
    }

    @BeforeEach
    void prepareConsumer() {
        runtime = spy(DbSwitchRuntime.standalone());
        var resolver = mock(SupplyDatabaseResolver.class);
        connection = new SupplyDatabaseResolver.ConnectionInfo();
        connection.setDatabaseId(DATABASE_ID);
        connection.setProductType(ProductTypeEnum.DORIS);
        connection.setJdbcUrl(queryUrl("DEFAULT"));
        connection.setUsername(user);
        connection.setPassword(password);
        connection.setSchema(schema);
        when(resolver.resolve(DATABASE_ID)).thenReturn(connection);
        var executor = new DataApiExecutor();
        ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
        ReflectionTestUtils.setField(executor, "dbSwitchRuntime", runtime);
        ReflectionTestUtils.setField(executor, "dataApiProperties", new SupplyDataApiProperties());
        ReflectionTestUtils.setField(executor, "apiLogMapper", mock(ApiLogMapper.class));
        service = new DataApiServiceImpl();
        ReflectionTestUtils.setField(service, "dataApiExecutor", executor);
    }

    @AfterEach
    void closeConsumerRuntime() {
        if (runtime != null) runtime.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEFAULT", "ANSI_QUOTES", "NO_BACKSLASH_ESCAPES", "ANSI_QUOTES,NO_BACKSLASH_ESCAPES"})
    void draftReadsBoundParametersAndSecondPageAcrossSqlModes(String mode) throws Exception {
        connection.setJdbcUrl(queryUrl(mode));
        assertSessionMode(mode);
        var api = query("SELECT id, payload FROM " + table()
                + " WHERE region=#{region} AND id>=#{minimumId} ORDER BY id");
        api.setRequestParams(List.of(parameter("region", "STRING"), parameter("minimumId", "INTEGER")));
        var result = preview(api, Map.of("region", "北区", "minimumId", "1"), 2, 2);
        assertThat(result.getExecuteMode()).isEqualTo("DRAFT_READONLY_PREVIEW");
        assertThat(result.getTestRunNo()).isNotBlank();
        assertThat(result.getConfigHash()).hasSize(64);
        assertThat(result.getTotal()).isEqualTo(4);
        assertThat(result.getRows()).extracting(row -> String.valueOf(row.get("id")))
                .containsExactly("3", "4");
        assertThat(result.getRows().getFirst().get("payload")).isEqualTo(SPECIAL_PAYLOAD);
        var injected = preview(api, Map.of("region", "北区' OR 1=1 -- \\", "minimumId", "1"), 1, 10);
        assertThat(injected.getRows()).isEmpty();
        assertThat(injected.getTotal()).isZero();

        var payloadQuery = query("SELECT id, payload FROM " + table() + " WHERE payload=#{payload}");
        payloadQuery.setRequestParams(List.of(parameter("payload", "STRING")));
        var literal = preview(payloadQuery, Map.of("payload", SPECIAL_PAYLOAD), 1, 10);
        assertThat(literal.getRows()).hasSize(1);
        assertThat(literal.getRows().getFirst().get("payload")).isEqualTo(SPECIAL_PAYLOAD);

        var dynamic = query("SELECT id FROM " + table()
                + " <where><if test=\"region != null\">AND region=#{region}</if></where> ORDER BY id");
        dynamic.setRequestParams(List.of(parameter("region", "STRING")));
        assertThat(preview(dynamic, Map.of("region", "北区"), 1, 2).getRows())
                .extracting(row -> String.valueOf(row.get("id"))).containsExactly("1", "2");

        var terminated = query("SELECT 'literal;value' AS marker FROM " + table()
                + " WHERE id=#{id}; -- 单条查询的尾注释");
        terminated.setRequestParams(List.of(parameter("id", "INTEGER")));
        var terminalResult = preview(terminated, Map.of("id", "1"), 1, 10);
        assertThat(terminalResult.getTotal()).isEqualTo(1);
        assertThat(terminalResult.getRows().getFirst().get("marker")).isEqualTo("literal;value");

        var limited = query("SELECT id FROM " + table() + " WHERE id>=#{minimumId} ORDER BY id LIMIT 4 OFFSET 1");
        limited.setRequestParams(List.of(parameter("minimumId", "INTEGER")));
        var limitedResult = preview(limited, Map.of("minimumId", "1"), 2, 2);
        assertThat(limitedResult.getTotal()).isEqualTo(4);
        assertThat(limitedResult.getRows()).extracting(row -> String.valueOf(row.get("id")))
                .containsExactly("4", "5");

        var cte = query("WITH chosen AS (SELECT id FROM " + table() + " WHERE region=#{region}) "
                + "SELECT id FROM chosen ORDER BY id LIMIT 3 OFFSET 1");
        cte.setRequestParams(List.of(parameter("region", "STRING")));
        var cteResult = preview(cte, Map.of("region", "北区"), 2, 2);
        assertThat(cteResult.getTotal()).isEqualTo(3);
        assertThat(cteResult.getRows()).extracting(row -> String.valueOf(row.get("id"))).containsExactly("4");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEFAULT", "ANSI_QUOTES", "NO_BACKSLASH_ESCAPES", "ANSI_QUOTES,NO_BACKSLASH_ESCAPES"})
    void draftRejectsWritesAndSideEffectsBeforeOpeningJdbc(String mode) throws Exception {
        connection.setJdbcUrl(queryUrl(mode));
        for (String sql : List.of(
                "INSERT INTO " + table() + " VALUES (99, '非法', '拒绝')",
                "UPDATE " + table() + " SET payload='非法' WHERE id=1",
                "DELETE FROM " + table() + " WHERE id=1",
                "SELECT id FROM " + table() + "; DELETE FROM " + table(),
                "SELECT id FROM " + table() + " <if test=\"danger != null\">; DELETE FROM " + table() + "</if>",
                "SELECT id FROM " + table() + " INTO OUTFILE 'file:///tmp/datahub-readonly-must-not-exist'",
                "SELECT id INTO @datahub_readonly_variable FROM " + table(),
                "SELECT id FROM " + table() + " FOR UPDATE",
                "SELECT id FROM " + table() + " FOR SHARE",
                "SELECT SLEEP(1)",
                "SELECT GET_LOCK('datahub_readonly_forbidden', 1)",
                "SELECT @datahub_readonly_variable:=1",
                "SELECT /*+ SET_VAR(query_timeout=1000) */ id FROM " + table(),
                "SELECT 1 /*! INTO OUTFILE '/tmp/datahub-readonly-must-not-exist' */")) {
            var attempt = query(sql);
            attempt.setRequestParams(List.of(parameter("danger", "STRING")));
            assertThatThrownBy(() -> preview(attempt, Map.of("danger", "yes"), 1, 10))
                    .isInstanceOf(ServiceException.class);
        }
        var multi = query("SELECT id FROM " + table());
        multi.setApiType("MULTI");
        assertThatThrownBy(() -> preview(multi, Map.of(), 1, 10)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(runtime);
        try (var statement = fixture.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*), SUM(id) FROM " + table())) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isEqualTo(5);
            assertThat(result.getLong(2)).isEqualTo(15);
        }
    }

    private DataApiExecuteRespVO preview(DataApiSaveReqVO api, Map<String, Object> params, int page, int size) {
        var request = new DataApiTestReqVO();
        request.setApi(api);
        request.setParams(params);
        request.setPageNo(page);
        request.setPageSize(size);
        return service.testDataApi(request, "127.0.0.1");
    }

    private DataApiSaveReqVO query(String sql) {
        var api = new DataApiSaveReqVO();
        api.setId(123L);
        api.setDatabaseId(DATABASE_ID);
        api.setConfigType("SQL");
        api.setApiType("QUERY");
        api.setMethod("GET");
        api.setResultFormat("PAGE");
        api.setSqlText(sql);
        return api;
    }

    private DataApiSaveReqVO.ParamItem parameter(String name, String type) {
        var parameter = new DataApiSaveReqVO.ParamItem();
        parameter.setName(name);
        parameter.setParamType(type);
        parameter.setNullable(0);
        return parameter;
    }

    private String queryUrl(String mode) {
        String url = System.getProperty("datahub.test.doris.url") + schema
                + "?useSSL=false&connectTimeout=5000&socketTimeout=15000"
                + "&useServerPrepStmts=false&USESERVERPREPSTMTS=false&emulateUnsupportedPstmts=true";
        return "DEFAULT".equals(mode) ? url : url + "&sessionVariables="
                + URLEncoder.encode("sql_mode='" + mode + "'", StandardCharsets.UTF_8);
    }

    /** 用与执行器相同的 JDBC URL 证明会话设置已生效；管理查询不走用户 SQL 入口。 */
    private void assertSessionMode(String mode) throws Exception {
        try (var session = DriverManager.getConnection(queryUrl(mode), user, password);
             var statement = session.createStatement();
             var result = statement.executeQuery("SELECT @@sql_mode")) {
            assertThat(result.next()).isTrue();
            String actual = result.getString(1);
            for (String flag : List.of("ANSI_QUOTES", "NO_BACKSLASH_ESCAPES")) {
                assertThat(actual.contains(flag)).as("会话模式 %s / %s", mode, flag)
                        .isEqualTo(mode.contains(flag));
            }
        }
    }

    private String table() {
        return '`' + schema + "`.`sample`";
    }
}
