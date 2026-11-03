package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.issue.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 回源权限、快照绑定与处置身份边界；并发原子性另由真实 PostgreSQL 专测验证。 */
class QualityIssueRowServiceTest {
    private QualityIssueRowService service;
    private QualityAuditLogMapper audits;
    private QualityIssueDataMapper issues;
    private QualityTaskInstanceMapper instances;
    private QualityIssueRowStateMapper states;
    private QualityTaskEvaluationService evaluations;
    private PlatformTransactionManager transactions;
    private MockedStatic<SecurityFrameworkUtils> security;
    private QualityIssueDataDO issue;
    private QualityTaskInstanceDO instance;

    @BeforeEach void setup() {
        security = mockStatic(SecurityFrameworkUtils.class);
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(11L);
        audits = mock(QualityAuditLogMapper.class); issues = mock(QualityIssueDataMapper.class);
        instances = mock(QualityTaskInstanceMapper.class); states = mock(QualityIssueRowStateMapper.class);
        evaluations = mock(QualityTaskEvaluationService.class); transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new QualityIssueRowService();
        ReflectionTestUtils.setField(service, "auditLogs", audits);
        ReflectionTestUtils.setField(service, "issues", issues);
        ReflectionTestUtils.setField(service, "instances", instances);
        ReflectionTestUtils.setField(service, "states", states);
        ReflectionTestUtils.setField(service, "evaluationService", evaluations);
        ReflectionTestUtils.setField(service, "transactionManager", transactions);
        var audit = new QualityAuditLogDO(); audit.setId(1L); audit.setTaskId(2L);
        audit.setInstanceId(3L); audit.setCheckObjectId(4L); audit.setCheckField("secret,amount");
        when(audits.selectById(1L)).thenReturn(audit);
        issue = new QualityIssueDataDO(); issue.setId(5L); issue.setTaskId(2L);
        issue.setInstanceId(3L); issue.setObjectId(4L); issue.setLocationSql("SELECT amount FROM source_rows WHERE amount < 0");
        when(issues.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(issue));
        instance = new QualityTaskInstanceDO(); instance.setId(3L); instance.setTaskId(2L);
        var object = new QualityCheckObjectDO(); object.setId(4L); object.setDatasourceConnId(9L);
        var frozen = new QualityTaskExecutor.GateDefinition(); frozen.setObjects(List.of(object));
        instance.setDefinitionSnapshot(JsonUtils.toJsonString(frozen));
        when(instances.selectById(3L)).thenReturn(instance);
        when(states.selectStates(anyLong(), any())).thenReturn(List.of());
        when(states.compareAndSet(anyLong(), anyString(), anyInt(), anyString(), anyString(), anyString())).thenReturn(1);
        rows(List.of(Map.of("amount", new BigDecimal("9007199254740993.123456789"))));
    }
    @AfterEach void clear() { security.close(); }
    private void rows(List<Map<String, Object>> values) {
        rows(values, false);
    }
    private void rows(List<Map<String, Object>> values, boolean truncated) {
        List<Map<String, String>> result = values.stream().map(row -> {
            Map<String, String> converted = new LinkedHashMap<>();
            row.forEach((key, value) -> converted.put(key, value == null ? null : value.toString()));
            return converted;
        }).toList();
        when(evaluations.readFrozenRows(any(), any(), anyLong(), anyInt())).thenReturn(
                new QualityTaskEvaluationService.FrozenRows(List.of("amount"), result, truncated));
    }
    private QualityIssueRowQueryReqVO query() {
        var value = new QualityIssueRowQueryReqVO(); value.setAuditLogId(1L); value.setPageNo(1); value.setPageSize(20); return value;
    }
    private QualityIssueRowActionReqVO action(String key, int revision) {
        var value = new QualityIssueRowActionReqVO(); value.setAuditLogId(1L); value.setAction("IGNORE");
        var row = new QualityIssueRowActionReqVO.Row(); row.setRowKey(key); row.setRevision(revision);
        value.setRows(List.of(row)); return value;
    }
    @Test void usesFrozenDatasourceCurrentActorAndReturnedColumnsWithoutDecimalLoss() {
        var page = service.page(query());
        assertEquals(List.of("amount"), page.getColumns());
        assertEquals("9007199254740993.123456789", page.getList().getFirst().getValues().get("amount"));
        var captor = org.mockito.ArgumentCaptor.forClass(QualityTaskExecutor.GateDefinition.class);
        verify(evaluations).readFrozenRows(captor.capture(), argThat(audit -> audit.getId() == 1L), eq(11L), eq(1000));
        assertEquals(9L, captor.getValue().getObjects().getFirst().getDatasourceConnId());
    }
    @Test void deniesMissingActorTamperedBindingAndMissingSnapshotBeforeSourceRead() {
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.page(query()));
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(11L);
        issue.setObjectId(99L); assertThrows(ServiceException.class, () -> service.page(query()));
        issue.setObjectId(4L); instance.setTaskId(99L);
        assertThrows(ServiceException.class, () -> service.page(query()));
        instance.setTaskId(2L); instance.setDefinitionSnapshot("{}");
        assertThrows(ServiceException.class, () -> service.page(query())); verifyNoInteractions(evaluations);
    }
    @Test void propagatesProviderDenialWithoutRawQueryFallback() {
        when(evaluations.readFrozenRows(any(), any(), anyLong(), anyInt())).thenThrow(new ServiceException(403, "denied"));
        assertEquals(403, assertThrows(ServiceException.class, () -> service.page(query())).getCode());
        verify(evaluations).readFrozenRows(any(), any(), anyLong(), anyInt()); verifyNoInteractions(states);
    }
    @Test void repeatedProjectionRowsCannotBeIndividuallyIgnored() {
        rows(List.of(Map.of("amount", "-1"), Map.of("amount", "-1")));
        var page = service.page(query()); assertEquals(2, page.getTotal());
        assertFalse(page.getList().getFirst().getProcessable());
        assertThrows(ServiceException.class, () -> service.act(action(page.getList().getFirst().getRowKey(), 0)));
        verifyNoInteractions(transactions);
    }
    @Test void changedSourceValuesAndForgedRevisionsCannotReusePreviousDisposition() {
        var key = service.page(query()).getList().getFirst().getRowKey();
        assertThrows(ServiceException.class, () -> service.act(action(key, 1)));
        rows(List.of(Map.of("amount", "-2")));
        assertThrows(ServiceException.class, () -> service.act(action(key, 0))); verifyNoInteractions(transactions);
    }
    @Test void differentActorsAndFrozenDefinitionsNeverShareDispositionIdentity() {
        var key = service.page(query()).getList().getFirst().getRowKey();
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(12L);
        assertNotEquals(key, service.page(query()).getList().getFirst().getRowKey());
        assertThrows(ServiceException.class, () -> service.act(action(key, 0)));
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(11L);
        var changed = JsonUtils.parseObject(instance.getDefinitionSnapshot(), QualityTaskExecutor.GateDefinition.class);
        changed.getObjects().getFirst().setTableName("changed_source");
        instance.setDefinitionSnapshot(JsonUtils.toJsonString(changed));
        assertNotEquals(key, service.page(query()).getList().getFirst().getRowKey());
        assertThrows(ServiceException.class, () -> service.act(action(key, 0)));
        verifyNoInteractions(transactions);
    }
    @Test void truncatedResultsAreReadOnlyEvenIfVisibleProjectionIsUnique() {
        rows(List.of(Map.of("amount", "-1")), true);
        var page = service.page(query());
        assertTrue(page.getTruncated()); assertFalse(page.getList().getFirst().getProcessable());
        assertThrows(ServiceException.class, () -> service.act(action(page.getList().getFirst().getRowKey(), 0)));
        verifyNoInteractions(transactions);
    }
    @Test void ignoresHistoricalRawSqlAndReadsOnlyTheFrozenDefinition() {
        issue.setLocationSql("SELECT pg_read_file('/never-execute')");
        assertEquals(1, service.page(query()).getTotal());
        verify(evaluations).readFrozenRows(any(), any(), eq(11L), eq(1000));
    }
    @Test void validatesFiltersAndSafelyBoundsPagination() {
        var request = query(); request.setFilters(Map.of("secret", "x"));
        assertThrows(ServiceException.class, () -> service.page(request));
        request.setFilters(Map.of("amount", "9007199254740993")); request.setPageNo(Integer.MAX_VALUE);
        assertEquals(List.of(), service.page(request).getList());
        request.setPageNo(1); assertEquals(1, service.page(request).getTotal());
    }
    @Test void authorizesBeforeTransactionAndAuditsOnlySuccessfulCas() {
        var key = service.page(query()).getList().getFirst().getRowKey(); clearInvocations(evaluations);
        service.act(action(key, 0));
        var order = inOrder(evaluations, transactions, states);
        order.verify(evaluations).readFrozenRows(any(), any(), eq(11L), eq(1000)); order.verify(transactions).getTransaction(any());
        order.verify(states).ensureState(5L, key, "11");
        order.verify(states).compareAndSet(5L, key, 0, "IGNORE", "", "11");
        order.verify(states).recordAction(eq(5L), eq(key), eq(11L), eq("IGNORE"), eq("UNPROCESSED"), eq(0), anyString(), anyString());
        order.verify(transactions).commit(any());
        when(states.compareAndSet(anyLong(), anyString(), anyInt(), anyString(), anyString(), anyString())).thenReturn(0);
        assertThrows(ServiceException.class, () -> service.act(action(key, 0)));
        verify(transactions).rollback(any());
        verify(states, times(1)).recordAction(anyLong(), anyString(), anyLong(), anyString(), anyString(), anyInt(), anyString(), anyString());
    }
}
