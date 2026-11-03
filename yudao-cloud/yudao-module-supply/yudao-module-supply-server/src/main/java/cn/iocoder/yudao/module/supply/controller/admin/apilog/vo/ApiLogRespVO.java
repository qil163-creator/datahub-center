package cn.iocoder.yudao.module.supply.controller.admin.apilog.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - API 调用记录 Response VO")
@Data
public class ApiLogRespVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "API服务编号", example = "1")
    private Long apiId;

    @Schema(description = "API服务名称", example = "站点流量实时数据服务")
    private String apiName;

    @Schema(description = "服务分类编号", example = "1")
    private Long categoryId;

    @Schema(description = "调用应用编号", example = "1")
    private Long appId;

    @Schema(description = "调用者IP", example = "172.28.0.1")
    private String callerIp;

    @Schema(description = "调用接口地址", example = "/services/v1.0.0/api/water/stations")
    private String callPath;

    @Schema(description = "请求方式", example = "POST")
    private String method;

    @Schema(description = "调用参数 JSON")
    private String paramsJson;

    @Schema(description = "调用时间")
    private LocalDateTime callTime;

    @Schema(description = "调用耗时（毫秒）", example = "12")
    private Long durationMs;

    @Schema(description = "调用数据量", example = "20")
    private Long dataCount;

    @Schema(description = "状态：1-成功 0-失败", example = "1")
    private Integer status;

    @Schema(description = "异常信息记录")
    private String errorInfo;

    @Schema(description = "拒绝阶段：AUTH/GRANT/CONTRACT/IP/RATE/BREAKER/EXECUTE")
    private String rejectStage;

    @Schema(description = "请求追踪编号")
    private String traceId;

    private String callMode;

    private Long releaseId;

}
