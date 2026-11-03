package cn.iocoder.yudao.module.governance.controller.admin.whitelist.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 脱敏白名单分页 Request VO")
@Data
public class SafetyWhitelistPageReqVO extends PageParam {

    @Schema(description = "白名单名称", example = "芋艿")
    private String name;

    @Schema(description = "生效主体", example = "1")
    private String subjectType;

    @Schema(description = "状态", example = "1")
    private Short status;

    @Schema(description = "审批状态：DRAFT / PENDING / APPROVED / REJECTED", example = "PENDING")
    private String approvalStatus;

}