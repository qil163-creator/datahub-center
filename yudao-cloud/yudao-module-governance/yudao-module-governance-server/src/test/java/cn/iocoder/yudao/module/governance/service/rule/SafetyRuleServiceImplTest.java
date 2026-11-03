package cn.iocoder.yudao.module.governance.service.rule;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.governance.dal.dataobject.rule.SafetyRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.rulever.SafetyRuleVerDO;
import cn.iocoder.yudao.module.governance.dal.mysql.permission.SafetyFieldPermissionMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.replaceitem.SafetyReplaceItemMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.rule.SafetyRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.rulever.SafetyRuleVerMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.statictask.SafetyStaticTaskMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.RULE_REFERENCED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SafetyRuleServiceImplTest {

    @Mock
    private SafetyRuleMapper ruleMapper;
    @Mock
    private SafetyRuleVerMapper ruleVerMapper;
    @Mock
    private SafetyReplaceItemMapper replaceItemMapper;
    @Mock
    private SafetyFieldPermissionMapper fieldPermissionMapper;
    @Mock
    private SafetyStaticTaskMapper staticTaskMapper;
    @InjectMocks
    private SafetyRuleServiceImpl service;

    @Test
    void deleteRule_rejectsActiveFieldPermissionReference() {
        SafetyRuleDO rule = SafetyRuleDO.builder().id(4L).sourceType("CUSTOM").currentVerId(9L).build();
        when(ruleMapper.selectForUpdate(4L)).thenReturn(rule);
        when(fieldPermissionMapper.countActiveMaskByRuleId(4L)).thenReturn(1L);
        when(staticTaskMapper.countByRuleId(4L)).thenReturn(0L);

        assertThatThrownBy(() -> service.deleteRule(4L))
                .isInstanceOf(ServiceException.class)
                .extracting("code")
                .isEqualTo(RULE_REFERENCED.getCode());
        verify(ruleMapper, never()).deleteById(4L);
    }

    @Test
    void resolveUsableMaskingSpec_disabledOrUnpublished_isUnavailable() {
        SafetyRuleDO disabled = SafetyRuleDO.builder().id(4L).currentVerId(9L)
                .status(CommonStatusEnum.DISABLE.getStatus().shortValue()).build();
        when(ruleMapper.selectById(4L)).thenReturn(disabled);
        assertThat(service.resolveUsableMaskingSpec(4L)).isNull();

        SafetyRuleDO enabled = SafetyRuleDO.builder().id(5L).currentVerId(10L)
                .status(CommonStatusEnum.ENABLE.getStatus().shortValue()).methodCode("MASK").build();
        when(ruleMapper.selectById(5L)).thenReturn(enabled);
        when(ruleVerMapper.selectById(10L)).thenReturn(SafetyRuleVerDO.builder()
                .id(10L).ruleId(5L).status(SafetyRuleVerDO.STATUS_DRAFT).configJson("{}").build());
        assertThat(service.resolveUsableMaskingSpec(5L)).isNull();
    }
}
