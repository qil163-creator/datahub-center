package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 元数据采集实例 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 元数据采集实例 Response VO")
@Data
public class MetaCollectInstanceRespVO {

    private String dbType;

    private String taskName;

    private Long datasourceId;

    private String datasourceName;

    private Long sourceSystemId;

    private String sourceSystemName;

    private Integer collectType;

    private Long ownerUserId;

    private String ownerName;

    private String ownerPhone;

    private String description;

    private Integer fileCount;

    private String creatorName;

    private String creatorPhone;


    @Schema(description = "实例编号", example = "1")
    private Long id;

    @Schema(description = "采集任务编号", example = "1")
    private Long taskId;

    @Schema(description = "触发方式：1-调度 2-手动 3-事件 4-导入 5-手工登记", example = "1")
    private Integer triggerType;

    @Schema(description = "状态：1-运行中 2-成功 3-失败 4-部分成功", example = "2")
    private Integer status;

    @Schema(description = "阶段：CONNECT/READ/DIFF/WRITE/DONE", example = "DONE")
    private String stage;

    @Schema(description = "错误分类", example = "CONNECT_FAIL")
    private String errorType;

    @Schema(description = "断点游标 JSON")
    private String collectCursor;

    @Schema(description = "处理库数量", example = "1")
    private Integer dbCount;

    @Schema(description = "处理表数量", example = "11")
    private Integer tableCount;

    @Schema(description = "处理字段数量", example = "120")
    private Integer columnCount;

    @Schema(description = "表新增数；文件任务表示文件新增数", example = "7")
    private Integer tableAdd;

    @Schema(description = "表变更数；文件任务表示文件物理变更数", example = "0")
    private Integer tableUpdate;

    @Schema(description = "表待删除数；文件任务表示源端缺失文件数", example = "0")
    private Integer tableDelete;

    @Schema(description = "字段新增数", example = "80")
    private Integer columnAdd;

    @Schema(description = "字段变更数", example = "0")
    private Integer columnUpdate;

    @Schema(description = "字段删除数", example = "0")
    private Integer columnDelete;

    @Schema(description = "失败对象清单 JSON")
    private String failDetail;

    @Schema(description = "开始时间")
    private LocalDateTime startTime;

    @Schema(description = "结束时间")
    private LocalDateTime endTime;

    @Schema(description = "耗时（毫秒）", example = "6000")
    private Long durationMs;

    @Schema(description = "汇总摘要 JSON")
    private String report;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
