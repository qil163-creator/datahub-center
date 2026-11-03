package cn.iocoder.yudao.module.governance.api.etl.dto;

import lombok.Data;

@Data
public class UnsupportedQualityRuleDTO {

    private Long ruleId;

    private String code;

    private String reason;
}
