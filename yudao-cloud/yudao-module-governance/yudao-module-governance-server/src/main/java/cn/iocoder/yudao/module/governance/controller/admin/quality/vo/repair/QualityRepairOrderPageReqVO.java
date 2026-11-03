package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.repair;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 修复工单分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class QualityRepairOrderPageReqVO extends PageParam {

    @Schema(description = "工单标题（模糊）")
    private String orderTitle;

    @Schema(description = "修复类型")
    private String repairType;

    @Schema(description = "审批状态：PENDING/PASSED/REJECTED")
    private String approveStatus;

    @Schema(description = "执行状态：PENDING/SUCCESS/FAILED")
    private String execStatus;

    @Schema(description = "验证状态：UNVERIFIED/PASSED/FAILED")
    private String verifyStatus;

}
