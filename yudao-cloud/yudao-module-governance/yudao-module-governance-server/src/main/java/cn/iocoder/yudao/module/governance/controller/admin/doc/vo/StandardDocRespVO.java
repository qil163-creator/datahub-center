package cn.iocoder.yudao.module.governance.controller.admin.doc.vo;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import cn.iocoder.yudao.framework.excel.core.annotations.DictFormat;
import cn.iocoder.yudao.framework.excel.core.convert.DictConvert;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "管理后台 - 标准文件登记 Response VO")
@Data
@ExcelIgnoreUnannotated
public class StandardDocRespVO {

    @Schema(description = "标准文件编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "15387")
    @ExcelProperty("标准文件编号")
    private Long id;

    @Schema(description = "标准编号")
    @ExcelProperty("标准编号")
    private String stdCode;

    @Schema(description = "标准名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("标准名称")
    private String nameCn;

    @Schema(description = "标准英文名称（已废弃）")
    @ExcelProperty("标准英文名称")
    private String nameEn;

    @Schema(description = "标准分类", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @ExcelProperty(value = "标准分类", converter = DictConvert.class)
    @DictFormat("standard_std_type")
    private String stdType;

    @Schema(description = "标准类目编号")
    private Long categoryId;

    @Schema(description = "标准类目名称")
    @ExcelProperty("标准类目")
    private String categoryName;

    @Schema(description = "标准原文版本号")
    @ExcelProperty("版本号")
    private String versionNo;

    @Schema(description = "废止日期")
    @ExcelProperty("废止日期")
    @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate abolishDate;

    @Schema(description = "发布机构")
    @ExcelProperty("发布机构")
    private String publishOrg;

    @Schema(description = "发布日期")
    @ExcelProperty("发布日期")
    @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate publishDate;

    @Schema(description = "实施日期")
    @ExcelProperty("实施日期")
    @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate implementDate;

    @Schema(description = "实施状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @ExcelProperty(value = "实施状态", converter = DictConvert.class)
    @DictFormat("standard_implement_status")
    private String implementStatus;

    @Schema(description = "入库编号")
    @ExcelProperty("入库编号")
    private String registryNo;

    @Schema(description = "入库时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("入库时间")
    private LocalDateTime registryTime;

    @Schema(description = "标准原文文件编号", example = "28838")
    @ExcelProperty("标准原文文件编号")
    private Long fileId;

    @Schema(description = "标准原文文件名快照", example = "李四")
    @ExcelProperty("标准原文文件名快照")
    private String fileName;

    @Schema(description = "标准原文访问地址快照", example = "https://www.iocoder.cn")
    @ExcelProperty("标准原文访问地址快照")
    private String fileUrl;

    @Schema(description = "被本标准替代的旧标准编号", example = "1024")
    @ExcelProperty("被本标准替代的旧标准编号")
    private Long replaceDocId;

    @Schema(description = "标准摘要/适用范围说明")
    @ExcelProperty("标准摘要/适用范围说明")
    private String summary;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @ExcelProperty(value = "状态", converter = DictConvert.class)
    @DictFormat("common_status")
    private Short status;

    @Schema(description = "显示顺序", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("显示顺序")
    private Integer sort;

    @Schema(description = "备注", example = "随便")
    @ExcelProperty("备注")
    private String remark;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("创建时间")
    private LocalDateTime createTime;

    @Schema(description = "创建人编号")
    private String creator;

    @Schema(description = "创建人名称")
    @ExcelProperty("创建人")
    private String creatorName;

    @Schema(description = "更新时间")
    @ExcelProperty("更新时间")
    private LocalDateTime updateTime;

}
