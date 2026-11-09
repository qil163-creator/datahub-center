package cn.iocoder.yudao.module.asset.controller.admin.monitor.vo;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "管理后台 - 资产运营指标 Response VO")
@Data
@ExcelIgnoreUnannotated
public class AssetOperationRespVO {

    @Schema(description = "统计日期")
    @ExcelProperty("统计日期")
    private LocalDate statDate;

    @Schema(description = "统计窗口天数")
    private Integer windowDays;

    @Schema(description = "资产编号")
    @ExcelProperty("资产编号")
    private Long assetId;

    @Schema(description = "资产名称")
    @ExcelProperty("资产名称")
    private String assetName;

    @Schema(description = "资产编码")
    @ExcelProperty("资产编码")
    private String assetCode;

    @Schema(description = "资产类型")
    private Integer assetType;

    @Schema(description = "资产状态")
    private Integer assetStatus;

    @Schema(description = "资产类目")
    private Long categoryId;

    @Schema(description = "类目名称")
    @ExcelProperty("资产类目")
    private String categoryName;

    @Schema(description = "数仓分层")
    @ExcelProperty("分层")
    private String layer;

    @Schema(description = "来源系统")
    @ExcelProperty("来源系统")
    private String sourceSystemName;

    @Schema(description = "归属部门")
    private Long ownerDeptId;

    @Schema(description = "浏览次数")
    @ExcelProperty("浏览次数")
    private Long browseCount;

    @Schema(description = "浏览人数")
    @ExcelProperty("浏览人数")
    private Long browseUserCount;

    @Schema(description = "申请次数")
    @ExcelProperty("申请次数")
    private Long applyCount;

    @Schema(description = "使用次数")
    @ExcelProperty("使用次数")
    private Long usageCount;

    @Schema(description = "使用人数")
    @ExcelProperty("使用人数")
    private Long usageUserCount;

    @Schema(description = "收藏数")
    @ExcelProperty("收藏数")
    private Long favoriteCount;

    @Schema(description = "有效授权数")
    @ExcelProperty("有效授权数")
    private Long activeGrantCount;

    @Schema(description = "授权部门数")
    @ExcelProperty("授权部门数")
    private Long grantDeptCount;

    @Schema(description = "接口调用次数")
    @ExcelProperty("接口调用次数")
    private Long apiCallCount;

    @Schema(description = "接口成功次数")
    private Long apiSuccessCount;

    @Schema(description = "调用应用数")
    @ExcelProperty("调用应用数")
    private Long apiAppCount;

    @Schema(description = "问题反馈数")
    @ExcelProperty("问题反馈数")
    private Long feedbackCount;

    @Schema(description = "最近使用时间")
    @ExcelProperty("最近使用时间")
    private LocalDateTime lastUsedTime;

    @Schema(description = "闲置天数")
    @ExcelProperty("闲置天数")
    private Integer idleDays;

    @Schema(description = "热度分")
    @ExcelProperty("热度分")
    private BigDecimal heatScore;

    @Schema(description = "使用广度分")
    @ExcelProperty("使用广度分")
    private BigDecimal breadthScore;

    @Schema(description = "质量分")
    @ExcelProperty("质量分")
    private BigDecimal qualityScore;

    @Schema(description = "元数据完整度分")
    @ExcelProperty("完整度分")
    private BigDecimal completenessScore;

    @Schema(description = "综合价值分")
    @ExcelProperty("价值分")
    private BigDecimal valueScore;

    @Schema(description = "价值等级")
    @ExcelProperty("价值等级")
    private String valueLevel;

    @Schema(description = "运营建议")
    @ExcelProperty("运营建议")
    private String suggestion;

    @Schema(description = "是否取得接口调用统计")
    private Boolean apiStatsAvailable;

}
