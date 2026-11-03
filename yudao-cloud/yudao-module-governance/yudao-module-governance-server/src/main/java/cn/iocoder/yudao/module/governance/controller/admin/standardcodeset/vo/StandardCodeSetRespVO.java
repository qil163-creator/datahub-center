package cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDateTime;

import cn.idev.excel.annotation.*;
import cn.iocoder.yudao.framework.excel.core.annotations.DictFormat;
import cn.iocoder.yudao.framework.excel.core.convert.DictConvert;

@Schema(description = "管理后台 - 数据标准--标准码 Response VO")
@Data
@ExcelIgnoreUnannotated
public class StandardCodeSetRespVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "13104")
    @ExcelProperty("主键")
    private Integer id;

    @Schema(description = "字典名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "李四")
    @ExcelProperty("字典名称")
    private String name;

    @Schema(description = "字典类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @ExcelProperty("字典类型")
    private String type;

    @Schema(description = "条目数量", example = "3")
    @ExcelProperty("条目数量")
    private Integer itemCount;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @ExcelProperty(value = "状态", converter = DictConvert.class)
    @DictFormat("common_status") // TODO 代码优化：建议设置到对应的 DictTypeConstants 枚举类中
    private Short status;

    @Schema(description = "备注", example = "你说的对")
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

    @Schema(description = "删除时间")
    @ExcelProperty("删除时间")
    private LocalDateTime deletedTime;

    @Schema(description = "共用数据元类目")
    private Long categoryId;

    @Schema(description = "字段类型")
    private String columnType;

    @Schema(description = "数据长度")
    private Integer dataLength;

    @Schema(description = "小数位数")
    private Integer dataScale;

    @Schema(description = "标准文件编号")
    private Long documentId;

    @Schema(description = "负责人编号")
    private Long personCharge;

    @Schema(description = "描述")
    private String description;

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
