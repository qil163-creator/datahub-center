package cn.iocoder.yudao.module.governance.controller.admin.whitelist.vo;

import cn.iocoder.yudao.module.governance.dal.dataobject.subject.SafetyWhitelistSubjectDO;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 脱敏白名单新增/修改 Request VO")
@Data
public class SafetyWhitelistSaveReqVO {

    @Schema(description = "白名单编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "868")
    private Long id;

    @Schema(description = "白名单名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "芋艿")
    @NotEmpty(message = "白名单名称不能为空")
    private String name;

    @Schema(description = "生效主体", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotEmpty(message = "生效主体不能为空")
    private String subjectType;

    @Schema(description = "豁免场景", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "豁免场景不能为空")
    private String scene;

    @Schema(description = "豁免范围", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotEmpty(message = "豁免范围不能为空")
    private String objectType;

    @Schema(description = "豁免对象编号", example = "2450")
    private Long objectId;

    @Schema(description = "生效开始时间")
    private LocalDateTime effectiveTime;

    @Schema(description = "生效结束时间")
    private LocalDateTime expireTime;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "状态不能为空")
    private Short status;

    @Schema(description = "申请理由", example = "测试环境排查需要查看明文数据")
    private String applyReason;

    @Schema(description = "描述", example = "你说的对")
    private String description;

    @Schema(description = "备注", example = "随便")
    private String remark;

    @Schema(description = "数据安全--脱敏白名单生效主体列表")
    private List<SafetyWhitelistSubjectDO> whitelistSubjects;

}