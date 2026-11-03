package cn.iocoder.yudao.module.governance.service.etl;

import cn.iocoder.yudao.module.governance.api.etl.dto.InlineQualityResultReqDTO;
import cn.iocoder.yudao.module.governance.api.etl.dto.QualityEtlArchiveAuthorizeReqDTO;
import cn.iocoder.yudao.module.governance.api.etl.dto.RowQualityPlanReqDTO;
import cn.iocoder.yudao.module.governance.api.etl.dto.CleanRulePlanReqDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.etl.InlineQualityReportDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule.CleanRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderEtlReportDO;
import cn.iocoder.yudao.module.governance.dal.mysql.etl.InlineQualityReportMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRuleSetItemMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRuleSetMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderEtlReportMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderRecheckMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderSubmissionMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.qualityrule.CleanRuleMapper;
import cn.iocoder.yudao.module.governance.service.quality.QualityWorkOrderService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.framework.quality.core.row.QualityRowEvaluator;
import cn.iocoder.yudao.framework.quality.core.row.QualityRowRule;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionRegistry;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GovernanceEtlServiceTest {
    private final GovernanceEtlService service = new GovernanceEtlService();
    private final QualityRuleMapper rules = mock(QualityRuleMapper.class);
    private final QualityRuleSetMapper ruleSets = mock(QualityRuleSetMapper.class);
    private final QualityRuleSetItemMapper ruleSetItems = mock(QualityRuleSetItemMapper.class);
    private final InlineQualityReportMapper reports = mock(InlineQualityReportMapper.class);
    private final AdminUserApi users = mock(AdminUserApi.class);
    private final PermissionApi permissions = mock(PermissionApi.class);
    private final QualityWorkOrderService workOrders = mock(QualityWorkOrderService.class);
    private final QualityWorkOrderEtlReportMapper workOrderReports = mock(QualityWorkOrderEtlReportMapper.class);
    private final QualityWorkOrderMapper workOrderMapper = mock(QualityWorkOrderMapper.class);
    private final QualityWorkOrderSubmissionMapper submissions = mock(QualityWorkOrderSubmissionMapper.class);
    private final QualityWorkOrderRecheckMapper rechecks = mock(QualityWorkOrderRecheckMapper.class);
    private final CleanRuleMapper cleanRules = mock(CleanRuleMapper.class);

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "etl-report"), InlineQualityReportDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "etl-rule"), QualityRuleDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "etl-clean-rule"), CleanRuleDO.class);
        ReflectionTestUtils.setField(service, "ruleMapper", rules);
        ReflectionTestUtils.setField(service, "ruleSetMapper", ruleSets);
        ReflectionTestUtils.setField(service, "ruleSetItemMapper", ruleSetItems);
        ReflectionTestUtils.setField(service, "reportMapper", reports);
        ReflectionTestUtils.setField(service, "userApi", users);
        ReflectionTestUtils.setField(service, "permissionApi", permissions);
        ReflectionTestUtils.setField(service, "qualityWorkOrderService", workOrders);
        ReflectionTestUtils.setField(service, "workOrderReportMapper", workOrderReports);
        ReflectionTestUtils.setField(service, "workOrderMapper", workOrderMapper);
        ReflectionTestUtils.setField(service, "submissionMapper", submissions);
        ReflectionTestUtils.setField(service, "recheckMapper", rechecks);
        ReflectionTestUtils.setField(service, "cleanRuleMapper", cleanRules);
        ReflectionTestUtils.setField(service, "cleanFunctionRegistry", new CleanFunctionRegistry());
        TenantContextHolder.clear();
    }

    @Test
    void cleanRulePlanRequiresPermissionEnabledFieldRuleAndStableCanonicalHash() {
        TenantContextHolder.clear();
        when(permissions.hasAnyPermissions(eq(9L), eq("governance:clean-rule:query")))
                .thenReturn(cn.iocoder.yudao.framework.common.pojo.CommonResult.success(true));
        when(cleanRules.selectBatchIds(List.of(3L))).thenReturn(List.of(CleanRuleDO.builder().id(3L)
                .code("CLEAN_NAME").name("去空格").ruleLevel(1).strategyKey("REMOVE_WHITESPACE")
                .configJson("{\"handleType\":1}").status(CleanRuleDO.STATUS_ENABLE).build()));
        CleanRulePlanReqDTO req = cleanRuleRequest(3L, "name", Map.of("columns", List.of("name"), "handleType", 1));
        var first = service.snapshotCleanRulePlan(req);
        var second = service.snapshotCleanRulePlan(req);
        assertEquals(first.getPlanHash(), second.getPlanHash());
        assertEquals("REMOVE_WHITESPACE", first.getRules().getFirst().getStrategyKey());
        assertEquals(List.of("name"), first.getRules().getFirst().getConfig().get("columns"));
        assertEquals(1, first.getRules().getFirst().getConfig().get("handleType"));
        TenantContextHolder.clear();
    }

    @Test
    void cleanRulePlanUsesPersistedParametersAndRejectsLegacyNodeDrift() {
        TenantContextHolder.clear();
        when(permissions.hasAnyPermissions(eq(9L), eq("governance:clean-rule:query")))
                .thenReturn(cn.iocoder.yudao.framework.common.pojo.CommonResult.success(true));
        when(cleanRules.selectBatchIds(List.of(3L))).thenReturn(List.of(CleanRuleDO.builder().id(3L)
                .code("CLEAN_NAME").name("去空格").ruleLevel(1).strategyKey("REMOVE_WHITESPACE")
                .configJson("{\"handleType\":2}").status(CleanRuleDO.STATUS_ENABLE).build()));

        CleanRulePlanReqDTO.Rule newNode = new CleanRulePlanReqDTO.Rule();
        newNode.setRuleId(3L); newNode.setField("name");
        CleanRulePlanReqDTO request = new CleanRulePlanReqDTO();
        request.setExecutionUserId(9L); request.setRules(List.of(newNode));
        var plan = service.snapshotCleanRulePlan(request);
        assertEquals(2, plan.getRules().getFirst().getConfig().get("handleType"));

        newNode.setLegacyConfig(Map.of("columns", List.of("name"), "handleType", 1));
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> service.snapshotCleanRulePlan(request));
        TenantContextHolder.clear();
    }

    @Test
    void cleanRulePlanRejectsUnsupportedConfigAndNonEnabledOrTableLevelRules() {
        TenantContextHolder.clear();
        when(permissions.hasAnyPermissions(eq(9L), eq("governance:clean-rule:query")))
                .thenReturn(cn.iocoder.yudao.framework.common.pojo.CommonResult.success(true));
        when(cleanRules.selectBatchIds(List.of(4L))).thenReturn(List.of(CleanRuleDO.builder().id(4L)
                .code("CUSTOM").name("custom").ruleLevel(1).strategyKey("MENU_CUSTOM")
                .status(CleanRuleDO.STATUS_ENABLE).build()));
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> service.snapshotCleanRulePlan(cleanRuleRequest(4L, "name", Map.of("columns", List.of("name")))));
        when(cleanRules.selectBatchIds(List.of(5L))).thenReturn(List.of(CleanRuleDO.builder().id(5L)
                .code("TABLE").name("table").ruleLevel(2).strategyKey("REMOVE_WHITESPACE")
                .status(CleanRuleDO.STATUS_ENABLE).build()));
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> service.snapshotCleanRulePlan(cleanRuleRequest(5L, "name", Map.of("columns", List.of("name"), "sql", "x"))));
        when(cleanRules.selectBatchIds(List.of(8L))).thenReturn(List.of(CleanRuleDO.builder().id(8L)
                .code("BOUNDARY").name("boundary").ruleLevel(1).strategyKey("WITHIN_BOUNDARY")
                .status(CleanRuleDO.STATUS_ENABLE).build()));
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> service.snapshotCleanRulePlan(cleanRuleRequest(8L, "age",
                        Map.of("columns", List.of("age"), "min", "1e999999999", "max", "2"))));
        TenantContextHolder.clear();
    }

    @Test
    void cleanRulePlanRejectsMissingPermissionWithoutTenantContextBeforeReadingRules() {
        TenantContextHolder.clear();
        when(permissions.hasAnyPermissions(eq(9L), eq("governance:clean-rule:query")))
                .thenReturn(cn.iocoder.yudao.framework.common.pojo.CommonResult.success(false));
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> service.snapshotCleanRulePlan(cleanRuleRequest(6L, "name", Map.of("columns", List.of("name")))));
        verifyNoInteractions(cleanRules);
    }

    private static CleanRulePlanReqDTO cleanRuleRequest(Long id, String field, Map<String, Object> legacyConfig) {
        CleanRulePlanReqDTO.Rule rule = new CleanRulePlanReqDTO.Rule();
        rule.setRuleId(id); rule.setField(field); rule.setLegacyConfig(legacyConfig);
        CleanRulePlanReqDTO request = new CleanRulePlanReqDTO();
        request.setExecutionUserId(9L); request.setRules(List.of(rule));
        return request;
    }

    @Test
    void cleanPlanFreezesCompositeBindingsAndValidatesSortAndUpdateFields() {
        when(permissions.hasAnyPermissions(eq(9L), eq("governance:clean-rule:query")))
                .thenReturn(cn.iocoder.yudao.framework.common.pojo.CommonResult.success(true));
        when(cleanRules.selectBatchIds(List.of(3L))).thenReturn(List.of(CleanRuleDO.builder().id(3L)
                .code("LATEST").name("组合去重").ruleLevel(2).strategyKey("KEEP_LATEST_OR_FIRST")
                .configJson("{\"stringValue\":[{\"columns\":\"updated\",\"type\":0,\"sort\":1}]}")
                .status(CleanRuleDO.STATUS_ENABLE).build()));
        var request = cleanRuleRequest(3L, null, null);
        request.getRules().getFirst().setFields(List.of("id", "type"));
        request.setInputFields(List.of("id", "type", "updated"));
        var plan = service.snapshotCleanRulePlan(request);
        assertEquals(List.of("id", "type"), plan.getRules().getFirst().getConfig().get("columns"));
        request.setInputFields(List.of("id", "type"));
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> service.snapshotCleanRulePlan(request));
        request.setInputFields(List.of("id", "type", "updated"));
        request.getRules().getFirst().setField("id");
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> service.snapshotCleanRulePlan(request));

        when(cleanRules.selectBatchIds(List.of(3L))).thenReturn(List.of(CleanRuleDO.builder().id(3L)
                .code("EXPIRY").name("过期标记").ruleLevel(1).strategyKey("CHECK_EXPIRATION")
                .configJson("{\"dataRange\":1,\"dataRangeValue\":\"2025-01-01\",\"handleType\":0,\"handleColumns\":\"status\"}")
                .status(CleanRuleDO.STATUS_ENABLE).build()));
        request.getRules().getFirst().setFields(null);
        request.getRules().getFirst().setField("updated");
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> service.snapshotCleanRulePlan(request));
        request.setInputFields(List.of("updated", "status"));
        assertEquals("status", service.snapshotCleanRulePlan(request).getRules().getFirst().getConfig().get("handleColumns"));
    }

    @Test
    void cleanBindingAndCapabilityJsonContractsPreserveCompositeFieldsAndLosslessIds() {
        var request = cleanRuleRequest(9_007_199_254_740_993L, null, null);
        request.getRules().getFirst().setFields(List.of("id", "kind"));
        request.setInputFields(List.of("id", "kind", "updated"));
        String json = cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(request);
        assertTrue(json.contains("9007199254740993"));
        var restored = cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseObject(json, CleanRulePlanReqDTO.class);
        assertEquals(request.getRules().getFirst().getRuleId(), restored.getRules().getFirst().getRuleId());
        assertEquals(List.of("id", "kind"), restored.getRules().getFirst().getFields());
        assertEquals(List.of("id", "kind", "updated"), restored.getInputFields());
        var response = new cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.CleanRuleRespVO();
        response.setId(9_007_199_254_740_993L);
        response.setEngineSupported(true); response.setRowTransformSupported(false);
        response.setColumnBinding("MULTIPLE");
        Map<String, Object> wire = cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseMap(
                cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(response));
        assertEquals(Boolean.TRUE, wire.get("engineSupported"));
        assertEquals(Boolean.FALSE, wire.get("rowTransformSupported"));
        assertEquals("MULTIPLE", wire.get("columnBinding"));
    }

    @Test
    void archiveAuthorizationRequiresEtlAndGovernanceReadPermissions() {
        when(permissions.hasAnyPermissions(eq(9L), anyString()))
                .thenReturn(cn.iocoder.yudao.framework.common.pojo.CommonResult.success(false));
        QualityEtlArchiveAuthorizeReqDTO request = archiveRequest(100L);
        assertFalse(service.canArchiveReports(request));
        verify(reports, never()).selectById(any());
    }

    @Test
    void archiveAuthorizationAllowsVisibleUnlinkedInlineReport() {
        authorizeArchive(9L);
        when(reports.selectById(100L)).thenReturn(InlineQualityReportDO.builder()
                .id(100L).sourceType("ETL_INLINE").build());
        when(workOrders.getVisibleInlineReportWorkOrderId(100L, 9L)).thenReturn(null);
        assertTrue(service.canArchiveReports(archiveRequest(100L)));
    }

    @Test
    void archiveAuthorizationBlocksOpenWorkOrder() {
        authorizeArchive(9L);
        when(reports.selectById(100L)).thenReturn(InlineQualityReportDO.builder()
                .id(100L).sourceType("ETL_INLINE").build());
        when(workOrders.getVisibleInlineReportWorkOrderId(100L, 9L)).thenReturn(200L);
        when(workOrderReports.selectByWorkOrderId(200L)).thenReturn(QualityWorkOrderEtlReportDO.builder()
                .workOrderId(200L).reportId(100L).build());
        when(workOrderMapper.selectById(200L)).thenReturn(QualityWorkOrderDO.builder()
                .id(200L).sourceType("ETL_INLINE").orderStatus(QualityWorkOrderDO.STATUS_PENDING_RECHECK).build());
        assertFalse(service.canArchiveReports(archiveRequest(100L)));
        verify(submissions, never()).selectListByWorkOrderId(200L);
    }

    private static QualityEtlArchiveAuthorizeReqDTO archiveRequest(Long reportId) {
        QualityEtlArchiveAuthorizeReqDTO request = new QualityEtlArchiveAuthorizeReqDTO();
        request.setExecutionUserId(9L); request.setReportIds(List.of(reportId));
        return request;
    }

    private void authorizeArchive(Long actor) {
        when(permissions.hasAnyPermissions(eq(actor), anyString()))
                .thenReturn(cn.iocoder.yudao.framework.common.pojo.CommonResult.success(true));
    }

    @Test
    void rowPlanRejectsMissingFieldBinding() {
        authorizeQuality();
        when(rules.selectBatchIds(List.of(4L))).thenReturn(List.of(QualityRuleDO.builder().id(4L)
                .ruleCode("REQUIRED").ruleType("NOT_NULL").checkLevel("FIELD").status(0)
                .strategyKey("NOT_NULL_VALIDATION").paramSchema("{\"allowBlank\":false}")
                .severity("MID").build()));
        var request = new RowQualityPlanReqDTO();
        request.setExecutionUserId(9L);
        request.setRuleIds(List.of(4L));
        request.setInputFields(List.of("name"));
        var plan = service.getRowQualityPlan(request);
        assertTrue(plan.getRules().isEmpty());
        assertEquals("FIELD_BINDING_INVALID", plan.getUnsupportedRules().getFirst().getReason());
    }

    @Test
    void rowPlanMarksRowSetStrategyUnsupported() {
        authorizeQuality();
        when(rules.selectBatchIds(List.of(5L))).thenReturn(List.of(QualityRuleDO.builder().id(5L)
                .ruleCode("UNIQUE").ruleType("UNIQUE").checkLevel("FIELD").status(0)
                .strategyKey("UNIQUE_VALIDATION").paramSchema("{\"column\":\"\"}")
                .severity("HIGH").build()));
        var request = new RowQualityPlanReqDTO();
        request.setExecutionUserId(9L);
        request.setRuleIds(List.of(5L));
        request.setFieldBindings(Map.of("5", "code"));
        request.setInputFields(List.of("code"));
        var plan = service.getRowQualityPlan(request);
        assertTrue(plan.getRules().isEmpty());
        assertEquals("UNSUPPORTED_ROW_STRATEGY", plan.getUnsupportedRules().getFirst().getReason());
    }

    @Test
    void frozenPlanWithoutSchemaCompilesAndEvaluatesWithStableHashAndRuleCode() {
        authorizeQuality();
        QualityRuleDO firstOrder = QualityRuleDO.builder().id(6L).ruleCode("AMOUNT_RANGE")
                .ruleType("NUMERIC_BOUNDS").checkLevel("FIELD").status(0)
                .strategyKey("NUMERIC_RANGE_VALIDATION")
                .paramSchema("{\"column\":\"amount\",\"minValue\":\"10\",\"maxValue\":\"20\"}")
                .severity("HIGH").build();
        QualityRuleDO otherOrder = QualityRuleDO.builder().id(6L).ruleCode("AMOUNT_RANGE")
                .ruleType("NUMERIC_BOUNDS").checkLevel("FIELD").status(0)
                .strategyKey("NUMERIC_RANGE_VALIDATION")
                .paramSchema("{\"maxValue\":\"20\",\"column\":\"amount\",\"minValue\":\"10\"}")
                .severity("HIGH").build();
        when(rules.selectBatchIds(List.of(6L))).thenReturn(List.of(firstOrder), List.of(otherOrder));

        var request = new RowQualityPlanReqDTO();
        request.setExecutionUserId(9L);
        request.setRuleIds(List.of(6L));
        request.setFieldBindings(Map.of("6", "amount"));
        // inputFields intentionally omitted: dataforge freezes no client schema for this call.
        var plan = service.getRowQualityPlan(request);
        assertTrue(plan.isExecutable());
        assertEquals("AMOUNT_RANGE", plan.getRules().getFirst().getCode());
        assertEquals("NUMERIC_RANGE_VALIDATION", plan.getRules().getFirst().getStrategy());
        var evaluator = new QualityRowEvaluator();
        var compiled = evaluator.compile(plan.getRules().stream().map(rule -> new QualityRowRule(
                rule.getCode(), rule.getStrategy(), rule.getField(), rule.getConfig(), rule.getSeverity())).toList());
        assertEquals("AMOUNT_RANGE", compiled.evaluate(Map.of("amount", "9")).getFirst().ruleCode());
        assertTrue(compiled.evaluate(Map.of("amount", "10")).isEmpty());
        String firstPlanHash = plan.getPlanHash();
        assertEquals(firstPlanHash, service.getRowQualityPlan(request).getPlanHash());
    }

    @Test
    void notNullRuleWithEmptyConfigIsExecutableAndExplicitEmptySchemaRejectsBinding() {
        authorizeQuality();
        when(rules.selectBatchIds(List.of(7L))).thenReturn(List.of(QualityRuleDO.builder().id(7L)
                .ruleCode("REQUIRED_NAME").ruleType("CUSTOM_REQUIRED").checkLevel("FIELD").status(0)
                .strategyKey("NOT_NULL_VALIDATION").paramSchema("{}").severity("MID").build()));
        var request = new RowQualityPlanReqDTO();
        request.setExecutionUserId(9L);
        request.setRuleIds(List.of(7L));
        request.setFieldBindings(Map.of("7", "name"));
        var plan = service.getRowQualityPlan(request);
        assertTrue(plan.isExecutable());
        var compiled = new QualityRowEvaluator().compile(plan.getRules().stream().map(rule -> new QualityRowRule(
                rule.getCode(), rule.getStrategy(), rule.getField(), rule.getConfig(), rule.getSeverity())).toList());
        assertEquals("REQUIRED_NAME", compiled.evaluate(java.util.Collections.singletonMap("name", null))
                .getFirst().ruleCode());

        request.setInputFields(List.of());
        var noSchemaMatch = service.getRowQualityPlan(request);
        assertFalse(noSchemaMatch.isExecutable());
        assertEquals("FIELD_BINDING_INVALID", noSchemaMatch.getUnsupportedRules().getFirst().getReason());
    }

    @Test
    void inlineReportSameKeyAndPayloadReplaysButDifferentPayloadConflicts() {
        authorizeInline();
        var first = reportRequest(10L);
        final InlineQualityReportDO[] stored = new InlineQualityReportDO[1];
        when(reports.selectByIdempotencyKey("run:n1:b1")).thenAnswer(call -> stored[0]);
        doAnswer(call -> {
            stored[0] = call.getArgument(0);
            stored[0].setId(88L);
            return 1;
        }).when(reports).insert(any(InlineQualityReportDO.class));
        var accepted = service.acceptInlineQualityResult(first);
        assertEquals("ACCEPTED", accepted.getStatus());
        assertEquals("ETL_INLINE", stored[0].getSourceType());
        assertEquals("REPLAY", service.acceptInlineQualityResult(reportRequest(10L)).getStatus());
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> service.acceptInlineQualityResult(reportRequest(11L)));
        verify(reports, times(1)).insert(any(InlineQualityReportDO.class));
    }

    @Test
    void inlineReportPersistsFullPopulationIdentityAndBindsItToIdempotencyKey() {
        authorizeInline();
        var first = reportRequest(10L);
        first.setIdentityFields(List.of("customer_id", "source_code"));
        first.setIdentityHash("b".repeat(64));
        final InlineQualityReportDO[] stored = new InlineQualityReportDO[1];
        when(reports.selectByIdempotencyKey("run:n1:b1")).thenAnswer(call -> stored[0]);
        doAnswer(call -> { stored[0] = call.getArgument(0); stored[0].setId(89L); return 1; })
                .when(reports).insert(any(InlineQualityReportDO.class));

        assertEquals("ACCEPTED", service.acceptInlineQualityResult(first).getStatus());
        assertEquals(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(List.of("customer_id", "source_code")),
                stored[0].getIdentityFieldsJson());
        assertEquals("b".repeat(64), stored[0].getIdentityHash());
        assertEquals("REPLAY", service.acceptInlineQualityResult(first).getStatus());
        var changedPopulation = reportRequest(10L);
        changedPopulation.setIdentityFields(List.of("customer_id", "source_code"));
        changedPopulation.setIdentityHash("c".repeat(64));
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> service.acceptInlineQualityResult(changedPopulation));
        verify(reports, times(1)).insert(any(InlineQualityReportDO.class));
    }

    @Test
    void inlineReportQueryUsesExistingQualityReportPermissionAndReturnsReferences() {
        when(permissions.hasAnyPermissions(eq(9L), eq("governance:quality-audit-log:query")))
                .thenReturn(cn.iocoder.yudao.framework.common.pojo.CommonResult.success(true));
        when(workOrders.getVisibleInlineReportWorkOrderId(88L, 9L)).thenReturn(123L);
        when(reports.selectById(88L)).thenReturn(InlineQualityReportDO.builder().id(88L)
                .runId("run-1").nodeId("node-1").batchId("batch-1").sourceType("ETL_INLINE")
                .planHash("hash").totalCount(4L).passCount(3L).warnCount(0L).rejectCount(1L)
                .issueReferencesJson("[\"evidence:1\"]").build());
        var request = new cn.iocoder.yudao.module.governance.api.etl.dto.InlineQualityReportQueryReqDTO();
        request.setReportId(88L);
        cn.iocoder.yudao.module.governance.api.etl.dto.InlineQualityReportDTO result;
        try (MockedStatic<SecurityFrameworkUtils> security = org.mockito.Mockito.mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            result = service.queryInlineQualityReport(request);
        }
        assertEquals("ETL_INLINE", result.getSourceType());
        assertEquals(List.of("evidence:1"), result.getIssueReferences());
        assertEquals(123L, result.getWorkOrderId());
        verify(reports).selectById(88L);
    }

    private void authorizeQuality() {
        when(permissions.hasAnyPermissions(eq(9L), anyString()))
                .thenReturn(cn.iocoder.yudao.framework.common.pojo.CommonResult.success(true));
    }

    private void authorizeInline() {
        when(permissions.hasAnyPermissions(eq(9L), eq("governance:quality-rule:query")))
                .thenReturn(cn.iocoder.yudao.framework.common.pojo.CommonResult.success(true));
    }

    private InlineQualityResultReqDTO reportRequest(long passCount) {
        var request = new InlineQualityResultReqDTO();
        request.setExecutionUserId(9L);
        request.setRunId("run1"); request.setNodeId("n1"); request.setBatchId("b1");
        request.setPlanHash("a".repeat(64)); request.setIdempotencyKey("run:n1:b1");
        request.setTotalCount(10L); request.setPassCount(passCount); request.setWarnCount(0L);
        request.setRejectCount(10L - passCount); request.setIssueReferences(List.of("evidence:1"));
        return request;
    }
}
