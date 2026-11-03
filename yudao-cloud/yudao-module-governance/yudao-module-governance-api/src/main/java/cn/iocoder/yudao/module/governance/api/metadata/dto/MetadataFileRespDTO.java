package cn.iocoder.yudao.module.governance.api.metadata.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "RPC 服务 - 当前文件元数据的资产注册投影，不含连接凭据及联系人")
public class MetadataFileRespDTO {

    private Long id;

    private Long datasourceId;

    private String datasourceName;

    private String sourceSystemName;

    private String path;

    private String name;

    private String fileType;

    private Long size;

    private String businessName;

    private String description;

    private Integer bizRevision;
}
