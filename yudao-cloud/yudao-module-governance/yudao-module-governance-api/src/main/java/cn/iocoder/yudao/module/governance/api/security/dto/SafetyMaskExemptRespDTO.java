package cn.iocoder.yudao.module.governance.api.security.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 脱敏白名单豁免查询结果 DTO
 *
 * @author Li
 */
@Schema(description = "RPC 服务 - 脱敏白名单豁免查询结果 DTO")
@Data
public class SafetyMaskExemptRespDTO {

    @Schema(description = "是否豁免脱敏", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean exempt;

    @Schema(description = "命中的白名单编号")
    private Long whitelistId;

    @Schema(description = "命中的白名单名称")
    private String whitelistName;

}
