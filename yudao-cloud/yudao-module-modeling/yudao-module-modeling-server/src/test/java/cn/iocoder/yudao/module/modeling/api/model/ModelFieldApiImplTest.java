package cn.iocoder.yudao.module.modeling.api.model;

import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelColumnDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelColumnMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper;
import cn.iocoder.yudao.module.modeling.service.model.LogicModelColumnService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModelFieldApiImplTest {
    @Mock private SecurityFrameworkService security;
    @Mock private LogicModelMapper logicModelMapper;
    @Mock private LogicModelColumnMapper columnMapper;
    @Mock private LogicModelColumnService columnService;
    @InjectMocks private ModelFieldApiImpl api;

    @org.junit.jupiter.api.BeforeEach
    void trustedChannel() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(api, "request",
                cn.iocoder.yudao.module.modeling.framework.security.ModelRuleContextSecurityTest.verifiedRequest());
    }

    @Test
    void unverifiedCallNeverReachesPermissionOrStorage() {
        org.springframework.test.util.ReflectionTestUtils.setField(api, "request", new org.springframework.mock.web.MockHttpServletRequest());
        assertThrows(RuntimeException.class, () -> api.getRuleContextFieldList(7L));
        verifyNoInteractions(security, logicModelMapper, columnMapper, columnService);
    }

    @Test
    void deniesUnauthorizedBeforeReadingModels() {
        assertThrows(AccessDeniedException.class, () -> api.getRuleContextFieldList(7L));
        verifyNoInteractions(logicModelMapper, columnMapper, columnService);
    }

    @Test
    void returnsSiblingFieldsOnlyFromStillBoundModels() {
        when(security.hasPermission("modeling:model:query")).thenReturn(true);
        when(columnMapper.selectListByStandardId(7L)).thenReturn(List.of(
                LogicModelColumnDO.builder().modelId(11L).build(),
                LogicModelColumnDO.builder().modelId(11L).build(),
                LogicModelColumnDO.builder().modelId(12L).build()));
        LogicModelDO bound = new LogicModelDO(); bound.setId(11L); bound.setName("订单"); bound.setCode("orders");
        LogicModelDO detached = new LogicModelDO(); detached.setId(12L);
        when(logicModelMapper.selectByIds(List.of(11L, 12L))).thenReturn(List.of(bound, detached));
        ModelFieldItem start = new ModelFieldItem(); start.setFieldName("start_at"); start.setStandardId(7L);
        ModelFieldItem end = new ModelFieldItem(); end.setFieldName("end_at");
        when(columnService.listByModelId(11L)).thenReturn(List.of(start, end));
        when(columnService.listByModelId(12L)).thenReturn(List.of(end));

        var fields = api.getRuleContextFieldList(7L).getCheckedData();
        assertEquals(List.of("start_at", "end_at"), fields.stream().map(row -> row.getFieldName()).toList());
        assertTrue(fields.stream().allMatch(row -> row.getModelId().equals(11L)));
        assertEquals("orders", fields.get(1).getTableName());
        verify(logicModelMapper, never()).selectList();
    }

    @Test
    void emptyReferenceSetDoesNotQueryAllModels() {
        when(security.hasPermission("modeling:model:query")).thenReturn(true);
        when(columnMapper.selectListByStandardId(7L)).thenReturn(List.of());
        assertTrue(api.getRuleContextFieldList(7L).getCheckedData().isEmpty());
        assertTrue(api.getRuleContextFieldList(null).getCheckedData().isEmpty());
        verifyNoInteractions(logicModelMapper, columnService);
    }

    @Test
    void returnsOnlyFieldsDirectlyBoundToCodeTable() {
        LogicModelDO model = new LogicModelDO();
        model.setId(11L); model.setName("订单"); model.setCode("orders");
        when(logicModelMapper.selectList()).thenReturn(List.of(model));
        ModelFieldItem matched = new ModelFieldItem(); matched.setFieldName("status"); matched.setCodeTableId(7L);
        ModelFieldItem other = new ModelFieldItem(); other.setFieldName("type"); other.setCodeTableId(8L);
        when(columnService.listByModelId(11L)).thenReturn(List.of(matched, other));

        var fields = api.getFieldListByCodeTableId(7L).getCheckedData();
        assertEquals(1, fields.size());
        assertEquals("status", fields.get(0).getFieldName());
        assertEquals("orders", fields.get(0).getTableName());
    }
}
