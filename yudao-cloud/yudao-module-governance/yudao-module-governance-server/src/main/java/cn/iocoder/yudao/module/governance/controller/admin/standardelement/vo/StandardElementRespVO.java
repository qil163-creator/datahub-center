package cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDateTime;

import cn.idev.excel.annotation.*;
import cn.iocoder.yudao.framework.excel.core.annotations.DictFormat;
import cn.iocoder.yudao.framework.excel.core.convert.DictConvert;

@Schema(description = "管理后台 - 数据标准--标准数据元 Response VO")
@Data
@ExcelIgnoreUnannotated
public class StandardElementRespVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "32283")
    @ExcelProperty("主键")
    private Integer id;

    @Schema(description = "数据元编码")
    @ExcelProperty("数据元编码")
    private String code;

    @Schema(description = "中文名称", example = "张三")
    @ExcelProperty("中文名称")
    private String name;

    @Schema(description = "英文名称", example = "张三")
    @ExcelProperty("英文名称")
    private String engName;

    @Schema(description = "数据元类目", example = "12292")
    @ExcelProperty("数据元类目")
    private Long categoryId;

    @Schema(description = "标准登记", example = "28220")
    @ExcelProperty("标准登记")
    private Long documentId;

    @Schema(description = "字段类型", example = "VARCHAR")
    @ExcelProperty("字段类型")
    private String columnType;

    @Schema(description = "数据长度", example = "64")
    @ExcelProperty("数据长度")
    private Integer dataLength;

    @Schema(description = "数据精度（数值总位数）", example = "10")
    @ExcelProperty("数据精度")
    private Integer dataPrecision;

    @Schema(description = "小数位数", example = "2")
    @ExcelProperty("小数位数")
    private Integer dataScale;

    @Schema(description = "数据格式", example = "yyyy-MM-dd")
    @ExcelProperty("数据格式")
    private String dataFormat;

    @Schema(description = "计量单位", example = "元")
    @ExcelProperty("计量单位")
    private String measureUnit;

    @Schema(description = "值域范围说明", example = "[0,150]")
    @ExcelProperty("值域范围")
    private String valueRange;

    @Schema(description = "是否可为空：0-不可空 1-可空", example = "1")
    private Integer nullableFlag;

    @Schema(description = "默认值", example = "0")
    private String defaultValue;

    @Schema(description = "生命周期状态：DRAFT-草稿 PUBLISHED-已发布 DEPRECATED-已废止", example = "DRAFT")
    @ExcelProperty("生命周期状态")
    private String lifecycleStatus;

    @Schema(description = "关联标准码表编号", example = "1")
    @ExcelProperty("关联标准码表编号")
    private Integer codeSetId;

    @Schema(description = "负责人")
    @ExcelProperty("负责人")
    private Long personCharge;

    @Schema(description = "描述", example = "你猜")
    @ExcelProperty("描述")
    private String description;

    @Schema(description = "排序", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("排序")
    private Integer sort;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @ExcelProperty(value = "状态", converter = DictConvert.class)
    @DictFormat("common_status") // TODO 代码优化：建议设置到对应的 DictTypeConstants 枚举类中
    private Short status;

    @Schema(description = "备注", example = "随便")
    @ExcelProperty("备注")
    private String remark;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("创建时间")
    private LocalDateTime createTime;

    @Schema(description = "创建者")
    private String creator;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @Schema(description = "更新者")
    private String updater;

    @Schema(description = "标准类型，与标准文件分类一致")
    private String standardType;

    @Schema(description = "负责人联系电话，由服务端读取")
    private String contactNumber;

    @Schema(description = "编辑版本；修改时原样提交")
    private Long revision;

    private String categoryName;

    private String creatorName;

    private String personChargeName;

    private String documentName;

    private String updaterName;
}
