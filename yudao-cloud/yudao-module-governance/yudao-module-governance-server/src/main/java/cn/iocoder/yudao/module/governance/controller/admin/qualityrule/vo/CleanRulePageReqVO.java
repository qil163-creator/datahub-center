package cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 清洗规则分页 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 清洗规则分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class CleanRulePageReqVO extends PageParam {

    @Schema(description = "规则名称（模糊）", example = "空格")
    private String name;

    @Schema(description = "描述（模糊）", example = "空格")
    private String description;

    @Schema(description = "清洗规则类目编号（含子级）", example = "5")
    private Long categoryId;

    @Schema(description = "状态：0-启用 1-停用", example = "0")
    private Integer status;

    @Schema(description = "排序字段：id、createTime", example = "createTime")
    @Pattern(regexp = "id|createTime", message = "排序字段仅支持 id 或 createTime")
    private String sortField = "createTime";

    @Schema(description = "排序方向：asc、desc；创建时间相同时按同方向编号排序", example = "desc")
    @Pattern(regexp = "asc|desc", message = "排序方向仅支持 asc 或 desc")
    private String sortOrder = "desc";

}
