package cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.dir;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 开发目录 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 开发目录 Response VO")
@Data
public class DevDirRespVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "父级编号，0 表示顶级", example = "0")
    private Long parentId;

    @Schema(description = "目录名称", example = "水情监测")
    private String name;

    @Schema(description = "排序", example = "0")
    private Integer sort;

    private Integer status;

    private String description;

    private String remark;

}
