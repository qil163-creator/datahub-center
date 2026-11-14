package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic.ModelDraftSaveReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version.SourceSyncApplyReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 贴源表结构同步：预览、合并规则、CAS 与重新发布。 */
@ExtendWith(MockitoExtension.class)
class LogicModelSourceSyncServiceTest {

    @Mock private LogicModelService modelService;
    @Mock private LogicModelColumnService columnService;
    @Mock private ModelingDatabaseResolver databaseResolver;
    @Spy private ModelTypeConfigValidator typeConfigValidator = new ModelTypeConfigValidator();
    @InjectMocks private LogicModelSourceSyncService service;

    private static LogicModelDO source(String status, long revision) {
        LogicModelDO model = new LogicModelDO();
        model.setId(5L);
        model.setName("受理记录");
        model.setTableType("SOURCE");
        model.setStatus(status);
        model.setRevision(revision);
        model.setDatasourceId(30L);
        model.setTypeConfig("{\"source\":{\"sourceTable\":\"t_accept\"}}");
        return model;
    }

    private static ModelFieldItem field(String uid, String name, String type, Integer length) {
        ModelFieldItem item = new ModelFieldItem();
        item.setFieldUid(uid);
        item.setFieldName(name);
        item.setCnName(name);
        item.setDataType(type);
        item.setDataLength(length);
        return item;
    }

    private void givenStructures() {
        ModelFieldItem governed = field("u1", "accept_no", "VARCHAR", 32);
        governed.setCnName("受理编号");
        governed.setStandardId(88L);
        when(columnService.listByModelId(5L)).thenReturn(List.of(governed, field("u2", "old_col", "VARCHAR", 10)));
        when(databaseResolver.importFields(30L, "t_accept")).thenReturn(List.of(
                field(null, "accept_no", "VARCHAR", 64), field(null, "new_col", "TEXT", null)));
    }

    @Test
    void previewReportsStructuralDifferencesAgainstSourceTable() {
        when(modelService.getModel(5L)).thenReturn(source("PUBLISHED", 4));
        givenStructures();

        var preview = service.preview(5L);

        assertEquals("t_accept", preview.getSourceTable());
        assertEquals(4L, preview.getRevision());
        assertEquals(1, preview.getDiff().getSummary().getModified());
        assertEquals(1, preview.getDiff().getSummary().getAdded());
        assertEquals(1, preview.getDiff().getSummary().getRemoved());
    }

    @Test
    void applyKeepsIdentityAndGovernanceThenRepublishes() {
        when(modelService.getModel(5L)).thenReturn(source("PUBLISHED", 4), source("DRAFT", 5));
        givenStructures();
        var request = new SourceSyncApplyReqVO();
        request.setModelId(5L);
        request.setExpectedRevision(4L);

        var result = service.apply(request);

        ArgumentCaptor<ModelDraftSaveReqVO> draft = ArgumentCaptor.forClass(ModelDraftSaveReqVO.class);
        verify(modelService).saveDraft(draft.capture());
        assertEquals(4L, draft.getValue().getExpectedRevision());
        assertEquals("SOURCE", draft.getValue().getTableType());
        assertEquals("t_accept", draft.getValue().getTypeConfig().getSource().getSourceTable());
        List<ModelFieldItem> fields = draft.getValue().getFields();
        assertEquals(List.of("accept_no", "new_col"), fields.stream().map(ModelFieldItem::getFieldName).toList());
        assertEquals("u1", fields.get(0).getFieldUid());
        assertEquals(64, fields.get(0).getDataLength());
        assertEquals("受理编号", fields.get(0).getCnName(), "中文名等设计属性保留");
        assertEquals(88L, fields.get(0).getStandardId(), "治理属性保留");
        assertNotNull(fields.get(1).getFieldUid(), "新增字段生成稳定标识");
        verify(modelService).publishModel(5L, 5L);
        assertTrue(result.getPublished());
    }

    @Test
    void draftModelIsSavedButNotPublishedAndPublishFailureIsReported() {
        when(modelService.getModel(5L)).thenReturn(source("DRAFT", 2));
        givenStructures();
        var request = new SourceSyncApplyReqVO();
        request.setModelId(5L);
        request.setExpectedRevision(2L);
        assertFalse(service.apply(request).getPublished());
        verify(modelService, never()).publishModel(any(), any());

        reset(modelService);
        when(modelService.getModel(5L)).thenReturn(source("PUBLISHED", 2), source("DRAFT", 3));
        doThrow(new ServiceException(1, "主题域已停用")).when(modelService).publishModel(5L, 3L);
        var failed = service.apply(request);
        assertTrue(failed.getSaved());
        assertFalse(failed.getPublished());
        assertEquals("主题域已停用", failed.getPublishMessage());
    }

    @Test
    void rejectsStaleRevisionNonSourceTablesAndNoOpSync() {
        when(modelService.getModel(5L)).thenReturn(source("PUBLISHED", 7));
        var stale = new SourceSyncApplyReqVO();
        stale.setModelId(5L);
        stale.setExpectedRevision(6L);
        assertEquals(MODEL_DESIGN_REVISION_CONFLICT.getCode(),
                assertThrows(ServiceException.class, () -> service.apply(stale)).getCode());

        LogicModelDO fact = source("PUBLISHED", 1);
        fact.setTableType("DETAIL");
        when(modelService.getModel(6L)).thenReturn(fact);
        assertEquals(MODEL_SOURCE_SYNC_NOT_SOURCE.getCode(),
                assertThrows(ServiceException.class, () -> service.preview(6L)).getCode());

        when(columnService.listByModelId(5L)).thenReturn(List.of(field("u1", "accept_no", "VARCHAR", 32)));
        when(databaseResolver.importFields(30L, "t_accept")).thenReturn(List.of(field(null, "accept_no", "VARCHAR", 32)));
        var same = new SourceSyncApplyReqVO();
        same.setModelId(5L);
        same.setExpectedRevision(7L);
        assertEquals(MODEL_SOURCE_SYNC_NO_CHANGE.getCode(),
                assertThrows(ServiceException.class, () -> service.apply(same)).getCode());
        verify(modelService, never()).saveDraft(any());
    }
}
