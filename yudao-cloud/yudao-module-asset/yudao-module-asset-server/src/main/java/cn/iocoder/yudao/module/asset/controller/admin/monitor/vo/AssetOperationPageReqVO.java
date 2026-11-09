package cn.iocoder.yudao.module.asset.controller.admin.monitor.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDate;

@Schema(description = "管理后台 - 资产运营指标分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AssetOperationPageReqVO extends PageParam {

    @Schema(description = "统计日期，默认最近一次快照", example = "2026-11-09")
    private LocalDate statDate;

    @Schema(description = "资产名称或编码关键字")
    @Size(max = 100, message = "关键字不能超过 100 个字符")
    private String keyword;

    @Schema(description = "资产类型：1-库表 2-接口 3-文件")
    private Integer assetType;

    @Schema(description = "资产状态：0-未发布 1-已发布 2-已下线")
    private Integer assetStatus;

    @Schema(description = "资产类目")
    private Long categoryId;

    @Schema(description = "价值等级：HIGH/MEDIUM/LOW/IDLE")
    @Pattern(regexp = "HIGH|MEDIUM|LOW|IDLE", message = "价值等级不合法")
    private String valueLevel;

    @Schema(description = "运营建议：KEEP/IMPROVE_QUALITY/COMPLETE_METADATA/PROMOTE/RETIRE_REVIEW")
    @Pattern(regexp = "KEEP|IMPROVE_QUALITY|COMPLETE_METADATA|PROMOTE|RETIRE_REVIEW", message = "运营建议不合法")
    private String suggestion;

    @Schema(description = "排序字段：valueScore/heatScore/idleDays/usageCount/apiCallCount", example = "valueScore")
    @Pattern(regexp = "valueScore|heatScore|idleDays|usageCount|apiCallCount", message = "排序字段不合法")
    private String sortField;

    @Schema(description = "排序方向：asc/desc", example = "desc")
    @Pattern(regexp = "asc|desc", message = "排序方向不合法")
    private String sortOrder;

}
