package cn.iocoder.yudao.module.supply.api.dataapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "RPC 服务 - 已发布 API 的资产注册摘要，不含执行配置")
public class SupplyApiCandidateRespDTO {

    private Long id;

    private String name;

    private String path;

    private String method;

    private String version;

    private Long activeReleaseId;

    private String contractHash;
}
