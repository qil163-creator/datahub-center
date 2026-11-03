package cn.iocoder.yudao.module.governance.controller.admin.category.vo;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import cn.iocoder.yudao.framework.excel.core.annotations.DictFormat;
import cn.iocoder.yudao.framework.excel.core.convert.DictConvert;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 数据分类信息 Response VO")
@Data
@ExcelIgnoreUnannotated
public class SafetyCategoryRespVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "9062")
    @ExcelProperty("主键")
    private Long id;

    private Long revision;

    private String sourceRecordId;

    private String categoryName;

    private String levelName;

    private String levelDisplayName;

    private String levelColor;

    private String ruleName;

    private Boolean configured;

    private String creatorName;

    private String updaterName;

    @Schema(description = "所属类目ID", example = "9061")
    @ExcelProperty("所属类目ID")
    private Long categoryId;

    @Schema(description = "分类名称", example = "王五")
    @ExcelProperty("分类名称")
    private String name;

    @Schema(description = "分类缩写", example = "芋艿")
    @ExcelProperty("分类缩写")
    private String shortName;

    @Schema(description = "数据分级", example = "25576")
    @ExcelProperty("数据分级")
    private Integer dataLevelId;

    @Schema(description = "优先级")
    @ExcelProperty("优先级")
    private String priority;

    @Schema(description = "脱敏配置", example = "29537")
    @ExcelProperty("脱敏配置")
    private Long ruleId;

    @Schema(description = "备注", example = "你猜")
    @ExcelProperty("备注")
    private String remark;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @ExcelProperty(value = "状态", converter = DictConvert.class)
    @DictFormat("common_status")
    private Short status;

    @Schema(description = "显示顺序", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("显示顺序")
    private Integer sort;

    @Schema(description = "规则描述", example = "随便")
    @ExcelProperty("规则描述")
    private String description;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("创建时间")
    private LocalDateTime createTime;

    @Schema(description = "创建者", example = "1")
    private String creator;

    @Schema(description = "修改者", example = "1")
    private String updater;

    @Schema(description = "修改时间")
    private LocalDateTime updateTime;

}
