package cn.iocoder.yudao.module.governance.controller.admin.security.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理后台 - 密钥引用 Response VO。不含任何密钥材料与派生盐。
 */
@Schema(description = "管理后台 - 密钥引用 Response VO")
@Data
public class SafetyKeyRefRespVO {

    @Schema(description = "编号", example = "1")
    private Long id;

    @Schema(description = "引用编码", example = "pii_default")
    private String keyCode;

    @Schema(description = "名称", example = "个人信息字段加密")
    private String keyName;

    @Schema(description = "提供方：KMS、VAULT、KEY_CENTER", example = "KEY_CENTER")
    private String provider;

    @Schema(description = "密钥类型", example = "SM4_SYMMETRIC")
    private String keyType;

    @Schema(description = "创建时的主密钥版本", example = "1")
    private String keyVersion;

    @Schema(description = "状态：0 启用、1 停用", example = "0")
    private Integer status;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
