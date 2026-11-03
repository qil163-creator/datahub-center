package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/** 人工核查只展示交付状态，不返回冻结命令、字段范围或载荷摘要。 */
@Schema(description = "管理后台 - 数据集市交付操作状态")
public record MarketDeliveryOperationRespVO(
        Long id, String operationId, Long applyId,
        @Schema(description = "技术操作类型；REVOKE 同时承载暂停和终态撤销，不代表真实请求动作") String opType,
        Long sourceRevision,
        String status, String resolutionStatus, Integer attemptCount,
        String resultRef, String lastError, LocalDateTime updateTime, Integer protocolVersion,
        @Schema(description = "经冻结证据校验的原请求动作：GRANT/SUSPEND/REVOKE/UNKNOWN；不代表执行成功或当前权限")
        String action) {

}
