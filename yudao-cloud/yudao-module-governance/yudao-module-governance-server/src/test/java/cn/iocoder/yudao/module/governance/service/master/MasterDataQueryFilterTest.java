package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.JdbcSelectResult;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDataPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterColumnMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证模型字段到 DB Switch 谓词的业务适配，不用 H2 推断 PostgreSQL 语义。 */
class MasterDataQueryFilterTest extends BaseMockitoUnitTest {
    private MasterDataServiceImpl service;
    private MasterModelService models;
    private MasterColumnMapper columns;
    private MasterDatabaseResolver resolver;
    private DbSwitchRuntime runtime;
    private MetaDataByJdbcServiceImpl metadata;

    @BeforeEach
    void setup() {
        service = new MasterDataServiceImpl();
        models = mock(MasterModelService.class);
        columns = mock(MasterColumnMapper.class);
        resolver = mock(MasterDatabaseResolver.class);
        runtime = mock(DbSwitchRuntime.class);
        metadata = mock(MetaDataByJdbcServiceImpl.class);
        ReflectionTestUtils.setField(service, "modelService", models);
        ReflectionTestUtils.setField(service, "columnMapper", columns);
        ReflectionTestUtils.setField(service, "databaseResolver", resolver);
        ReflectionTestUtils.setField(service, "dbSwitchRuntime", runtime);
        lenient().when(models.validateModelReleased(10L)).thenReturn(
                MasterModelDO.builder().id(10L).databaseId(20L).tableName("records").build());
        lenient().when(columns.selectListByModelId(10L)).thenReturn(List.of(
                column("id", ColumnMetaData.TYPE_INTEGER, 1),
                column("name", ColumnMetaData.TYPE_STRING, 0),
                column("amount", ColumnMetaData.TYPE_BIGNUMBER, 0),
                column("score", ColumnMetaData.TYPE_NUMBER, 0),
                column("active", ColumnMetaData.TYPE_BOOLEAN, 0),
                column("day", ColumnMetaData.TYPE_DATE, 0),
                column("moment", ColumnMetaData.TYPE_TIMESTAMP, 0),
                column("clock", ColumnMetaData.TYPE_TIME, 0),
                column("binary", ColumnMetaData.TYPE_BINARY, 0)));
        var info = new MasterDatabaseResolver.ConnectionInfo();
        info.setProductType(ProductTypeEnum.POSTGRESQL);
        info.setSchema("query_fixture");
        info.setJdbcUrl("jdbc:fixture");
        info.setUsername("fixture");
        info.setPassword("");
        lenient().when(resolver.resolve(20L, MASTER_MODEL_DATABASE_NOT_EXISTS)).thenReturn(info);
    }

    @Test
    void explicitFiltersKeepSqlParameterizedAndIntegerDecimalValuesExact() {
        prepareResult();
        service.getMasterDataPage(request(List.of(
                condition("id", "GT", "9007199254740993"),
                condition("amount", "LE", "12345678901234567890.1234500"),
                condition("name", "CONTAINS", "a%_!b' OR 1=1 --"),
                condition("active", "NE", "false"))));
        var sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<Object>> params = ArgumentCaptor.captor();
        verify(metadata).pageDataBySql(eq("jdbc:fixture"), eq("fixture"), eq(""),
                sql.capture(), params.capture(), eq(1), eq(10), isNull());
        assertTrue(sql.getValue().contains("\"id\" > ?"));
        assertTrue(sql.getValue().contains("\"amount\" <= ?"));
        assertTrue(sql.getValue().endsWith("ORDER BY \"id\""));
        assertFalse(sql.getValue().contains("OR 1=1"));
        assertEquals(9007199254740993L, params.getValue().get(0));
        assertInstanceOf(Long.class, params.getValue().get(0));
        assertEquals(new BigDecimal("12345678901234567890.1234500"), params.getValue().get(1));
        assertEquals("%a!%!_!!b' OR 1=1 --%", params.getValue().get(2));
        assertEquals(false, params.getValue().get(3));
    }

    @Test
    void unaryOperatorsDiscardStaleValuesAndBindOnlyComponentEmptyStringConstants() {
        prepareResult();
        service.getMasterDataPage(request(List.of(
                condition("amount", "IS_NULL", "not a decimal"),
                condition("binary", "IS_NOT_NULL", "not binary"),
                condition("name", "IS_EMPTY", "old value"),
                condition("name", "IS_NOT_EMPTY", null))));
        var sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<Object>> params = ArgumentCaptor.captor();
        verify(metadata).pageDataBySql(anyString(), anyString(), anyString(), sql.capture(),
                params.capture(), eq(1), eq(10), isNull());
        assertTrue(sql.getValue().contains("\"amount\" IS NULL"));
        assertTrue(sql.getValue().contains("\"binary\" IS NOT NULL"));
        assertTrue(sql.getValue().contains("\"name\" = ?"));
        assertTrue(sql.getValue().contains("\"name\" <> ?"));
        assertEquals(List.of("", ""), params.getValue());
    }

    @Test
    void missingServerLogicalTypeAllowsOnlyNullPredicates() {
        when(columns.selectListByModelId(10L)).thenReturn(List.of(
                MasterColumnDO.builder().name("unmapped").fieldType(null).build()));
        prepareResult();
        service.getMasterDataPage(request(List.of(condition("unmapped", "IS_NULL", "stale"))));
        var sql = ArgumentCaptor.forClass(String.class);
        verify(metadata).pageDataBySql(anyString(), anyString(), anyString(), sql.capture(),
                eq(List.of()), eq(1), eq(10), isNull());
        assertTrue(sql.getValue().contains("\"unmapped\" IS NULL"));
    }

    @Test
    void missingServerLogicalTypeRejectsValuedComparisons() {
        when(columns.selectListByModelId(10L)).thenReturn(List.of(
                MasterColumnDO.builder().name("unmapped").fieldType(null).build()));
        assertInvalid(condition("unmapped", "EQ", "1"));
    }

    @Test
    void omittedOperatorPreservesLegacyWildcardsAndBlankConditions() {
        prepareResult();
        service.getMasterDataPage(request(List.of(condition("name", null, "a%_b"),
                condition("id", null, "9007199254740993"), condition("unknown", null, "  "))));
        var sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<Object>> params = ArgumentCaptor.captor();
        verify(metadata).pageDataBySql(anyString(), anyString(), anyString(), sql.capture(),
                params.capture(), eq(1), eq(10), isNull());
        assertTrue(sql.getValue().contains("\"name\" LIKE ?"));
        assertFalse(sql.getValue().contains("ESCAPE"));
        assertEquals(List.of("%a%_b%", 9007199254740993L), params.getValue());
        assertFalse(sql.getValue().contains("unknown"));
    }

    @ParameterizedTest
    @CsvSource({"day,GE,2026-10-12,java.sql.Date", "moment,LT,2026-10-12T10:11:12.123456789,java.sql.Timestamp",
            "clock,LE,10:11:12,java.sql.Time", "score,GT,3.14,java.lang.Double", "active,EQ,true,java.lang.Boolean"})
    void bindsServerTypedTemporalApproximateAndBooleanValues(String name, String op, String value, String javaType) {
        prepareResult();
        service.getMasterDataPage(request(List.of(condition(name, op, value))));
        ArgumentCaptor<List<Object>> params = ArgumentCaptor.captor();
        verify(metadata).pageDataBySql(anyString(), anyString(), anyString(), anyString(),
                params.capture(), eq(1), eq(10), isNull());
        assertEquals(javaType, params.getValue().getFirst().getClass().getName());
        if ("moment".equals(name)) assertEquals(123456789,
                ((java.sql.Timestamp) params.getValue().getFirst()).getNanos());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "eq", " EQ", "=", "EQ OR 1=1", "CUSTOM"})
    void rejectsUnknownOperatorsBeforeAcquiringJdbcService(String operator) {
        assertInvalid(condition("id", operator, "1"));
    }

    @ParameterizedTest
    @CsvSource({"id,CONTAINS,1", "amount,IS_EMPTY,1", "active,GT,true", "day,NOT_CONTAINS,2026-10-12",
            "binary,EQ,abc", "name,GT,abc"})
    void rejectsOperatorsIncompatibleWithServerMetadata(String name, String op, String value) {
        assertInvalid(condition(name, op, value));
    }

    @ParameterizedTest
    @CsvSource({"ORACLE,CONTAINS", "DM,IS_EMPTY"})
    void rejectsOperatorsOutsideTheDatabaseProductCapability(String product, String operator) {
        var info = new MasterDatabaseResolver.ConnectionInfo();
        info.setProductType(ProductTypeEnum.valueOf(product));
        info.setSchema("query_fixture");
        when(resolver.resolve(20L, MASTER_MODEL_DATABASE_NOT_EXISTS)).thenReturn(info);
        assertInvalid(condition("name", operator, "literal"));
    }

    @ParameterizedTest
    @CsvSource({"id,EQ,9223372036854775808", "amount,GT,not-decimal", "score,GT,NaN",
            "score,GT,Infinity", "active,NE,yes", "day,EQ,not-date", "moment,GE,not-timestamp", "clock,LE,not-time"})
    void rejectsValuesThatCannotBeConvertedWithoutExecutingQuery(String name, String op, String value) {
        assertInvalid(condition(name, op, value));
    }

    @Test
    void rejectsMissingValuesNullItemsAndExcessConditions() {
        assertInvalid(condition("id", "EQ", "  "));
        assertInvalid(condition("name", "CONTAINS", null));
        var items = new ArrayList<MasterDataPageReqVO.Condition>();
        items.add(null);
        assertConditionFailure(request(items));
        assertConditionFailure(request(java.util.Collections.nCopies(51, condition("id", "EQ", "1"))));
    }

    @Test
    void rejectsUnknownOrInjectedFieldNamesEvenForUnaryConditions() {
        for (String name : List.of("unknown", "id\" OR 1=1 --")) {
            var error = assertThrows(ServiceException.class,
                    () -> service.getMasterDataPage(request(List.of(condition(name, "IS_NULL", null)))));
            assertEquals(MASTER_DATA_QUERY_COLUMN_INVALID.getCode(), error.getCode());
        }
        verifyNoInteractions(runtime, metadata);
    }

    @Test
    void modelReleaseAndAuthorizationGuardRunsBeforeColumnOrDatasourceLookup() {
        when(models.validateModelReleased(10L)).thenThrow(exception(MASTER_MODEL_NOT_RELEASED));
        var error = assertThrows(ServiceException.class,
                () -> service.getMasterDataPage(request(List.of(condition("id", "EQ", "1")))));
        assertEquals(MASTER_MODEL_NOT_RELEASED.getCode(), error.getCode());
        verifyNoInteractions(columns, resolver, runtime, metadata);
    }

    @Test
    void requestJsonPreservesDecimalTextAndAllowsValueFreeOperators() {
        var request = JsonMapper.builder().build().readValue("""
                {"modelId":"9007199254740993","pageNo":1,"pageSize":10,"conditions":[
                  {"name":"amount","operator":"GE","value":"12345678901234567890.1234500"},
                  {"name":"id","operator":"IS_NULL"},
                  {"name":"name","value":"legacy"}
                ]}
                """, MasterDataPageReqVO.class);
        assertEquals(9007199254740993L, request.getModelId());
        assertEquals("12345678901234567890.1234500", request.getConditions().getFirst().getValue());
        assertNull(request.getConditions().get(1).getValue());
        assertNull(request.getConditions().get(2).getOperator());
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(request).isEmpty());
            request.getConditions().getFirst().setOperator("x".repeat(33));
            assertFalse(factory.getValidator().validate(request).isEmpty());
            request.setConditions(java.util.Collections.nCopies(51, condition("id", "EQ", "1")));
            assertFalse(factory.getValidator().validate(request).isEmpty());
            var nullItems = new ArrayList<MasterDataPageReqVO.Condition>();
            nullItems.add(null);
            request.setConditions(nullItems);
            assertFalse(factory.getValidator().validate(request).isEmpty());
        }
    }

    @Test
    void springManagedConsumerPreservesFilterAndCapabilityJsonContracts() {
        prepareResult();
        var request = JsonUtils.parseObject("""
                {"modelId":10,"pageNo":1,"pageSize":10,"conditions":[
                  {"name":"id","operator":"GT","value":"9007199254740993"},
                  {"name":"amount","operator":"LE","value":"12345678901234567890.1234500"},
                  {"name":"name","operator":"IS_NULL","value":"stale input"}
                ]}
                """, MasterDataPageReqVO.class);
        var requestJson = JsonUtils.getObjectMapper().readTree(JsonUtils.toJsonString(request));
        assertTrue(requestJson.path("conditions").get(0).path("operator").isString());
        assertTrue(requestJson.path("conditions").get(0).path("value").isString());
        assertEquals("9007199254740993", requestJson.path("conditions").get(0).path("value").asString());
        assertEquals("12345678901234567890.1234500", requestJson.path("conditions").get(1).path("value").asString());
        var roundTrip = JsonUtils.parseObject(JsonUtils.toJsonString(request), MasterDataPageReqVO.class);
        assertEquals(request.getConditions(), roundTrip.getConditions());
        try (var context = new AnnotationConfigApplicationContext()) {
            // 现成 mock 作为外部依赖注册；只有实际消费服务经过 Spring 创建与 @Resource 注入。
            context.getBeanFactory().registerSingleton("dbSwitchRuntime", runtime);
            context.getBeanFactory().registerSingleton("modelService", models);
            context.getBeanFactory().registerSingleton("columnMapper", columns);
            context.getBeanFactory().registerSingleton("databaseResolver", resolver);
            context.registerBean(MasterDataServiceImpl.class);
            context.refresh();
            var response = context.getBean(MasterDataServiceImpl.class).getMasterDataPage(roundTrip);
            var responseJson = JsonUtils.getObjectMapper().readTree(JsonUtils.toJsonString(response));
            assertTrue(responseJson.path("list").get(0).path("id").isString());
            assertEquals("9007199254740993", responseJson.path("list").get(0).path("id").asString());
            assertEquals("3.1400", responseJson.path("list").get(0).path("amount").asString());
        }
        var sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<Object>> params = ArgumentCaptor.captor();
        verify(metadata).pageDataBySql(anyString(), anyString(), anyString(), sql.capture(),
                params.capture(), eq(1), eq(10), isNull());
        assertTrue(sql.getValue().contains("\"id\" > ?"));
        assertTrue(sql.getValue().contains("\"name\" IS NULL"));
        assertFalse(sql.getValue().contains("stale input"));
        assertEquals(List.of(9007199254740993L, new BigDecimal("12345678901234567890.1234500")), params.getValue());
        var modelResponse = new MasterModelRespVO();
        modelResponse.setQueryFilterOperators(List.of("EQ", "CONTAINS", "IS_NULL"));
        var capabilityJson = JsonUtils.getObjectMapper().readTree(JsonUtils.toJsonString(modelResponse));
        var operators = capabilityJson.path("queryFilterOperators");
        assertTrue(operators.isArray());
        assertEquals(3, operators.size());
        assertTrue(operators.get(0).isString());
        assertEquals("IS_NULL", operators.get(2).asString());
        assertEquals(modelResponse.getQueryFilterOperators(), JsonUtils.parseObject(
                JsonUtils.toJsonString(modelResponse), MasterModelRespVO.class).getQueryFilterOperators());
    }

    private void prepareResult() {
        when(runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL)).thenReturn(metadata);
        var result = new JdbcSelectResult();
        result.setList(List.of(Map.of("id", 9007199254740993L, "amount", new BigDecimal("3.1400"))));
        result.setTotal(1L);
        when(metadata.pageDataBySql(anyString(), anyString(), anyString(), anyString(), anyList(),
                anyInt(), anyInt(), isNull())).thenReturn(result);
    }

    private void assertInvalid(MasterDataPageReqVO.Condition condition) {
        assertConditionFailure(request(List.of(condition)));
    }

    private void assertConditionFailure(MasterDataPageReqVO request) {
        var error = assertThrows(ServiceException.class, () -> service.getMasterDataPage(request));
        assertEquals(MASTER_DATA_QUERY_CONDITION_INVALID.getCode(), error.getCode());
        verifyNoInteractions(runtime, metadata);
    }

    static MasterColumnDO column(String name, int type, int pk) {
        return MasterColumnDO.builder().masterModelId(10L).name(name).fieldType(type).pk(pk).build();
    }

    static MasterDataPageReqVO.Condition condition(String name, String operator, String value) {
        var condition = new MasterDataPageReqVO.Condition();
        condition.setName(name);
        condition.setOperator(operator);
        condition.setValue(value);
        return condition;
    }

    static MasterDataPageReqVO request(List<MasterDataPageReqVO.Condition> conditions) {
        var request = new MasterDataPageReqVO();
        request.setModelId(10L);
        request.setPageNo(1);
        request.setPageSize(10);
        request.setConditions(conditions);
        return request;
    }
}
