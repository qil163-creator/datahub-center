package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 建表语句解析字段 Request VO")
@Data
public class SqlParseFieldsReqVO {

    @Schema(description = "建表语句方言：MYSQL、DORIS、POSTGRESQL、ORACLE", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "DORIS")
    @NotBlank(message = "请选择建表语句的数据库类型")
    private String dialect;

    @Schema(description = "CREATE TABLE 语句，可附带同一张表的 COMMENT ON 语句", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请输入建表语句")
    @Size(max = 100_000, message = "建表语句不能超过 100000 个字符")
    private String sql;
}
