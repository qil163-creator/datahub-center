package cn.iocoder.yudao.module.governance.api.security.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "RPC 服务 - 资产敏感清单可引用的治理分类")
@Data
public class SafetyClassificationRespDTO {

    @Schema(description = "节点键，目录为 dir:编号，分类为 category:编号")
    private String id;

    private String parentId;

    @Schema(description = "分类编号，目录为空")
    private Long categoryId;

    private String name;

    @Schema(description = "是否分类叶节点；是否启用另看 status")
    private Boolean selectable;

    @Schema(description = "有效状态，0 启用、1 停用；包含祖先目录与分级状态")
    private Short status;

    private String levelName;

    private String levelColor;

    @Schema(description = "真实本地关联规则名称；未关联时为空")
    private String ruleName;

    private Long ruleId;
}
