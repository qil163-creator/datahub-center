package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterColumnMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.MASTER_MODEL_DATABASE_NOT_EXISTS;
import static cn.iocoder.yudao.module.governance.service.master.MasterDataQueryFilterTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 本机私有 PostgreSQL 随机 schema；经过业务服务及真实 DB Switch 分页查询。 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[a-z][a-z0-9_]*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(30)
class MasterDataQueryFilterPostgreSqlTest {
    private static final String LARGE_ID = "9007199254740993";
    private Connection control;
    private String schema;
    private DbSwitchRuntime runtime;
    private MasterDataServiceImpl service;

    @BeforeAll
    void setup() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        String user = System.getProperty("datahub.test.postgres.user", "codex_test");
        control = DriverManager.getConnection(url, user, "");
        schema = "filter_consumer_" + UUID.randomUUID().toString().replace("-", "");
        try (var statement = control.createStatement()) {
            statement.setQueryTimeout(15);
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("CREATE TABLE " + schema + ".records (id BIGINT PRIMARY KEY, name TEXT, "
                    + "amount NUMERIC(38,7), active BOOLEAN, day DATE)");
            statement.execute("INSERT INTO " + schema + ".records VALUES "
                    + "(1,'a%_!b',1,true,'2026-10-01'),(2,'ax_b',2,false,'2026-10-12'),"
                    + "(3,'',NULL,NULL,NULL),(4,NULL,3,true,'2026-10-31'),"
                    + "(" + LARGE_ID + ",'literal '' OR 1=1 --',12345678901234567890.1234500,false,'2026-10-20')");
        }
        runtime = DbSwitchRuntime.standalone();
        var models = mock(MasterModelService.class);
        var columns = mock(MasterColumnMapper.class);
        var resolver = mock(MasterDatabaseResolver.class);
        when(models.validateModelReleased(10L)).thenReturn(
                MasterModelDO.builder().id(10L).databaseId(20L).tableName("records").build());
        when(columns.selectListByModelId(10L)).thenReturn(List.of(
                column("id", ColumnMetaData.TYPE_INTEGER, 1), column("name", ColumnMetaData.TYPE_STRING, 0),
                column("amount", ColumnMetaData.TYPE_BIGNUMBER, 0), column("active", ColumnMetaData.TYPE_BOOLEAN, 0),
                column("day", ColumnMetaData.TYPE_DATE, 0)));
        var info = new MasterDatabaseResolver.ConnectionInfo();
        info.setProductType(ProductTypeEnum.POSTGRESQL);
        info.setSchema(schema);
        info.setJdbcUrl(url);
        info.setUsername(user);
        info.setPassword("");
        when(resolver.resolve(20L, MASTER_MODEL_DATABASE_NOT_EXISTS)).thenReturn(info);
        service = new MasterDataServiceImpl();
        ReflectionTestUtils.setField(service, "modelService", models);
        ReflectionTestUtils.setField(service, "columnMapper", columns);
        ReflectionTestUtils.setField(service, "databaseResolver", resolver);
        ReflectionTestUtils.setField(service, "dbSwitchRuntime", runtime);
    }

    @AfterAll
    void cleanup() throws Exception {
        try {
            if (runtime != null) runtime.close();
        } finally {
            if (control != null) {
                try (var statement = control.createStatement()) {
                    statement.setQueryTimeout(15);
                    if (schema != null && schema.matches("filter_consumer_[a-f0-9]{32}")) {
                        statement.execute("DROP SCHEMA " + schema + " CASCADE");
                    }
                } finally {
                    control.close();
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource(value = {"EQ|2", "NE|1,4," + LARGE_ID, "GT|4," + LARGE_ID,
            "GE|2,4," + LARGE_ID, "LT|1", "LE|1,2"}, delimiter = '|')
    void numericOperatorsAndSqlNullSemanticsUseExactParameters(String operator, String expected) {
        assertIds("amount", operator, "2.0000000", expected);
    }

    @ParameterizedTest
    @CsvSource(value = {"EQ|a%_!b|1", "NE|a%_!b|2,3," + LARGE_ID,
            "CONTAINS|%_!|1", "NOT_CONTAINS|%_!|2,3," + LARGE_ID,
            "IS_NULL|stale|4", "IS_NOT_NULL|stale|1,2,3," + LARGE_ID,
            "IS_EMPTY|stale|3", "IS_NOT_EMPTY|stale|1,2," + LARGE_ID}, delimiter = '|')
    void stringOperatorsDistinguishLiteralWildcardsEmptyStringAndNull(String operator, String value, String expected) {
        assertIds("name", operator, value, expected);
    }

    @Test
    void keepsLargeIntegerAndExactDecimalWireValuesThroughRealPagination() {
        var response = service.getMasterDataPage(request(List.of(
                condition("id", "EQ", LARGE_ID),
                condition("amount", "EQ", "12345678901234567890.1234500"))));
        assertEquals(1L, response.getTotal());
        assertEquals(LARGE_ID, response.getList().getFirst().get("id"));
        assertEquals("12345678901234567890.1234500", response.getList().getFirst().get("amount"));
        assertIds("name", "CONTAINS", "' OR 1=1 --", LARGE_ID);
    }

    @Test
    void appliesTypedDateBooleanAndLegacyStringFiltersThroughRuntime() {
        assertIds("day", "GT", "2026-10-12", "4," + LARGE_ID);
        assertIds("active", "EQ", "false", "2," + LARGE_ID);
        assertIds("amount", "IS_NULL", "not a decimal", "3");
        assertIds("name", null, "%_", "1,2," + LARGE_ID);
        var response = service.getMasterDataPage(request(List.of(
                condition("amount", "GE", "1"), condition("amount", "LE", "2"))));
        assertEquals(2L, response.getTotal());
        assertEquals(List.of("1", "2"), response.getList().stream().map(row -> row.get("id")).toList());
    }

    private void assertIds(String field, String operator, String value, String expected) {
        var response = service.getMasterDataPage(request(List.of(condition(field, operator, value))));
        List<String> ids = response.getList().stream().map(row -> String.valueOf(row.get("id"))).toList();
        assertEquals(List.of(expected.split(",")), ids);
        assertEquals((long) ids.size(), response.getTotal());
        assertTrue(response.getList().stream().allMatch(row -> row.get("id") instanceof String));
    }
}
