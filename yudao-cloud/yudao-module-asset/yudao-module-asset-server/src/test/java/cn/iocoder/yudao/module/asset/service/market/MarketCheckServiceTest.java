package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.*;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketApprovalWorkOrderDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketApprovalWorkOrderMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCheckTicketRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 来源意见与真实授权隔离的领域契约；实际事务和SQL由独立PostgreSQL测试验证。 */
class MarketCheckServiceTest extends BaseMockitoUnitTest {
    @InjectMocks private MarketCheckServiceImpl service;
    @Mock private MarketApprovalWorkOrderMapper workOrderMapper;
    @Mock private MarketApplyService applyService;
    @Mock private MarketObjectGuard objectGuard;
    @Mock private MarketDisplayHelper displayHelper;
    @Mock private DataAssetMapper assetMapper;
    @Mock private MarketAuditRecorder auditRecorder;
    @Mock private MarketSnapshotMaskSupport snapshotMasks;

    @BeforeEach
    void permissions() {
        when(objectGuard.requireUserId()).thenReturn(9L);
        when(objectGuard.hasPermission(MarketObjectGuard.PERM_APPLY_CHECK)).thenReturn(true);
    }

    @ParameterizedTest
    @CsvSource({"1,true", "2,true", "3,false", "4,false", "5,false", "6,false"})
    void sourceStatusIsExplicitAndNeverBecomesNativeIdentity(String status, boolean pending) {
        var order = order(status);
        when(workOrderMapper.selectSource("9007199254740993")).thenReturn(order);
        var detail = service.get("9007199254740993", "QDATA");
        assertThat(detail.getTicket().getId()).isEqualTo("9007199254740993");
        assertThat(detail.getTicket().getApplicantKey()).isEqualTo("QDATA:9");
        assertThat(detail.getTicket().getApplicantName()).isEqualTo("来源申请人");
        assertThat(detail.getTicket().getStatus()).isEqualTo(status);
        assertThat(detail.getTicket().getCanApprove()).isEqualTo(pending);
        assertThat(detail.getTicket().getCanReject()).isEqualTo(pending);
        assertThat(detail.getTicket().getNativeApply()).isNull();
        assertThat(detail.getCanInvoke()).isFalse();
        verifyNoInteractions(applyService, assetMapper, displayHelper, auditRecorder);
    }

    @Test
    void detailProjectsAllowedFieldsAndOverridesUntrustedCapabilities() {
        var order = order("2");
        order.setDetailSnapshot("""
                {"resource":{"sourceRecordId":"untrusted","assetName":"来源资产","rowCount":"9007199254740993","accessToken":"must-not-leak"},
                 "canInvoke":true,"canPreview":true,"accessToken":"must-not-leak",
                 "ticket":{"id":"untrusted","origin":"NATIVE","canApprove":false},
                 "columns":[{"columnName":"station","dataType":"VARCHAR","privateKey":"must-not-leak"}],
                 "preview":{"total":9007199254740993,"columns":[],"rows":[],"truncated":true},
                 "apiDocument":{"name":"只读文档","appSecret":"must-not-leak","requestParams":[]}}
                """);
        when(workOrderMapper.selectSource("9007199254740993")).thenReturn(order);
        var detail = service.get("9007199254740993", "QDATA");
        assertThat(detail.getTicket().getId()).isEqualTo("9007199254740993");
        assertThat(detail.getResource().getSourceRecordId()).isEqualTo("22");
        assertThat(detail.getResource().getRowCount()).isEqualTo("9007199254740993");
        assertThat(detail.getColumns()).extracting(MarketCheckDetailRespVO.Column::getColumnName).containsExactly("station");
        assertThat(detail.getCanPreview()).isTrue();
        assertThat(detail.getTicket().getCanView()).isTrue();
        assertThat(detail.getPreview().getTotal()).isEqualTo("9007199254740993");
        assertThat(detail.getCanInvoke()).isFalse();
        assertThat(JsonUtils.toJsonString(detail)).doesNotContain("must-not-leak", "untrusted", "appSecret", "privateKey", "accessToken");
        // 来源快照按审批人脱敏；资产只按外部编码读取以定位物理来源。
        verify(snapshotMasks).mask(any(), eq(detail.getPreview()), anyLong());
        verify(assetMapper).selectByExternalCode(anyString());
        verifyNoMoreInteractions(assetMapper);
        verifyNoInteractions(applyService, displayHelper, auditRecorder);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"{}", "{\"sourceMetadata\":{\"sourceUpdateTime\":\"2026-10-22 12:00:00\"}}",
            "{\"preview\":{\"rows\":[]}}", "{\"columns\":[{\"columnName\":\"station\"}]}"})
    void missingResourceSnapshotDoesNotOfferDetail(String snapshot) {
        var order = order("2"); order.setDetailSnapshot(snapshot);
        when(workOrderMapper.selectSource("9007199254740993")).thenReturn(order);
        var detail = service.get("9007199254740993", "QDATA");
        assertThat(detail.getTicket().getCanView()).isFalse();
        assertThat(detail.getCanPreview()).isFalse();
        assertThat(detail.getTicket().getCanApprove()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"3,true", "5,false"})
    void withdrawnSourceDoesNotOfferDetailOrPreview(String status, boolean viewable) {
        var order = order(status);
        order.setDetailSnapshot("{\"resource\":{\"assetName\":\"来源资源\"},\"preview\":{\"rows\":[]}}");
        when(workOrderMapper.selectSource("9007199254740993")).thenReturn(order);
        var detail = service.get("9007199254740993", "QDATA");
        assertThat(detail.getTicket().getCanView()).isEqualTo(viewable);
        assertThat(detail.getCanPreview()).isEqualTo(viewable);
    }

    @ParameterizedTest
    @CsvSource({"1,3", "2,3", "2,4"})
    void sourceDecisionOnlyUpdatesIndependentWorkOrderAndAudits(String original, String target) {
        var order = order(original);
        when(workOrderMapper.selectSource("9007199254740993")).thenReturn(order);
        when(workOrderMapper.decideSource(77L, 1, target, "审核意见", 9L)).thenReturn(1);
        service.check(decision("QDATA", target, 1));
        verify(auditRecorder).record("SOURCE_WORK_ORDER_REVIEW", "SOURCE_WORK_ORDER", 77L,
                Integer.valueOf(original), Integer.valueOf(target), "审核意见", "SAVED_WITHOUT_GRANT");
        assertThat(order.getSourceStatus()).isEqualTo(original);
        assertThat(order.getApplicantKey()).isEqualTo("9");
        verifyNoInteractions(applyService, assetMapper, displayHelper);
    }

    @Test
    void sourceOptimisticConflictNeverWritesAnAuditOrNativeApplication() {
        when(workOrderMapper.selectSource("9007199254740993")).thenReturn(order("2"));
        when(workOrderMapper.decideSource(anyLong(), anyInt(), anyString(), any(), anyLong())).thenReturn(0);
        assertThatThrownBy(() -> service.check(decision("QDATA", "3", 1)))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(MARKET_APPLY_REVISION_CONFLICT.getCode()));
        verifyNoInteractions(applyService, assetMapper, displayHelper, auditRecorder);
    }

    @Test
    void oldRevisionAndRepeatedTerminalDecisionAreRejected() {
        var order = order("2");
        when(workOrderMapper.selectSource("9007199254740993")).thenReturn(order);
        assertThatThrownBy(() -> service.check(decision("QDATA", "3", 2))).isInstanceOf(ServiceException.class);
        order.setLocalStatus("4");
        assertThatThrownBy(() -> service.check(decision("QDATA", "3", 1))).isInstanceOf(ServiceException.class);
        verify(workOrderMapper, never()).decideSource(anyLong(), anyInt(), anyString(), any(), anyLong());
        verifyNoInteractions(applyService, assetMapper, displayHelper, auditRecorder);
    }

    @Test
    void localDecisionChangesDisplayedStatusWithoutReplacingSourceSnapshot() {
        var order = order("2"); order.setLocalStatus("3"); order.setLocalApprovalReason("本地意见"); order.setRevision(2);
        when(workOrderMapper.selectSource("9007199254740993")).thenReturn(order);
        var ticket = service.get("9007199254740993", "QDATA").getTicket();
        assertThat(ticket.getStatus()).isEqualTo("3");
        assertThat(ticket.getSourceStatus()).isEqualTo("2");
        assertThat(ticket.getApprovalReason()).isEqualTo("本地意见");
        assertThat(ticket.getCanApprove()).isFalse();
    }

    @Test
    void nativeDecisionDelegatesOriginalGuardedApprovalAndSeenRevision() {
        service.check(decision("NATIVE", "3", 7));
        var request = ArgumentCaptor.forClass(MarketCheckReqVO.class);
        verify(applyService).checkApply(request.capture());
        assertThat(request.getValue().getId()).isEqualTo(9007199254740993L);
        assertThat(request.getValue().getStatus()).isEqualTo(2);
        assertThat(request.getValue().getExpectedRevision()).isEqualTo(7);
        assertThat(request.getValue().getCheckNote()).isEqualTo("审核意见");
        verifyNoInteractions(workOrderMapper, assetMapper, displayHelper, auditRecorder);
    }

    @Test
    void allEntrypointsRejectMissingApprovalPermissionBeforeReadingAnySource() {
        when(objectGuard.hasPermission(MarketObjectGuard.PERM_APPLY_CHECK)).thenReturn(false);
        assertThatThrownBy(() -> service.get("21", "QDATA")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.getPage(new MarketCheckPageReqVO())).isInstanceOf(ServiceException.class);
        assertThatThrownBy(service::applicants).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.check(decision("QDATA", "3", 1))).isInstanceOf(ServiceException.class);
        verifyNoInteractions(workOrderMapper, applyService, assetMapper, displayHelper, auditRecorder);
    }

    @Test
    void pageScopesNativeObjectsAndKeepsOffsetInDatabase() {
        var request = new MarketCheckPageReqVO(); request.setPageNo(3); request.setPageSize(10);
        when(objectGuard.providerAssetIds(9L)).thenReturn(List.of(5L));
        when(workOrderMapper.countTickets(request, List.of(5L))).thenReturn(21L);
        var row = new MarketCheckTicketRow(); row.setId("9007199254740993"); row.setOrigin("QDATA");
        when(workOrderMapper.selectTickets(request, List.of(5L), 20L)).thenReturn(List.of(row));
        when(workOrderMapper.selectSource("9007199254740993")).thenReturn(order("2"));
        assertThat(service.getPage(request).getTotal()).isEqualTo(21L);
        verify(workOrderMapper).selectTickets(request, List.of(5L), 20L);
    }

    @Test
    void applicantsKeepSameNumberedRemoteAndLocalPeopleSeparate() {
        when(objectGuard.providerAssetIds(9L)).thenReturn(List.of());
        var remote = new MarketCheckTicketRow(); remote.setApplicantKey("QDATA:9"); remote.setApplicantName("来源申请人");
        var local = new MarketCheckTicketRow(); local.setApplicantKey("NATIVE:9");
        when(workOrderMapper.selectApplicants(List.of())).thenReturn(List.of(remote, local));
        when(displayHelper.userNames(List.of(9L))).thenReturn(Map.of(9L, "本地申请人"));
        when(displayHelper.userLabel(Map.of(9L, "本地申请人"), 9L)).thenReturn("本地申请人");
        assertThat(service.applicants()).extracting(MarketCheckApplicantRespVO::getKey).containsExactly("QDATA:9", "NATIVE:9");
        assertThat(service.applicants()).extracting(MarketCheckApplicantRespVO::getName).containsExactly("来源申请人", "本地申请人");
    }

    private static MarketApprovalWorkOrderDO order(String status) {
        var order = new MarketApprovalWorkOrderDO(); order.setId(77L); order.setSourceSystem("QDATA");
        order.setSourceRecordId("9007199254740993"); order.setMartSourceId("22"); order.setMartName("来源资产");
        order.setApplicantKey("9"); order.setApplicantName("来源申请人"); order.setApplyType(1);
        order.setApplyTime(LocalDateTime.of(2026, 1, 1, 1, 0)); order.setSourceStatus(status);
        order.setSourceApprovalReason("来源意见"); order.setRevision(1); order.setDetailSnapshot("{}"); return order;
    }

    private static MarketCheckDecisionReqVO decision(String origin, String status, int revision) {
        var request = new MarketCheckDecisionReqVO(); request.setId("9007199254740993"); request.setOrigin(origin);
        request.setStatus(status); request.setExpectedRevision(revision); request.setApprovalReason("审核意见"); return request;
    }
}
