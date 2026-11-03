package cn.iocoder.yudao.module.supply.api.app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "RPC 服务 - 授权操作结果")
@Data
public class SupplyGrantOpRespDTO {

    private String sourceSystem;

    private String operationId;

    private String resultStatus;

    private Long grantId;

    private Integer grantRevision;

    private String errorCode;

    private String payloadHash;
}
