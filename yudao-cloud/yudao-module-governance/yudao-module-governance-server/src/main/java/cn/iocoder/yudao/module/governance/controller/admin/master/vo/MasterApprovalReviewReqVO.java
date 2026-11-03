package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - MasterApprovalReviewReqVO")
@Data
public class MasterApprovalReviewReqVO {

    @NotNull
    private Long id;

    @Size(max = 500)
    private String remark;
}
