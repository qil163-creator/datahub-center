package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task;

import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.checkobject.QualityCheckObjectSaveReqVO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 向导暂存对象使用客户端键关联评测，持久化后统一返回对象编号字符串。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class QualityTaskObjectSaveReqVO extends QualityCheckObjectSaveReqVO {

    @NotBlank
    @Size(max = 128)
    private String clientKey;

    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    private String datasourceConnName;
}
