package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 元数据采集实例日志 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 元数据采集实例日志 Response VO")
@Data
public class MetaCollectLogRespVO {

    @Schema(description = "日志编号", example = "1")
    private Long id;

    @Schema(description = "采集实例编号", example = "1")
    private Long instanceId;

    @Schema(description = "日志时间")
    private LocalDateTime logTime;

    @Schema(description = "日志级别：INFO/WARN/ERROR", example = "INFO")
    private String logLevel;

    @Schema(description = "阶段：CONNECT/READ/DIFF/WRITE/DONE", example = "READ")
    private String stage;

    @Schema(description = "对象引用", example = "[TABLE] dwd.dwd_dim_station")
    private String objectRef;

    @Schema(description = "日志内容")
    private String content;

}
