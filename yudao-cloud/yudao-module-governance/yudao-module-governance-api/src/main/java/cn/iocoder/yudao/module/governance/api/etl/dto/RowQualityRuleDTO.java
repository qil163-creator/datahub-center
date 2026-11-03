package cn.iocoder.yudao.module.governance.api.etl.dto;

import lombok.Data;

import java.util.Map;

@Data
public class RowQualityRuleDTO {

    private Long ruleId;

    private String code;

    private String strategy;

    private String field;

    private Map<String, Object> config;

    /** INFO、WARN、ERROR（信息、警告、错误）。 */
    private String severity;
}
