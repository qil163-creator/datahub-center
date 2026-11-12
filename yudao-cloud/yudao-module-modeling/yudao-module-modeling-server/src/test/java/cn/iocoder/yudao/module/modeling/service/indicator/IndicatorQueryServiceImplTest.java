package cn.iocoder.yudao.module.modeling.service.indicator;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.api.security.SafetySecurityApi;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskReqDTO;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskRespDTO;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.AggregateQuerySql;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.*;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businesscondition.BusinessConditionDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.indicator.IndicatorModelDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.ModelMaterializedDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.businesscondition.BusinessConditionMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.indicator.IndicatorModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.ModelMaterializedMapper;
import cn.iocoder.yudao.module.modeling.service.model.ModelingDatabaseResolver;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 指标查询：取数规则、SQL 组装入口、复合计算、截断、脱敏扩展点与失败语义。 */
@ExtendWith(MockitoExtension.class)
class IndicatorQueryServiceImplTest {

    @Mock private IndicatorModelMapper mapper;
    @Mock private ModelMaterializedMapper materializedMapper;
    @Mock private BusinessConditionMapper conditionMapper;
    @Mock private ModelingDatabaseResolver databases;
    @Mock private IndicatorQueryExecutor executor;
    @Mock private SafetySecurityApi safetySecurityApi;
    @InjectMocks private IndicatorQueryServiceImpl service;

    private final List<IndicatorModelDO> rows = new ArrayList<>();

    private final ModelingDatabaseResolver.ConnectionInfo connection = new ModelingDatabaseResolver.ConnectionInfo();

    /** 执行器替身：按度量列返回固定结果，并记录收到的计划。 */
    private final Map<String, List<Map<String, Object>>> resultsByValueColumn = new HashMap<>();

    private final List<AggregateQuerySql.Plan> executed = new ArrayList<>();

    @BeforeEach
    void setup() {
        var resolver = new IndicatorDependencyResolver();
        ReflectionTestUtils.setField(resolver, "mapper", mapper);
        ReflectionTestUtils.setField(service, "dependencies", resolver);
        var maskSupport = new IndicatorQueryMaskSupport();
        ReflectionTestUtils.setField(maskSupport, "safetySecurityApi", safetySecurityApi);
        ReflectionTestUtils.setField(service, "maskSupport", maskSupport);
        // 默认场景未启用：原样返回行。
        lenient().when(safetySecurityApi.maskRows(any())).thenAnswer(invocation -> {
            SafetyDynamicMaskReqDTO request = invocation.getArgument(0);
            var response = new SafetyDynamicMaskRespDTO();
            response.setEnabled(false);
            response.setRows(request.getRows());
            return CommonResult.success(response);
        });
        lenient().when(mapper.selectBatchIds(anyCollection())).thenAnswer(invocation -> {
            Collection<?> ids = invocation.getArgument(0);
            return rows.stream().filter(row -> ids.contains(row.getId())).toList();
        });
        lenient().when(mapper.selectById(anyLong())).thenAnswer(invocation -> rows.stream()
                .filter(row -> row.getId().equals(invocation.getArgument(0))).findFirst().orElse(null));
        lenient().when(mapper.selectListByCodesIgnoreCase(anyCollection())).thenAnswer(invocation -> {
            Collection<String> codes = invocation.getArgument(0);
            return rows.stream().filter(row -> codes.contains(row.getCode().toLowerCase(Locale.ROOT))).toList();
        });
        connection.setDatabaseId(50L);
        connection.setName("订单库");
        connection.setProductType(ProductTypeEnum.POSTGRESQL);
        connection.setSchema("public");
        lenient().when(databases.resolve(50L)).thenReturn(connection);
        lenient().when(materializedMapper.selectLatestSuccess(5L)).thenReturn(materialized());
        lenient().when(executor.execute(any(), anyList(), anyInt())).thenAnswer(invocation -> {
            List<AggregateQuerySql.Plan> plans = invocation.getArgument(1);
            executed.addAll(plans);
            List<List<Map<String, Object>>> results = new ArrayList<>();
            for (AggregateQuerySql.Plan plan : plans) {
                String column = plan.sql().contains("\"amount\" AS \"dh_value\"") ? "amount" : "order_no";
                results.add(resultsByValueColumn.getOrDefault(column, List.of()));
            }
            return results;
        });

        rows.add(atomic(1L, "gmv", "SUM", "amount-uid"));
        rows.add(atomic(2L, "order_cnt", "COUNT", "order-uid"));
        rows.get(1).setResultDataType("BIGINT");
        var derived = indicator(3L, "paid_gmv_m", IndicatorModelDO.DERIVED);
        derived.setBaseIndicatorId(1L);
        derived.setTimePeriod("MONTH");
        derived.setBusinessCondition("paid_only");
        derived.setResultDataType("DECIMAL");
        rows.add(derived);
        var composite = indicator(4L, "avg_price", IndicatorModelDO.COMPOSITE);
        composite.setFormula("{gmv} / {order_cnt} * 100");
        composite.setResultDataType("DECIMAL");
        rows.add(composite);
        var nested = indicator(6L, "avg_price_k", IndicatorModelDO.COMPOSITE);
        nested.setFormula("{avg_price} / 1000");
        rows.add(nested);

        var paid = new BusinessConditionDO();
        paid.setId(70L);
        paid.setCode("paid_only");
        paid.setName("已支付");
        paid.setExpression("pay_status = 'PAID'");
        paid.setStatus(BusinessConditionDO.ACTIVE);
        paid.setSourceModelId(5L);
        lenient().when(conditionMapper.selectByCodeIgnoreCase("paid_only")).thenReturn(paid);
    }

    private static IndicatorModelDO indicator(Long id, String code, String type) {
        var row = new IndicatorModelDO();
        row.setId(id);
        row.setCode(code);
        row.setName(code + "名称");
        row.setIndicatorType(type);
        row.setStatus(IndicatorModelDO.ACTIVE);
        row.setDataDomainId(7L);
        return row;
    }

    private static IndicatorModelDO atomic(Long id, String code, String aggregation, String valueUid) {
        var row = indicator(id, code, IndicatorModelDO.ATOMIC);
        row.setSourceModelId(5L);
        row.setAggregation(aggregation);
        row.setValueFieldUid(valueUid);
        row.setTimeFieldUid("time-uid");
        row.setResultDataType("DECIMAL");
        return row;
    }

    private static ModelFieldItem field(String uid, String name, String type) {
        var field = new ModelFieldItem();
        field.setFieldUid(uid);
        field.setFieldName(name);
        field.setCnName(name + "中文");
        field.setDataType(type);
        return field;
    }

    private static ModelMaterializedDO materialized() {
        var row = new ModelMaterializedDO();
        row.setId(90L);
        row.setModelId(5L);
        row.setDatasourceId(50L);
        row.setDatasourceName("订单库");
        row.setSchemaName("dw");
        row.setTableName("fact_order");
        row.setStatus(ModelMaterializedDO.STATUS_SUCCESS);
        row.setFieldsJson(JsonUtils.toJsonString(List.of(
                field("region-uid", "region", "VARCHAR(20)"),
                field("level-uid", "user_level", "INT"),
                field("status-uid", "pay_status", "VARCHAR(20)"),
                field("amount-uid", "amount", "DECIMAL(20,4)"),
                field("order-uid", "order_no", "VARCHAR(40)"),
                field("time-uid", "pay_time", "TIMESTAMP"))));
        return row;
    }

    private static Map<String, Object> row(String region, LocalDate bucket, Object value) {
        Map<String, Object> row = new HashMap<>();
        row.put("dh_dim_0", region);
        if (bucket != null) {
            row.put("dh_time_bucket", Date.valueOf(bucket));
        }
        row.put("dh_value", value);
        return row;
    }

    private static IndicatorQueryReqVO request(List<Long> ids, List<String> dimensions) {
        var request = new IndicatorQueryReqVO();
        request.setIndicatorIds(ids);
        request.setDimensions(dimensions);
        return request;
    }

    private static void assertCode(int code, Runnable runnable) {
        var error = assertThrows(ServiceException.class, runnable::run);
        assertEquals(code, error.getCode(), error.getMessage());
    }

    @Test
    void atomicAndDerivedShareConnectionAndDerivedAddsPeriodAndBusinessCondition() {
        resultsByValueColumn.put("amount", List.of(
                row("east", LocalDate.of(2026, 1, 1), new BigDecimal("10.5000")),
                row("west", LocalDate.of(2026, 2, 1), new BigDecimal("9007199254740993.1234"))));
        var request = request(List.of(1L, 3L), List.of("region-uid"));
        var range = new IndicatorQueryReqVO.TimeRange();
        range.setStart("2026-01-01");
        range.setEnd("2026-03-31");
        request.setTimeRange(range);

        var result = service.query(request);

        verify(executor, times(1)).execute(same(connection), anyList(), eq(DEFAULT_LIMIT_PLUS_ONE));
        assertEquals(2, executed.size());
        assertFalse(executed.get(0).sql().contains("PAID"));
        assertTrue(executed.get(1).sql().contains("WHERE (\"pay_status\" = 'PAID')"), executed.get(1).sql());
        assertTrue(executed.get(1).sql().contains("DATE_TRUNC('month'"));
        assertTrue(executed.get(1).sql().contains("FROM \"dw\".\"fact_order\""));
        assertEquals(List.of(LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 4, 1, 0, 0)),
                executed.get(1).parameters());
        assertEquals("MONTH", result.getTimeGrain(), "未指定粒度时沿用派生指标统计周期");
        assertEquals(List.of("dim_0", "time_bucket", "ind_1", "ind_3"),
                result.getColumns().stream().map(IndicatorQueryRespVO.Column::getKey).toList());
        assertEquals("region中文", result.getColumns().getFirst().getTitle());
        assertEquals(2, result.getRows().size());
        assertEquals(Map.of("dim_0", "east", "time_bucket", "2026-01-01", "ind_1", "10.5000", "ind_3", "10.5000"),
                result.getRows().getFirst());
        assertEquals("9007199254740993.1234", result.getRows().get(1).get("ind_3"));
        assertFalse(result.getTruncated());
        var derived = result.getCalibers().get(1);
        assertEquals("MONTH", derived.getLeaves().getFirst().getTimePeriod());
        assertEquals("pay_status = 'PAID'", derived.getLeaves().getFirst().getConditions().getFirst().getExpression());
        assertEquals("fact_order", derived.getLeaves().getFirst().getSource().getTableName());
        assertEquals("dw", derived.getLeaves().getFirst().getSource().getSchemaName());
    }

    private static final int DEFAULT_LIMIT_PLUS_ONE = IndicatorQueryServiceImpl.DEFAULT_LIMIT + 1;

    @Test
    void compositeIsComputedPerDimensionKeyWithBigDecimalAndNullOnZeroOrMissing() {
        resultsByValueColumn.put("amount", List.of(row("east", null, new BigDecimal("100.00")),
                row("north", null, new BigDecimal("5")), row("west", null, new BigDecimal("30"))));
        resultsByValueColumn.put("order_no", List.of(row("east", null, 3L), row("north", null, 0L),
                row("south", null, 4L)));

        var result = service.query(request(List.of(4L, 6L, 2L), List.of("region-uid")));

        assertEquals(2, executed.size(), "复合指标只执行其组成的基础指标");
        Map<String, Map<String, String>> byRegion = new HashMap<>();
        result.getRows().forEach(row -> byRegion.put(row.get("dim_0"), row));
        assertEquals(List.of("east", "north", "south", "west"),
                result.getRows().stream().map(row -> row.get("dim_0")).toList());
        assertEquals("3333.333333333333333333333333333333", byRegion.get("east").get("ind_4"));
        assertEquals("3.333333333333333333333333333333333", byRegion.get("east").get("ind_6"));
        assertNull(byRegion.get("north").get("ind_4"), "分母为 0 返回 null");
        assertEquals("0", byRegion.get("north").get("ind_2"));
        assertNull(byRegion.get("south").get("ind_4"), "缺少分子返回 null");
        assertNull(byRegion.get("west").get("ind_4"), "缺少分母返回 null");
        assertEquals("{gmv} / {order_cnt} * 100", result.getCalibers().getFirst().getFormula());
        assertEquals(List.of("gmv", "order_cnt"), result.getCalibers().getFirst().getLeaves().stream()
                .map(IndicatorQueryRespVO.LeafCaliber::getCode).toList());
    }

    @Test
    void limitTruncatesAndFlagsResult() {
        resultsByValueColumn.put("amount", List.of(row("a", null, BigDecimal.ONE), row("b", null, BigDecimal.TEN),
                row("c", null, BigDecimal.TWO)));
        var request = request(List.of(1L), List.of("region-uid"));
        request.setLimit(2);
        var result = service.query(request);
        verify(executor).execute(any(), anyList(), eq(3));
        assertTrue(result.getTruncated());
        assertEquals(List.of("a", "b"), result.getRows().stream().map(row -> row.get("dim_0")).toList());
    }

    @Test
    void filtersAreTypedAndBoundNeverInlined() {
        var request = request(List.of(1L), List.of("region-uid"));
        var region = new IndicatorQueryReqVO.Filter();
        region.setFieldUid("region-uid");
        region.setValue("east' OR '1'='1");
        var level = new IndicatorQueryReqVO.Filter();
        level.setFieldUid("level-uid");
        level.setValue("42");
        request.setFilters(List.of(region, level));
        service.query(request);
        assertEquals(List.of("east' OR '1'='1", 42L), executed.getFirst().parameters());
        assertFalse(executed.getFirst().sql().contains("east"));

        level.setValue("4 OR 1=1");
        assertCode(INDICATOR_QUERY_INVALID.getCode(), () -> service.query(request));
        level.setFieldUid("unknown-uid");
        assertCode(INDICATOR_QUERY_INVALID.getCode(), () -> service.query(request));
    }

    @Test
    void unresolvableOrMaliciousBusinessConditionsFailClosed() {
        var derived = rows.get(2);
        derived.setBusinessCondition("仅统计已支付订单");
        assertCode(INDICATOR_QUERY_CONDITION_INVALID.getCode(), () -> service.query(request(List.of(3L), List.of())));

        derived.setBusinessCondition("paid_only");
        var malicious = new BusinessConditionDO();
        malicious.setCode("paid_only");
        malicious.setName("恶意");
        malicious.setStatus(BusinessConditionDO.ACTIVE);
        malicious.setSourceModelId(5L);
        for (String expression : List.of("pay_status = 'PAID'; DROP TABLE fact_order",
                "order_no IN (SELECT usename FROM pg_user)", "pg_sleep(30) IS NULL", "secret_col = 1")) {
            malicious.setExpression(expression);
            when(conditionMapper.selectByCodeIgnoreCase("paid_only")).thenReturn(malicious);
            assertCode(INDICATOR_QUERY_CONDITION_INVALID.getCode(),
                    () -> service.query(request(List.of(3L), List.of())));
        }
        malicious.setExpression("pay_status = 'PAID'");
        malicious.setSourceModelId(99L);
        assertCode(INDICATOR_QUERY_CONDITION_INVALID.getCode(), () -> service.query(request(List.of(3L), List.of())));
        verify(executor, never()).execute(any(), anyList(), anyInt());
    }

    @Test
    void sourceProductAndParameterProblemsAreRejectedBeforeExecution() {
        when(materializedMapper.selectLatestSuccess(5L)).thenReturn(null);
        assertCode(INDICATOR_QUERY_SOURCE_UNAVAILABLE.getCode(), () -> service.query(request(List.of(1L), List.of())));
        when(materializedMapper.selectLatestSuccess(5L)).thenReturn(materialized());

        connection.setProductType(ProductTypeEnum.ORACLE);
        assertCode(INDICATOR_QUERY_UNSUPPORTED.getCode(), () -> service.query(request(List.of(1L), List.of())));
        connection.setProductType(ProductTypeEnum.POSTGRESQL);

        var mismatch = request(List.of(3L), List.of());
        mismatch.setTimeGrain("WEEK");
        assertCode(INDICATOR_QUERY_INVALID.getCode(), () -> service.query(mismatch));

        assertCode(INDICATOR_QUERY_INVALID.getCode(), () -> service.query(request(List.of(1L), List.of("nope"))));
        assertCode(INDICATOR_QUERY_INVALID.getCode(),
                () -> service.query(request(List.of(1L), List.of("region-uid", "region-uid"))));

        rows.getFirst().setTimeFieldUid(null);
        var timed = request(List.of(1L), List.of());
        timed.setTimeGrain("DAY");
        assertCode(INDICATOR_QUERY_INVALID.getCode(), () -> service.query(timed));

        rows.get(1).setStatus(IndicatorModelDO.DRAFT);
        assertCode(INDICATOR_QUERY_INVALID.getCode(), () -> service.query(request(List.of(2L), List.of())));
        assertCode(INDICATOR_NOT_EXISTS.getCode(), () -> service.query(request(List.of(404L), List.of())));
        verify(executor, never()).execute(any(), anyList(), anyInt());
    }

    @Test
    void compositeOverNonNumericResultIsRejected() {
        rows.get(1).setResultDataType("STRING");
        assertCode(INDICATOR_QUERY_INVALID.getCode(), () -> service.query(request(List.of(4L), List.of())));
    }

    private SafetyDynamicMaskReqDTO capturedMaskRequest() {
        var captor = ArgumentCaptor.forClass(SafetyDynamicMaskReqDTO.class);
        verify(safetySecurityApi).maskRows(captor.capture());
        return captor.getValue();
    }

    private static SafetyDynamicMaskReqDTO.Column column(SafetyDynamicMaskReqDTO request, String name) {
        return request.getColumns().stream().filter(item -> item.getOutputName().equals(name)).findFirst()
                .orElseThrow();
    }

    @Test
    void dimensionColumnIsMaskedByGovernanceAndMarkedForFrontend() {
        resultsByValueColumn.put("amount", List.of(row("13800001234", null, BigDecimal.ONE)));
        doAnswer(invocation -> {
            SafetyDynamicMaskReqDTO request = invocation.getArgument(0);
            List<Map<String, Object>> masked = new ArrayList<>();
            request.getRows().forEach(row -> {
                Map<String, Object> copy = new LinkedHashMap<>(row);
                copy.put("dim_0", "138****1234");
                masked.add(copy);
            });
            var column = new SafetyDynamicMaskRespDTO.MaskedColumn();
            column.setOutputName("dim_0");
            column.setReason(SafetyDynamicMaskRespDTO.REASON_RULE);
            column.setCategoryNames(List.of("手机号"));
            var response = new SafetyDynamicMaskRespDTO();
            response.setEnabled(true);
            response.setRows(masked);
            response.setMaskedColumns(List.of(column));
            return CommonResult.success(response);
        }).when(safetySecurityApi).maskRows(any());

        var result = service.query(request(List.of(1L), List.of("region-uid")));

        assertEquals("138****1234", result.getRows().getFirst().get("dim_0"));
        assertEquals("1", result.getRows().getFirst().get("ind_1"));
        assertEquals("dim_0", result.getMaskedColumns().getFirst().getName());
        assertEquals("RULE", result.getMaskedColumns().getFirst().getReason());
        assertEquals(List.of("手机号"), result.getMaskedColumns().getFirst().getCategoryNames());
        var request = capturedMaskRequest();
        assertEquals("INDICATOR_QUERY", request.getScene());
        assertEquals("dw.fact_order", request.getResourceName());
        assertEquals(List.of(SafetyDynamicMaskReqDTO.Source.of(50L, "dw", "fact_order", "region")),
                column(request, "dim_0").getSources());
        assertTrue(column(request, "dim_0").getTraceable());
        assertEquals("13800001234", request.getRows().getFirst().get("dim_0"));
    }

    @Test
    void aggregatesThatHideRawValuesHaveNoSourcesButMinMaxCarryTheirColumn() {
        var maxAmount = atomic(8L, "max_amount", "MAX", "amount-uid");
        rows.add(maxAmount);
        var withMax = indicator(9L, "max_ratio", IndicatorModelDO.COMPOSITE);
        withMax.setFormula("{max_amount} / {order_cnt}");
        rows.add(withMax);
        resultsByValueColumn.put("amount", List.of(row("east", LocalDate.of(2026, 1, 1), BigDecimal.TEN)));
        resultsByValueColumn.put("order_no", List.of(row("east", LocalDate.of(2026, 1, 1), 2L)));
        var request = request(List.of(1L, 2L, 4L, 8L, 9L), List.of("region-uid"));
        request.setTimeGrain("MONTH");

        service.query(request);

        var mask = capturedMaskRequest();
        assertEquals(List.of(), column(mask, "ind_1").getSources(), "SUM 不暴露原值");
        assertEquals(List.of(), column(mask, "ind_2").getSources(), "COUNT 不暴露原值");
        assertEquals(List.of(), column(mask, "ind_4").getSources(), "只由 SUM/COUNT 组成的复合指标不暴露原值");
        var amount = SafetyDynamicMaskReqDTO.Source.of(50L, "dw", "fact_order", "amount");
        assertEquals(List.of(amount), column(mask, "ind_8").getSources(), "MAX 原样暴露被聚合列");
        assertEquals(List.of(amount), column(mask, "ind_9").getSources(), "引用 MAX 的复合指标保守提交来源列");
        assertEquals(List.of(SafetyDynamicMaskReqDTO.Source.of(50L, "dw", "fact_order", "pay_time")),
                column(mask, "time_bucket").getSources());
        assertTrue(mask.getColumns().stream().allMatch(SafetyDynamicMaskReqDTO.Column::getTraceable));
    }

    @Test
    void maskingFailureOrIncompleteResultNeverReturnsOriginalValues() {
        resultsByValueColumn.put("amount", List.of(row("13800001234", null, BigDecimal.ONE)));
        doThrow(new IllegalStateException("governance down")).when(safetySecurityApi).maskRows(any());
        var error = assertThrows(ServiceException.class,
                () -> service.query(request(List.of(1L), List.of("region-uid"))));
        assertEquals(INDICATOR_QUERY_MASK_FAILED.getCode(), error.getCode());
        assertTrue(error.getMessage().contains("动态脱敏服务暂不可用"), error.getMessage());
        assertFalse(error.getMessage().contains("13800001234"));

        doReturn(CommonResult.error(500, "unavailable")).when(safetySecurityApi).maskRows(any());
        assertCode(INDICATOR_QUERY_MASK_FAILED.getCode(),
                () -> service.query(request(List.of(1L), List.of("region-uid"))));

        var short_ = new SafetyDynamicMaskRespDTO();
        short_.setRows(List.of());
        doReturn(CommonResult.success(short_)).when(safetySecurityApi).maskRows(any());
        assertCode(INDICATOR_QUERY_MASK_FAILED.getCode(),
                () -> service.query(request(List.of(1L), List.of("region-uid"))));

        var dropped = new SafetyDynamicMaskRespDTO();
        dropped.setRows(List.of(new HashMap<>(Map.of("ind_1", "1"))));
        doReturn(CommonResult.success(dropped)).when(safetySecurityApi).maskRows(any());
        assertCode(INDICATOR_QUERY_MASK_FAILED.getCode(),
                () -> service.query(request(List.of(1L), List.of("region-uid"))));
    }

    @Test
    void emptyResultSkipsMaskingCall() {
        var result = service.query(request(List.of(1L), List.of("region-uid")));
        assertTrue(result.getRows().isEmpty());
        assertEquals(List.of(), result.getMaskedColumns());
        verify(safetySecurityApi, never()).maskRows(any());
    }

    @Test
    void metaReturnsCommonMaterializedFieldsAndTimeCapability() {
        var meta = new IndicatorQueryMetaReqVO();
        meta.setIndicatorIds(List.of(4L, 3L));
        var result = service.meta(meta);
        assertEquals(6, result.getDimensions().size());
        assertTrue(result.getTimeSupported());
        assertEquals(List.of("MONTH"), result.getDerivedPeriods());
        verify(executor, never()).execute(any(), anyList(), anyInt());
    }

    @Test
    void keysSortTimeFirstThenDimensionsNumericallyWithNullsLast() {
        var keys = new ArrayList<List<String>>();
        keys.add(Arrays.asList("2026-02-01", "a"));
        keys.add(Arrays.asList("2026-01-01", null));
        keys.add(Arrays.asList("2026-01-01", "10"));
        keys.add(Arrays.asList("2026-01-01", "9"));
        keys.sort(IndicatorQueryServiceImpl::compareKeys);
        assertEquals(List.of(Arrays.asList("2026-01-01", "9"), Arrays.asList("2026-01-01", "10"),
                Arrays.asList("2026-01-01", null), Arrays.asList("2026-02-01", "a")), keys);
    }
}
