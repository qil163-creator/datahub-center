package cn.iocoder.yudao.module.governance.controller.admin.identify.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 敏感数据识别规则分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class SafetyIdentifyRulePageReqVO extends PageParam {

    @Schema(description = "规则名称（模糊）", example = "手机号")
    private String ruleName;

    @Schema(description = "匹配方式", example = "COLUMN_NAME")
    private String matchType;

    @Schema(description = "数据分类编号", example = "1")
    private Long categoryId;

    @Schema(description = "状态：0-启用 1-停用", example = "0")
    private Integer status;

}
