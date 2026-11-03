package cn.iocoder.yudao.module.governance.controller.admin.identify;

import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.governance.dal.dataobject.category.SafetyCategoryDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.identify.SafetyIdentifyRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.level.SafetyLevelDO;
import cn.iocoder.yudao.module.governance.dal.mysql.category.SafetyCategoryMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.level.SafetyLevelMapper;
import cn.iocoder.yudao.module.governance.service.identify.SafetyIdentifyService;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

class SafetyIdentifyRuleControllerTest extends BaseMockitoUnitTest {
    @InjectMocks private SafetyIdentifyRuleController controller;
    @Mock private SafetyIdentifyService identifyService;
    @Mock private SafetyCategoryMapper categoryMapper;
    @Mock private SafetyLevelMapper levelMapper;

    @Test
    void inheritedLevelIsDisplayedWithoutChangingStoredRuleConfiguration() {
        when(identifyService.getRule(1L)).thenReturn(SafetyIdentifyRuleDO.builder().id(1L).categoryId(2L).build());
        when(categoryMapper.selectByIds(anyCollection())).thenReturn(List.of(
                SafetyCategoryDO.builder().id(2L).name("分类").dataLevelId(3).build()));
        when(levelMapper.selectList()).thenReturn(List.of(SafetyLevelDO.builder().id(3L).safetyName("三级").build()));

        var rule = controller.getRule(1L).getCheckedData();
        assertNull(rule.getLevelId());
        assertEquals("三级", rule.getLevelName());
        assertEquals("分类", rule.getCategoryName());
    }

    @Test
    void explicitLevelTakesPrecedenceOverCategoryLevel() {
        when(identifyService.getRule(1L)).thenReturn(SafetyIdentifyRuleDO.builder().id(1L).categoryId(2L).levelId(4L).build());
        when(categoryMapper.selectByIds(anyCollection())).thenReturn(List.of(
                SafetyCategoryDO.builder().id(2L).dataLevelId(3).build()));
        when(levelMapper.selectList()).thenReturn(List.of(SafetyLevelDO.builder().id(4L).safetyName("四级").build()));

        var rule = controller.getRule(1L).getCheckedData();
        assertEquals(4L, rule.getLevelId());
        assertEquals("四级", rule.getLevelName());
    }
}
