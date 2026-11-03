package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 表元数据手工登记 Request VO（origin=手工，走与采集同一套 diff 引擎）
 *
 * @author Li
 */
@Schema(description = "管理后台 - 表元数据手工登记 Request VO")
@Data
public class MetaTableRegisterReqVO {

    @Schema(description = "dataforge 数据源编号（无直连场景登记逻辑数据源）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "数据源不能为空")
    private Long datasourceId;

    @Schema(description = "来源系统编号", example = "1")
    private Long sourceSystemId;

    @Schema(description = "来源系统名称（展示快照）", example = "山洪灾害监测预警系统")
    private String sourceSystemName;

    @Schema(description = "库名", requiredMode = Schema.RequiredMode.REQUIRED, example = "ods")
    @NotEmpty(message = "库名不能为空")
    @Size(max = 200, message = "库名长度不能超过 200")
    private String dbName;

    @Schema(description = "表名", requiredMode = Schema.RequiredMode.REQUIRED, example = "ods_person")
    @NotEmpty(message = "表名不能为空")
    @Size(max = 200, message = "表名长度不能超过 200")
    private String tableName;

    @Schema(description = "表注释", example = "人口基础信息表")
    private String tableComment;

    @Schema(description = "字段清单", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "字段清单不能为空")
    @Valid
    private List<RegisterColumn> columns = new ArrayList<>();

    @Schema(description = "手工登记字段")
    @Data
    public static class RegisterColumn {

        @Schema(description = "字段名", requiredMode = Schema.RequiredMode.REQUIRED, example = "id_card")
        @NotEmpty(message = "字段名不能为空")
        @Size(max = 200, message = "字段名长度不能超过 200")
        private String columnName;

        @Schema(description = "字段注释", example = "身份证号")
        private String columnComment;

        @Schema(description = "数据类型", example = "varchar")
        private String dataType;

        @Schema(description = "长度", example = "18")
        private Long dataLength;

        @Schema(description = "精度", example = "10")
        private Integer dataPrecision;

        @Schema(description = "小数位", example = "2")
        private Integer dataScale;

        @Schema(description = "是否可空：0-否 1-是", example = "1")
        private Integer nullableFlag;

        @Schema(description = "默认值")
        private String defaultValue;

        @Schema(description = "是否主键：0-否 1-是", example = "0")
        private Integer pkFlag;

        @Schema(description = "是否自增：0-否 1-是", example = "0")
        private Integer autoIncrementFlag;

    }

}
