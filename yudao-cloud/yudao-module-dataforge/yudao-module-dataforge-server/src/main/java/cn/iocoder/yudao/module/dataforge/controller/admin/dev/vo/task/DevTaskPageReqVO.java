package cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 开发任务分页 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 开发任务分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class DevTaskPageReqVO extends PageParam {

    @Schema(description = "运行模式；BATCH 包含未配置引擎模式的历史任务")
    @jakarta.validation.constraints.Pattern(regexp = "BATCH|STREAMING")
    private String workloadMode;

    @Schema(description = "任务名称（模糊）", example = "水位")
    private String name;

    @Schema(description = "目录编号（含子目录）", example = "1")
    private Long dirId;

    @Schema(description = "任务状态：0-下线 1-上线", example = "1")
    private Integer status;

    @Schema(description = "数据连接产品类型", example = "POSTGRESQL")
    @jakarta.validation.constraints.Pattern(regexp = "[A-Z][A-Z0-9_]{0,31}")
    private String dbType;

    @Schema(description = "最新执行实例的开始时间范围（包含两端）")
    @jakarta.validation.constraints.Size(min = 2, max = 2)
    @org.springframework.format.annotation.DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private java.time.LocalDateTime[] executionStartTime;

    @Schema(description = "最新实例状态；RUNNING 汇总等待、提交、运行与核对状态")
    @jakarta.validation.constraints.Pattern(regexp = "RUNNING|WAITING|SUBMITTING|UNKNOWN|CANCEL_REQUESTED|SUCCESS|FAILED|STOPPED|NEVER")
    private String executionStatus;

}
