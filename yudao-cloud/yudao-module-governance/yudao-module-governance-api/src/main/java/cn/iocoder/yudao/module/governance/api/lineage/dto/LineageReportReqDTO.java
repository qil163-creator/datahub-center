package cn.iocoder.yudao.module.governance.api.lineage.dto;

import cn.iocoder.yudao.framework.common.validation.InEnum;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageContractConstants;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageReportStatusEnum;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageSourceTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 血缘上报请求：一个来源对象在某个修订下的完整血缘快照。
 *
 * <p>本契约不含任何租户、组织或操作人字段，服务端也不信任调用方传入的此类参数。</p>
 */
@Schema(description = "RPC 服务 - 血缘上报请求")
@Data
public class LineageReportReqDTO {

    @Schema(description = "来源类型：DEV_TASK/DATA_ACCESS/INTEGRATION_TASK/ETL_PIPELINE", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "来源类型不能为空")
    @InEnum(value = LineageSourceTypeEnum.class, message = "来源类型必须是 {value}")
    private String sourceType;

    @Schema(description = "来源对象编号（dataforge 内的任务/同步/集成/ETL 编号）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "来源编号不能为空")
    @Positive(message = "来源编号必须为正数")
    private Long sourceId;

    @Schema(description = "来源名称快照", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "来源名称不能为空")
    @Size(max = LineageContractConstants.MAX_SOURCE_NAME_LENGTH, message = "来源名称不能超过 255 个字符")
    private String sourceName;

    @Schema(description = "来源对象修订号，单调递增", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "来源修订号不能为空")
    @Min(value = 0, message = "来源修订号不能小于 0")
    private Long sourceRevision;

    @Schema(description = "解析器版本，解析规则升级时递增", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "解析器版本不能为空")
    @Min(value = 1, message = "解析器版本不能小于 1")
    private Integer analyzerVersion;

    @Schema(description = "规范化血缘内容的 SHA-256（64 位小写十六进制）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "内容摘要不能为空")
    @Pattern(regexp = LineageContractConstants.CONTENT_HASH_PATTERN, message = "内容摘要必须是 64 位小写十六进制")
    private String contentHash;

    @Schema(description = "解析状态：SUCCESS/PARTIAL/FAILED/UNSUPPORTED/REMOVED", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "解析状态不能为空")
    @InEnum(value = LineageReportStatusEnum.class, message = "解析状态必须是 {value}")
    private String status;

    @Schema(description = "解析问题，最多 200 条")
    @Size(max = LineageContractConstants.MAX_ISSUES, message = "解析问题不能超过 200 条")
    @Valid
    private List<LineageIssueDTO> issues = new ArrayList<>();

    @Schema(description = "表级血缘边，最多 2000 条；REMOVED 时必须为空")
    @Size(max = LineageContractConstants.MAX_TABLE_EDGES, message = "表级血缘边不能超过 2000 条")
    @Valid
    private List<LineageTableEdgeDTO> tableEdges = new ArrayList<>();

    @Schema(description = "字段级血缘边，最多 20000 条；REMOVED 时必须为空")
    @Size(max = LineageContractConstants.MAX_COLUMN_EDGES, message = "字段级血缘边不能超过 20000 条")
    @Valid
    private List<LineageColumnEdgeDTO> columnEdges = new ArrayList<>();

}
