package cn.iocoder.yudao.module.governance.api.quality.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 质量任务最近一次执行结果
 *
 * @author Li
 */
@Schema(description = "RPC 服务 - 质量任务最近执行结果")
@Data
public class QualityTaskResultRespDTO {

    private Long instanceId;

    private Long errorRuleCount;

    private Long failedRuleCount;

    @Schema(description = "质量任务编号")
    private Long taskId;

    @Schema(description = "实例状态：RUNNING/SUCCESS/FAILED")
    private String instanceStatus;

    @Schema(description = "开始时间")
    private LocalDateTime startTime;

    @Schema(description = "结束时间")
    private LocalDateTime endTime;

    @Schema(description = "检测总条数")
    private Long totalCount;

    @Schema(description = "失败条数")
    private Long failCount;

    @Schema(description = "质量评分")
    private BigDecimal score;

}
