package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 在线填报-Excel 导入确认 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - Excel 导入确认 Request VO")
@Data
public class FillExcelImportConfirmReqVO {

    @Schema(description = "导入模式：1-追加到现有草稿 2-替换全部草稿行", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "导入模式不能为空")
    private Integer mode;

    @Schema(description = "客户端已同步到的服务端版本（并发编辑冲突检测）", requiredMode = Schema.RequiredMode.REQUIRED, example = "135")
    @NotNull(message = "expectedVersion 不能为空")
    private Long expectedVersion;

    @Schema(description = "追加时判断重复的唯一标识字段码（为空表示不查重）", example = "[\"id_card\"]")
    @jakarta.validation.constraints.Size(max = 5, message = "唯一标识最多 5 个字段")
    private List<String> duplicateKeys;

    @Schema(description = "遇到重复数据：SKIP-跳过 OVERWRITE-覆盖（用导入值更新已有行）", example = "SKIP")
    @jakarta.validation.constraints.Pattern(regexp = "^(|SKIP|OVERWRITE)$", message = "重复处理方式不合法")
    private String duplicateMode;

    @Schema(description = "预检返回的数据行（fieldCode -> 值）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "导入数据行不能为空")
    private List<Map<String, Object>> rows;

}
