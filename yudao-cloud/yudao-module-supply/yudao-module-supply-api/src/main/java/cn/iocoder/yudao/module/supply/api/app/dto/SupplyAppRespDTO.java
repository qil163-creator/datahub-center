package cn.iocoder.yudao.module.supply.api.app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "RPC 服务 - 数据服务应用")
@Data
public class SupplyAppRespDTO {

    private Long id;

    private String name;

    private String appType;

    private String appKey;

    private String secret;

    private Integer publicFlag;

    private String description;

    private String remark;

    private String creator;

    private LocalDateTime createTime;

}
