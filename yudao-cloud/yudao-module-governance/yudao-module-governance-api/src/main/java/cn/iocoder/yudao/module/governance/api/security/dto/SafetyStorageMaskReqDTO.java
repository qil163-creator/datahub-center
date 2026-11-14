package cn.iocoder.yudao.module.governance.api.security.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 持久化前脱敏请求：结果会被保存并展示给多个用户，没有确定的查看者，因此不看场景开关、不享受白名单豁免。
 * 列来源的写法与 {@link SafetyDynamicMaskReqDTO.Column} 相同。
 */
@Schema(description = "RPC 服务 - 持久化前脱敏请求")
@Data
public class SafetyStorageMaskReqDTO {

    @Schema(description = "输出列及其物理来源", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "输出列不能为空")
    @Size(max = SafetyDynamicMaskReqDTO.MAX_COLUMNS, message = "输出列不能超过 500 个")
    @Valid
    private List<SafetyDynamicMaskReqDTO.Column> columns = new ArrayList<>();

    @Schema(description = "待保存的行，键为输出列名", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "结果行不能为空")
    @Size(max = SafetyDynamicMaskReqDTO.MAX_ROWS, message = "结果行不能超过 1000 行")
    private List<Map<String, Object>> rows = new ArrayList<>();

}
