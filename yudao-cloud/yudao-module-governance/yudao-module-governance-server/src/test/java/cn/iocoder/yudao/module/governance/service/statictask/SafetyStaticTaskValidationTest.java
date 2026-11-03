package cn.iocoder.yudao.module.governance.service.statictask;

import cn.iocoder.yudao.module.governance.controller.admin.statictask.vo.SafetyStaticTaskFieldRuleVO;
import cn.iocoder.yudao.module.governance.controller.admin.statictask.vo.SafetyStaticTaskSaveReqVO;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SafetyStaticTaskValidationTest {

    @Test
    void rejectsNullAndIncompleteFieldRules() {
        var req = new SafetyStaticTaskSaveReqVO();
        req.setTaskName("测试");
        req.setSourceDatabaseId(1L);
        req.setSourceTableName("source");
        req.setTargetDatabaseId(2L);
        req.setTargetTableName("target");
        req.setWriteMode("APPEND");
        req.setStatus(0);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            req.setFieldRules(Collections.singletonList(null));
            assertThat(validator.validate(req)).anyMatch(v -> v.getMessage().equals("脱敏字段配置不能为空"));
            var fieldRule = new SafetyStaticTaskFieldRuleVO();
            req.setFieldRules(List.of(fieldRule));
            assertThat(validator.validate(req)).extracting(v -> v.getMessage())
                    .contains("脱敏字段名不能为空", "脱敏规则不能为空");
            fieldRule.setColumnName("phone");
            fieldRule.setRuleId(1L);
            assertThat(validator.validate(req)).isEmpty();
        }
    }
}
