package cn.iocoder.yudao.module.dataforge.api.migration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 仅供受信 Governance 服务使用；执行人取自持久任务，租户取自认证通道上下文。 */
public record SyncBatchQueryReqDTO(@NotNull @Positive Long databaseId,
                                   @NotBlank @Size(max = 2048) String tableReference,
                                   @NotNull @Positive Long executionUserId) {

}

