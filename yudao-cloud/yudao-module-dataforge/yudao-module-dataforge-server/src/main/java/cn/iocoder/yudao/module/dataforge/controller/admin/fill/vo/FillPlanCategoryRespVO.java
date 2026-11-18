package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 采集计划分类 Response VO")
@Data
public class FillPlanCategoryRespVO {

    @Schema(description = "分类编号", example = "1")
    private Long id;

    @Schema(description = "父分类编号，根目录为 0", example = "0")
    private Long parentId;

    @Schema(description = "分类名称", example = "基础信息")
    private String name;

    @Schema(description = "同级显示顺序", example = "0")
    private Integer sort;

    @Schema(description = "分类状态，0 启用，1 停用", example = "0")
    private Integer status;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
