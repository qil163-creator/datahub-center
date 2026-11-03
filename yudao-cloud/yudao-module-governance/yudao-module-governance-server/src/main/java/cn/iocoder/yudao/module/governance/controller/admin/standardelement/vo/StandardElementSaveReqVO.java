package cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import jakarta.validation.constraints.*;

@Schema(description = "管理后台 - 数据标准--标准数据元新增/修改 Request VO")
@Data
public class StandardElementSaveReqVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "32283")
    private Integer id;

    @Schema(description = "数据元编码")
    private String code;

    @Schema(description = "中文名称", example = "张三")
    @NotBlank
    @Size(max = 50)
    private String name;

    @Schema(description = "英文名称", example = "张三")
    @NotBlank
    @Size(max = 50)
    private String engName;

    @Schema(description = "关联 dh_foundation_category_datameta.id", example = "12292")
    @NotNull(message = "关联信息不能为空")
    private Long categoryId;

    @Schema(description = "关联 dh_governance_standard_doc.id", example = "28220")
    @NotNull(message = "关联信息不能为空")
    private Long documentId;

    @Schema(description = "字段类型，如 VARCHAR、BIGINT", example = "1")
    @NotBlank
    @Size(max = 64)
    private String columnType;

    @Schema(description = "数据长度", example = "64")
    @Min(1)
    @Max(4000)
    private Integer dataLength;

    @Schema(description = "数据精度（数值总位数）", example = "10")
    private Integer dataPrecision;

    @Schema(description = "小数位数", example = "2")
    @Min(0)
    @Max(18)
    private Integer dataScale;

    @Schema(description = "数据格式", example = "yyyy-MM-dd")
    private String dataFormat;

    @Schema(description = "计量单位", example = "元")
    private String measureUnit;

    @Schema(description = "值域范围说明", example = "[0,150]")
    private String valueRange;

    @Schema(description = "是否可为空：0-不可空 1-可空", example = "1")
    private Integer nullableFlag;

    @Schema(description = "默认值", example = "0")
    private String defaultValue;

    @Schema(description = "关联标准码表编号", example = "1")
    private Integer codeSetId;

    @Schema(description = "负责人id")
    @NotNull(message = "关联信息不能为空")
    private Long personCharge;

    @Schema(description = "描述", example = "你猜")
    @Size(max = 256)
    private String description;

    @Schema(description = "排序", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "排序不能为空")
    private Integer sort;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @NotNull(message = "状态不能为空")
    @Min(0)
    @Max(1)
    private Short status;

    @Schema(description = "备注", example = "随便")
    private String remark;

    @Schema(description = "标准类型，与标准文件分类一致")
    @NotBlank
    @Size(max = 64)
    private String standardType;

    @Schema(description = "负责人联系电话，由服务端读取")
    private String contactNumber;

    @Schema(description = "编辑版本；修改时原样提交")
    private Long revision;

}
