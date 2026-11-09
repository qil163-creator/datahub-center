package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 例外到期处理失败账本分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class QualityExpiryFailurePageReqVO extends PageParam {

    @Schema(description = "状态：RETRYING 退避重试中、EXHAUSTED 次数耗尽待人工、RESOLVED 已关闭", example = "EXHAUSTED")
    @Pattern(regexp = "RETRYING|EXHAUSTED|RESOLVED", message = "状态不合法")
    private String status;

    @Schema(description = "原工单编号", example = "8")
    private Long workOrderId;
}
