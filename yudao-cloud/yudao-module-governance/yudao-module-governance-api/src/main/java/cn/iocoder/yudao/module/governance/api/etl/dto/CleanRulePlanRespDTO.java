package cn.iocoder.yudao.module.governance.api.etl.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** ETL 发布时冻结的治理清洗规则及其执行参数。 */
@Data
public class CleanRulePlanRespDTO {

    private String planHash;

    private LocalDateTime frozenAt;

    private List<Rule> rules;

    @Data
    public static class Rule {

        private Long ruleId;

        private String code;

        private String name;

        private String strategyKey;

        private Map<String, Object> config;
    }
}
