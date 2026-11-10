package cn.iocoder.yudao.module.governance.controller.admin.standardcodeitem.vo;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import cn.iocoder.yudao.framework.excel.core.annotations.DictFormat;
import cn.iocoder.yudao.framework.excel.core.convert.DictConvert;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 数据标准--码值 Response VO")
@Data
@ExcelIgnoreUnannotated
public class StandardCodeItemRespVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @ExcelProperty("编号")
    private Integer id;

    @Schema(description = "码值标签", requiredMode = Schema.RequiredMode.REQUIRED, example = "主汛期")
    @ExcelProperty("码值标签")
    private String label;

    @Schema(description = "码值键值", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @ExcelProperty("码值键值")
    private String value;

    @Schema(description = "码值类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "FSTP")
    @ExcelProperty("码值类型")
    private String dictType;

    @Schema(description = "标准码表编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer codeSetId;

    @Schema(description = "码值排序", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @ExcelProperty("排序")
    private Integer sort;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @ExcelProperty(value = "状态", converter = DictConvert.class)
    @DictFormat("common_status")
    private Short status;

    @Schema(description = "备注", example = "主汛期")
    @ExcelProperty("备注")
    private String remark;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("创建时间")
    private LocalDateTime createTime;

    private String updaterName;

    private String creator;

    private String creatorName;

    private java.time.LocalDateTime updateTime;

    private String updater;
}
