package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 主数据域分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class MasterDomainPageReqVO extends PageParam {

    private String name;

    private String code;

    @Min(0)
    @Max(1)
    private Integer status;
}
