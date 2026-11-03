package cn.iocoder.yudao.module.supply.controller.admin.apicategory.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 服务分类 Response VO")
@Data
public class ApiCategoryRespVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "父级编号，0 表示顶级节点", example = "0")
    private Long parentId;

    @Schema(description = "数据服务类目名称", example = "业务数据")
    private String name;

    @Schema(description = "状态：0-启用 1-停用", example = "0")
    private Integer status;

    @Schema(description = "排序", example = "0")
    private Integer sort;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建者编号")
    private String creator;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
