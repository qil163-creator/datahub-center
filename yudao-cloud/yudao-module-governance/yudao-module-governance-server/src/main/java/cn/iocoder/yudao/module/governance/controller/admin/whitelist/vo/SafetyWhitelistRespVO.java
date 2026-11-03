package cn.iocoder.yudao.module.governance.controller.admin.whitelist.vo;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import cn.iocoder.yudao.framework.excel.core.annotations.DictFormat;
import cn.iocoder.yudao.framework.excel.core.convert.DictConvert;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 脱敏白名单 Response VO")
@Data
@ExcelIgnoreUnannotated
public class SafetyWhitelistRespVO {

    @Schema(description = "白名单编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "868")
    @ExcelProperty("白名单编号")
    private Long id;

    @Schema(description = "白名单名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "芋艿")
    @ExcelProperty("白名单名称")
    private String name;

    @Schema(description = "生效主体", requiredMode = Schema.RequiredMode.REQUIRED, example = "USER")
    @ExcelProperty(value = "生效主体", converter = DictConvert.class)
    @DictFormat("subject_type")
    private String subjectType;

    @Schema(description = "豁免场景", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("豁免场景")
    private String scene;

    @Schema(description = "豁免范围", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @ExcelProperty("豁免范围")
    private String objectType;

    @Schema(description = "豁免对象编号", example = "2450")
    @ExcelProperty("豁免对象编号")
    private Long objectId;

    @Schema(description = "生效开始时间")
    @ExcelProperty("生效开始时间")
    private LocalDateTime effectiveTime;

    @Schema(description = "生效结束时间")
    @ExcelProperty("生效结束时间")
    private LocalDateTime expireTime;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @ExcelProperty(value = "状态", converter = DictConvert.class)
    @DictFormat("common_status") // TODO 代码优化：建议设置到对应的 DictTypeConstants 枚举类中
    private Short status;

    @Schema(description = "审批状态：DRAFT / PENDING / APPROVED / REJECTED")
    @ExcelProperty("审批状态")
    private String approvalStatus;

    @Schema(description = "申请理由")
    @ExcelProperty("申请理由")
    private String applyReason;

    @Schema(description = "审批人（用户编号）")
    private String approver;

    @Schema(description = "描述", example = "你说的对")
    @ExcelProperty("描述")
    private String description;

    @Schema(description = "备注", example = "随便")
    @ExcelProperty("备注")
    private String remark;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("创建时间")
    private LocalDateTime createTime;

}
