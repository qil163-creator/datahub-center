package cn.iocoder.yudao.module.supply.controller.admin.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "管理后台 - 应用 API 授权保存 Request VO")
@Data
public class AppApiSaveReqVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "应用编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "应用编号不能为空")
    private Long appId;

    @Schema(description = "API服务编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "API 服务不能为空")
    private Long apiId;

    @Schema(description = "是否永久有效：0-否 1-是", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "是否永久有效不能为空")
    private Integer foreverFlag;

    @Schema(description = "有效期开始日期")
    private LocalDate startDate;

    @Schema(description = "有效期结束日期")
    private LocalDate endDate;

    @Schema(description = "描述")
    @Size(max = 500, message = "描述不能超过 500 个字符")
    private String description;

    @Schema(description = "应用授权绑定的执行用户。管理端提交会被忽略；仅 RPC 在校验后写入")
    private Long permissionUserId;

    private String operationId;

    private String sourceSystem;

    private String sourceDecisionId;

    private String sourceType;

    private String purpose;

    private LocalDateTime validFrom;

    private LocalDateTime validTo;

    private Integer grantRevision;

    private String approvedContractHash;

    private String apiVersion;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;

}
