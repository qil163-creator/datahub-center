package cn.iocoder.yudao.module.supply.api.dataapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据 API 开放网关调用统计请求。
 */
@Schema(description = "RPC 服务 - 数据 API 调用统计请求")
@Data
public class SupplyApiCallStatReqDTO {

    /** 单次最多统计的 API 数量 */
    public static final int MAX_API_IDS = 500;

    @Schema(description = "API 编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "API 编号不能为空")
    @Size(max = MAX_API_IDS, message = "单次最多统计 500 个 API")
    private List<Long> apiIds;

    @Schema(description = "统计起始时间（含）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "统计起始时间不能为空")
    private LocalDateTime since;

}
