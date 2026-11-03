package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 在线填报-采集任务分页 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 采集任务分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class FillTaskPageReqVO extends PageParam {

    @Schema(description = "计划编号", example = "1")
    private Long planId;

    @Schema(description = "任务名称（模糊）", example = "月报")
    private String name;

    @Schema(description = "状态：0-待开始 1-进行中 2-已完成 3-已取消", example = "1")
    private Integer status;

    @Schema(description = "数据所属期", example = "2026-08")
    private String periodKey;

}
