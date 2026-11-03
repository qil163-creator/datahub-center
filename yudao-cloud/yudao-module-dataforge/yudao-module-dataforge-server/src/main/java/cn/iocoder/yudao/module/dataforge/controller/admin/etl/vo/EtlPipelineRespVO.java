package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - ETL 流程 Response VO")
@Data
public class EtlPipelineRespVO {

    @Schema(description = "连接环境编号")
    private Long environmentId;

    @Schema(description = "草稿乐观锁修订号", example = "3")
    private Integer revision;

    private Integer publishedRevision;

    private Long publishedExecutionUserId;

    private Long qualityTaskId;


    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "流程名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "用户数据清洗入仓")
    private String name;

    @Schema(description = "调度类型：1-一次性 2-周期性", example = "1")
    private Integer taskType;

    @Schema(description = "Cron 表达式", example = "0 0 2 * * ?")
    private String cron;

    @Schema(description = "下次执行时间")
    private LocalDateTime nextRunTime;

    @Schema(description = "任务状态：0-未上线 1-已上线", example = "0")
    private Integer status;

    @Schema(description = "调度状态：0-关闭 1-开启（仅周期任务）", example = "0")
    private Integer scheduleStatus;

    @Schema(description = "最近运行状态：1-等待 2-运行中 3-成功 4-失败 5-停止", example = "3")
    private Integer runStatus;

    @Schema(description = "最近运行开始时间")
    private LocalDateTime startTime;

    @Schema(description = "最近运行结束时间")
    private LocalDateTime endTime;

    @Schema(description = "发布时间")
    private LocalDateTime releaseTime;

    @Schema(description = "发布人昵称", example = "管理员")
    private String releaseUserName;

    @Schema(description = "描述", example = "每天凌晨同步清洗")
    private String description;

    @Schema(description = "所属机构编号", example = "100")
    private Long orgId;

    @Schema(description = "所属机构名称", example = "大数据局")
    private String orgName;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "流程参数定义")
    private List<EtlPipelineSaveReqVO.ParamItem> parameters;

    @Schema(description = "画布节点（LogicFlow nodes）")
    private List<Map<String, Object>> nodes;

    @Schema(description = "画布连线（LogicFlow edges）")
    private List<Map<String, Object>> edges;

}
