package cn.iocoder.yudao.module.dataforge.api.migration.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 幂等键由 Governance 根据已提交的整改和对象产生，不能取自浏览器任意参数。 */
public record SyncBoundaryReqDTO(
        @NotNull @Pattern(regexp = "quality:[a-f0-9]{64}") String requestKey,
        @NotNull @Valid SyncBatchQueryReqDTO target) {

}

