package cn.iocoder.yudao.module.governance.service.metadata;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.excel.core.util.ExcelUtils;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaImportRowVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaTableRegisterReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaCollectInstanceDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaDbDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaCollectInstanceMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaCollectLogMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaCollectContext;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaCollectedModel.CollectedTable;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaDiffEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MetaImportRegistrationTest {
    private MetaImportServiceImpl service;
    private MetaDiffEngine diff;
    private MetaTableMapper tables;
    private MetaCollectInstanceMapper instances;
    private MasterDatabaseResolver resolver;

    @BeforeEach void setup() {
        service = new MetaImportServiceImpl();
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
        verify(diff).processTable(any(), any(), any());
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

    @Test void spreadsheetImportStillContinuesAfterOneTableFails() throws Exception {
        var connection = new MasterDatabaseResolver.ConnectionInfo();
        connection.setName("测试源");
        connection.setProductType(ProductTypeEnum.POSTGRESQL);
        when(resolver.resolve(eq(1L), any())).thenReturn(connection);
        doAnswer(call -> {
            CollectedTable table = call.getArgument(2);
            if ("bad_table".equals(table.getTableName())) {
                throw new IllegalStateException("bad table");
            }
            ((MetaCollectContext) call.getArgument(0)).getTableProcessed().incrementAndGet();
            return null;
        }).when(diff).processTable(any(), any(), any());
        MultipartFile file = mock(MultipartFile.class);
        try (var excel = mockStatic(ExcelUtils.class)) {
            excel.when(() -> ExcelUtils.read(file, MetaImportRowVO.class))
                    .thenReturn(List.of(row("bad_table"), row("good_table")));
            var result = service.importDict(1L, null, "测试业务系统", file);
            assertEquals(1, result.getTableCount());
            assertEquals(1, result.getErrors().size());
            assertTrue(result.getErrors().getFirst().contains("bad_table"));
        }
        verify(instances).updateById(argThat((MetaCollectInstanceDO row) ->
                row.getStatus() == MetaCollectInstanceDO.STATUS_PARTIAL));
    }

    private MetaImportService.RegistrationSource source() {
        return new MetaImportService.RegistrationSource(1L, "测试源", "POSTGRESQL");
    }

    private MetaTableRegisterReqVO request() {
        var request = new MetaTableRegisterReqVO();
        request.setDatasourceId(1L); request.setDbName("public"); request.setTableName("dwd_case");
        var column = new MetaTableRegisterReqVO.RegisterColumn();
        column.setColumnName("case_id"); column.setDataType("varchar");
        request.setColumns(List.of(column));
        return request;
    }

    private MetaImportRowVO row(String tableName) {
        var row = new MetaImportRowVO();
        row.setDbName("public"); row.setTableName(tableName); row.setColumnName("id"); row.setDataType("varchar");
        return row;
    }
}
