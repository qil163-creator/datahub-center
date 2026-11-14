package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelVersionDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelVersionMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.MODEL_VERSION_MISMATCH;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.MODEL_VERSION_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 逻辑模型版本：版本号递增、快照内容、变化摘要与比对边界。 */
@ExtendWith(MockitoExtension.class)
class LogicModelVersionServiceImplTest {

    @Mock private LogicModelVersionMapper versionMapper;
    @Mock private LogicModelMapper modelMapper;
    @Mock private LogicModelColumnService columnService;
    @Mock private AdminUserApi adminUserApi;
    @InjectMocks private LogicModelVersionServiceImpl service;

    private static LogicModelDO model() {
        LogicModelDO model = new LogicModelDO();
        model.setId(7L);
        model.setName("订单事实表");
        model.setTableNameNotation("dwd_order");
        model.setRevision(5L);
        model.setLayerId(3L);
        return model;
    }

    private static ModelFieldItem field(String uid, String name, String type) {
        ModelFieldItem item = new ModelFieldItem();
        item.setFieldUid(uid);
        item.setFieldName(name);
        item.setCnName(name);
        item.setDataType(type);
        return item;
    }

    private static LogicModelVersionDO version(Long id, Long modelId, int no, List<ModelFieldItem> fields) {
        return LogicModelVersionDO.builder().id(id).modelId(modelId).versionNo(no)
                .modelSnapshot(JsonUtils.toJsonString(LogicModelVersionService.headerSnapshot(model())))
                .fieldsSnapshot(JsonUtils.toJsonString(fields)).fieldCount(fields.size())
                .publishedTime(LocalDateTime.of(2026, 11, 1, 9, 0)).build();
    }

    @Test
    void firstPublicationIsVersionOneMarkedInitial() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 14, 10, 0);

        service.recordPublish(model(), List.of(field("u1", "order_id", "BIGINT")), "{\"summary\":{}}", 9L, now);

        ArgumentCaptor<LogicModelVersionDO> saved = ArgumentCaptor.forClass(LogicModelVersionDO.class);
        verify(versionMapper).insert(saved.capture());
        LogicModelVersionDO row = saved.getValue();
        assertEquals(1, row.getVersionNo());
        assertEquals(5L, row.getModelRevision());
        assertEquals(1, row.getFieldCount());
        assertEquals(9L, row.getPublishedBy());
        assertEquals(now, row.getPublishedTime());
        assertEquals("{\"summary\":{}}", row.getTypeConfig());
        assertEquals(Map.of("initial", true), JsonUtils.parseObject(row.getChangeSummary(), Map.class));
        assertTrue(row.getModelSnapshot().contains("\"tableNameNotation\":\"dwd_order\""));
        assertTrue(row.getFieldsSnapshot().contains("\"fieldName\":\"order_id\""));
    }

    @Test
    void laterPublicationIncrementsVersionAndSummarisesFieldChanges() {
        when(versionMapper.selectLatest(7L)).thenReturn(version(1L, 7L, 2,
                List.of(field("u1", "order_id", "BIGINT"), field("u2", "remark", "VARCHAR"))));

        service.recordPublish(model(), List.of(field("u1", "order_id", "VARCHAR"), field("u3", "amount", "DECIMAL")),
                null, 9L, LocalDateTime.now());

        ArgumentCaptor<LogicModelVersionDO> saved = ArgumentCaptor.forClass(LogicModelVersionDO.class);
        verify(versionMapper).insert(saved.capture());
        assertEquals(3, saved.getValue().getVersionNo());
        assertNull(saved.getValue().getTypeConfig());
        Map<?, ?> summary = JsonUtils.parseObject(saved.getValue().getChangeSummary(), Map.class);
        assertEquals(1, summary.get("added"));
        assertEquals(1, summary.get("removed"));
        assertEquals(1, summary.get("modified"));
        assertEquals(0, summary.get("headerChanged"));
    }

    @Test
    void compareRejectsVersionsOfAnotherModelAndUnknownVersions() {
        when(versionMapper.selectById(1L)).thenReturn(version(1L, 8L, 1, List.of()));
        ServiceException mismatch = assertThrows(ServiceException.class, () -> service.compare(7L, 1L, null));
        assertEquals(MODEL_VERSION_MISMATCH.getCode(), mismatch.getCode());

        ServiceException missing = assertThrows(ServiceException.class, () -> service.compare(7L, 99L, null));
        assertEquals(MODEL_VERSION_NOT_EXISTS.getCode(), missing.getCode());
        verify(columnService, never()).listByModelId(any());
    }

    @Test
    void compareWithoutTargetUsesCurrentDesign() {
        when(versionMapper.selectById(1L)).thenReturn(version(1L, 7L, 1, List.of(field("u1", "order_id", "BIGINT"))));
        LogicModelDO current = model();
        current.setName("订单明细事实表");
        when(modelMapper.selectById(7L)).thenReturn(current);
        when(columnService.listByModelId(7L)).thenReturn(List.of(field("u1", "order_id", "BIGINT"),
                field("u2", "amount", "DECIMAL")));

        var diff = service.compare(7L, 1L, null);

        assertEquals("V1", diff.getFromLabel());
        assertEquals("当前设计", diff.getToLabel());
        assertEquals(1, diff.getSummary().getAdded());
        assertEquals(1, diff.getSummary().getUnchanged());
        assertEquals(1, diff.getSummary().getHeaderChanged());
    }

    @Test
    void listToleratesUnavailableUserService() {
        LogicModelVersionDO row = version(1L, 7L, 1, List.of());
        row.setPublishedBy(9L);
        row.setChangeSummary("{\"initial\":true}");
        when(versionMapper.selectListByModelId(7L)).thenReturn(List.of(row));
        when(adminUserApi.getUserMap(any())).thenThrow(new IllegalStateException("system down"));

        var list = service.listVersions(7L);

        assertEquals(1, list.size());
        assertEquals("V1", list.getFirst().getVersionLabel());
        assertNull(list.getFirst().getPublishedByName());
        assertEquals(Boolean.TRUE, list.getFirst().getChangeSummary().get("initial"));
    }
}
