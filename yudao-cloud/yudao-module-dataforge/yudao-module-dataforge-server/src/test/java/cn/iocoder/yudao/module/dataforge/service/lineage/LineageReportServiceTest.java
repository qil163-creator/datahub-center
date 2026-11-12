package cn.iocoder.yudao.module.dataforge.service.lineage;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.governance.api.lineage.LineageApi;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageNodeRefDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportReqDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportRespDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LineageReportServiceTest extends BaseMockitoUnitTest {

    @Mock
    private LineageSourceCollector collector;
    @Mock
    private LineageApi lineageApi;
    private LineageReportService service;

    @BeforeEach
    void setUp() {
        service = new LineageReportService();
        ReflectionTestUtils.setField(service, "collectors", List.of(collector));
        ReflectionTestUtils.setField(service, "lineageApi", lineageApi);
    }

    @Test
    void acceptedReportUpdatesMarkerWithRevisionAndAnalyzerVersion() {
        LineageMarkerRow row = row(false, "1:101");
        when(collector.build(row)).thenReturn(report(2L));
        when(lineageApi.report(any())).thenReturn(CommonResult.success(resp("ACCEPTED")));
        when(collector.mark(row, "2:101")).thenReturn(true);
        assertTrue(service.reportOne(collector, row));
        verify(collector).mark(row, "2:101");
    }

    @Test
    void transientFailureKeepsMarkerForRetry() {
        LineageMarkerRow row = row(false, null);
        when(collector.build(row)).thenReturn(report(2L));
        when(lineageApi.report(any())).thenThrow(new IllegalStateException("connect timed out"));
        assertFalse(service.reportOne(collector, row));
        verify(collector, never()).mark(any(), anyString());
    }

    @Test
    void rejectedReportIsReplacedByFailureReasonOnceAndMarked() {
        LineageMarkerRow row = row(false, null);
        when(collector.build(row)).thenReturn(report(2L));
        when(lineageApi.report(any()))
                .thenReturn(CommonResult.error(1_900_018_002, "血缘上报内容无效：节点不合法"))
                .thenReturn(CommonResult.success(resp("ACCEPTED")));
        when(collector.mark(row, "2:101")).thenReturn(true);
        assertTrue(service.reportOne(collector, row));
        ArgumentCaptor<LineageReportReqDTO> sent = ArgumentCaptor.forClass(LineageReportReqDTO.class);
        verify(lineageApi, times(2)).report(sent.capture());
        LineageReportReqDTO fallback = sent.getAllValues().get(1);
        assertEquals("FAILED", fallback.getStatus());
        assertTrue(fallback.getTableEdges().isEmpty());
        assertTrue(fallback.getIssues().getFirst().getMessage().contains("节点不合法"));
    }

    @Test
    void analysisCrashIsReportedAsFailedInsteadOfLoopingForever() {
        LineageMarkerRow row = row(false, null);
        row.setSourceRevision(9L);
        when(collector.build(row)).thenThrow(new IllegalArgumentException("bad snapshot: SELECT secret"));
        when(collector.sourceType()).thenReturn("DEV_TASK");
        when(collector.analyzerVersion()).thenReturn(101);
        when(lineageApi.report(any())).thenReturn(CommonResult.success(resp("ACCEPTED")));
        when(collector.mark(row, "9:101")).thenReturn(true);
        assertTrue(service.reportOne(collector, row));
        ArgumentCaptor<LineageReportReqDTO> sent = ArgumentCaptor.forClass(LineageReportReqDTO.class);
        verify(lineageApi).report(sent.capture());
        assertEquals("FAILED", sent.getValue().getStatus());
        assertFalse(sent.getValue().toString().contains("secret"));
    }

    @Test
    void removedSourceClearsMarker() {
        LineageMarkerRow row = row(true, "5:101");
        when(collector.build(row)).thenReturn(LineageReportBuilder.removed("DEV_TASK", 3L, "t", 5L, 101));
        when(lineageApi.report(any())).thenReturn(CommonResult.success(resp("ACCEPTED")));
        when(collector.mark(eq(row), eq(null))).thenReturn(true);
        assertTrue(service.reportOne(collector, row));
        verify(collector).mark(row, null);
    }

    @Test
    void rejectedRemovalIsRetriedRatherThanReplaced() {
        LineageMarkerRow row = row(true, "5:101");
        when(collector.build(row)).thenReturn(LineageReportBuilder.removed("DEV_TASK", 3L, "t", 5L, 101));
        when(lineageApi.report(any())).thenThrow(new ServiceException(1_900_018_002, "invalid"));
        assertFalse(service.reportOne(collector, row));
        verify(lineageApi, times(1)).report(any());
    }

    // ==================== 工具 ====================

    private static LineageMarkerRow row(boolean removed, String key) {
        LineageMarkerRow row = new LineageMarkerRow();
        row.setId(3L);
        row.setSourceName("t");
        row.setSourceRevision(2L);
        row.setLineageReportKey(key);
        row.setRemoved(removed);
        return row;
    }

    private static LineageReportReqDTO report(long revision) {
        return new LineageReportBuilder("DEV_TASK", 3L, "t", revision, 101)
                .tableEdge(LineageNodeRefDTO.table(1L, "ods", "a"), LineageNodeRefDTO.table(1L, "dw", "b"),
                        "PARSED", null).build();
    }

    private static LineageReportRespDTO resp(String result) {
        LineageReportRespDTO resp = new LineageReportRespDTO();
        resp.setResult(result);
        return resp;
    }
}
