package cn.iocoder.yudao.module.supply.api.dataapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 单个数据 API 的开放网关调用统计。只计应用经开放网关的真实调用，不含预览、管理端执行与测试运行。
 */
@Schema(description = "RPC 服务 - 数据 API 调用统计")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupplyApiCallStatRespDTO {

    @Schema(description = "API 编号")
    private Long apiId;

    @Schema(description = "调用次数")
    private Long callCount;

    @Schema(description = "成功次数")
    private Long successCount;

    @Schema(description = "调用应用数")
    private Long appCount;

    @Schema(description = "最近调用时间")
    private LocalDateTime lastCallTime;

}
