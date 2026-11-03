package cn.iocoder.yudao.module.governance.controller.admin.rule.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 脱敏规则（主表）；参数演进由版本表承载分页 Request VO")
@Data
public class SafetyRulePageReqVO extends PageParam {

    @Schema(description = "规则名称", example = "王五")
    private String ruleName;

    @Schema(description = "规则来源", example = "1")
    private String sourceType;

    @Schema(description = "状态", example = "1")
    private Short status;

}