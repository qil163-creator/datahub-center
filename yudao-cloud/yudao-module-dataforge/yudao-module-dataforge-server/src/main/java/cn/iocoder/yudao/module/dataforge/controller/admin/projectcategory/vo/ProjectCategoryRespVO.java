package cn.iocoder.yudao.module.dataforge.controller.admin.projectcategory.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "管理后台 - 项目类目信息")
public class ProjectCategoryRespVO {

    private Long id;

    private Long parentId;

    private String name;

    private String description;

    @Schema(description = "状态：0 启用，1 禁用")
    private Integer status;

    private Integer sort;

    private Long taskCount;

    private Long childCount;

    private String creator;

    private String creatorName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
