package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "管理后台 - 服务审批决定；来源工单只保存本地意见，不开通数据权限")
public class MarketCheckDecisionReqVO {

    @NotBlank
    @Size(max = 128)
    private String id;

    @NotBlank
    @Pattern(regexp = "NATIVE|QDATA")
    private String origin;

    @NotBlank
    @Pattern(regexp = "3|4")
    private String status;

    @NotBlank
    @Size(max = 256)
    private String approvalReason;

    @NotNull
    @Min(1)
    private Integer expectedRevision;

    /**
     * 接收审批意见字段，并按接口约定写入审核说明。
     */
    public void setApprovalReason(String approvalReason) {
        this.approvalReason = approvalReason == null ? null : approvalReason.strip();
    }
}
