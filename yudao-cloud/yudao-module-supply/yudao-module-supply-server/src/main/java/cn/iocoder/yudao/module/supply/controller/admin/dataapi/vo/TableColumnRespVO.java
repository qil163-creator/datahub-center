package cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 数据表列元数据 Response VO（向导选择请求参数/返回字段用）")
@Data
public class TableColumnRespVO {

    @Schema(description = "列名", example = "station_code")
    private String name;

    @Schema(description = "数据类型", example = "VARCHAR2")
    private String dataType;

    @Schema(description = "数据长度", example = "64")
    private Integer length;

    @Schema(description = "数据小数位", example = "0")
    private Integer scale;

    @Schema(description = "是否主键：0-否 1-是", example = "0")
    private Integer pk;

    @Schema(description = "允许为空：0-否 1-是", example = "1")
    private Integer nullable;

    @Schema(description = "列默认值")
    private String defaultValue;

    @Schema(description = "列描述", example = "站点编码")
    private String comment;

}
