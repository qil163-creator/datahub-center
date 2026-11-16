package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterMatchRuleVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterColumnDO;
import cn.iocoder.yudao.module.governance.service.master.match.MasterMatchConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.MASTER_MATCH_MERGE_INVALID;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.MASTER_MATCH_RULE_INVALID;
import static cn.iocoder.yudao.module.governance.service.master.MasterDataQueryFilterTest.column;
import static org.junit.jupiter.api.Assertions.*;

/** 相似匹配规则按模型字段校验，以及黄金记录取值只能来自参与合并的记录 */
class MasterMatchRuleValidationTest {

    private static final List<MasterColumnDO> COLUMNS = List.of(column("id", ColumnMetaData.TYPE_INTEGER, 1),
            column("name", ColumnMetaData.TYPE_STRING, 0), column("amount", ColumnMetaData.TYPE_BIGNUMBER, 0),
            column("active", ColumnMetaData.TYPE_BOOLEAN, 0));

    private static MasterMatchRuleVO.Field field(String name, String algorithm, int weight) {
        var field = new MasterMatchRuleVO.Field();
        field.setName(name);
        field.setAlgorithm(algorithm);
        field.setWeight(weight);
        return field;
    }

    private static MasterMatchRuleVO rule(List<MasterMatchRuleVO.Field> fields, String threshold) {
        var rule = new MasterMatchRuleVO();
        rule.setModelId(1L);
        rule.setFields(fields);
        rule.setThreshold(threshold);
        return rule;
    }

    private static String invalid(MasterMatchRuleVO rule) {
        var error = assertThrows(ServiceException.class, () -> MasterMatchService.parse(rule, COLUMNS));
        assertEquals(MASTER_MATCH_RULE_INVALID.getCode(), error.getCode());
        return error.getMessage();
    }

    @Test
    void validRuleIsNormalized() {
        var rule = rule(List.of(field("name", "TEXT", 3), field("amount", "NUMERIC", 1)), "0.85");
        rule.setBlockingFields(List.of("active"));
        rule.setSurvivorship(Map.of("amount", "MAX", "name", "SURVIVOR"));
        MasterMatchConfig config = MasterMatchService.parse(rule, COLUMNS);
        assertEquals(new BigDecimal("0.8500"), config.threshold());
        assertEquals(List.of("active"), config.blockingFields());
        assertEquals(Map.of("amount", MasterMatchConfig.Survivorship.MAX), config.survivorship(),
                "默认规则 SURVIVOR 不单独保存");
        assertEquals(MasterMatchConfig.DEFAULT_MAX_ROWS, config.maxRows());
    }

    @Test
    void ruleRejectsFieldsAlgorithmsAndThresholdsThatDoNotFitTheModel() {
        assertTrue(invalid(rule(List.of(field("missing", "EXACT", 1)), "0.9")).contains("不存在"));
        assertTrue(invalid(rule(List.of(field("id", "EXACT", 1)), "0.9")).contains("主键"));
        assertTrue(invalid(rule(List.of(field("amount", "TEXT", 1)), "0.9")).contains("不支持所选算法"));
        assertTrue(invalid(rule(List.of(field("name", "NUMERIC", 1)), "0.9")).contains("不支持所选算法"));
        assertTrue(invalid(rule(List.of(field("name", "EXACT", 1), field("name", "TEXT", 1)), "0.9")).contains("重复"));
        assertTrue(invalid(rule(List.of(field("name", "TEXT", 1)), "0.4")).contains("0.5至1"));
        assertTrue(invalid(rule(List.of(field("name", "TEXT", 1)), "0.12345")).contains("0.5至1"));
        var bool = rule(List.of(field("name", "TEXT", 1)), "0.9");
        bool.setSurvivorship(Map.of("active", "MAX"));
        assertTrue(invalid(bool).contains("布尔"));
        var unknown = rule(List.of(field("name", "TEXT", 1)), "0.9");
        unknown.setSurvivorship(Map.of("name", "LONGEST"));
        assertTrue(invalid(unknown).contains("取值规则"));
    }

    @Test
    void minimalSpringContextWiresMatchServiceAndController() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            var beans = context.getBeanFactory();
            beans.registerSingleton("masterMatchRuleMapper",
                    org.mockito.Mockito.mock(cn.iocoder.yudao.module.governance.dal.mysql.master.MasterMatchRuleMapper.class));
            beans.registerSingleton("masterMatchRunMapper",
                    org.mockito.Mockito.mock(cn.iocoder.yudao.module.governance.dal.mysql.master.MasterMatchRunMapper.class));
            beans.registerSingleton("masterMatchGroupMapper",
                    org.mockito.Mockito.mock(cn.iocoder.yudao.module.governance.dal.mysql.master.MasterMatchGroupMapper.class));
            beans.registerSingleton("masterMergeXrefMapper",
                    org.mockito.Mockito.mock(cn.iocoder.yudao.module.governance.dal.mysql.master.MasterMergeXrefMapper.class));
            beans.registerSingleton("masterDataChangeMapper",
                    org.mockito.Mockito.mock(cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDataChangeMapper.class));
            beans.registerSingleton("masterColumnMapper",
                    org.mockito.Mockito.mock(cn.iocoder.yudao.module.governance.dal.mysql.master.MasterColumnMapper.class));
            beans.registerSingleton("masterModelService", org.mockito.Mockito.mock(MasterModelService.class));
            beans.registerSingleton("masterDataService", org.mockito.Mockito.mock(MasterDataService.class));
            beans.registerSingleton("masterDataChangeService", org.mockito.Mockito.mock(MasterDataChangeService.class));
            beans.registerSingleton("adminUserApi",
                    org.mockito.Mockito.mock(cn.iocoder.yudao.module.system.api.user.AdminUserApi.class));
            beans.registerSingleton("transactionManager",
                    org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class));
            context.register(MasterMatchService.class,
                    cn.iocoder.yudao.module.governance.controller.admin.master.MasterMatchController.class);
            context.refresh();
            assertNotNull(org.springframework.test.util.ReflectionTestUtils.getField(
                    context.getBean(MasterMatchService.class), "groupMapper"));
            assertNotNull(context.getBean(
                    cn.iocoder.yudao.module.governance.controller.admin.master.MasterMatchController.class));
        }
    }

    @Test
    void goldenValuesMustComeFromParticipatingRecords() {
        List<Map<String, Object>> live = List.of(
                new LinkedHashMap<>(Map.of("id", "1", "name", "华为", "amount", "1.50", "active", true)),
                new LinkedHashMap<>(Map.of("id", "2", "name", "华为技术", "amount", "2.00", "active", false)));
        Map<String, Object> golden = MasterMatchService.golden(
                Map.of("id", "1", "name", "华为技术", "amount", "1.50", "active", false), live, COLUMNS);
        assertEquals("华为技术", golden.get("name"));
        assertEquals(MASTER_MATCH_MERGE_INVALID.getCode(), assertThrows(ServiceException.class, () -> MasterMatchService
                .golden(Map.of("id", "2", "name", "华为", "amount", "1.50", "active", true), live, COLUMNS)).getCode(),
                "主键须取保留记录");
        assertThrows(ServiceException.class, () -> MasterMatchService.golden(
                Map.of("id", "1", "name", "华为", "amount", "1.5", "active", true), live, COLUMNS),
                "精确小数按传输形式比较，1.5 与 1.50 不同");
        assertThrows(ServiceException.class, () -> MasterMatchService.golden(
                Map.of("id", "1", "name", "华为"), live, COLUMNS), "须覆盖全部字段");
    }
}
