package cn.iocoder.yudao.module.governance.service.metadata;

import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaChangeDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaCollectInstanceDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaDbDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaCollectInstanceMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaCollectLogMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaDiffEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MetaTableRegistrationServiceTest {
    private MetaTableRegistrationService service;
    private MetaDiffEngine diff;
    private MetaTableMapper tables;
    private MetaCollectInstanceMapper instances;
    private MasterDatabaseResolver resolver;

    @BeforeEach void setup() {
        service = new MetaTableRegistrationService();
        diff = mock(MetaDiffEngine.class);
        tables = mock(MetaTableMapper.class);
        instances = mock(MetaCollectInstanceMapper.class);
        resolver = mock(MasterDatabaseResolver.class);
        ReflectionTestUtils.setField(service, "diffEngine", diff);
        ReflectionTestUtils.setField(service, "tableMapper", tables);
        ReflectionTestUtils.setField(service, "instanceMapper", instances);
        ReflectionTestUtils.setField(service, "logMapper", mock(MetaCollectLogMapper.class));
        ReflectionTestUtils.setField(service, "databaseResolver", resolver);
        when(diff.upsertDb(any(), eq("public"))).thenReturn(MetaDbDO.builder().id(10L).build());
    }

    @Test void preparedRegistrationReusesSourceWithoutCallingRpc() {
        when(tables.selectByDbIdAndName(10L, "dwd_case")).thenReturn(MetaTableDO.builder().id(20L).build());
        assertEquals(20L, service.registerTablePrepared(request(), source()));
        verifyNoInteractions(resolver);
        verify(diff).processTable(argThat(ctx ->
                ctx.getTriggerType() == MetaCollectInstanceDO.TRIGGER_REGISTER
                        && ctx.resolveOrigin() == MetaDbDO.ORIGIN_MANUAL
                        && ctx.resolveChangeSource() == MetaChangeDO.SOURCE_MANUAL), any(), any());
        verify(instances).updateById(argThat((MetaCollectInstanceDO row) ->
                row.getStatus() == MetaCollectInstanceDO.STATUS_SUCCESS));
    }

    @Test void failedRegistrationCannotReturnPreviouslyRegisteredTableAsSuccess() {
        IllegalStateException failure = new IllegalStateException("field persistence failed");
        doThrow(failure).when(diff).processTable(any(), any(), any());
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.registerTablePrepared(request(), source())));
        verifyNoInteractions(tables);
        verify(instances).updateById(argThat((MetaCollectInstanceDO row) ->
                row.getStatus() == MetaCollectInstanceDO.STATUS_FAIL));
    }

    private MetaTableRegistrationService.RegistrationSource source() {
        return new MetaTableRegistrationService.RegistrationSource(1L, "测试源", "POSTGRESQL");
    }

    private MetaTableRegistration request() {
        var request = new MetaTableRegistration();
        request.setDatasourceId(1L);
        request.setDbName("public");
        request.setTableName("dwd_case");
        var column = new MetaTableRegistration.Column();
        column.setColumnName("case_id");
        column.setDataType("varchar");
        request.setColumns(List.of(column));
        return request;
    }

}
