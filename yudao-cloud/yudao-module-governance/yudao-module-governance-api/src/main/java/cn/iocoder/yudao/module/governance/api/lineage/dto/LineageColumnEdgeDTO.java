package cn.iocoder.yudao.module.governance.api.lineage.dto;

import cn.iocoder.yudao.framework.common.validation.InEnum;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageContractConstants;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageTransformSubtypeEnum;
import cn.iocoder.yudao.module.governance.enums.lineage.LineageTransformTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 字段级血缘边：downstream.downstreamColumn 受 upstream.upstreamColumn 影响。
 *
 * <p>transformSubtype 必须属于 transformType（见 {@link LineageTransformSubtypeEnum}）。字段名按小写规范化存储；
 * upstreamColumn 可为 {@code *} 表示整行（如 count(*)）。同一报告内重复的边由服务端合并。</p>
 */
@Schema(description = "RPC 服务 - 字段级血缘边")
@Data
public class LineageColumnEdgeDTO {

    @Schema(description = "上游节点", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "上游节点不能为空")
    @Valid
    private LineageNodeRefDTO upstream;

    @Schema(description = "上游字段名，可为 *", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "上游字段不能为空")
    @Size(max = LineageContractConstants.MAX_COLUMN_NAME_LENGTH, message = "上游字段不能超过 255 个字符")
    private String upstreamColumn;

    @Schema(description = "下游节点", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "下游节点不能为空")
    @Valid
    private LineageNodeRefDTO downstream;

    @Schema(description = "下游字段名", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "下游字段不能为空")
    @Size(max = LineageContractConstants.MAX_COLUMN_NAME_LENGTH, message = "下游字段不能超过 255 个字符")
    private String downstreamColumn;

    @Schema(description = "变换大类：DIRECT/INDIRECT", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "变换大类不能为空")
    @InEnum(value = LineageTransformTypeEnum.class, message = "变换大类必须是 {value}")
    private String transformType;

    @Schema(description = "变换子类：IDENTITY/TRANSFORMATION/AGGREGATION/JOIN/FILTER/GROUP_BY/SORT/WINDOW/CONDITIONAL",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "变换子类不能为空")
    @InEnum(value = LineageTransformSubtypeEnum.class, message = "变换子类必须是 {value}")
    private String transformSubtype;

    @Schema(description = "是否存在歧义（如无法唯一确定字段所属表），空视为 false")
    private Boolean ambiguous;

    @Schema(description = "是否经过脱敏/哈希等遮蔽处理，空视为 false")
    private Boolean masking;

    @Schema(description = "所在语句序号（从 0 开始，可空）")
    @Min(value = 0, message = "语句序号不能小于 0")
    private Integer statementIndex;

}
