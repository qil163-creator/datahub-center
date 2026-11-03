package cn.iocoder.yudao.module.governance.controller.admin.standardterm.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 业务术语分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class StandardTermPageReqVO extends PageParam {

    @Schema(description = "术语名称", example = "客户")
    private String name;

    @Schema(description = "英文名称", example = "customer")
    private String engName;

    @Schema(description = "业务术语类目编号", example = "1")
    private Long termCategoryId;

    @Schema(description = "所属类目编号", example = "1")
    private Long categoryId;

    @Schema(description = "负责人用户编号", example = "1")
    private Long ownerUserId;

    @Schema(description = "状态", example = "0")
    private Short status;

}
