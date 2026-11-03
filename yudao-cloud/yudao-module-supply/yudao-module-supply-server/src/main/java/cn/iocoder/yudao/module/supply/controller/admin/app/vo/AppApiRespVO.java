package cn.iocoder.yudao.module.supply.controller.admin.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "管理后台 - 应用 API 授权 Response VO")
@Data
public class AppApiRespVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "应用编号", example = "1")
    private Long appId;

    @Schema(description = "API服务编号", example = "1")
    private Long apiId;

    @Schema(description = "API服务名称", example = "站点流量实时数据服务")
    private String apiName;

    @Schema(description = "API服务描述")
    private String apiDescription;

    @Schema(description = "API路径", example = "/services/v1.0.0/api/water/stations")
    private String apiPath;

    @Schema(description = "请求方式", example = "POST")
    private String apiMethod;

    @Schema(description = "是否永久有效：0-否 1-是", example = "1")
    private Integer foreverFlag;

    @Schema(description = "有效期开始日期")
    private LocalDate startDate;

    @Schema(description = "有效期结束日期")
    private LocalDate endDate;

    @Schema(description = "实际授权状态：1-有效 0-不可用/待核查 3-独立 ADMIN 已撤销；按来源账本判定", example = "1")
    private Integer authStatus;

    private Integer grantStatus;

    private LocalDateTime validFrom;

    private LocalDateTime validTo;

    private String approvedContractHash;

    private String apiVersion;

    private Integer grantRevision;

    private String sourceType;

    @Schema(description = "聚合整体禁用，与逐来源撤销区分")
    private Boolean aggregateDisabled;

    @Schema(description = "聚合控制版本；重新启用时原样提交，禁止有损数值转换")
    private Long aggregateRevision;

    private Integer protocolVersion;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建者编号")
    private String creator;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
