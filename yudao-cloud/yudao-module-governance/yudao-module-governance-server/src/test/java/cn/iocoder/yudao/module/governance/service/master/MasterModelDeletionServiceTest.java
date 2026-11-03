package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDataChangeDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.*;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MasterModelDeletionServiceTest {
    private MasterModelMapper models;
    private MasterColumnMapper columns;
    private MasterDistributeMapper distributes;
    private MasterCodeRuleMapper rules;
    private MasterDataChangeMapper changes;
    private MasterDatabaseResolver resolver;
    private DbSwitchRuntime runtime;
    private MasterModelServiceImpl service;

    @BeforeEach
    void setup() {
        models = mock(MasterModelMapper.class);
        columns = mock(MasterColumnMapper.class);
        distributes = mock(MasterDistributeMapper.class);
        rules = mock(MasterCodeRuleMapper.class);
        changes = mock(MasterDataChangeMapper.class);
        resolver = mock(MasterDatabaseResolver.class);
        runtime = mock(DbSwitchRuntime.class);
        service = new MasterModelServiceImpl();
        ReflectionTestUtils.setField(service, "logRecordService", mock(com.mzt.logapi.service.ILogRecordService.class));
        ReflectionTestUtils.setField(service, "modelMapper", models);
        ReflectionTestUtils.setField(service, "columnMapper", columns);
        ReflectionTestUtils.setField(service, "distributeMapper", distributes);
        ReflectionTestUtils.setField(service, "codeRuleMapper", rules);
        ReflectionTestUtils.setField(service, "dataChangeMapper", changes);
        ReflectionTestUtils.setField(service, "databaseResolver", resolver);
        ReflectionTestUtils.setField(service, "dbSwitchRuntime", runtime);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void draftAndOfflineDeleteOnlyPlatformDefinition(Integer status) {
        when(models.selectForUpdate(100L)).thenReturn(model(100L, status));
        service.deleteModel(100L);
        var order = inOrder(models, distributes, rules, changes, columns);
        order.verify(models).selectForUpdate(100L);
        order.verify(distributes).selectCountByModelId(100L);
        order.verify(rules).selectCount(any(SFunction.class), eq(100L));
        order.verify(changes).selectActive(100L);
        order.verify(models).deleteById(100L);
        order.verify(columns).deleteByModelId(100L);
        verifyNoMoreInteractions(models, columns, changes);
        verifyNoInteractions(resolver, runtime);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {1, -1, 3})
    void releasedAndUnrecognizedStatusesAreRejected(Integer status) {
        when(models.selectForUpdate(100L)).thenReturn(model(100L, status));
        ServiceException failure = assertThrows(ServiceException.class, () -> service.deleteModel(100L));
        assertEquals(MASTER_MODEL_STRUCTURE_FROZEN.getCode(), failure.getCode());
        verify(models, never()).deleteById(any(Long.class));
        verifyNoInteractions(columns, changes, distributes, rules, resolver, runtime);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPLYING", "UNKNOWN", "UNRECOGNIZED"})
    void offlinePublicationMustHaveKnownResult(String publishState) {
        MasterModelDO row = model(100L, 2);
        row.setPublishState(publishState);
        when(models.selectForUpdate(100L)).thenReturn(row);
        ServiceException failure = assertThrows(ServiceException.class, () -> service.deleteModel(100L));
        assertEquals(MASTER_MODEL_PUBLISH_UNCERTAIN.getCode(), failure.getCode());
        verify(models, never()).deleteById(any(Long.class));
        verifyNoInteractions(columns, changes, distributes, rules, resolver, runtime);
    }

    @Test
    void legacyNullPublishStateStillAllowsOfflineDeletion() {
        MasterModelDO row = model(100L, 2);
        row.setPublishState(null);
        when(models.selectForUpdate(100L)).thenReturn(row);
        service.deleteModel(100L);
        verify(models).deleteById(100L);
        verify(columns).deleteByModelId(100L);
        verifyNoInteractions(resolver, runtime);
    }

    @Test
    void offlineDistributeReferenceStillPreventsDeletion() {
        when(models.selectForUpdate(100L)).thenReturn(model(100L, 2));
        when(distributes.selectCountByModelId(100L)).thenReturn(1L);
        ServiceException failure = assertThrows(ServiceException.class, () -> service.deleteModel(100L));
        assertEquals(MASTER_MODEL_HAS_DISTRIBUTE.getCode(), failure.getCode());
        verify(models, never()).deleteById(any(Long.class));
        verifyNoInteractions(columns, changes, rules, resolver, runtime);
    }

    @Test
    void offlineCodeRuleReferenceStillPreventsDeletion() {
        when(models.selectForUpdate(100L)).thenReturn(model(100L, 2));
        when(rules.selectCount(any(SFunction.class), eq(100L))).thenReturn(1L);
        ServiceException failure = assertThrows(ServiceException.class, () -> service.deleteModel(100L));
        assertEquals(MASTER_MODEL_HAS_CODE_RULE.getCode(), failure.getCode());
        verify(models, never()).deleteById(any(Long.class));
        verifyNoInteractions(columns, changes, resolver, runtime);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "WAIT_START", "APPROVED", "APPLYING", "UNKNOWN"})
    void activeApprovalOrUncertainWritePreventsOfflineDeletion(String status) {
        when(models.selectForUpdate(100L)).thenReturn(model(100L, 2));
        MasterDataChangeDO change = new MasterDataChangeDO();
        change.setId(9L);
        change.setModelId(100L);
        change.setStatus(status);
        when(changes.selectActive(100L)).thenReturn(List.of(change));
        ServiceException failure = assertThrows(ServiceException.class, () -> service.deleteModel(100L));
        assertEquals(MASTER_DATA_ROW_PENDING.getCode(), failure.getCode());
        verify(models, never()).deleteById(any(Long.class));
        verifyNoInteractions(columns, resolver, runtime);
    }

    @Test
    void batchLocksDistinctIdsInOrderAndKeepsExternalDatabaseUntouched() {
        when(models.selectForUpdate(100L)).thenReturn(model(100L, 0));
        when(models.selectForUpdate(200L)).thenReturn(model(200L, 2));
        service.deleteModelList(List.of(200L, 100L, 200L));
        var order = inOrder(models, columns);
        order.verify(models).selectForUpdate(100L);
        order.verify(models).deleteById(100L);
        order.verify(columns).deleteByModelId(100L);
        order.verify(models).selectForUpdate(200L);
        order.verify(models).deleteById(200L);
        order.verify(columns).deleteByModelId(200L);
        verify(models, times(1)).selectForUpdate(200L);
        verifyNoInteractions(resolver, runtime);
    }

    private static MasterModelDO model(Long id, Integer status) {
        return MasterModelDO.builder().id(id).status(status).publishState(MasterModelDO.PUBLISH_READY)
                .releaseTime(LocalDateTime.of(2026, 10, 12, 12, 0)).build();
    }
}
