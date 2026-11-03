package cn.iocoder.yudao.module.governance.api.metadata.dto;

import jakarta.validation.constraints.*;

/** 同一目录与技术表的来源版本单调递增，operationId 重试时不变。 */
public record MetadataCatalogLinkCommandDTO(
        @NotBlank @Size(max = 64) String operationId,
        @NotBlank @Size(max = 256) String sourceKey,
        @NotNull @Positive Long sourceRevision,
        @NotNull @Positive Long tableId,
        @NotNull Boolean linked) {

}
