package cn.iocoder.yudao.module.dataforge.controller.admin.database.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 数据源中心--数据库信息分页 Request VO")
@Data
public class DatabasePageReqVO extends PageParam {

    @Schema(description = "连接名称", example = "赵六")
    private String name;

    @Schema(description = "状态", example = "2")
    private Integer status;

    @Schema(description = "用途分类：1-业务库 2-中台库", example = "1")
    private Integer usageType;

}
