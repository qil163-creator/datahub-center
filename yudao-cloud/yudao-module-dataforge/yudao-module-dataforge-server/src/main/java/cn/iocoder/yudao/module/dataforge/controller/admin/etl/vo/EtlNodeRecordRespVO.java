package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - ETL 节点执行记录 Response VO")
@Data
public class EtlNodeRecordRespVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "画布节点编号", example = "node-uuid")
    private String nodeNo;

    @Schema(description = "根设计图中的节点或子流程调用编号")
    private String designNodeNo;

    @Schema(description = "子流程调用路径")
    private String callPath;

    @Schema(description = "子流程定义内原始节点编号")
    private String originalNodeNo;

    @Schema(description = "节点名称", example = "读取用户表")
    private String nodeName;

    @Schema(description = "组件代码", example = "read-db")
    private String toolCode;

    @Schema(description = "运行状态：1-等待 2-运行中 3-成功 4-失败", example = "3")
    private Integer runStatus;

    @Schema(description = "开始时间")
    private LocalDateTime startTime;

    @Schema(description = "结束时间")
    private LocalDateTime endTime;

    @Schema(description = "节点日志")
    private String log;

    @Schema(description = "读取行数", example = "1000")
    private Long readCount;

    @Schema(description = "输出行数", example = "990")
    private Long writeCount;

    @Schema(description = "端口产生行数，广播只计一次；NULL 表示历史未记录或统计未完成")
    private java.util.Map<String, Long> portCounts;

    @Schema(description = "文件发布或清理状态，不等同于节点的临时文件写入成功")
    private String fileOutputState;

}
