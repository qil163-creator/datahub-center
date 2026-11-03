package cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 标准数据元码值内容稽核 SQL Response VO")
@Data
public class StandardElementEnumCheckRespVO {

    @Schema(description = "元数据字段编号", example = "2048")
    private Long columnId;

    @Schema(description = "所属表名", example = "t_person")
    private String tableName;

    @Schema(description = "字段名", example = "gender")
    private String columnName;

    @Schema(description = "码表名称", example = "性别")
    private String codeSetName;

    @Schema(description = "允许的码值列表")
    private List<String> allowedValues;

    @Schema(description = "内容稽核 SQL（统计非法码值行数，可直接在质量模块/数据源执行）")
    private String checkSql;

}
