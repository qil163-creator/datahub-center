package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.materialized;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 物化发布日志 Response VO")
@Data
public class ModelMaterializedLogRespVO {

    private Long id;

    private Long materializedId;

    private Long modelId;

    @Schema(description = "本条日志对应的部署尝试编号；历史未知为空")
    private Long attemptId;

    private String modelName;

    private String modelAlias;

    private String description;

    private Long datasourceId;

    private String datasourceName;

    private String datasourceType;

    private String publishMode;

    private String status;

    private String ddlSql;

    private String message;

    private String operator;

    private String operatorName;

    private String creatorName;

    @Schema(description = "本次记录覆盖的执行耗时，单位毫秒")
    private Long publishDuration;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private LocalDateTime createTime;
}
