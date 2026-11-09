package cn.iocoder.yudao.module.supply.controller.admin.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 应用 Response VO")
@Data
public class SupplyAppRespVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "应用名称", example = "某县灌区系统")
    private String name;

    @Schema(description = "应用类型：Web/App/小程序", example = "Web")
    private String appType;

    @Schema(description = "应用图标地址")
    private String icon;

    @Schema(description = "应用 AppKey（认证标识）")
    private String appKey;

    @Schema(description = "应用秘钥 AppSecret（签名认证用）")
    private String secret;

    @Schema(description = "是否公开：0-非公开 1-公开", example = "1")
    private Integer publicFlag;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建者编号")
    private String creator;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新者编号")
    private String updater;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    private Long owningDeptId;

    private String systemCode;

    private Long ownerUserId;

    private String purpose;

    private String environment;

    private Integer appStatus;

    private Integer credentialRevision;

}
