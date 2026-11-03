package cn.iocoder.yudao.module.governance.service.qualityrule;

import cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionRegistry;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.CleanRuleSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorycleanrule.CategoryCleanRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule.CleanRuleDO;
import cn.iocoder.yudao.module.governance.dal.mysql.categorycleanrule.CategoryCleanRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.qualityrule.CleanRuleMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class CleanRuleParameterPersistenceTest {

    private final CleanRuleServiceImpl service = new CleanRuleServiceImpl();
    private final CleanRuleMapper rules = mock(CleanRuleMapper.class);
    private final CategoryCleanRuleMapper categories = mock(CategoryCleanRuleMapper.class);

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "clean-rule-config"), CleanRuleDO.class);
        ReflectionTestUtils.setField(service, "cleanRuleMapper", rules);
        ReflectionTestUtils.setField(service, "categoryCleanRuleMapper", categories);
        ReflectionTestUtils.setField(service, "cleanFunctionRegistry", new CleanFunctionRegistry());
        when(categories.selectById(2L)).thenReturn(CategoryCleanRuleDO.builder().id(2L).build());
        when(rules.selectByName("Precise boundary")).thenReturn(null);
    }

    @Test
    void createPersistsCanonicalParametersAsExactJsonAndUnknownKeysAreRejected() {
        String min = "9007199254740993.123456789012345678901";
        doAnswer(invocation -> {
            CleanRuleDO stored = invocation.getArgument(0);
            stored.setId(51L);
            return 1;
        }).when(rules).insert(any(CleanRuleDO.class));

        CleanRuleSaveReqVO req = request(Map.of("min", min, "max", "9007199254740994.123456789012345678901"));
        assertEquals(51L, service.createCleanRule(req));
        var captor = org.mockito.ArgumentCaptor.forClass(CleanRuleDO.class);
        verify(rules).insert(captor.capture());
        assertEquals(Map.of("min", min, "max", "9007199254740994.123456789012345678901", "handleType", 1),
                JsonUtils.parseObject(captor.getValue().getConfigJson(), Map.class));

        CleanRuleSaveReqVO invalid = request(Map.of("min", "0", "max", "1", "columns", "client-field"));
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> service.createCleanRule(invalid));
        verify(rules, times(1)).insert(any(CleanRuleDO.class));
    }

    @Test
    void unknownLegacyStrategyCanBeSavedOnlyWithoutParameters() {
        CleanRuleSaveReqVO req = request(Map.of());
        req.setStrategyKey("LEGACY_DISPLAY_ONLY");
        doAnswer(invocation -> {
            CleanRuleDO stored = invocation.getArgument(0);
            stored.setId(52L);
            return 1;
        }).when(rules).insert(any(CleanRuleDO.class));
        assertEquals(52L, service.createCleanRule(req));
        verify(rules).insert(argThat((CleanRuleDO rule) -> "{}".equals(rule.getConfigJson())));

        req.setConfig(Map.of("arbitrary", "value"));
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> service.createCleanRule(req));
    }

    @Test
    void testResponseSerializesExactNumericResultsAsStringsBeforeJsonEncoding() {
        var registry = new CleanFunctionRegistry();
        registry.register(new cn.iocoder.yudao.framework.quality.core.clean.CleanFunction() {
            @Override public String getStrategyKey() { return "EXACT_TEST"; }
            @Override public String getStrategyName() { return "精确数值试跑契约"; }
            @Override public String getConfigExample() { return "{}"; }
            @Override public java.util.List<Map<String, Object>> apply(java.util.List<Map<String, Object>> rows, Map<String, Object> config) {
                return java.util.List.of(Map.of("amount", new java.math.BigDecimal("9007199254740993.123456789012345678901"),
                        "nested", Map.of("values", java.util.List.of(new java.math.BigInteger("900719925474099300000"),
                                new java.math.BigDecimal("0.00000000000000000001")))));
            }
        });
        ReflectionTestUtils.setField(service, "cleanFunctionRegistry", registry);
        var request = new cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.CleanRuleTestReqVO();
        request.setStrategyKey("EXACT_TEST"); request.setConfigJson("{}"); request.setRowsJson("[{}]");
        var response = service.testCleanRule(request);
        String wire = JsonUtils.toJsonString(response);
        assertTrue(wire.contains("\"9007199254740993.123456789012345678901\""));
        assertTrue(wire.contains("\"900719925474099300000\""));
        assertTrue(wire.contains("\"0.00000000000000000001\""));
    }

    @Test
    void rawDecimalSampleIsParsedExactlyBeforeTheAlgorithmRuns() {
        var request = new cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.CleanRuleTestReqVO();
        request.setStrategyKey("UNIT_CONVERSION");
        request.setConfigJson("{\"columns\":[\"amount\"],\"factor\":\"1\",\"offset\":\"0\",\"scale\":12}");
        request.setRowsJson("[{\"amount\":9007199254740993.123456789012,\"count\":900719925474099300000000000000000000000}]");
        var response = service.testCleanRule(request);
        assertEquals("9007199254740993.123456789012", response.getRows().getFirst().get("amount"));
        assertEquals("900719925474099300000000000000000000000", response.getRows().getFirst().get("count"));
    }

    @Test
    void updateWithoutConfigPreservesExistingParametersAndCannotChangeFunctionSilently() {
        CleanRuleDO current = CleanRuleDO.builder().id(51L).name("Precise boundary").categoryId(2L)
                .ruleLevel(1).strategyKey("WITHIN_BOUNDARY")
                .configJson("{\"min\":\"1.25\",\"max\":\"9.75\",\"handleType\":2}").build();
        when(rules.selectById(51L)).thenReturn(current);
        doAnswer(invocation -> {
            CleanRuleDO updated = invocation.getArgument(0);
            assertEquals("{\"min\":\"1.25\",\"max\":\"9.75\",\"handleType\":2}", updated.getConfigJson());
            return 1;
        }).when(rules).updateById(any(CleanRuleDO.class));

        CleanRuleSaveReqVO req = request(null);
        req.setId(51L);
        service.updateCleanRule(req);
        verify(rules).updateById(any(CleanRuleDO.class));

        req.setStrategyKey("REMOVE_WHITESPACE");
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> service.updateCleanRule(req));
        verify(rules, times(1)).updateById(any(CleanRuleDO.class));
    }

    private static CleanRuleSaveReqVO request(Map<String, Object> config) {
        CleanRuleSaveReqVO req = new CleanRuleSaveReqVO();
        req.setName("Precise boundary");
        req.setCategoryId(2L);
        req.setStrategyKey("WITHIN_BOUNDARY");
        req.setRuleLevel(1);
        req.setConfig(config);
        return req;
    }
}
