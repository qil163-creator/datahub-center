package cn.iocoder.yudao.module.governance.api.quality.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Schema(description = "RPC - 集市校核发起工单")
@Data
public class QualityWorkOrderCreateReqDTO {

    @NotBlank
    private String orderName;

    private String description;

    private String problemType;

    private String sourceType;

    private String topicRef;

    private String idempotentKey;

    private Long requestDeptId;

    private Long providerDeptId;
}
