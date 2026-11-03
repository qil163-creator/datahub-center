package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.etl.core.connection.EtlRowPolicyPort;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.*;
import cn.iocoder.yudao.module.governance.api.etl.GovernanceEtlApi;
import cn.iocoder.yudao.module.governance.api.etl.dto.InlineQualityResultReqDTO;
import cn.iocoder.yudao.module.governance.api.etl.dto.InlineQualityResultRespDTO;
import cn.iocoder.yudao.module.governance.api.etl.dto.InlineQualityReportDTO;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EtlQualityEvidenceServiceTest {
    private final EtlQualityEvidenceService service = new EtlQualityEvidenceService();
    private final EtlQualityBatchMapper batches = mock(EtlQualityBatchMapper.class);
    private final EtlQualityIssueMapper issues = mock(EtlQualityIssueMapper.class);
    private final EtlRunCoordinator coordinator = mock(EtlRunCoordinator.class);
    private final GovernanceEtlApi governance = mock(GovernanceEtlApi.class);
    private final EtlRecordMapper records = mock(EtlRecordMapper.class);
    private final ExecutionAccessService access = mock(ExecutionAccessService.class);
    private final List<Write> writes = new ArrayList<>();
    private EtlRecordDO record;

    @BeforeEach void setup() {
        var configuration = new Configuration(); configuration.setMapUnderscoreToCamelCase(true);
        var assistant = new MapperBuilderAssistant(configuration, "etl-quality-evidence-test");
        TableInfoHelper.initTableInfo(assistant, EtlQualityBatchDO.class);
        TableInfoHelper.initTableInfo(assistant, EtlQualityIssueDO.class);
        ReflectionTestUtils.setField(service, "batchMapper", batches);
        ReflectionTestUtils.setField(service, "issueMapper", issues);
        ReflectionTestUtils.setField(service, "coordinator", coordinator);
        ReflectionTestUtils.setField(service, "governanceApi", governance);
        ReflectionTestUtils.setField(service, "recordMapper", records);
        ReflectionTestUtils.setField(service, "access", access);
        ReflectionTestUtils.setField(service, "archives", mock(EtlArchiveService.class));
        record = EtlRecordDO.builder().id(99L).executionUserId(42L).executionToken("owner-token").build();
        when(coordinator.writeOwned(eq(99L), eq("owner-token"), any())).thenAnswer(call -> {
            ((Runnable) call.getArgument(2)).run(); return true;
        });
        when(batches.update(isNull(), any())).thenAnswer(call -> {
            LambdaUpdateWrapper<EtlQualityBatchDO> wrapper = call.getArgument(1);
            String set = wrapper.getSqlSet(), where = wrapper.getSqlSegment();
            writes.add(new Write(set, where, new HashMap<>(wrapper.getParamNameValuePairs()))); return 1;
        });
    }

    @AfterEach void clearTenant() { TenantContextHolder.clear(); SecurityContextHolder.clearContext(); }

    @Test void unfinishedFailedOrUnknownRunIsDisplayedIncompleteWithNullCountsPreserved() {
        var batch = candidate("COLLECTING", 0);
        batch.setInputCount(null); batch.setPassCount(null); batch.setWarnCount(null);
        batch.setRejectCount(null); batch.setViolationCount(null);
        when(records.selectById(99L)).thenReturn(record); when(batches.selectList(any())).thenReturn(List.of(batch));
        for (int status : List.of(4, 6)) {
            record.setRunStatus(status); var response = service.batches(99L).getFirst();
            assertEquals("INCOMPLETE", response.getRegistrationStatus());
            assertNull(response.getInputCount()); assertNull(response.getPassCount()); assertNull(response.getWarnCount());
            assertNull(response.getRejectCount()); assertNull(response.getViolationCount());
        }
        assertEquals("COLLECTING", batch.getRegistrationStatus(), "Display derivation must not rewrite stored state");
        verify(batches, never()).update(any(), any());
    }

    @Test void governanceReportRequiresMatchingRunNodeBatchAndFrozenPlan() {
        var batch = candidate("REGISTERED", 1); batch.setGovernanceReportId(333L);
        when(batches.selectById(1L)).thenReturn(batch); when(records.selectById(99L)).thenReturn(record);
        var report = matchingReport();
        when(governance.queryInlineQualityReport(any())).thenReturn(CommonResult.success(report));
        assertEquals(333L, service.governanceReport(1L).getReportId());
    }

    @Test void mismatchedGovernanceReportCannotBeExposedOrUsedToCreateWorkOrder() {
        var batch = candidate("REGISTERED", 1); batch.setGovernanceReportId(333L);
        when(batches.selectById(1L)).thenReturn(batch); when(records.selectById(99L)).thenReturn(record);
        var request = new cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlQualityWorkOrderReqVO(); request.setBatchId(1L);
        Map<String, java.util.function.Consumer<InlineQualityReportDTO>> mismatches = new LinkedHashMap<>();
        mismatches.put("reportId", report -> report.setReportId(444L));
        mismatches.put("batchId", report -> report.setBatchId("other-batch"));
        mismatches.put("runId", report -> report.setRunId("other-run"));
        mismatches.put("nodeId", report -> report.setNodeId("other-node"));
        mismatches.put("planHash", report -> report.setPlanHash("other-plan"));
        mismatches.put("sourceType", report -> report.setSourceType("SQL_SCAN"));
        mismatches.put("totalCount", report -> report.setTotalCount(999L));
        mismatches.put("passCount", report -> report.setPassCount(929L));
        mismatches.put("warnCount", report -> report.setWarnCount(19L));
        mismatches.put("rejectCount", report -> report.setRejectCount(49L));
        for (var mismatch : mismatches.entrySet()) {
            var report = matchingReport(); mismatch.getValue().accept(report);
            when(governance.queryInlineQualityReport(any())).thenReturn(CommonResult.success(report));
            assertThrows(IllegalStateException.class, () -> service.governanceReport(1L), mismatch.getKey());
            assertThrows(IllegalStateException.class, () -> service.createWorkOrder(request), mismatch.getKey());
        }
        when(governance.queryInlineQualityReport(any())).thenReturn(CommonResult.success(null));
        assertThrows(IllegalStateException.class, () -> service.governanceReport(1L));
        verify(governance, never()).createInlineQualityWorkOrder(any());
    }

    @Test void storedEvidenceFromExternalSourceUsesTaskQueryPermission() {
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(55L).setTenantId(7L), new MockHttpServletRequest());
        record.setConfigJson("{\"nodes\":[{\"id\":\"source\",\"properties\":{\"toolCode\":\"read-db\",\"toolType\":1,\"nodeJson\":{\"databaseId\":1,\"tableName\":\"sensitive\"}}},{\"id\":\"quality\",\"properties\":{\"toolCode\":\"quality-check\",\"toolType\":2}}],\"edges\":[{\"sourceNodeId\":\"source\",\"targetNodeId\":\"quality\"}]}");
        var issue = new EtlQualityIssueDO(); issue.setId(10L); issue.setBatchId(1L); issue.setRowJson("{\"secret\":\"value\"}");
        when(issues.selectById(10L)).thenReturn(issue); when(batches.selectById(1L)).thenReturn(candidate("REGISTERED", 1));
        when(records.selectById(99L)).thenReturn(record);
        assertEquals(issue.getRowJson(), service.issue(10L).getRowJson());
        verify(access).requireUserPermission(55L, "dataforge:etl:query");
        doThrow(new IllegalStateException("query permission revoked")).when(access)
                .requireUserPermission(55L, "dataforge:etl:query");
        assertThrows(IllegalStateException.class, () -> service.issue(10L));
    }

    @Test void manualRetryChecksCurrentActorAndOriginalExecutionIdentityBeforeCoordinator() {
        TenantContextHolder.setTenantId(7L);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(55L).setTenantId(7L), new MockHttpServletRequest());
        when(batches.selectById(1L)).thenReturn(candidate("FAILED", 10));
        when(records.selectById(99L)).thenReturn(record);
        doThrow(new IllegalStateException("permission revoked")).when(access).requireUserPermission(55L, "dataforge:etl:run");
        assertThrows(IllegalStateException.class, () -> service.retryRegistration(1L));
        verify(coordinator, never()).retryQualityRegistration(any(), any(), any());
        reset(access);
        service.retryRegistration(1L);
        verify(access).requireUserPermission(55L, "dataforge:etl:run");
        verify(access).requireUserPermission(55L, "dataforge:etl:query");
        verify(access).requireUserPermission(42L, "governance:quality-rule:query");
        verify(access).requireUserPermission(42L, "governance:quality-rule-set:query");
        verify(coordinator).retryQualityRegistration(1L, 99L, 55L);
        verifyNoInteractions(governance);
    }

    @Test void lostRunOwnershipPreventsEvidenceAndCompletionWrites() {
        when(coordinator.writeOwned(eq(99L), eq("owner-token"), any())).thenReturn(false);
        assertThrows(CancellationException.class, () -> service.recordIssue(record, 1L, 1, "{}", violation()));
        assertThrows(CancellationException.class, () -> service.complete(record, 1L, new EtlRowPolicyPort.Counts(1, 0, 0, 1, 1)));
        verifyNoInteractions(issues); verify(batches, never()).update(any(), any());
    }

    @Test void insertExceptionAndZeroAffectedRowsCannotPretendEvidenceWasStored() {
        when(issues.insert(any(EtlQualityIssueDO.class))).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> service.recordIssue(record, 1L, 1, "{}", violation()));
        when(issues.insert(any(EtlQualityIssueDO.class))).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.recordIssue(record, 1L, 1, "{}", violation()));
        when(batches.insert(any(EtlQualityBatchDO.class))).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.begin(record, "quality", "hash"));
        verify(batches, never()).update(any(), any());
    }

    @Test void completedBatchOrExistingIssuePreventsImplicitReplay() {
        var batch = candidate("REGISTERED", 1);
        when(batches.selectOne(any())).thenReturn(batch);
        assertThrows(IllegalStateException.class, () -> service.begin(record, "quality", "hash"));
        batch.setRegistrationStatus("COLLECTING"); when(issues.selectCount(any())).thenReturn(1L);
        assertThrows(IllegalStateException.class, () -> service.begin(record, "quality", "hash"));
        when(issues.selectCount(any())).thenReturn(0L); batch.setPlanHash("different");
        assertThrows(IllegalStateException.class, () -> service.begin(record, "quality", "hash"));
        verify(batches, never()).insert(any(EtlQualityBatchDO.class));
    }

    @Test void nonConservingCountsAndLostCompletionCasFail() {
        assertThrows(IllegalArgumentException.class, () -> service.complete(record, 1L, new EtlRowPolicyPort.Counts(1000, 930, 20, 49, 100)));
        verifyNoInteractions(coordinator);
        doReturn(0).when(batches).update(isNull(), any());
        assertThrows(IllegalStateException.class, () -> service.complete(record, 1L, new EtlRowPolicyPort.Counts(1000, 930, 20, 50, 120)));
    }

    @Test void failedClaimDoesNotSendGovernanceRpc() {
        when(batches.selectDeliveryCandidates(any())).thenReturn(List.of(candidate("PENDING", 0)));
        doReturn(0).when(batches).update(isNull(), any());
        service.relayPending(); verifyNoInteractions(governance);
    }

    @Test void acceptedReceiptRunsOutsideTransactionAndAcknowledgesSameLease() {
        TenantContextHolder.setTenantId(100L); TenantContextHolder.setIgnore(true);
        when(batches.selectDeliveryCandidates(any())).thenReturn(List.of(candidate("PENDING", 0)));
        when(governance.acceptInlineQualityResult(any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            InlineQualityResultReqDTO request = call.getArgument(0);
            assertEquals("dataforge:etl-quality:1", request.getIdempotencyKey());
            assertEquals(42L, request.getExecutionUserId()); assertEquals(1000, request.getTotalCount());
            assertEquals(930, request.getPassCount()); assertEquals(20, request.getWarnCount()); assertEquals(50, request.getRejectCount());
            assertEquals(List.of("dataforge:etl-quality-batch:1"), request.getIssueReferences());
            return CommonResult.success(receipt("ACCEPTED"));
        });
        service.relayPending();
        assertEquals(100L, TenantContextHolder.getTenantId()); assertTrue(TenantContextHolder.isIgnore());
        assertEquals(2, writes.size()); assertTrue(writes.getLast().values.containsValue("REGISTERED"));
        assertTrue(writes.getLast().values.containsValue(333L));
        assertTrue(writes.getFirst().where.contains("attempt_count"));
        assertTrue(writes.getLast().where.contains("lease_token"));
        assertEquals(uuid(writes.getFirst()), uuid(writes.getLast()));
    }

    @Test void responseLostOrUnknownReceiptRetriesTheSameIdempotentPayload() {
        when(batches.selectDeliveryCandidates(any())).thenReturn(List.of(candidate("PENDING", 0)));
        when(governance.acceptInlineQualityResult(any())).thenThrow(new IllegalStateException("response lost"))
                .thenReturn(CommonResult.success(null)).thenReturn(CommonResult.success(receipt("REPLAY")));
        service.relayPending(); assertTrue(writes.getLast().values.containsValue("RETRY"));
        service.relayPending(); assertTrue(writes.getLast().values.containsValue("RETRY"));
        service.relayPending(); assertTrue(writes.getLast().values.containsValue("REGISTERED"));
        var captor = org.mockito.ArgumentCaptor.forClass(InlineQualityResultReqDTO.class);
        verify(governance, times(3)).acceptInlineQualityResult(captor.capture());
        assertEquals(1, captor.getAllValues().stream().map(InlineQualityResultReqDTO::getIdempotencyKey).distinct().count());
        assertEquals(1, captor.getAllValues().stream().map(InlineQualityResultReqDTO::getPlanHash).distinct().count());
    }

    @Test void exhaustedRetryBecomesFailedAndExpiredTenthLeaseIsNotSentAgain() {
        when(batches.selectDeliveryCandidates(any())).thenReturn(List.of(candidate("RETRY", 9)));
        when(governance.acceptInlineQualityResult(any())).thenThrow(new IllegalStateException("offline"));
        service.relayPending(); assertTrue(writes.getLast().values.containsValue("FAILED"));
        assertTrue(writes.getFirst().values.containsValue(10));
        clearInvocations(governance); writes.clear();
        when(batches.selectDeliveryCandidates(any())).thenReturn(List.of(candidate("REGISTERING", 10)));
        service.relayPending(); verifyNoInteractions(governance);
        assertEquals(1, writes.size()); assertTrue(writes.getFirst().values.containsValue("FAILED"));
        assertTrue(writes.getFirst().where.contains("lease_until"));
    }

    private record Write(String set, String where, Map<String, Object> values) { }
    private static String uuid(Write write) {
        return write.values.values().stream().filter(value -> value instanceof String s && s.matches("[0-9a-f-]{36}"))
                .map(String.class::cast).findFirst().orElseThrow();
    }
    private static EtlQualityBatchDO candidate(String status, int attempts) {
        var batch = new EtlQualityBatchDO(); batch.setId(1L); batch.setRecordId(99L);
        batch.setNodeNo("quality"); batch.setExecutionUserId(42L); batch.setPlanHash("hash");
        batch.setRegistrationStatus(status); batch.setAttemptCount(attempts); batch.setInputCount(1000L);
        batch.setPassCount(930L); batch.setWarnCount(20L); batch.setRejectCount(50L); batch.setViolationCount(120L);
        batch.setLeaseUntil(LocalDateTime.now().minusMinutes(1)); return batch;
    }
    private static InlineQualityResultRespDTO receipt(String status) {
        var result = new InlineQualityResultRespDTO(); result.setReportId(333L); result.setStatus(status); return result;
    }
    private static InlineQualityReportDTO matchingReport() {
        var report = new InlineQualityReportDTO(); report.setReportId(333L); report.setBatchId("1");
        report.setRunId("99"); report.setNodeId("quality"); report.setPlanHash("hash"); report.setSourceType("ETL_INLINE");
        report.setTotalCount(1000L); report.setPassCount(930L); report.setWarnCount(20L); report.setRejectCount(50L);
        return report;
    }
    private static List<EtlRowPolicyPort.Violation> violation() {
        return List.of(new EtlRowPolicyPort.Violation("r", "field", "ERROR", "INVALID", "invalid"));
    }
}
