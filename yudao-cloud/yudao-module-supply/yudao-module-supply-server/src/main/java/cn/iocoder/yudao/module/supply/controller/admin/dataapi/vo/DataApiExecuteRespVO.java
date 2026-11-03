package cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - API 服务执行结果 Response VO")
@Data
public class DataApiExecuteRespVO {

    @Schema(description = "数据行")
    private List<Map<String, Object>> rows;

    @Schema(description = "总条数（分页格式时）", example = "100")
    private Long total;

    @Schema(description = "消费请求键，可用于查询消费回执")
    private String consumptionKey;

    @Schema(description = "消费记账状态")
    private String consumptionState;

    private Integer consumptionRows;

    @Schema(description = "ETL 批次游标，后续请求使用 afterRow；不能视为页码或总数")
    private Long nextAfterRow;

    private Boolean hasMore;

    @Schema(description = "原始响应（第三方转发非 JSON 数组时）")
    private String raw;

    @Schema(description = "耗时（毫秒）", example = "12")
    private Long durationMs;

    @Schema(description = "测试运行编号（草稿试调）")
    private String testRunNo;

    @Schema(description = "本次执行配置摘要")
    private String configHash;

    @Schema(description = "执行场景")
    private String executeMode;

    @Schema(description = "写操作记录编号")
    private Long operationId;

    @Schema(description = "调用方稳定业务操作编号")
    private String operationKey;

    @Schema(description = "RUNNING/SUCCEEDED/UNKNOWN/CONFIRMED_SUCCEEDED/CONFIRMED_NOT_APPLIED")
    private String operationStatus;

    @Schema(description = "是否从持久台账返回，未再次执行")
    private Boolean replayed;

    @Schema(description = "操作处置提示，不含SQL或参数")
    private String operationMessage;

}
