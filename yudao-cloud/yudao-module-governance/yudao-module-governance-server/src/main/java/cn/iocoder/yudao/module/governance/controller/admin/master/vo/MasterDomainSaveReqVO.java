package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Schema(description = "管理后台 - 主数据域保存 Request VO")
@Data
public class MasterDomainSaveReqVO {

    private Long id;

    @NotBlank(message = "域编码不能为空")
    @Size(max = 50)
    private String code;

    @NotBlank(message = "域名称不能为空")
    @Size(max = 100)
    private String name;

    @Size(max = 500)
    private String description;

    @Size(max = 500)
    private String remark;

    @NotNull(message = "负责人不能为空")
    private Long leaderUserId;

    /** 兼容页面带出值；保存时以负责人通讯录电话为准。 */
    @Size(max = 20)
    private String leaderPhone;

    @Size(max = 100)
    private String icon;

    @Min(0)
    @Max(9999)
    private Integer sort;

    @Min(0)
    @Max(1)
    private Integer status;
}
