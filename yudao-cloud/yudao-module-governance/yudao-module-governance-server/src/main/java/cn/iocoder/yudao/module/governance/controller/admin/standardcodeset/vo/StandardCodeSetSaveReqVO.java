package cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import jakarta.validation.constraints.*;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 数据标准--标准码新增/修改 Request VO")
@Data
public class StandardCodeSetSaveReqVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "13104")
    private Integer id;

    @Schema(description = "字典名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "李四")
    @NotEmpty(message = "字典名称不能为空")
    @Size(max = 50)
    private String name;

    @Schema(description = "字典类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @NotEmpty(message = "字典类型不能为空")
    @Size(max = 100)
    private String type;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "状态不能为空")
    @Min(0)
    @Max(1)
    private Short status;

    @Schema(description = "备注", example = "你说的对")
    private String remark;

    @Schema(description = "删除时间")
    private LocalDateTime deletedTime;

    @Schema(description = "共用数据元类目")
    @NotNull(message = "关联信息不能为空")
    private Long categoryId;

    @Schema(description = "字段类型")
    @NotBlank
    @Size(max = 64)
    private String columnType;

    @Schema(description = "数据长度")
    @Min(1)
    @Max(4000)
    private Integer dataLength;

    @Schema(description = "小数位数")
    @Min(0)
    @Max(18)
    private Integer dataScale;

    @Schema(description = "标准文件编号")
    @NotNull(message = "关联信息不能为空")
    private Long documentId;

    @Schema(description = "负责人编号")
    @NotNull(message = "关联信息不能为空")
    private Long personCharge;

    @Schema(description = "描述")
    @Size(max = 256)
    private String description;

    @Schema(description = "标准类型，与标准文件分类一致")
    @NotBlank
    @Size(max = 64)
    private String standardType;

    @Schema(description = "负责人联系电话，由服务端读取")
    private String contactNumber;

    @Schema(description = "编辑版本；修改时原样提交")
    private Long revision;

}
