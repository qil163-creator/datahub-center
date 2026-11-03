package cn.iocoder.yudao.module.dataforge.controller.admin.databasetype.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 数据库类型定义列表 Request VO")
@Data
public class DatabaseTypeListReqVO {

    @Schema(description = "数据源名称", example = "MySQL")
    private String typeName;

    @Schema(description = "连接分类")
    private String categoryCode;

    @Schema(description = "状态", example = "0")
    private Short status;

}
