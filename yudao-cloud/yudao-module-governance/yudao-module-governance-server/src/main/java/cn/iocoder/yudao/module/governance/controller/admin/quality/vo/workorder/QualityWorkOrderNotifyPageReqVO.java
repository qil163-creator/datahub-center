package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class QualityWorkOrderNotifyPageReqVO extends PageParam {

    @NotNull
    private Long workOrderId;
}
