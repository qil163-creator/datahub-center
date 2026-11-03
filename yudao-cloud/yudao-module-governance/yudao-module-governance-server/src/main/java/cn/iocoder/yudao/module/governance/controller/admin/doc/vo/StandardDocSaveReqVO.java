package cn.iocoder.yudao.module.governance.controller.admin.doc.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "管理后台 - 标准文件登记新增/修改 Request VO")
@Data
public class StandardDocSaveReqVO {

    @Schema(description = "标准文件编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "15387")
    private Long id;

    @Schema(description = "标准编号")
    @Size(max = 64)
    private String stdCode;

    @Schema(description = "标准名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "标准名称不能为空")
    @Size(max = 255)
    private String nameCn;

    @Schema(description = "标准英文名称（已废弃，前端不再采集）")
    private String nameEn;

    @Schema(description = "标准分类", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotEmpty(message = "标准分类不能为空")
    @Size(max = 16)
    private String stdType;

    @Schema(description = "标准类目编号；登记接口新增和修改必填，原检索接口新增可空、修改未传时保留原类目")
    private Long categoryId;

    @Schema(description = "标准原文版本号，与平台版本快照编号无关")
    @Size(max = 64)
    private String versionNo;

    @Schema(description = "废止日期", example = "2026-10-19")
    @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate abolishDate;

    @Schema(description = "发布机构")
    @Size(max = 255)
    private String publishOrg;

    @Schema(description = "发布日期")
    @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate publishDate;

    @Schema(description = "实施日期")
    @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate implementDate;

    @Schema(description = "实施状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotEmpty(message = "实施状态不能为空")
    @Size(max = 500)
    private String implementStatus;

    @Schema(description = "入库编号")
    private String registryNo;

    @Schema(description = "入库时间")
    private LocalDateTime registryTime;

    @Schema(description = "标准原文文件编号", example = "28838")
    private Long fileId;

    @Schema(description = "标准原文文件名快照", example = "李四")
    @Size(max = 255)
    private String fileName;

    @Schema(description = "标准原文访问地址快照", example = "https://www.iocoder.cn")
    @Pattern(regexp = "(?i)^https?://.+$", message = "标准原文访问地址仅支持 http/https 协议")
    @Size(max = 1024)
    private String fileUrl;

    @Schema(description = "被本标准替代的旧标准编号", example = "12526")
    private Long replaceDocId;

    @Schema(description = "标准摘要/适用范围说明")
    @Size(max = 2048)
    private String summary;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @NotNull(message = "状态不能为空")
    @jakarta.validation.constraints.Min(0)
    @jakarta.validation.constraints.Max(1)
    private Short status;

    @Schema(description = "显示顺序", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "显示顺序不能为空")
    private Integer sort;

    @Schema(description = "备注", example = "随便")
    @Size(max = 512)
    private String remark;

}
