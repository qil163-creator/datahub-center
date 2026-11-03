package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 主数据模型保存 Request VO")
@Data
public class MasterModelSaveReqVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "编辑时读取的模型版本；曾发布模型必须提供，用于拒绝过期修改")
    @Positive(message = "模型版本必须为正数")
    private Integer expectedVersion;

    @Schema(description = "历史目录编号，仅兼容旧入口")
    private Long catalogId;

    @Schema(description = "主数据域编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "主数据域不能为空")
    private Long domainId;

    @Schema(description = "启用数据变更审批")
    private Boolean approvalEnabled;

    @Schema(description = "可选 BPM 流程定义标识")
    @Size(max = 100)
    private String approvalProcessKey;

    @Schema(description = "物理表所在数据源编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "数据源不能为空")
    private Long databaseId;

    @Schema(description = "物理表名", requiredMode = Schema.RequiredMode.REQUIRED, example = "md_person")
    @NotBlank(message = "表名不能为空")
    @Size(max = 100, message = "表名不能超过 100 个字符")
    private String tableName;

    @Schema(description = "中文名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "人口主数据")
    @NotBlank(message = "中文名称不能为空")
    @Size(max = 100, message = "中文名称不能超过 100 个字符")
    private String tableCn;

    @Schema(description = "描述", example = "全市统一人口主数据")
    @Size(max = 200, message = "描述不能超过 200 个字符")
    private String description;

    @Schema(description = "字段列表，草稿允许为空，发布时必须有字段和主键", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "字段列表不能为 null")
    @Size(max = 200, message = "模型字段不能超过 200 个")
    @Valid
    private List<MasterColumnVO> columns;

}
