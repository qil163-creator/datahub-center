package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - 主数据记录批量操作 Request VO")
@Data
public class MasterDataRowsReqVO {

    @Schema(description = "主数据模型编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "主数据模型编号不能为空")
    private Long modelId;

    @Schema(description = "稳定请求标识；超时重试须保持相同标识")
    @NotBlank
    @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private String requestId;

    @Schema(description = "行数据（key 为字段名）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "行数据不能为空")
    @Size(max = 200)
    private List<Map<String, Object>> rows;

}
