package cn.iocoder.yudao.module.modeling.service.businesscondition;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.modeling.controller.admin.businesscondition.vo.*;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businesscondition.BusinessConditionDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.*;
import cn.iocoder.yudao.module.modeling.dal.mysql.businesscondition.BusinessConditionMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.*;
import cn.iocoder.yudao.module.modeling.service.indicator.IndicatorModelService;
import cn.iocoder.yudao.module.modeling.service.model.ModelHierarchyResolver;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.function.Consumer;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BusinessConditionServiceImplTest {
    @Mock private BusinessConditionMapper mapper;
    @Mock private LogicModelMapper modelMapper;
    @Mock private IndicatorModelService indicatorService;
    @Mock private ModelHierarchyResolver hierarchy;
    @Mock private AdminUserApi userApi;
    @Mock private SecurityFrameworkService security;
    @Mock private TransactionTemplate transactions;
    @Mock private cn.iocoder.yudao.module.modeling.dal.mysql.businessentity.BusinessEntityMapper entityMapper;
    @InjectMocks private BusinessConditionServiceImpl service;

    @BeforeEach
    void transactions() {
        lenient().doAnswer(i -> ((TransactionCallback<?>) i.getArgument(0)).doInTransaction(new SimpleTransactionStatus()))
                .when(transactions).execute(any());
        lenient().doAnswer(i -> { Consumer<TransactionStatus> c=i.getArgument(0); c.accept(new SimpleTransactionStatus()); return null; })
                .when(transactions).executeWithoutResult(any());
        lenient().when(security.hasPermission("modeling:model:query")).thenReturn(true);
        var source = new LogicModelDO(); source.setId(5L); source.setDataDomainId(1L);
        lenient().when(modelMapper.selectById(5L)).thenReturn(source);
    }

    @Test
    void savesDefinitionAndChecksOwnerBeforeLockedTransaction() {
        var request=request(); request.setOwnerUserId(9L);
        when(userApi.validateUserList(List.of(9L))).thenReturn(CommonResult.success(true));
        doAnswer(i -> { ((BusinessConditionDO)i.getArgument(0)).setId(21L); return 1; }).when(mapper).insert(any(BusinessConditionDO.class));
        assertEquals(21L,service.create(request));
        var saved=ArgumentCaptor.forClass(BusinessConditionDO.class);
        verify(mapper).insert(saved.capture());
        assertEquals("VALID_ORDER",saved.getValue().getCode());
        assertEquals("status = 'SUCCESS'",saved.getValue().getExpression());
        assertEquals("DRAFT",saved.getValue().getStatus());
        assertEquals(0L,saved.getValue().getRevision());
        verify(indicatorService).validateScopedSource(5L,1L,2L,7L,"1");
        var order=inOrder(userApi,mapper);order.verify(userApi).validateUserList(List.of(9L));order.verify(mapper).lockReferences();
    }

    @Test
    void unsupportedConfigurationsAndBlankDefinitionsAreRejected() {
        var request=request();request.setConfigType("COMPOSITE");
        assertCode(BUSINESS_CONDITION_UNSUPPORTED.getCode(),()->service.create(request));
        request.setConfigType("BASIC");request.setLogicMode("SEGMENTED");
        assertCode(BUSINESS_CONDITION_UNSUPPORTED.getCode(),()->service.create(request));
        request.setLogicMode("UNIFIED");request.setExpression(" ");
        assertCode(BUSINESS_CONDITION_EXPRESSION_INVALID.getCode(),()->service.create(request));
        verify(mapper,never()).insert(any(BusinessConditionDO.class));
    }

    @Test
    void sourcePermissionAndInvalidSnapshotPreventWrites() {
        when(security.hasPermission("modeling:model:query")).thenReturn(false);
        assertCode(INDICATOR_SOURCE_FORBIDDEN.getCode(),()->service.create(request()));
        verify(mapper,never()).lockReferences();
        when(security.hasPermission("modeling:model:query")).thenReturn(true);
        doThrow(new ServiceException(INDICATOR_SOURCE_INVALID)).when(indicatorService).validateScopedSource(5L,1L,2L,7L,"1");
        assertCode(INDICATOR_SOURCE_INVALID.getCode(),()->service.create(request()));
        verify(mapper,never()).insert(any(BusinessConditionDO.class));
    }

    @Test
    void staleRequestsAndActiveEditsCannotOverwrite() {
        var row=row();when(mapper.selectForUpdate(21L)).thenReturn(row);
        var request=request();request.setId(21L);request.setExpectedRevision(5L);
        assertCode(BUSINESS_CONDITION_REVISION_CONFLICT.getCode(),()->service.update(request));
        request.setExpectedRevision(0L);row.setStatus("ACTIVE");
        assertCode(BUSINESS_CONDITION_NOT_DRAFT.getCode(),()->service.update(request));
        assertCode(BUSINESS_CONDITION_NOT_DRAFT.getCode(),()->service.delete(21L,0L));
        verify(mapper,never()).updateById(any(BusinessConditionDO.class));
        verify(mapper,never()).deleteById(anyLong());
    }

    @Test
    void clientCannotAssignASourceModelToAnotherDataDomain() {
        var request = request(); request.setDataDomainId(99L);
        assertCode(BUSINESS_CONDITION_SOURCE_DOMAIN_INVALID.getCode(), () -> service.create(request));
        verify(mapper, never()).insert(any(BusinessConditionDO.class));
    }

    @Test
    void activationRevalidatesSnapshotAndAdvancesRevisionWithoutUserRpc() {
        var row=row();row.setUpdater("old");when(mapper.selectForUpdate(21L)).thenReturn(row);
        var status=new BusinessConditionStatusReqVO();status.setId(21L);status.setExpectedRevision(0L);status.setStatus("ACTIVE");
        service.updateStatus(status);
        verify(indicatorService).validateScopedSource(5L,1L,2L,7L,"1");verifyNoInteractions(userApi);
        assertEquals(1L,row.getRevision());assertEquals("ACTIVE",row.getStatus());assertNull(row.getUpdater());
    }

    @Test
    void detailEnrichesSourceAndAuditUsersWithoutInventingDerivedReferences() {
        var row=row();row.setOwnerUserId(9L);row.setCreator("9");row.setUpdater("migration:0185");
        when(mapper.selectById(21L)).thenReturn(row);
        var model=new LogicModelDO();model.setId(5L);model.setName("订单事实表");model.setCode("DWD_ORDER");
        when(modelMapper.selectBatchIds(List.of(5L))).thenReturn(List.of(model));
        var user=new AdminUserRespDTO();user.setId(9L);user.setNickname("负责人");user.setMobile("脱敏测试联系电话");
        when(userApi.getUserMap(Set.of(9L))).thenReturn(Map.of(9L,user));
        var result=service.get(21L);
        assertEquals("DWD_ORDER",result.getSourceModelCode());
        assertEquals("负责人",result.getCreatorName());assertEquals("9",result.getCreator());
        assertNull(result.getUpdaterName());assertEquals(0L,result.getDerivedIndicatorCount());
    }

    @Test
    void createAndDraftUpdatePersistTheRequestedStatusTogetherWithTheDefinition() {
        var request=request(); request.setStatus("ACTIVE");
        when(indicatorService.validateScopedSource(5L,1L,2L,7L,"1")).thenReturn("支付");
        service.create(request);
        var created=ArgumentCaptor.forClass(BusinessConditionDO.class); verify(mapper).insert(created.capture());
        assertEquals("ACTIVE",created.getValue().getStatus()); assertEquals("支付",created.getValue().getBusinessEntity());
        when(mapper.selectForUpdate(21L)).thenReturn(row()); request.setId(21L); request.setExpectedRevision(0L);
        service.update(request);
        var updated=ArgumentCaptor.forClass(BusinessConditionDO.class); verify(mapper).updateById(updated.capture());
        assertEquals("ACTIVE",updated.getValue().getStatus()); assertEquals(1L,updated.getValue().getRevision());
    }

    private BusinessConditionSaveReqVO request() {
        var r=new BusinessConditionSaveReqVO();r.setCode("VALID_ORDER");r.setName("有效订单");r.setDataDomainId(1L);
        r.setSourceModelId(5L);r.setConfigType("BASIC");r.setLogicMode("UNIFIED");
        r.setSubjectDomainId(2L);r.setEntityId(7L);r.setDataFreshness("1");
        r.setExpression(" status = 'SUCCESS' ");return r;
    }
    private BusinessConditionDO row() {
        var r=new BusinessConditionDO();r.setId(21L);r.setRevision(0L);r.setStatus("DRAFT");r.setDataDomainId(1L);
        r.setSubjectDomainId(2L);r.setEntityId(7L);r.setDataFreshness("1");
        r.setSourceModelId(5L);r.setConfigType("BASIC");r.setLogicMode("UNIFIED");r.setExpression("status = 'SUCCESS'");return r;
    }
    private void assertCode(int code,Runnable action) {
        assertEquals(code,assertThrows(ServiceException.class,action::run).getCode());
    }
}
