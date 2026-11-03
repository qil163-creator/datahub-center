package cn.iocoder.yudao.module.governance.controller.admin.statictask.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 静态脱敏任务执行记录分页 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 静态脱敏任务执行记录分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class SafetyStaticTaskLogPageReqVO extends PageParam {

    @Schema(description = "任务编号", example = "1024")
    private Long taskId;

    @Schema(description = "运行状态：2-运行中 3-成功 4-失败", example = "3")
    private Integer runStatus;

}
