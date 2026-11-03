package cn.iocoder.yudao.module.governance.api.quality.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

/** 研发运行对应的质量检查请求，业务键在全局唯一。 */
@Data
public class QualityRunReqDTO {

    @NotNull
    private Long qualityTaskId;

    @NotBlank
    @Size(max = 180)
    private String requestKey;

    @NotNull
    private Long executionUserId;
}
