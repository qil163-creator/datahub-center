package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class MasterChangeLogPageReqVO extends PageParam {

    private Long modelId;

    @Pattern(regexp = "CREATE|UPDATE|DELETE")
    private String changeType;
}
