package cn.iocoder.yudao.module.dataforge.controller.admin.database.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 数据源表字段结构 Response VO")
@Data
public class DatabaseTableColumnRespVO {

    @Schema(description = "字段名", example = "id")
    private String fieldName;

    @Schema(description = "数据库类型名", example = "bigint")
    private String fieldTypeName;

    @Schema(description = "字段注释")
    private String remarks;

    @Schema(description = "是否可空")
    private Boolean nullable;

    @Schema(description = "是否主键")
    private Boolean pk;

    @Schema(description = "是否自增")
    private Boolean autoIncrement;

    @Schema(description = "是否为原生目录声明的分区列；未提供目录事实时为空")
    private Boolean partitionKey;

    @Schema(description = "长度")
    private Integer length;

    @Schema(description = "精度")
    private Integer precision;

    @Schema(description = "小数位")
    private Integer scale;

    @Schema(description = "默认值")
    private String defaultValue;

}
