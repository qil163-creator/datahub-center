package cn.iocoder.yudao.module.governance.service.identify;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.governance.controller.admin.identify.vo.SafetyIdentifyRuleSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.category.SafetyCategoryDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.identify.SafetyIdentifyRuleDO;
import cn.iocoder.yudao.module.governance.dal.mysql.category.SafetyCategoryMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.identify.SafetyIdentifyRuleMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;


import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.IDENTIFY_RULE_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SafetyIdentifyRuleServiceTest extends BaseMockitoUnitTest {
    @InjectMocks private SafetyIdentifyServiceImpl service;
    @Mock private SafetyIdentifyRuleMapper rules;
    @Mock private SafetyCategoryMapper categories;

    @Test
    void missingRuleReturnsBusinessError() {
        var failure = assertThrows(ServiceException.class, () -> service.getRule(1L));
        assertEquals(IDENTIFY_RULE_NOT_EXISTS.getCode(), failure.getCode());
    }

    @Test
    void editingCanClearTheExplicitLevelAndDescription() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SafetyIdentifyRuleDO.class);
        when(rules.selectById(1L)).thenReturn(SafetyIdentifyRuleDO.builder().id(1L).levelId(4L).build());
        when(categories.selectById(2L)).thenReturn(SafetyCategoryDO.builder().id(2L).build());
        var request = new SafetyIdentifyRuleSaveReqVO();
        request.setId(1L);
        request.setCategoryId(2L);
        request.setRuleName("手机号");
        request.setMatchType("COLUMN_NAME");
        request.setPattern("phone");

        service.updateRule(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<SafetyIdentifyRuleDO>> wrapper = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(rules).update(any(SafetyIdentifyRuleDO.class), wrapper.capture());
        assertTrue(wrapper.getValue().getSqlSet().contains("level_id="));
        assertTrue(wrapper.getValue().getSqlSet().contains("description="));
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue(null));
    }

    @Test
    void editingWithExplicitLevelKeepsItsValue() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SafetyIdentifyRuleDO.class);
        when(rules.selectById(1L)).thenReturn(SafetyIdentifyRuleDO.builder().id(1L).levelId(4L).build());
        when(categories.selectById(2L)).thenReturn(SafetyCategoryDO.builder().id(2L).build());
        var request = new SafetyIdentifyRuleSaveReqVO();
        request.setId(1L);
        request.setCategoryId(2L);
        request.setRuleName("手机号");
        request.setMatchType("COLUMN_NAME");
        request.setPattern("phone");
        request.setLevelId(4L);
        request.setDescription("测试");

        service.updateRule(request);

        var entity = ArgumentCaptor.forClass(SafetyIdentifyRuleDO.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<SafetyIdentifyRuleDO>> wrapper = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(rules).update(entity.capture(), wrapper.capture());
        assertEquals(4L, entity.getValue().getLevelId());
        assertEquals("测试", entity.getValue().getDescription());
        assertNull(wrapper.getValue().getSqlSet());
    }


}
