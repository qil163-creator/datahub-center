package cn.iocoder.yudao.module.governance.controller.admin.identify.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 敏感数据扫描记录 Response VO")
@Data
public class SafetyScanRunRespVO {

    @Schema(description = "扫描记录编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "数据源编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long databaseId;

    @Schema(description = "扫描表范围（逗号分隔，空=全部表）")
    private String tableScope;

    @Schema(description = "运行状态：2-运行中 3-成功 4-失败", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer runStatus;

    @Schema(description = "扫描表数")
    private Integer tableCount;

    @Schema(description = "扫描字段数")
    private Integer columnCount;

    @Schema(description = "新增待确认打标数")
    private Integer hitCount;

    @Schema(description = "开始时间")
    private LocalDateTime startTime;

    @Schema(description = "结束时间")
    private LocalDateTime endTime;

    @Schema(description = "失败信息")
    private String errorInfo;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
