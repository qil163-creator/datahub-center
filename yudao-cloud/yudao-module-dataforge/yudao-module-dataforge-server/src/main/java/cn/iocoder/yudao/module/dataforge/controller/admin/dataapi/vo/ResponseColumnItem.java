package cn.iocoder.yudao.module.dataforge.controller.admin.dataapi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - API 返回字段定义项")
@Data
public class ResponseColumnItem {

    @Schema(description = "字段名", example = "id_card")
    private String fieldName;

    @Schema(description = "字段类型（JDBC Types）：4-整数 8-小数 12-字符串", example = "12")
    private Integer fieldType;

    @Schema(description = "字段长度", example = "100")
    private Integer displaySize;

    @Schema(description = "小数位数", example = "0")
    private Integer precisionSize;

    @Schema(description = "是否可为空", example = "true")
    private Boolean nullable;

    @Schema(description = "是否主键", example = "false")
    private Boolean pk;

    @Schema(description = "说明")
    private String description;

}
