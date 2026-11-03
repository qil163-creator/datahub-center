package cn.iocoder.yudao.module.governance.controller.admin.level.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 数据分级分页 Request VO")
@Data
public class SafetyLevelPageReqVO extends PageParam {

    @Schema(description = "显示名称", example = "芋艿")
    private String safetyName;

    @Schema(description = "状态", example = "1")
    private Short status;

}