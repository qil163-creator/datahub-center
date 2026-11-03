package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic.LogicModelCreateReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 逻辑模型版本列表：fields 为 JSON 字符串，不能走 BeanUtils 整对象拷贝。
 */
@ExtendWith(MockitoExtension.class)
class LogicModelServiceImplTest {
    @Mock private cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicRelationMapper relationMapper;
    @Mock private cn.iocoder.yudao.module.modeling.dal.mysql.indicator.IndicatorModelMapper indicatorMapper;

    @Test
    void discardReferencedIndicatorSource_shouldFailBeforeMutation() {
        LogicModelDO model = LogicModelDO.builder().id(2L).revision(0L)
                .status(LogicModelDO.STATUS_PUBLISHED).build();
        when(modelMapper.selectByIdForUpdate(2L)).thenReturn(model);
        when(indicatorMapper.countSourceModelReferences(2L)).thenReturn(1L);
        ServiceException failure = assertThrows(ServiceException.class, () -> logicModelService.discardModel(2L));
        assertEquals(cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.INDICATOR_SOURCE_REFERENCED.getCode(), failure.getCode());
        verify(modelMapper, never()).update(any(), any());
    }

    @Test
    void deleteReferencedIndicatorSource_shouldFailBeforeDeletingVersions() {
        LogicModelDO model = LogicModelDO.builder().id(2L).revision(3L)
                .status(LogicModelDO.STATUS_PUBLISHED).build();
        when(modelMapper.selectByIdForUpdate(2L)).thenReturn(model);
        when(indicatorMapper.countSourceModelReferences(2L)).thenReturn(1L);
        ServiceException failure = assertThrows(ServiceException.class, () -> logicModelService.deleteModel(2L, 3L));
        assertEquals(cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.INDICATOR_SOURCE_REFERENCED.getCode(), failure.getCode());
        verify(modelMapper, never()).deleteById(2L);
    }

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, LogicModelDO.class);
    }

    @Mock
    private LogicModelMapper modelMapper;
    @Mock
    private cn.iocoder.yudao.module.modeling.dal.mysql.model.ModelMaterializedMapper materializedMapper;
    @Mock
    private LogicModelColumnService columnService;
    @Mock
    private LogicModelDefinitionService definitionService;
@Mock
    private ModelHierarchyResolver hierarchyResolver;

    @Spy
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate =
            new org.springframework.transaction.support.TransactionTemplate(
                    org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class));
    /** 版本转换会解析专属配置 JSON，用真实校验器 */
    @Spy
    private ModelTypeConfigValidator typeConfigValidator = new ModelTypeConfigValidator();

    @InjectMocks
    private LogicModelServiceImpl logicModelService;

    @Test
    void createModel_shouldPersistInitialFieldsWithStableIdentity() {
        ModelFieldItem field = field("uid-create", "客户名称", "cust_name");
        LogicModelCreateReqVO reqVO = new LogicModelCreateReqVO();
        reqVO.setDataDomainId(9L);
        reqVO.setName("客户");
        reqVO.setCode("customer");
        reqVO.setCreateMode(LogicModelDO.MODE_FORWARD);
        reqVO.setFields(List.of(field));

        logicModelService.createModel(reqVO);

        InOrder order = inOrder(modelMapper, columnService);
        order.verify(modelMapper).insert(any(LogicModelDO.class));
        verify(columnService).replaceByModelId(any(), argThat(fields -> fields.size() == 1
                && "uid-create".equals(fields.getFirst().getFieldUid())));
    }

    @Test
    void fiveLogicTypesUseFullTableNameWithoutReservingTheDisplayNameOrStem() {
        var request = new LogicModelCreateReqVO(); request.setName("测站"); request.setCode("station");
        request.setTableType("SUMMARY"); request.setTableNameNotation("dws_station");
        logicModelService.createModel(request);
        verify(modelMapper, never()).selectByNameOrCode(any(), any());
        verify(modelMapper).selectCount(argThat(wrapper -> wrapper.getSqlSegment().contains("lower(table_name_notation)")));
    }

    @Test
    void otherModelTypesKeepExistingNameOrCodeUniqueness() {
        var request = new LogicModelCreateReqVO(); request.setName("测站"); request.setCode("station");
        request.setTableType("CUSTOM");
        when(modelMapper.selectByNameOrCode("测站", "station")).thenReturn(LogicModelDO.builder().id(9L).build());
        assertThrows(ServiceException.class, () -> logicModelService.createModel(request));
        verify(modelMapper, never()).insert(any(LogicModelDO.class));
    }

    @Test
    void createModel_shouldRejectOversizedFieldDefinitionsBeforePersistence() {
        for (ModelFieldItem invalid : List.of(
                field("x".repeat(129), "客户名称", "customer_name"),
                field("uid-1", "名".repeat(256), "customer_name"),
                field("uid-1", "客户名称", "x".repeat(256)))) {
            LogicModelCreateReqVO request = new LogicModelCreateReqVO();
            request.setFields(List.of(invalid));

            ServiceException failure = assertThrows(ServiceException.class,
                    () -> logicModelService.createModel(request));

            assertEquals(cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.MODEL_FIELD_IDENTITY_INVALID.getCode(),
                    failure.getCode());
        }
        verify(modelMapper, never()).insert(any(LogicModelDO.class));
    }

    @Test
    void updateModel_shouldFailCasWhenPublishWinsAfterInitialRead() {
        when(modelMapper.selectByIdForUpdate(2L)).thenReturn(model(2L, 9L));
        when(modelMapper.update(any(), any())).thenReturn(0);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> logicModelService.updateModel(updateRequest()));

        assertEquals(MODEL_DESIGN_REVISION_CONFLICT.getCode(), exception.getCode());
        verify(modelMapper).update(any(), any());
    }

    @Test
    void staleDraft_shouldRejectBeforeAnyWrite() {
        LogicModelDO current = model(2L, 9L);
        current.setRevision(3L);
        when(modelMapper.selectByIdForUpdate(2L)).thenReturn(current);
        var request = new cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic.ModelDraftSaveReqVO();
        request.setModelId(2L);
        request.setExpectedRevision(2L);

        ServiceException failure = assertThrows(ServiceException.class, () -> logicModelService.saveDraft(request));

        assertEquals(cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants
                .MODEL_DESIGN_REVISION_CONFLICT.getCode(), failure.getCode());
        verify(modelMapper, never()).update(any(), any());
    }

    @Test
    void draftSave_shouldPreserveFieldIdentityAndSaveHeaderAndFieldsTogether() {
        LogicModelDO current = model(2L, 9L);
        currentFields(2L);
        var request = new cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic.ModelDraftSaveReqVO();
        request.setModelId(2L);
        request.setExpectedRevision(0L);
        request.setName("客户");
        request.setCode("customer");
        request.setGrain("每个客户一行");
        request.setFields(List.of(field(null, "客户名称", "cust_name")));
        when(modelMapper.selectByIdForUpdate(2L)).thenReturn(current);
        when(modelMapper.update(any(), any())).thenReturn(1);

        logicModelService.saveDraft(request);

        assertEquals("stable", request.getFields().getFirst().getFieldUid());
        InOrder order = inOrder(modelMapper, columnService);
        order.verify(modelMapper, org.mockito.Mockito.times(2)).update(any(), any());
        order.verify(columnService).replaceByModelId(eq(2L), any());
    }

    @Test
    void removingErReferencedField_shouldRejectBeforeHeaderWrite() {
        LogicModelDO current = model(2L, 9L);
        currentFields(2L);
        var relation = new cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicRelationDO();
        relation.setSourceModelId(2L);
        relation.setTargetModelId(3L);
        relation.setName("客户关联");
        relation.setFieldPairs("[{\"sourceFieldUid\":\"stable\",\"targetFieldUid\":\"target\"}]");
        var request = new cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic.ModelDraftSaveReqVO();
        request.setModelId(2L);
        request.setExpectedRevision(0L);
        request.setFields(List.of(field(null, "新字段", "new_field")));
        when(modelMapper.selectByIdForUpdate(2L)).thenReturn(current);
        when(relationMapper.selectListByModelId(2L)).thenReturn(List.of(relation));

        ServiceException failure = assertThrows(ServiceException.class, () -> logicModelService.saveDraft(request));

        assertEquals(cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants
                .MODEL_FIELD_REFERENCED.getCode(), failure.getCode());
        verify(modelMapper, never()).update(any(), any());
    }

    private void currentFields(Long modelId) {
        when(columnService.listByModelId(modelId)).thenReturn(List.of(field("stable", "客户名称", "cust_name")));
    }

    @Test
    void getDesignBindsCurrentFieldsConfigurationAndRevision() {
        LogicModelDO model = model(2L, 9L);
        model.setTypeConfig("{\"source\":{\"sourceSystem\":\"系统\"}}");
        when(modelMapper.selectById(2L)).thenReturn(model);
        currentFields(2L);
        var design = logicModelService.getDesign(2L);
        assertEquals(0L, design.getRevision());
        assertEquals("stable", design.getFields().getFirst().getFieldUid());
        assertEquals("系统", design.getTypeConfig().getSource().getSourceSystem());
    }

    @Test
    void publishedCurrentDesignCanBeEditedWithoutRevise() {
        LogicModelDO model = model(2L, 9L); model.setStatus("PUBLISHED");
        when(modelMapper.selectByIdForUpdate(2L)).thenReturn(model);
        when(modelMapper.update(any(), any())).thenReturn(1);
        logicModelService.updateModel(updateRequest());
        verify(modelMapper).update(any(), org.mockito.ArgumentMatchers.argThat(update -> {
            var values = ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>)update).getParamNameValuePairs().values();
            return values.contains("DRAFT") && values.contains(1L);
        }));
    }

    @Test
    void publishRejectsFieldChangeBetweenValidationAndLock() {
        LogicModelDO model = model(2L, 9L);
        when(modelMapper.selectById(2L)).thenReturn(model);
        when(modelMapper.selectByIdForUpdate(2L)).thenReturn(model);
        when(columnService.listByModelId(2L)).thenReturn(List.of(field("stable", "客户", "cust_name")),
                List.of(field("new", "另一个字段", "new_field")));
        org.mockito.Mockito.doNothing().when(typeConfigValidator).validateOnPublish(any(), any(), any());
        assertEquals(MODEL_PUBLISH_CONTENT_CHANGED.getCode(), assertThrows(ServiceException.class,
                () -> logicModelService.publishModel(2L, 0L)).getCode());
        verify(modelMapper, never()).update(any(), any());
    }

    @Test
    void publishCurrentDesignIgnoresLayoutOnlyChangesAndAdvancesRevision() {
        LogicModelDO model = model(2L, 9L);
        when(modelMapper.selectById(2L)).thenReturn(model);
        when(modelMapper.selectByIdForUpdate(2L)).thenReturn(model);
        currentFields(2L);
        org.mockito.Mockito.doAnswer(call -> {model.setPosX(100); model.setLayoutRevision(1L);return null;})
                .when(typeConfigValidator).validateOnPublish(any(), any(), any());
        logicModelService.publishModel(2L, 0L);
        verify(modelMapper).update(any(), org.mockito.ArgumentMatchers.argThat(update ->
                ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>)update).getParamNameValuePairs()
                        .values().contains("PUBLISHED")));
        verify(columnService).replaceByModelId(eq(2L), any());
    }

    @Test
    void sourceTurningDraftDuringExternalValidationRejectsPublication() {
        assertSourceChangeBeforeCommit(true);
    }

    @Test
    void sourceBeingDisabledDuringExternalValidationRejectsPublication() {
        assertSourceChangeBeforeCommit(false);
    }

    private void assertSourceChangeBeforeCommit(boolean draft) {
        LogicModelDO current = model(2L, 9L);
        current.setTypeConfig("{\"summary\":{\"inputModelIds\":[3]}}");
        LogicModelDO upstream = model(3L, 9L); upstream.setStatus("PUBLISHED"); upstream.setEnabled(1);
        when(modelMapper.selectById(2L)).thenReturn(current);
        when(modelMapper.selectById(3L)).thenReturn(upstream);
        when(modelMapper.selectByIdForUpdate(2L)).thenAnswer(call -> {
            if (draft) { upstream.setStatus("DRAFT"); } else { upstream.setEnabled(0); }
            return current;
        });
        currentFields(2L);
        org.mockito.Mockito.doNothing().when(typeConfigValidator).validateOnPublish(any(), any(), any());
        assertEquals(MODEL_REFERENCE_INVALID.getCode(), assertThrows(ServiceException.class,
                () -> logicModelService.publishModel(2L, 0L)).getCode());
        verify(modelMapper, never()).update(any(), any());
    }

    private LogicModelDO model(Long id, Long dataDomainId) {
        LogicModelDO model = new LogicModelDO();
        model.setId(id);
        model.setRevision(0L);
        model.setLayoutRevision(0L);
        model.setDataDomainId(dataDomainId);
        model.setName("客户模型");
        model.setCode("CUSTOMER_MODEL");
        model.setCreateMode(LogicModelDO.MODE_FORWARD);
        model.setDescription("客户模型说明");
        model.setModelStyle("RELATIONAL");
        model.setWarehouseLayer("DWD");
        model.setLayerId(2L);
        model.setSubjectDomainId(3L);
        model.setTableType("DETAIL");
        model.setClassificationStatus("CONFIRMED");
        model.setStatus(LogicModelDO.STATUS_DRAFT);
        return model;
    }

    private LogicModelCreateReqVO updateRequest() {
        LogicModelCreateReqVO request = new LogicModelCreateReqVO();
        request.setId(2L);
        request.setExpectedRevision(0L);
        request.setDataDomainId(9L);
        request.setName("客户模型修订");
        request.setCode("CUSTOMER_MODEL_V2");
        request.setModelStyle("RELATIONAL");
        request.setLayerId(null);
        request.setSubjectDomainId(null);
        request.setTableType(null);
        request.setDescription("修订说明");
        return request;
    }

    private ModelFieldItem field(String uid, String cnName, String fieldName) {
        ModelFieldItem field = new ModelFieldItem();
        field.setFieldUid(uid);
        field.setCnName(cnName);
        field.setFieldName(fieldName);
        field.setDataType("varchar");
        return field;
    }

}
