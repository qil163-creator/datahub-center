package cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.dir;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 开发目录新增/修改 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 开发目录新增/修改 Request VO")
@Data
public class DevDirSaveReqVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "父级编号，0 表示顶级", example = "0")
    private Long parentId;

    @Schema(description = "目录名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "水情监测")
    @NotBlank(message = "目录名称不能为空")
    @Size(max = 100, message = "目录名称不能超过 100 个字符")
    private String name;

    @Schema(description = "排序", example = "0")
    private Integer sort;

    @Schema(description = "状态：0 启用，1 禁用")
    @jakarta.validation.constraints.Min(0)
    @jakarta.validation.constraints.Max(1)
    private Integer status;

    @Size(max = 500)
    private String description;

    @Size(max = 500)
    private String remark;

}
