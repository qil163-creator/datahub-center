package cn.iocoder.yudao.module.modeling.service.indicator;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.*;
import cn.iocoder.yudao.module.modeling.dal.dataobject.indicator.IndicatorModelDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.businessentity.BusinessEntityMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.indicator.IndicatorModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper;
import cn.iocoder.yudao.module.modeling.service.model.LogicModelColumnService;
import cn.iocoder.yudao.module.modeling.service.model.ModelHierarchyResolver;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.*;

import java.util.*;
import java.util.function.Consumer;

import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 复合指标保存规则：公式白名单、引用状态与数据域、DAG 环检测、引用保护。 */
@ExtendWith(MockitoExtension.class)
class IndicatorCompositeServiceTest {

    @Mock private IndicatorModelMapper mapper;
    @Mock private LogicModelMapper modelMapper;
    @Mock private LogicModelColumnService columns;
    @Mock private ModelHierarchyResolver hierarchy;
    @Mock private AdminUserApi userApi;
    @Mock private SecurityFrameworkService security;
    @Mock private TransactionTemplate transactions;
    @Mock private BusinessEntityMapper entityMapper;
    @InjectMocks private IndicatorModelServiceImpl service;

    private final List<IndicatorModelDO> rows = new ArrayList<>();

    @BeforeEach
    void setup() {
        var resolver = new IndicatorDependencyResolver();
        ReflectionTestUtils.setField(resolver, "mapper", mapper);
        ReflectionTestUtils.setField(service, "dependencies", resolver);
        lenient().doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(new SimpleTransactionStatus());
        }).when(transactions).execute(any());
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(new SimpleTransactionStatus());
            return null;
        }).when(transactions).executeWithoutResult(any());
        lenient().when(mapper.selectListByCodesIgnoreCase(anyCollection())).thenAnswer(invocation -> {
            Collection<String> codes = invocation.getArgument(0);
            return rows.stream().filter(row -> codes.contains(row.getCode().toLowerCase(Locale.ROOT))).toList();
        });
        lenient().when(mapper.selectById(anyLong())).thenAnswer(invocation -> rows.stream()
                .filter(row -> row.getId().equals(invocation.getArgument(0))).findFirst().orElse(null));
        lenient().when(mapper.selectForUpdate(anyLong())).thenAnswer(invocation -> rows.stream()
                .filter(row -> row.getId().equals(invocation.getArgument(0))).findFirst().orElse(null));
        rows.add(indicator(1L, "gmv", IndicatorModelDO.ATOMIC, IndicatorModelDO.ACTIVE, 7L, null));
        rows.add(indicator(2L, "order_cnt", IndicatorModelDO.ATOMIC, IndicatorModelDO.ACTIVE, 7L, null));
        rows.add(indicator(3L, "draft_metric", IndicatorModelDO.ATOMIC, IndicatorModelDO.DRAFT, 7L, null));
        rows.add(indicator(4L, "other_domain", IndicatorModelDO.ATOMIC, IndicatorModelDO.ACTIVE, 8L, null));
    }

    private static IndicatorModelDO indicator(Long id, String code, String type, String status, Long domain,
            String formula) {
        var row = new IndicatorModelDO();
        row.setId(id);
        row.setCode(code);
        row.setName(code + "名称");
        row.setIndicatorType(type);
        row.setStatus(status);
        row.setDataDomainId(domain);
        row.setFormula(formula);
        row.setRevision(0L);
        row.setResultDataType("DECIMAL");
        return row;
    }

    private static IndicatorModelSaveReqVO composite(String code, String formula) {
        var request = new IndicatorModelSaveReqVO();
        request.setCode(code);
        request.setName("客单价");
        request.setIndicatorType(IndicatorModelDO.COMPOSITE);
        request.setDataDomainId(7L);
        request.setFormula(formula);
        request.setUnit("元");
        request.setSourceModelId(5L);
        request.setValueFieldUid("leak");
        request.setResultDataType("BIGINT");
        return request;
    }

    private static void assertCode(int code, Executable executable) {
        var error = assertThrows(ServiceException.class, executable::run);
        assertEquals(code, error.getCode(), error.getMessage());
    }

    private interface Executable {
        void run();
    }

    @Test
    void compositeIsSavedWithCanonicalReferencesAndFixedDecimalResult() {
        doAnswer(invocation -> {
            IndicatorModelDO row = invocation.getArgument(0);
            row.setId(30L);
            return 1;
        }).when(mapper).insert(any(IndicatorModelDO.class));

        assertEquals(30L, service.create(composite("avg_price", " {GMV} / {Order_Cnt} * 100 ")));

        var saved = ArgumentCaptor.forClass(IndicatorModelDO.class);
        verify(mapper).insert(saved.capture());
        assertEquals("{gmv} / {order_cnt} * 100", saved.getValue().getFormula());
        assertEquals("DECIMAL", saved.getValue().getResultDataType());
        assertNull(saved.getValue().getSourceModelId());
        assertNull(saved.getValue().getValueFieldUid());
        assertNull(saved.getValue().getAggregation());
        assertEquals("元", saved.getValue().getUnit());
        verify(mapper).lockReferences();
    }

    @Test
    void referencesMustExistBeActiveAndShareDataDomain() {
        assertCode(INDICATOR_FORMULA_REFERENCE_INVALID.getCode(),
                () -> service.create(composite("c1", "{gmv} / {missing}")));
        assertCode(INDICATOR_FORMULA_REFERENCE_INVALID.getCode(),
                () -> service.create(composite("c1", "{gmv} / {draft_metric}")));
        assertCode(INDICATOR_FORMULA_REFERENCE_INVALID.getCode(),
                () -> service.create(composite("c1", "{gmv} / {other_domain}")));
        assertCode(INDICATOR_FORMULA_INVALID.getCode(), () -> service.create(composite("c1", "{gmv} % 2")));
        assertCode(INDICATOR_FORMULA_INVALID.getCode(), () -> service.create(composite("c1", null)));
        verify(mapper, never()).insert(any(IndicatorModelDO.class));
    }

    @Test
    void selfReferenceAndIndirectCyclesAreRejected() {
        assertCode(INDICATOR_FORMULA_CYCLE.getCode(), () -> service.create(composite("c1", "{c1} + {gmv}")));
        assertCode(INDICATOR_FORMULA_CYCLE.getCode(), () -> service.create(composite("C1", "{gmv} + {c1}")));
        // 存量数据中已有 c2 → c3 → c1，保存 c1 引用 c2 时必须发现环。
        rows.add(indicator(12L, "c2", IndicatorModelDO.COMPOSITE, IndicatorModelDO.ACTIVE, 7L, "{c3} * 2"));
        rows.add(indicator(13L, "c3", IndicatorModelDO.COMPOSITE, IndicatorModelDO.ACTIVE, 7L, "{c1} + {gmv}"));
        var error = assertThrows(ServiceException.class, () -> service.create(composite("c1", "{c2} / 3")));
        assertEquals(INDICATOR_FORMULA_CYCLE.getCode(), error.getCode());
        assertTrue(error.getMessage().contains("c1 → c2 → c3 → c1"), error.getMessage());
        verify(mapper, never()).insert(any(IndicatorModelDO.class));
    }

    @Test
    void compositeMayReferenceActiveCompositeAsDag() {
        rows.add(indicator(12L, "avg_price", IndicatorModelDO.COMPOSITE, IndicatorModelDO.ACTIVE, 7L,
                "{gmv} / {order_cnt}"));
        var request = new IndicatorFormulaValidateReqVO();
        request.setDataDomainId(7L);
        request.setCode("avg_price_pct");
        request.setFormula("{avg_price} * 100 + {gmv} - {gmv}");
        var result = service.validateFormula(request);
        assertTrue(result.getValid(), result.getMessage());
        assertEquals(List.of("avg_price", "gmv"), result.getReferences());
        assertEquals(2, result.getDependencies().size());
        assertEquals(List.of("gmv", "order_cnt"), result.getDependencies().getFirst().getChildren().stream()
                .map(IndicatorDependencyNodeRespVO::getCode).toList());
        verify(mapper, never()).insert(any(IndicatorModelDO.class));
    }

    @Test
    void validateFormulaReportsProblemsWithoutThrowing() {
        var request = new IndicatorFormulaValidateReqVO();
        request.setDataDomainId(7L);
        request.setCode("x");
        request.setFormula("{gmv} / ");
        var result = service.validateFormula(request);
        assertFalse(result.getValid());
        assertTrue(result.getMessage().contains("公式"), result.getMessage());
        request.setFormula("{x} + 1");
        assertFalse(service.validateFormula(request).getValid());
    }

    @Test
    void indicatorReferencedByCompositeCannotBeDeactivatedOrDeleted() {
        when(mapper.countCompositeReferences(1L)).thenReturn(1L);
        var status = new IndicatorModelStatusReqVO();
        status.setId(1L);
        status.setExpectedRevision(0L);
        status.setStatus(IndicatorModelDO.DRAFT);
        assertCode(INDICATOR_COMPOSITE_REFERENCED.getCode(), () -> service.updateStatus(status));
        rows.getFirst().setStatus(IndicatorModelDO.DRAFT);
        assertCode(INDICATOR_COMPOSITE_REFERENCED.getCode(), () -> service.delete(1L, 0L));
        verify(mapper, never()).updateById(any(IndicatorModelDO.class));
        verify(mapper, never()).deleteById(anyLong());
    }

    @Test
    void activatingCompositeRevalidatesReferences() {
        rows.add(indicator(20L, "ratio", IndicatorModelDO.COMPOSITE, IndicatorModelDO.DRAFT, 7L,
                "{gmv} / {draft_metric}"));
        var status = new IndicatorModelStatusReqVO();
        status.setId(20L);
        status.setExpectedRevision(0L);
        status.setStatus(IndicatorModelDO.ACTIVE);
        assertCode(INDICATOR_FORMULA_REFERENCE_INVALID.getCode(), () -> service.updateStatus(status));
        rows.get(2).setStatus(IndicatorModelDO.ACTIVE);
        service.updateStatus(status);
        verify(mapper).updateById(argThat((IndicatorModelDO row) -> IndicatorModelDO.ACTIVE.equals(row.getStatus())
                && "DECIMAL".equals(row.getResultDataType())));
    }
}
