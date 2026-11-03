package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class MasterApprovalPageReqVO extends PageParam {

    private Long modelId;

    @Pattern(regexp = "PENDING|WAIT_START|APPROVED|APPLYING|APPLIED|REJECTED|CANCELLED|UNKNOWN")
    private String status;

    @Pattern(regexp = "CREATE|UPDATE|DELETE")
    private String operation;
}
