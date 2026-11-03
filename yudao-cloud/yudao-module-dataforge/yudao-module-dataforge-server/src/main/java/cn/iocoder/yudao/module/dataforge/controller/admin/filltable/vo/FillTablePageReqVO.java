package cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 填报表分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class FillTablePageReqVO extends PageParam {

    @Schema(description = "表英文名", example = "t_population")
    private String tableEngName;

    @Schema(description = "表中文名", example = "人口信息表")
    private String tableCnName;

    @Schema(description = "发布状态：0-未发布 1-已发布", example = "1")
    private Integer status;

    @Schema(description = "单位id", example = "100")
    private Long orgId;

}
