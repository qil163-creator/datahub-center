package cn.iocoder.yudao.module.governance.api.quality.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "RPC - 质量工单状态投影")
@Data
public class QualityWorkOrderStatusRespDTO {

    private Long id;

    private String orderCode;

    private String orderStatus;

    private String closeType;

    private Long ownerUserId;
}
