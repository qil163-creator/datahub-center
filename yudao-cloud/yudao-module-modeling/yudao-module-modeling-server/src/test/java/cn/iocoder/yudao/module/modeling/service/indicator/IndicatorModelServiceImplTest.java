package cn.iocoder.yudao.module.modeling.service.indicator;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.*;
import cn.iocoder.yudao.module.modeling.dal.dataobject.indicator.IndicatorModelDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.*;
import cn.iocoder.yudao.module.modeling.dal.mysql.indicator.IndicatorModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.*;
import cn.iocoder.yudao.module.modeling.service.model.ModelHierarchyResolver;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.*;
import java.util.List;
import java.util.function.Consumer;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IndicatorModelServiceImplTest {
    @Mock private IndicatorModelMapper mapper;
    @Mock private LogicModelMapper modelMapper;
    @Mock private cn.iocoder.yudao.module.modeling.service.model.LogicModelColumnService columns;
    @Mock private ModelHierarchyResolver hierarchy;
    @Mock private AdminUserApi userApi;
    @Mock private SecurityFrameworkService security;
    @Mock private TransactionTemplate transactions;
    @Mock private cn.iocoder.yudao.module.modeling.dal.mysql.businessentity.BusinessEntityMapper entityMapper;
    @InjectMocks private IndicatorModelServiceImpl service;

    @BeforeEach
    void transactionCallbacks() {
        lenient().doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(new SimpleTransactionStatus());
        }).when(transactions).execute(any());
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(new SimpleTransactionStatus());
            return null;
        }).when(transactions).executeWithoutResult(any());
    }

    @Test
    void atomicBindsFrozenFieldsAndChecksOwnerBeforeOpeningLockedTransaction() {
        source("DECIMAL");
        when(userApi.validateUserList(List.of(9L))).thenReturn(CommonResult.success(true));
        var request = atomic();
        request.setOwnerUserId(9L);
        doAnswer(invocation -> {
            IndicatorModelDO row = invocation.getArgument(0);
            row.setId(20L);
            return 1;
        }).when(mapper).insert(any(IndicatorModelDO.class));

        assertEquals(20L, service.create(request));

        var saved = ArgumentCaptor.forClass(IndicatorModelDO.class);
        verify(mapper).insert(saved.capture());
        assertEquals("TOTAL_AMOUNT", saved.getValue().getCode());
        assertEquals("amount-uid", saved.getValue().getValueFieldUid());
        assertEquals("DRAFT", saved.getValue().getStatus());
        assertEquals(0L, saved.getValue().getRevision());
        var order = inOrder(userApi, mapper);
        order.verify(userApi).validateUserList(List.of(9L));
        order.verify(mapper).lockReferences();
    }

    @Test
    void unpublishedVersionAndMissingStableFieldAreRejected() {
        source("DECIMAL");
        var model = modelMapper.selectById(5L);
        model.setStatus("DRAFT");
        assertCode(INDICATOR_SOURCE_INVALID.getCode(), () -> service.create(atomic()));
        model.setStatus("PUBLISHED");
        var request = atomic();
        request.setValueFieldUid("missing");
        assertCode(INDICATOR_FIELD_INVALID.getCode(), () -> service.create(request));
        verify(mapper, never()).insert(any(IndicatorModelDO.class));
    }

    @Test
    void nonNumericSumAndNonDateTimeDimensionAreRejected() {
        source("VARCHAR");
        assertCode(INDICATOR_DEFINITION_INVALID.getCode(), () -> service.create(atomic()));
        var request = atomic();
        request.setAggregation("COUNT");
        request.setResultDataType("INTEGER");
        request.setTimeFieldUid("amount-uid");
        assertCode(INDICATOR_DEFINITION_INVALID.getCode(), () -> service.create(request));
    }

    @Test
    void derivedNeedsAnActiveAtomicWithTimeFieldAndSameDataDomain() {
        source("DECIMAL");
        var base = base("DRAFT");
        when(mapper.selectForUpdate(10L)).thenReturn(base);
        assertCode(INDICATOR_BASE_INVALID.getCode(), () -> service.create(derived()));
        base.setStatus("ACTIVE");
        base.setDataDomainId(99L);
        assertCode(INDICATOR_DEFINITION_INVALID.getCode(), () -> service.create(derived()));
        base.setDataDomainId(1L);
        base.setSubjectDomainId(2L); base.setSourceModelId(5L); base.setValueFieldUid("amount-uid"); base.setAggregation("SUM");
        base.setTimeFieldUid(null);
        assertCode(INDICATOR_DEFINITION_INVALID.getCode(), () -> service.create(derived()));
        verify(mapper, never()).insert(any(IndicatorModelDO.class));
    }

    @Test
    void derivedInheritsUnitAndResultTypeWithoutInventingASecondSource() {
        source("DECIMAL");
        when(mapper.selectForUpdate(10L)).thenReturn(base("ACTIVE"));
        var request = derived();
        request.setSourceModelId(100L);
        request.setResultDataType("STRING");
        service.create(request);
        var saved = ArgumentCaptor.forClass(IndicatorModelDO.class);
        verify(mapper).insert(saved.capture());
        assertNull(saved.getValue().getSourceModelId());
        assertEquals("DECIMAL", saved.getValue().getResultDataType());
        assertEquals("元", saved.getValue().getUnit());
        assertEquals(7L, saved.getValue().getEntityId());
        assertEquals("1", saved.getValue().getDataFreshness());
    }

    @Test
    void atomicCanSaveEnabledAndDatabaseNumericTypesAtomicallyWithoutWeakeningAggregations() {
        source("DECIMAL");
        var request = atomic(); request.setStatus("ACTIVE"); request.setResultDataType("BIGINT");
        service.create(request);
        var saved = ArgumentCaptor.forClass(IndicatorModelDO.class); verify(mapper).insert(saved.capture());
        assertEquals("ACTIVE", saved.getValue().getStatus()); assertEquals("BIGINT", saved.getValue().getResultDataType());
        request.setResultDataType("STRING"); assertCode(INDICATOR_DEFINITION_INVALID.getCode(), () -> service.create(request));
        request.setAggregation("COUNT"); request.setResultDataType("DECIMAL");
        assertCode(INDICATOR_DEFINITION_INVALID.getCode(), () -> service.create(request));
    }

    @Test
    void entitySourceScopeAndFrozenScopeCannotBeForged() {
        source("DECIMAL");
        var request = atomic(); request.setDataFreshness("2");
        assertCode(INDICATOR_DEFINITION_INVALID.getCode(), () -> service.create(request));
        request.setDataFreshness("1"); request.setSubjectDomainId(99L);
        assertCode(INDICATOR_DEFINITION_INVALID.getCode(), () -> service.create(request));
        request.setSubjectDomainId(2L);
        modelMapper.selectById(5L).setEnabled(0);
        assertCode(INDICATOR_SOURCE_INVALID.getCode(), () -> service.create(request));
        verify(mapper, never()).insert(any(IndicatorModelDO.class));
    }

    @Test
    void statisticalTimeAcceptsTheDateAndTimeTypesProvenByTheReferenceForm() {
        source("DECIMAL");
        var fields = sourceFields("DECIMAL"); fields.get(1).setDataType(" TIME(6) ");
        when(columns.listByModelId(5L)).thenReturn(fields);
        service.create(atomic()); verify(mapper).insert(any(IndicatorModelDO.class));
    }

    @Test
    void referencedAtomicCannotBeDisabledAndStaleRevisionCannotOverwrite() {
        var row = base("ACTIVE");
        when(mapper.selectForUpdate(10L)).thenReturn(row);
        var request = new IndicatorModelStatusReqVO();
        request.setId(10L);
        request.setExpectedRevision(2L);
        request.setStatus("DRAFT");
        assertCode(INDICATOR_REVISION_CONFLICT.getCode(), () -> service.updateStatus(request));
        request.setExpectedRevision(0L);
        when(mapper.countReferences(10L)).thenReturn(1L);
        assertCode(INDICATOR_REFERENCED.getCode(), () -> service.updateStatus(request));
        verify(mapper, never()).updateById(any(IndicatorModelDO.class));
    }

    @Test
    void activeDefinitionsMustBeDisabledBeforeEditOrDelete() {
        when(mapper.selectForUpdate(10L)).thenReturn(base("ACTIVE"));
        var request = atomic();
        request.setId(10L);
        request.setExpectedRevision(0L);
        assertCode(INDICATOR_NOT_DRAFT.getCode(), () -> service.update(request));
        assertCode(INDICATOR_NOT_DRAFT.getCode(), () -> service.delete(10L, 0L));
        verify(mapper, never()).updateById(any(IndicatorModelDO.class));
        verify(mapper, never()).deleteById(anyLong());
    }

    @Test
    void createCannotChooseModelFieldsWithoutResourcePermission() {
        assertCode(INDICATOR_SOURCE_FORBIDDEN.getCode(), () -> service.create(atomic()));
        verifyNoInteractions(modelMapper, columns);
    }

    @Test
    void detailShowsSourceCodeAuditIdentityAndRealDerivedReferenceCount() {
        var row = base("ACTIVE");
        row.setSourceModelId(5L);
        row.setValueFieldUid("amount-uid");
        row.setAggregation("SUM");
        row.setCreator("migration:0171");
        when(mapper.selectById(10L)).thenReturn(row);
        var model = new LogicModelDO(); model.setId(5L); model.setName("订单事实表"); model.setCode("DWD_ORDER");
        when(modelMapper.selectBatchIds(List.of(5L))).thenReturn(List.of(model));
        when(columns.listByModelId(5L)).thenReturn(sourceFields("DECIMAL"));
        var count = new IndicatorModelMapper.ReferenceCount(); count.setBaseIndicatorId(10L); count.setTotal(3L);
        when(mapper.selectDerivedReferenceCounts(List.of(10L))).thenReturn(List.of(count));
        var result = service.get(10L);
        assertEquals("DWD_ORDER", result.getSourceModelCode());
        assertEquals(3L, result.getDerivedIndicatorCount());
        assertEquals("migration:0171", result.getCreator());
        assertEquals("SUM(amount)", result.getExpression());
        verifyNoInteractions(userApi);
    }

    @Test
    void activeAtomicWithRetainedUidButChangedCurrentTypeCannotBeConsumed() {
        source("DECIMAL");
        var atomic = base("ACTIVE");
        assertTrue(service.isAtomicSourceUsable(atomic));
        when(columns.listByModelId(5L)).thenReturn(sourceFields("VARCHAR"));
        assertFalse(service.isAtomicSourceUsable(atomic));
        var timeChanged = sourceFields("DECIMAL"); timeChanged.get(1).setDataType("VARCHAR");
        when(columns.listByModelId(5L)).thenReturn(timeChanged);
        assertFalse(service.isAtomicSourceUsable(atomic));
    }

    private void source(String valueType) {
        var model = new LogicModelDO();
        model.setId(5L);
        model.setEnabled(1);
        model.setStatus("PUBLISHED");
        model.setDataDomainId(1L); model.setSubjectDomainId(2L); model.setEntityId(7L);
        model.setDataTimeliness("1"); model.setTableType("DETAIL");
        var entity = new cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.BusinessEntityDO();
        entity.setId(7L); entity.setDataDomainId(1L); entity.setSubjectDomainId(2L); entity.setStatus(0); entity.setName("支付");
        lenient().when(entityMapper.selectById(7L)).thenReturn(entity);
        lenient().when(modelMapper.selectById(5L)).thenReturn(model);
        lenient().when(columns.listByModelId(5L)).thenReturn(sourceFields(valueType));
        lenient().when(security.hasPermission("modeling:model:query")).thenReturn(true);
    }

    private List<cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem> sourceFields(String type) {
        return cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseArray("[{\"fieldUid\":\"amount-uid\",\"fieldName\":\"amount\",\"dataType\":\"" + type
                + "\"},{\"fieldUid\":\"time-uid\",\"fieldName\":\"created_at\",\"dataType\":\"TIMESTAMP\"}]",
                cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem.class);
    }

    private IndicatorModelDO base(String status) {
        var base = new IndicatorModelDO();
        base.setId(10L);
        base.setIndicatorType("ATOMIC");
        base.setStatus(status);
        base.setRevision(0L);
        base.setDataDomainId(1L);
        base.setSubjectDomainId(2L); base.setSourceModelId(5L); base.setValueFieldUid("amount-uid"); base.setAggregation("SUM");
        base.setTimeFieldUid("time-uid");
        base.setUnit("元");
        base.setResultDataType("DECIMAL");
        base.setEntityId(7L); base.setDataFreshness("1");
        return base;
    }

    private IndicatorModelSaveReqVO atomic() {
        var request = new IndicatorModelSaveReqVO();
        request.setCode("TOTAL_AMOUNT");
        request.setName("总金额");
        request.setIndicatorType("ATOMIC");
        request.setDataDomainId(1L);
        request.setSubjectDomainId(2L); request.setEntityId(7L); request.setDataFreshness("1");
        request.setSourceModelId(5L);
        request.setValueFieldUid("amount-uid");
        request.setTimeFieldUid("time-uid");
        request.setAggregation("SUM");
        request.setResultDataType("DECIMAL");
        return request;
    }

    private IndicatorModelSaveReqVO derived() {
        var request = new IndicatorModelSaveReqVO();
        request.setCode("day_amount");
        request.setName("每日金额");
        request.setIndicatorType("DERIVED");
        request.setDataDomainId(1L);
        request.setBaseIndicatorId(10L);
        request.setTimePeriod("DAY");
        request.setBusinessCondition("已完成订单");
        return request;
    }

    private void assertCode(int expected, Runnable operation) {
        assertEquals(expected, assertThrows(ServiceException.class, operation::run).getCode());
    }
}

