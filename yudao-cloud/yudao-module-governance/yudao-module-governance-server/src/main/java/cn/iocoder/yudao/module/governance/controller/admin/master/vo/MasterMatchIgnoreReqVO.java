package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MasterMatchIgnoreReqVO {

    @NotNull(message = "疑似重复组编号不能为空")
    private Long groupId;

    @Size(max = 500, message = "说明不能超过500字")
    private String remark;
}
