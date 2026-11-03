package cn.iocoder.yudao.module.governance.service.etl;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionRegistry;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.governance.api.etl.dto.CleanRulePlanReqDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule.CleanRuleDO;
import cn.iocoder.yudao.module.governance.dal.mysql.qualityrule.CleanRuleMapper;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GovernanceFortyCleanPlanTest {
    private final GovernanceEtlService service = new GovernanceEtlService();
    private final CleanRuleMapper mapper = mock(CleanRuleMapper.class);
    private final PermissionApi permissions = mock(PermissionApi.class);
    private final Map<Long, CleanRuleDO> stored = new HashMap<>();

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "cleanRuleMapper", mapper);
        ReflectionTestUtils.setField(service, "cleanFunctionRegistry", new CleanFunctionRegistry());
        ReflectionTestUtils.setField(service, "permissionApi", permissions);
        ReflectionTestUtils.setField(service, "userApi", mock(AdminUserApi.class));
        when(permissions.hasAnyPermissions(9L, "governance:clean-rule:query")).thenReturn(CommonResult.success(true));
        when(mapper.selectBatchIds(anyCollection())).thenAnswer(call -> ((Collection<?>) call.getArgument(0)).stream()
                .map(stored::get).filter(Objects::nonNull).sorted(Comparator.comparing(CleanRuleDO::getId).reversed()).toList());
        TenantContextHolder.clear();
    }
    @AfterEach void clear() { TenantContextHolder.clear(); }

    @Test void wholeRowRuleFreezesWithoutDummyBindingAndReauthorizesItsOwnSnapshot() {
        store(1L, "REMOVE_EMPTY_ROW", "{}");
        var request = request(rule(1L, null, null), List.of("a", "b"));
        var frozen = service.snapshotCleanRulePlan(request);
        assertEquals(List.of(), frozen.getRules().getFirst().getConfig().get("columns"));
        request.getRules().getFirst().setLegacyConfig(frozen.getRules().getFirst().getConfig());
        assertEquals(frozen.getPlanHash(), service.snapshotCleanRulePlan(request).getPlanHash());
        request.getRules().getFirst().setField("a");
        assertThrows(ServiceException.class, () -> service.snapshotCleanRulePlan(request));
    }

    @Test void meanFillNeedsWholeInputButExactlyOneBinding() {
        store(2L, "NUMBER_FILL_NULL", "{\"mode\":\"MEAN\",\"value\":\"9007199254740993.123\",\"scale\":3}");
        var request = request(rule(2L, "amount", null), List.of("id", "amount"));
        var frozen = service.snapshotCleanRulePlan(request);
        assertEquals(List.of("amount"), frozen.getRules().getFirst().getConfig().get("columns"));
        assertEquals("9007199254740993.123", frozen.getRules().getFirst().getConfig().get("value"));
        request.getRules().getFirst().setField(null); request.getRules().getFirst().setFields(List.of("id", "amount"));
        assertThrows(ServiceException.class, () -> service.snapshotCleanRulePlan(request));
    }

    record Auxiliary(String strategy, String config, List<String> fields) { }
    static Stream<Auxiliary> auxiliaries() {
        return Stream.of(new Auxiliary("PK_DEDUP_LATEST", "{\"timeField\":\"updated\"}", List.of("updated")),
                new Auxiliary("DEDUP_BY_PRIORITY", "{\"priorityField\":\"source\",\"priorityValues\":[\"high\",\"low\"],\"timeField\":\"updated\"}", List.of("source", "updated")),
                new Auxiliary("DEDUP_PREFER_NON_NULL", "{\"preferredFields\":[\"phone\",\"name\"],\"timeField\":\"updated\"}", List.of("phone", "name", "updated")));
    }
    @ParameterizedTest @MethodSource("auxiliaries")
    void everyAuxiliaryFieldMustBePresentWhenFreezingTheInputSchema(Auxiliary example) {
        store(3L, example.strategy(), example.config());
        List<String> input = new ArrayList<>(List.of("id", "kind")); input.addAll(example.fields());
        var request = request(rule(3L, null, List.of("id", "kind")), input);
        assertEquals(List.of("id", "kind"), service.snapshotCleanRulePlan(request).getRules().getFirst().getConfig().get("columns"));
        for (String field : example.fields()) {
            request.setInputFields(input.stream().filter(value -> !value.equals(field)).toList());
            assertThrows(ServiceException.class, () -> service.snapshotCleanRulePlan(request), field);
        }
    }

    @Test void frozenRuleOrderAndExactPersistedParametersDetermineTheHash() {
        store(4L, "UNIT_CONVERSION", "{\"factor\":\"0.001\",\"offset\":\"0\",\"scale\":3}");
        store(5L, "NUMBER_FILL_NULL", "{\"value\":\"9007199254740993.123\"}");
        var first = rule(4L, "amount", null); var second = rule(5L, "amount", null);
        var request = request(first, List.of("amount")); request.setRules(List.of(first, second));
        var plan = service.snapshotCleanRulePlan(request);
        assertEquals(List.of(4L, 5L), plan.getRules().stream().map(value -> value.getRuleId()).toList());
        assertEquals("0.001", plan.getRules().getFirst().getConfig().get("factor"));
        request.setRules(List.of(second, first));
        assertNotEquals(plan.getPlanHash(), service.snapshotCleanRulePlan(request).getPlanHash());
        first.setLegacyConfig(Map.of("columns", List.of("amount"), "factor", "0.002", "offset", "0", "scale", 3));
        assertThrows(ServiceException.class, () -> service.snapshotCleanRulePlan(request));
    }

    @Test void malformedNewRuleConfigAndMissingPermissionFailBeforeExecution() {
        store(6L, "UNIT_CONVERSION", "{\"factor\":\"1\",\"sql\":\"ignored\"}");
        var request = request(rule(6L, "amount", null), List.of("amount"));
        assertThrows(ServiceException.class, () -> service.snapshotCleanRulePlan(request));
        clearInvocations(mapper);
        when(permissions.hasAnyPermissions(9L, "governance:clean-rule:query")).thenReturn(CommonResult.success(false));
        assertThrows(ServiceException.class, () -> service.snapshotCleanRulePlan(request));
        verifyNoInteractions(mapper);
    }

    private void store(Long id, String strategy, String json) {
        stored.put(id, CleanRuleDO.builder().id(id).code("CLEAN_" + id).name(strategy).ruleLevel(1)
                .status(CleanRuleDO.STATUS_ENABLE).strategyKey(strategy).configJson(json).build());
    }
    private static CleanRulePlanReqDTO.Rule rule(Long id, String field, List<String> fields) {
        var rule = new CleanRulePlanReqDTO.Rule(); rule.setRuleId(id); rule.setField(field); rule.setFields(fields); return rule;
    }
    private static CleanRulePlanReqDTO request(CleanRulePlanReqDTO.Rule rule, List<String> input) {
        var request = new CleanRulePlanReqDTO(); request.setExecutionUserId(9L); request.setRules(List.of(rule)); request.setInputFields(input); return request;
    }
}
