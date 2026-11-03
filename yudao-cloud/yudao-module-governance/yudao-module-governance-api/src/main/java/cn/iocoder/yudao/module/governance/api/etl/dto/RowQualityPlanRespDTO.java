package cn.iocoder.yudao.module.governance.api.etl.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class RowQualityPlanRespDTO {

    private String planHash;

    private LocalDateTime frozenAt;

    /** false 表示调用方必须拒绝所配置节点；支持的规则仅供参考。 */
    private boolean executable;

    private List<RowQualityRuleDTO> rules;

    private List<UnsupportedQualityRuleDTO> unsupportedRules;
}
