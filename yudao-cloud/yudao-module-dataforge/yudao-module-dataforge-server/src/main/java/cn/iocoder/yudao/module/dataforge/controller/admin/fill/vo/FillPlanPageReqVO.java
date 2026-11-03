package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 在线填报-采集计划分页 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 采集计划分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class FillPlanPageReqVO extends PageParam {

    @Schema(description = "计划名称（模糊）", example = "月报")
    private String name;

    @Schema(description = "分类编号，包含子孙分类；0 表示未分类，不传表示全部", example = "1")
    @Min(value = 0, message = "分类编号不能为负数")
    private Long categoryId;

    @Schema(description = "状态：0-草稿 1-启用 2-停用", example = "1")
    private Integer status;

    @Schema(description = "填报表编号", example = "1")
    private Long tableId;

    @Schema(description = "周期类型", example = "4")
    private Integer periodType;

}
