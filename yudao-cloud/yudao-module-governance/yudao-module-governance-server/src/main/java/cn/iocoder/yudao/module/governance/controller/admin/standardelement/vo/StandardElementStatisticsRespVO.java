package cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "管理后台 - 标准数据元统计概览 Response VO")
@Data
public class StandardElementStatisticsRespVO {

    @Schema(description = "数据元总数", example = "128")
    private Long totalCount;

    @Schema(description = "草稿数量", example = "12")
    private Long draftCount;

    @Schema(description = "已发布数量", example = "100")
    private Long publishedCount;

    @Schema(description = "已废止数量", example = "16")
    private Long deprecatedCount;

    @Schema(description = "已落标数据元数量（元数据字段引用或建模字段活动落标，按数据元去重）", example = "60")
    private Long mappedElementCount;

    @Schema(description = "设计态（建模字段）落标数据元数量", example = "20")
    private Long modelMappedElementCount;

    @Schema(description = "落标率（已落标 / 已发布，百分比，保留 1 位小数）", example = "60.0")
    private BigDecimal mappingRate;

    @Schema(description = "已绑定码表的数据元数量", example = "40")
    private Long codeSetBoundCount;

}
