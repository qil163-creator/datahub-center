package cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 数据查询文件目录新增/修改 Request VO")
@Data
public class DataQueryFileSaveReqVO {

    @Schema(description = "编号，更新时必填")
    private Long id;

    @Schema(description = "父节点编号，根目录为 0", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "父节点编号不能为空")
    private Long parentId;

    @Schema(description = "节点类型：1-目录 2-SQL 脚本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "节点类型不能为空")
    private Integer nodeType;

    @Schema(description = "名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "名称不能为空")
    @Size(max = 128, message = "名称不能超过 128 个字符")
    private String name;

    @Schema(description = "SQL 内容，脚本节点使用")
    private String sqlText;

    @Schema(description = "关联数据源编号")
    private Long datasourceId;

    @Schema(description = "最大返回行数")
    @Min(value = 1, message = "最大行数不能小于 1")
    @Max(value = 1000, message = "最大行数不能超过 1000")
    private Integer queryLimit;

    @Schema(description = "可见范围：1-公开 2-私有")
    private Integer visibility;

    @Schema(description = "排序")
    private Integer sort;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;

}
