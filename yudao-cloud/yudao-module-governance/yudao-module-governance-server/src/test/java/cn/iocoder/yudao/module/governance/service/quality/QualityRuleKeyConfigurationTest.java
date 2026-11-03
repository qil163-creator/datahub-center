package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.rule.QualityRuleSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRuleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.AUDIT_RULE_CONFIG_INVALID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QualityRuleKeyConfigurationTest {
    @Mock private QualityRuleMapper mapper;
    @Mock private AuditSqlRegistry registry;
    @InjectMocks private QualityRuleServiceImpl service;

    @Test void creationRejectsInvalidDeclarationsWithoutWrites() {
        for (String json : List.of("[]", "null", "{broken}", "{\"pkColumns\":null}",
                "{\"pkColumns\":[\"id\"]}", "{\"pkColumns\":[\"id\",\"ID\"]}",
                "{\"pkColumn\":\"id\",\"pkColumns\":[\"id\",\"source_id\"]}")) {
            assertThatThrownBy(() -> service.createQualityRule(request(json)))
                    .isInstanceOf(ServiceException.class).extracting("code").isEqualTo(AUDIT_RULE_CONFIG_INVALID.getCode());
        }
        verify(mapper, never()).insert(any(QualityRuleDO.class));
    }

    @Test void updateRejectsInvalidDeclarationWithoutWrites() {
        when(mapper.selectById(1L)).thenReturn(QualityRuleDO.builder().id(1L).build());
        var request = request("{\"pkColumns\":[\"id\",\"a;DELETE\"]}"); request.setId(1L);
        assertThatThrownBy(() -> service.updateQualityRule(request)).isInstanceOf(ServiceException.class);
        verify(mapper, never()).updateById(any(QualityRuleDO.class));
    }

    @Test void validSingleCompositeAndNoKeyRulesRemainSaveable() {
        for (String json : List.of("", "{}", "{\"pkColumn\":\"id\"}", "{\"pkColumns\":[\"source_id\",\"id\"]}")) {
            assertThatCode(() -> service.createQualityRule(request(json))).doesNotThrowAnyException();
        }
        verify(mapper, times(4)).insert(any(QualityRuleDO.class));
    }

    private QualityRuleSaveReqVO request(String json) {
        var request = new QualityRuleSaveReqVO(); request.setRuleName("key-fixture"); request.setParamSchema(json);
        return request;
    }
}
