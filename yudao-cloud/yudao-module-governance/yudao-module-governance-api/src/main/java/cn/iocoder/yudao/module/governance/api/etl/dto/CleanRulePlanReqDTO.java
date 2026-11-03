package cn.iocoder.yudao.module.governance.api.etl.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** ETL 请求冻结一组启用的治理清洗规则；规则顺序具有业务含义。 */
@Data
public class CleanRulePlanReqDTO {

    @NotNull
    private Long executionUserId;

    @NotNull
    @Size(min = 1, max = 50)
    @Valid
    private List<Rule> rules;

    /** 可用时传入冻结的输入字段，执行器仍会复核每一行。 */
    @Size(max = 1024)
    private List<@jakarta.validation.constraints.NotBlank @Size(max = 128) String> inputFields;

    @Data
    public static class Rule {

        @NotNull
        private Long ruleId;

        @Size(max = 128)
        private String field;

        /** 行集规则的字段绑定，与单字段 field 二选一。 */
        @Size(min = 1, max = 32)
        private List<@jakarta.validation.constraints.NotBlank @Size(max = 128) String> fields;

        /** 旧版 ETL 节点内嵌配置的可选断言，绝不作为执行输入。 */
        private Map<String, Object> legacyConfig;
    }
}
