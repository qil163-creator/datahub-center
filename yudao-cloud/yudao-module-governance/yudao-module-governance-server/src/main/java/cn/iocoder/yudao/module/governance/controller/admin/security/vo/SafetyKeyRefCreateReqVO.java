package cn.iocoder.yudao.module.governance.controller.admin.security.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理后台 - 新建密钥引用 Request VO。只创建 KEY_CENTER（本地主密钥派生）引用；派生盐由服务端随机生成。
 */
@Schema(description = "管理后台 - 新建密钥引用 Request VO")
@Data
public class SafetyKeyRefCreateReqVO {

    @Schema(description = "引用编码：字母开头，字母数字下划线或短横线，≤64", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "pii_default")
    @NotBlank(message = "引用编码不能为空")
    @Pattern(regexp = "[A-Za-z][A-Za-z0-9_-]{0,63}", message = "引用编码须字母开头，只含字母、数字、下划线或短横线")
    private String keyCode;

    @Schema(description = "名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "个人信息字段加密")
    @NotBlank(message = "名称不能为空")
    @Size(max = 128, message = "名称不能超过 128 个字符")
    private String keyName;

    @Schema(description = "备注")
    @Size(max = 512, message = "备注不能超过 512 个字符")
    private String remark;
}
