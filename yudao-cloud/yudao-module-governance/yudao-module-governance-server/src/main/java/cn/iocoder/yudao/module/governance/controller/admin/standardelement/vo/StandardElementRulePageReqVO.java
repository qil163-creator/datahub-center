package cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import cn.iocoder.yudao.framework.common.pojo.PageParam;

@Data
@EqualsAndHashCode(callSuper = true)
public class StandardElementRulePageReqVO extends PageParam {

    @jakarta.validation.constraints.NotNull
    private Integer elementId;

    private String kind;

    private String name;

    private Integer status;
}
