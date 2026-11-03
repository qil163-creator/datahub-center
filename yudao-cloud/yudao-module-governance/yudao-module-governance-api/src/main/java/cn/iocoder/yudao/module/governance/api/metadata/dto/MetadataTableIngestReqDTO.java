package cn.iocoder.yudao.module.governance.api.metadata.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 物理表回写入元数据（走与采集同一套 diff 引擎）
 *
 * @author Li
 */
@Schema(description = "RPC 服务 - 物理表元数据回写请求")
@Data
public class MetadataTableIngestReqDTO {

    @Schema(description = "可靠登记事件编号；与 source/revision/occurredAt/schemaVersion 同时提供")
    @jakarta.validation.constraints.Size(max = 128)
    private String eventId;

    @Schema(description = "生产者命名空间，例如 MODEL_DEPLOY")
    @jakarta.validation.constraints.Size(max = 64)
    private String source;

    @Schema(description = "同一生产者、同一目标单调递增的修订号")
    @jakarta.validation.constraints.Positive
    private Long revision;

    private java.time.LocalDateTime occurredAt;

    private Integer schemaVersion;

    @Schema(description = "数据源编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "数据源编号不能为空")
    private Long datasourceId;

    @Schema(description = "库名/Schema")
    private String dbName;

    @Schema(description = "表名", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "表名不能为空")
    private String tableName;

    @Schema(description = "表注释")
    private String tableComment;

    @Schema(description = "来源系统名称")
    private String sourceSystemName;

    @Schema(description = "是否主数据表：0-否 1-是")
    private Integer masterFlag;

    @Schema(description = "字段清单", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "字段清单不能为空")
    @Valid
    private List<Column> columns = new ArrayList<>();

    @Schema(description = "回写字段")
    @Data
    public static class Column {

        @Schema(description = "字段名", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "字段名不能为空")
        private String columnName;

        @Schema(description = "字段注释")
        private String columnComment;

        @Schema(description = "数据类型")
        private String dataType;

        @Schema(description = "长度")
        private Long dataLength;

        @Schema(description = "精度")
        private Integer dataPrecision;

        @Schema(description = "小数位")
        private Integer dataScale;

        @Schema(description = "是否可空：0-否 1-是")
        private Integer nullableFlag;

        @Schema(description = "是否主键：0-否 1-是")
        private Integer pkFlag;

        @Schema(description = "绑定的标准数据元编号")
        private Long standardElementId;

    }

}
