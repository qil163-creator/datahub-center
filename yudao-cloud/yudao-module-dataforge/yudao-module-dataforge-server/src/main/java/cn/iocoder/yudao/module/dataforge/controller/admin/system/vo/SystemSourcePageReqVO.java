package cn.iocoder.yudao.module.dataforge.controller.admin.system.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 来源系统分页 Request VO")
@Data
public class SystemSourcePageReqVO extends PageParam {

    @Schema(description = "系统名称", example = "芋艿")
    private String name;

    @Schema(description = "系统类型", example = "2")
    private String type;

    @Schema(description = "状态：0停用 1启用", example = "1")
    private Short status;

    @Schema(description = "所属部门编号", example = "1024")
    private Long deptId;

}