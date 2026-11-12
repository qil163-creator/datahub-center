package cn.iocoder.yudao.module.governance.controller.admin.security.vo;

import cn.iocoder.yudao.framework.common.validation.InEnum;
import cn.iocoder.yudao.module.governance.enums.security.SafetyMaskSceneEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理后台 - 动态脱敏策略预览 Request VO：查看某用户在某场景下读取一张表时各列的处理方式。
 */
@Schema(description = "管理后台 - 动态脱敏策略预览 Request VO")
@Data
public class SafetyMaskPreviewReqVO {

    @Schema(description = "要模拟的用户编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "用户编号不能为空")
    @Positive(message = "用户编号必须为正数")
    private Long userId;

    @Schema(description = "场景编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "DATA_QUERY")
    @NotBlank(message = "场景编码不能为空")
    @InEnum(value = SafetyMaskSceneEnum.class, message = "场景编码必须是 {value}")
    private String sceneCode;

    @Schema(description = "元数据表编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "元数据表编号不能为空")
    @Positive(message = "元数据表编号必须为正数")
    private Long metaTableId;

    @Schema(description = "示例值：对每个会被分类规则脱敏的列演示效果；不填则不演示")
    @Size(max = 200, message = "示例值不能超过 200 个字符")
    private String sampleValue;
}
