package cn.iocoder.yudao.module.dataforge.controller.admin.etltemplate.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class EtlTemplatePageReqVO extends PageParam {

    @Size(max = 100)
    private String name;
}
