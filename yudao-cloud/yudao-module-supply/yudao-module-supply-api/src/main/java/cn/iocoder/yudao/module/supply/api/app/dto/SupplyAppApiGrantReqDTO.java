package cn.iocoder.yudao.module.supply.api.app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 应用订阅 API 授权
 *
 * @author Li
 */
@Schema(description = "RPC 服务 - 应用 API 授权请求")
@Data
public class SupplyAppApiGrantReqDTO {

    @Schema(description = "应用编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "应用编号不能为空")
    private Long appId;

    @Schema(description = "API 服务编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "API 服务编号不能为空")
    private Long apiId;

    @Schema(description = "是否永久有效：0-否 1-是")
    private Integer foreverFlag;

    @Schema(description = "有效期开始日期")
    private LocalDate startDate;

    @Schema(description = "有效期结束日期")
    private LocalDate endDate;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "应用授权绑定的执行用户（集市申请人）")
    private Long permissionUserId;

    @Schema(description = "幂等操作编号")
    private String operationId;

    @Schema(description = "来源系统", example = "asset")
    private String sourceSystem;

    @Schema(description = "来源决定编号")
    private String sourceDecisionId;

    @Schema(description = "来源类型")
    private String sourceType;

    @Schema(description = "授权用途")
    private String purpose;

    @Schema(description = "生效起点（含）")
    private LocalDateTime validFrom;

    @Schema(description = "失效上界（不含）")
    private LocalDateTime validTo;

    @Schema(description = "调用方决定序号，用于乱序保护")
    private Integer grantRevision;

    @Schema(description = "批准合同摘要；空则使用当前发布合同")
    private String approvedContractHash;

    @Schema(description = "对外 API 版本")
    private String apiVersion;

}
