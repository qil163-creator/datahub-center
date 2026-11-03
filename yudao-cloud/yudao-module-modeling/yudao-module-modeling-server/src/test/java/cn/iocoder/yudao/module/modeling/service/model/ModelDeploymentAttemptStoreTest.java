package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.ModelDeploymentAttemptDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.ModelMaterializedDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.ModelMaterializedLogDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.ModelDeploymentAttemptMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.ModelMaterializedLogMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.ModelMaterializedMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 发布完成事务在仍拥有目标时固化日志，后续同目标重新发布不能改变已写入快照。 */
@ExtendWith(MockitoExtension.class)
class ModelDeploymentAttemptStoreTest {
    @Mock ModelDeploymentAttemptMapper attempts;
    @Mock ModelMaterializedMapper materialized;
    @Mock ModelMaterializedLogMapper logs;
    @Mock cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper models;
    @Mock LogicModelColumnService columns;
    @Mock LogicModelService modelService;
    @InjectMocks ModelDeploymentAttemptStore store;

    @BeforeAll static void tableInfo() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO.class);
        TableInfoHelper.initTableInfo(assistant, ModelDeploymentAttemptDO.class);
        TableInfoHelper.initTableInfo(assistant, ModelMaterializedDO.class);
    }

    @Test void repeatedPublicationAppendsIndependentTargetAndModelSnapshots() {
        when(attempts.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(materialized.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        var target = ModelMaterializedDO.builder().id(10L).modelId(2L).datasourceId(7L).datasourceType("POSTGRESQL")
                .datasourceName("第一次目标名称").modelName("first_model").modelAlias("第一次模型")
                .description("第一次说明").build();
        when(materialized.selectById(10L)).thenReturn(target);

        store.finish(attempt(100L), "SUCCESS", "建表成功");
        target.setModelName("second_model"); target.setModelAlias("第二次模型");
        target.setDatasourceName("第二次目标名称"); target.setDescription("第二次说明");
        store.finish(attempt(101L), "SUCCESS", "增量完成");

        var captured = ArgumentCaptor.forClass(ModelMaterializedLogDO.class);
        verify(logs, times(2)).insert(captured.capture());
        var first = captured.getAllValues().get(0); var second = captured.getAllValues().get(1);
        assertEquals(100L, first.getAttemptId()); assertEquals("first_model", first.getModelName());
        assertEquals("第一次模型", first.getModelAlias()); assertEquals("第一次目标名称", first.getDatasourceName());
        assertEquals("第一次说明", first.getDescription()); assertEquals(7L, first.getDatasourceId());
        assertEquals("POSTGRESQL", first.getDatasourceType());
        assertEquals(101L, second.getAttemptId()); assertEquals("second_model", second.getModelName());
        assertEquals("第二次目标名称", second.getDatasourceName()); assertEquals("第二次说明", second.getDescription());
    }

    @Test void lostAttemptOwnershipCannotAppendAHistorySnapshot() {
        when(attempts.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(materialized.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        assertThrows(ServiceException.class, () -> store.finish(attempt(100L), "SUCCESS", "迟到结果"));

        verifyNoInteractions(logs);
        verify(materialized, never()).selectById(anyLong());
    }

    @Test void staleDesignCannotReserveTarget() {
        var model = current(); model.setRevision(5L);
        when(models.selectByIdForUpdate(2L)).thenReturn(model);
        assertThrows(ServiceException.class, () -> store.reserve(desired(), prepared()));
        verifyNoInteractions(materialized, modelService);
    }

    @Test void changedFieldsCannotBePairedWithPreparedDdl() {
        when(models.selectByIdForUpdate(2L)).thenReturn(current());
        when(columns.listByModelId(2L)).thenReturn(java.util.List.of());
        assertThrows(ServiceException.class, () -> store.reserve(desired(), prepared()));
        verifyNoInteractions(materialized, modelService);
    }

    @Test void upstreamDraftPreventsAnyNewDeployment() {
        rejectedUpstream(true);
    }

    @Test void disabledUpstreamPreventsAnyNewDeployment() {
        rejectedUpstream(false);
    }

    private void rejectedUpstream(boolean draft) {
        var model = current(); model.setTypeConfig("{\"summary\":{\"inputModelIds\":[3]}}");
        var upstream = current(); upstream.setId(3L);
        if (draft) {upstream.setStatus("DRAFT");} else {upstream.setEnabled(0);}
        when(models.selectByIdForUpdate(2L)).thenReturn(model);
        when(models.selectById(3L)).thenReturn(upstream);
        when(columns.listByModelId(2L)).thenReturn(fields());
        var validation = new LogicModelServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(validation, "modelMapper", models);
        org.springframework.test.util.ReflectionTestUtils.setField(validation, "columnService", columns);
        org.springframework.test.util.ReflectionTestUtils.setField(validation, "typeConfigValidator", new ModelTypeConfigValidator());
        org.springframework.test.util.ReflectionTestUtils.setField(store, "modelService", validation);
        var task = prepared(); task.setModelSnapshotJson(ModelDeploymentAttemptStore.taskModelInput(model));
        var error = assertThrows(ServiceException.class, () -> store.reserve(desired(), task));
        assertEquals(cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.MODEL_REFERENCE_INVALID.getCode(), error.getCode());
        verifyNoInteractions(materialized, attempts);
    }

    @Test void reservationCarriesCurrentRevisionAndFrozenFieldsToTheExecutionRecord() {
        when(models.selectByIdForUpdate(2L)).thenReturn(current());
        when(columns.listByModelId(2L)).thenReturn(fields());
        doAnswer(call -> {call.getArgument(0, ModelMaterializedDO.class).setId(10L);return 1;})
                .when(materialized).insert(any(ModelMaterializedDO.class));
        when(materialized.updateById(any(ModelMaterializedDO.class))).thenReturn(1);
        var desired = desired(); var execution = prepared();
        store.reserve(desired, execution);
        assertEquals(4L, desired.getDesignRevision());
        assertEquals(execution.getFieldsJson(), desired.getFieldsJson());
        assertEquals(2L, execution.getModelId());
        verify(modelService).validateCurrentDependencies(any(), eq(fields()));
    }

    private cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO current() {
        return cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO.builder()
                .id(2L).revision(4L).enabled(1).status("PUBLISHED").name("当前模型").build();
    }

    private java.util.List<cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem> fields() {
        var field = new cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem();
        field.setFieldUid("id"); field.setFieldName("id"); field.setDataType("BIGINT");
        return java.util.List.of(field);
    }

    private ModelMaterializedDO desired() {
        return ModelMaterializedDO.builder().modelId(2L).datasourceId(7L).build();
    }

    private ModelDeploymentAttemptDO prepared() {
        var execution = new ModelDeploymentAttemptDO(); execution.setModelId(2L); execution.setDesignRevision(4L);
        execution.setFieldsJson(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(fields()));
        execution.setModelSnapshotJson(ModelDeploymentAttemptStore.taskModelInput(current()));
        return execution;
    }

    private ModelDeploymentAttemptDO attempt(Long id) {
        var result = new ModelDeploymentAttemptDO(); result.setId(id); result.setMaterializedId(10L);
        result.setLeaseOwner("test-owner"); result.setPublishMode("REBUILD");
        result.setCreateTime(LocalDateTime.of(2026, 10, 20, 10, 0));
        return result;
    }
}
