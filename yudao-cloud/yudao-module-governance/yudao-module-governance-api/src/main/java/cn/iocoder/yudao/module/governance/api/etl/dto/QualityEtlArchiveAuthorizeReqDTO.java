package cn.iocoder.yudao.module.governance.api.etl.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 用于归档大型内联 ETL 报告载荷的有数量上限的只读预检。 */
@Data
public class QualityEtlArchiveAuthorizeReqDTO {

    @NotNull
    @Positive
    private Long executionUserId;

    @NotEmpty
    @Size(max = 32)
    private List<@NotNull @Positive Long> reportIds;
}
