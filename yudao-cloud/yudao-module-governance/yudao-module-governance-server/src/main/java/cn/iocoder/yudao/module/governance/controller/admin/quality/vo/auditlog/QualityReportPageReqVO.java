package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.auditlog;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class QualityReportPageReqVO extends PageParam {

    @Size(max = 128)
    private String taskName;

    private Long taskId;

    @Size(max = 32)
    private String instanceStatus;
}
