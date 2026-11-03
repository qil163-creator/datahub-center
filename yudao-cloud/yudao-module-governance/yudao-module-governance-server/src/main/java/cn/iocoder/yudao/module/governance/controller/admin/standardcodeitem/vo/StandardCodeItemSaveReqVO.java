package cn.iocoder.yudao.module.governance.controller.admin.standardcodeitem.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 数据标准--码值新增/修改 Request VO")
@Data
public class StandardCodeItemSaveReqVO {

    @Schema(description = "主键", example = "1024")
    private Integer id;

    @Schema(description = "码值标签", requiredMode = Schema.RequiredMode.REQUIRED, example = "主汛期")
    @NotEmpty(message = "码值标签不能为空")
    @Size(max = 100, message = "码值标签长度不能超过 100 个字符")
    private String label;

    @Schema(description = "码值键值", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotEmpty(message = "码值键值不能为空")
    @Size(max = 100, message = "码值键值长度不能超过 100 个字符")
    private String value;

    @Schema(description = "码值类型（兼容字段；与 codeSetId 至少填写一个）", example = "FSTP")
    @Size(max = 100, message = "码值类型长度不能超过 100 个字符")
    private String dictType;

    @Schema(description = "标准码表编号", example = "1024")
    private Integer codeSetId;

    @Schema(description = "父码值编号", example = "2048")
    private Integer parentId;

    @Schema(description = "生效时间（含）")
    private LocalDateTime validFrom;

    @Schema(description = "失效时间（不含）")
    private LocalDateTime validTo;

    @Schema(description = "码值排序", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "码值排序不能为空")
    private Integer sort;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "状态不能为空")
    private Short status;

    @Schema(description = "备注", example = "主汛期")
    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;

}
