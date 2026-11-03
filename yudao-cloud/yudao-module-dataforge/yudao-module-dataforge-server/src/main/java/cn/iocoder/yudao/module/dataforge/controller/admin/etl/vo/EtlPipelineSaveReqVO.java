package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import tools.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - ETL 流程保存 Request VO")
@Data
public class EtlPipelineSaveReqVO {

    @Schema(description = "连接环境编号，不选择时沿用节点物理数据源")
    @jakarta.validation.constraints.Positive
    private Long environmentId;

    @Schema(description = "草稿乐观锁修订号（更新时必填）", example = "3")
    private Integer revision;

    @Schema(description = "成果质量任务编号（选填）", example = "1")
    private Long qualityTaskId;


    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "流程名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "用户数据清洗入仓")
    @NotBlank(message = "流程名称不能为空")
    @Size(max = 50, message = "流程名称不能超过 50 个字符")
    private String name;

    @Schema(description = "调度类型：1-一次性 2-周期性", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "调度类型不能为空")
    private Integer taskType;

    @Schema(description = "Cron 表达式（周期任务必填）", example = "0 0 2 * * ?")
    private String cron;

    @Schema(description = "描述", example = "每天凌晨同步清洗")
    @Size(max = 200, message = "描述不能超过 200 个字符")
    private String description;

    @Schema(description = "所属机构编号", example = "100")
    private Long orgId;

    @Schema(description = "流程参数定义")
    @jakarta.validation.Valid
    @Size(max = 100)
    private List<ParamItem> parameters;

    @Schema(description = "画布节点（可空，支持先建基础信息再配置任务）")
    @jakarta.validation.constraints.Size(max = 32, message = "轻量 ETL 最多32个节点")
    private List<Map<String, Object>> nodes;

    @Schema(description = "画布连线（LogicFlow edges）")
    @jakarta.validation.constraints.Size(max = 128, message = "轻量 ETL 最多128条连线")
    private List<Map<String, Object>> edges;

    @Schema(description = "流程参数定义项")
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ParamItem {

        @Schema(description = "参数名", example = "batchSize")
        @jakarta.validation.constraints.NotBlank
        @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]{0,63}")
        private String name;

        @Schema(description = "参数类型：STRING/INTEGER/DECIMAL/BOOLEAN；省略按 STRING 兼容", example = "INTEGER")
        @jakarta.validation.constraints.Pattern(regexp = "STRING|INTEGER|DECIMAL|BOOLEAN")
        private String type;

        @Schema(description = "默认值", example = "1000")
        @JsonDeserialize(using = StrictJsonStringDeserializer.class)
        @Size(max = 4096)
        private String defaultValue;

        @Schema(description = "是否必填")
        private Boolean required;

        @Schema(description = "数值最小值，仅 INTEGER/DECIMAL 使用，普通十进制字符串")
        @JsonDeserialize(using = StrictJsonStringDeserializer.class)
        @Size(max = 128)
        private String min;

        @Schema(description = "数值最大值，仅 INTEGER/DECIMAL 使用，普通十进制字符串")
        @JsonDeserialize(using = StrictJsonStringDeserializer.class)
        @Size(max = 128)
        private String max;

        @Schema(description = "字符串最小长度，0..4096")
        @jakarta.validation.constraints.Min(0)
        @jakarta.validation.constraints.Max(4096)
        private Integer minLength;

        @Schema(description = "字符串最大长度，0..4096")
        @jakarta.validation.constraints.Min(0)
        @jakarta.validation.constraints.Max(4096)
        private Integer maxLength;

        @Schema(description = "说明", example = "批次大小")
        @Size(max = 200)
        private String description;

    }

}
