package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class MasterMatchGroupPageReqVO extends PageParam {

    @NotNull(message = "模型编号不能为空")
    private Long modelId;

    @Pattern(regexp = "PENDING|MERGING|MERGED|IGNORED|STALE", message = "状态不正确")
    private String status;

    private Long runId;
}
