package cn.iocoder.yudao.module.governance.api.security.dto;

import cn.iocoder.yudao.framework.common.validation.InEnum;
import cn.iocoder.yudao.module.governance.enums.security.SafetyMaskSceneEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 动态脱敏请求：调用方已完成对象与字段读取授权，提交查询结果和每个输出列的物理来源。
 */
@Schema(description = "RPC 服务 - 动态脱敏请求")
@Data
public class SafetyDynamicMaskReqDTO {

    /** 单次最多处理的行数，与平台预览/查询上限一致。 */
    public static final int MAX_ROWS = 1000;
    /** 单次最多的输出列数。 */
    public static final int MAX_COLUMNS = 500;

    @Schema(description = "数据接收人用户编号；系统调用可为空，此时一律按无豁免处理")
    @Positive(message = "用户编号必须为正数")
    private Long userId;

    @Schema(description = "脱敏场景", requiredMode = Schema.RequiredMode.REQUIRED, example = "DATA_QUERY")
    @NotBlank(message = "脱敏场景不能为空")
    @InEnum(value = SafetyMaskSceneEnum.class, message = "脱敏场景必须是 {value}")
    private String scene;

    @Schema(description = "资源名称（用于审计，如表名或查询名）")
    @Size(max = 255, message = "资源名称不能超过 255 个字符")
    private String resourceName;

    @Schema(description = "输出列及其物理来源", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "输出列不能为空")
    @Size(max = MAX_COLUMNS, message = "输出列不能超过 500 个")
    @Valid
    private List<Column> columns = new ArrayList<>();

    @Schema(description = "结果行，键为输出列名", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "结果行不能为空")
    @Size(max = MAX_ROWS, message = "结果行不能超过 1000 行")
    private List<Map<String, Object>> rows = new ArrayList<>();

    /** 输出列。 */
    @Schema(description = "输出列")
    @Data
    public static class Column {

        @Schema(description = "输出列名（结果行中的键）", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "输出列名不能为空")
        @Size(max = 255, message = "输出列名不能超过 255 个字符")
        private String outputName;

        @Schema(description = "来源物理列；空列表表示常量或已不暴露原值的计算结果（如 COUNT、哈希）")
        @Size(max = 50, message = "单列来源不能超过 50 个")
        @Valid
        private List<Source> sources = new ArrayList<>();

        @Schema(description = "来源无法追溯时为 false：只要涉及的表存在敏感列就失败关闭")
        private Boolean traceable = true;

        @Schema(description = "来源无法追溯时，该列可能来自的表（用于判定是否失败关闭）")
        @Size(max = 20, message = "候选表不能超过 20 个")
        @Valid
        private List<Source> candidateTables = new ArrayList<>();
    }

    /** 物理来源。candidateTables 中的 columnName 可为空。 */
    @Schema(description = "物理来源列")
    @Data
    public static class Source {

        @Schema(description = "数据源编号", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "数据源编号不能为空")
        @Positive(message = "数据源编号必须为正数")
        private Long datasourceId;

        @Schema(description = "schema 名")
        @Size(max = 128, message = "schema 名不能超过 128 个字符")
        private String schemaName;

        @Schema(description = "表名", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "表名不能为空")
        @Size(max = 255, message = "表名不能超过 255 个字符")
        private String tableName;

        @Schema(description = "列名")
        @Size(max = 255, message = "列名不能超过 255 个字符")
        private String columnName;

        /**
         * 构造来源。
         */
        public static Source of(Long datasourceId, String schemaName, String tableName, String columnName) {
            Source source = new Source();
            source.setDatasourceId(datasourceId);
            source.setSchemaName(schemaName);
            source.setTableName(tableName);
            source.setColumnName(columnName);
            return source;
        }
    }
}
