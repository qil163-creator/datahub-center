package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 质量任务分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class QualityTaskPageReqVO extends PageParam {

    @Schema(description = "任务名称（模糊）")
    private String taskName;

    @Schema(description = "任务分类（模糊）")
    private String taskCategory;

    @Schema(description = "调度类型：MANUAL/TIMER")
    private String scheduleType;

    @Schema(description = "任务状态：0-上线 1-下线")
    private Integer taskStatus;

    @Schema(description = "规则集编号")
    private Long ruleSetId;

}
