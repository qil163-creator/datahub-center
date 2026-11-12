package cn.iocoder.yudao.module.asset.service.dataexport;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.dataexport.vo.DataExportSubmitReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryResultRespVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataexport.DataExportRequestDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataexport.DataExportRequestMapper;
import cn.iocoder.yudao.module.asset.service.dataquery.DataQueryService;
import cn.iocoder.yudao.module.bpm.api.event.BpmProcessInstanceStatusEvent;
import cn.iocoder.yudao.module.bpm.api.task.BpmProcessInstanceApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import cn.idev.excel.FastExcelFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据导出审批：申请、流程发起失败、回调防伪与幂等、一次性下载与水印。
 */
class DataExportServiceTest {

    private DataExportRequestMapper mapper;
    private DataQueryService dataQuery;
    private BpmProcessInstanceApi bpm;
    private DataExportService service;
    private MockedStatic<SecurityFrameworkUtils> security;

    @BeforeEach
    void setUp() {
        mapper = mock(DataExportRequestMapper.class);
        dataQuery = mock(DataQueryService.class);
        bpm = mock(BpmProcessInstanceApi.class);
        service = new DataExportService();
        ReflectionTestUtils.setField(service, "exportMapper", mapper);
        ReflectionTestUtils.setField(service, "dataQueryService", dataQuery);
        ReflectionTestUtils.setField(service, "processInstanceApi", bpm);
        security = mockStatic(SecurityFrameworkUtils.class);
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
        security.when(SecurityFrameworkUtils::getLoginUserNickname).thenReturn("张三");
    }

    @AfterEach
    void tearDown() {
        security.close();
    }

    @Test
    void submitChecksAccessBeforeStartingTheProcess() {
        when(dataQuery.checkExportable(any())).thenReturn(new String[]{"ods", "users"});
        when(mapper.insert(any(DataExportRequestDO.class))).thenAnswer(invocation -> {
            ((DataExportRequestDO) invocation.getArgument(0)).setId(5L);
            return 1;
        });
        when(bpm.createProcessInstance(eq(9L), any())).thenReturn(CommonResult.success("pi-1"));

        assertThat(service.submit(submit())).isEqualTo(5L);
        verify(mapper).bindProcess(5L, "pi-1");
    }

    @Test
    void processStartFailureMarksTheRequestFailed() {
        when(dataQuery.checkExportable(any())).thenReturn(new String[]{"ods", "users"});
        when(mapper.insert(any(DataExportRequestDO.class))).thenAnswer(invocation -> {
            ((DataExportRequestDO) invocation.getArgument(0)).setId(5L);
            return 1;
        });
        when(bpm.createProcessInstance(eq(9L), any())).thenThrow(new IllegalStateException("no definition"));

        assertThatThrownBy(() -> service.submit(submit())).isInstanceOf(ServiceException.class)
                .hasMessageContaining("asset_data_export");
        verify(mapper).transit(eq(5L), eq("PENDING"), eq("FAILED"), anyString(), isNull(), isNull());
    }

    @Test
    void forgedCallbackIsIgnoredAndRealApprovalTransitsOnce() {
        DataExportRequestDO record = record("PENDING");
        record.setProcessInstanceId("pi-1");
        when(mapper.selectById(5L)).thenReturn(record);
        when(bpm.getProcessInstanceStatus("pi-1")).thenReturn(CommonResult.success(1));
        service.handleBpmStatus(event("pi-1", 2));
        verify(mapper, never()).transit(anyLong(), anyString(), anyString(), any(), any(), any());

        when(bpm.getProcessInstanceStatus("pi-1")).thenReturn(CommonResult.success(2));
        service.handleBpmStatus(event("pi-1", 2));
        verify(mapper).transit(eq(5L), eq("PENDING"), eq("APPROVED"), any(), any(LocalDateTime.class),
                any(LocalDateTime.class));
        // 其他实例的回调不能改动本申请。
        service.handleBpmStatus(event("pi-other", 3));
        verify(bpm, never()).getProcessInstanceStatus("pi-other");
    }

    @Test
    void downloadIsOneTimeOwnerOnlyAndWatermarked() throws Exception {
        DataExportRequestDO record = record("APPROVED");
        record.setExpireTime(LocalDateTime.now().plusHours(1));
        when(mapper.selectById(5L)).thenReturn(record);
        when(dataQuery.executeForExport(any())).thenReturn(result());
        when(mapper.markDownloaded(eq(5L), eq(0), any(), anyInt())).thenReturn(1).thenReturn(0);

        DataExportService.ExportFile file = service.download(5L);
        List<Map<Integer, String>> sheet = FastExcelFactory.read(new ByteArrayInputStream(file.content()))
                .headRowNumber(0).sheet().doReadSync();
        assertThat(sheet.getFirst().get(0)).contains("导出人：张三（9）", "申请编号：5");
        assertThat(sheet.get(1)).containsValues("id", "phone（已脱敏）");
        assertThat(sheet.get(2)).containsValues("1", "***");
        assertThat(file.rows()).isEqualTo(1);

        assertThatThrownBy(() -> service.download(5L)).isInstanceOf(ServiceException.class);
    }

    @Test
    void expiredOrForeignRequestsCannotBeDownloaded() {
        DataExportRequestDO expired = record("APPROVED");
        expired.setExpireTime(LocalDateTime.now().minusMinutes(1));
        when(mapper.selectById(5L)).thenReturn(expired);
        assertThatThrownBy(() -> service.download(5L)).isInstanceOf(ServiceException.class)
                .hasMessageContaining("有效期");

        DataExportRequestDO foreign = record("APPROVED");
        foreign.setUserId(10L);
        when(mapper.selectById(6L)).thenReturn(foreign);
        assertThatThrownBy(() -> service.download(6L)).isInstanceOf(ServiceException.class);
        verify(dataQuery, never()).executeForExport(any());
    }

    private static DataExportSubmitReqVO submit() {
        DataExportSubmitReqVO req = new DataExportSubmitReqVO();
        req.setDatasourceId(1L);
        req.setSql("SELECT id, phone FROM ods.users");
        req.setLimit(100);
        req.setReason("月度对账");
        return req;
    }

    private static DataExportRequestDO record(String status) {
        DataExportRequestDO record = new DataExportRequestDO();
        record.setId(5L);
        record.setUserId(9L);
        record.setDatasourceId(1L);
        record.setSqlText("SELECT id, phone FROM ods.users");
        record.setRowLimit(100);
        record.setStatus(status);
        record.setRevision(0);
        return record;
    }

    private static BpmProcessInstanceStatusEvent event(String id, int status) {
        BpmProcessInstanceStatusEvent event = new BpmProcessInstanceStatusEvent(new Object());
        event.setId(id);
        event.setProcessDefinitionKey("asset_data_export");
        event.setStatus(status);
        event.setBusinessKey("5");
        return event;
    }

    private static DataQueryResultRespVO result() {
        DataQueryResultRespVO result = new DataQueryResultRespVO();
        DataQueryResultRespVO.Column id = new DataQueryResultRespVO.Column();
        id.setName("id");
        id.setLabel("id");
        DataQueryResultRespVO.Column phone = new DataQueryResultRespVO.Column();
        phone.setName("phone");
        phone.setLabel("phone");
        result.setColumns(List.of(id, phone));
        result.setRows(List.of(Map.of("id", 1, "phone", "***")));
        result.setMaskedColumns(List.of(new DatabasePreviewRespDTO.MaskedColumn("phone", "RULE", List.of("手机号"))));
        return result;
    }
}
