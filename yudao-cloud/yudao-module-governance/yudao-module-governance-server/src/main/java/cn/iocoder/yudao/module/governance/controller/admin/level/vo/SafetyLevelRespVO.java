package cn.iocoder.yudao.module.governance.controller.admin.level.vo;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import cn.iocoder.yudao.framework.excel.core.annotations.DictFormat;
import cn.iocoder.yudao.framework.excel.core.convert.DictConvert;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 数据分级 Response VO")
@Data
@ExcelIgnoreUnannotated
public class SafetyLevelRespVO {

    @Schema(description = "主键id", requiredMode = Schema.RequiredMode.REQUIRED, example = "14852")
    @ExcelProperty("主键id")
    private Long id;

    @Schema(description = "安全等级", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("安全等级")
    private String safetyLevel;

    @Schema(description = "显示名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "芋艿")
    @ExcelProperty("显示名称")
    private String safetyName;

    @Schema(description = "标志颜色（hex，如 #52c41a，为空时前端按等级使用默认配色）", example = "#52c41a")
    @ExcelProperty("标志颜色")
    private String color;

    private Integer sensitiveLevel;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @ExcelProperty(value = "状态", converter = DictConvert.class)
    @DictFormat("common_status") // TODO 代码优化：建议设置到对应的 DictTypeConstants 枚举类中
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

}
