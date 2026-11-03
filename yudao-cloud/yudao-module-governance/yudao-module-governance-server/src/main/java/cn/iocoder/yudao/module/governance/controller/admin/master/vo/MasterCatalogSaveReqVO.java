package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 主数据目录保存 Request VO")
@Data
public class MasterCatalogSaveReqVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "父级编号，0 表示根节点", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "父级编号不能为空")
    private Long parentId;

    @Schema(description = "节点类型：0-普通目录 1-主数据", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "节点类型不能为空")
    private Integer type;

    @Schema(description = "节点名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "人口主数据")
    @NotBlank(message = "节点名称不能为空")
    @Size(max = 100, message = "节点名称不能超过 100 个字符")
    private String name;

    @Schema(description = "同级排序号", example = "0")
    private Integer orderNo;

    @Schema(description = "描述", example = "人口相关主数据")
    @Size(max = 300, message = "描述不能超过 300 个字符")
    private String description;

    @Schema(description = "所属机构编号", example = "100")
    private Long orgId;

}
