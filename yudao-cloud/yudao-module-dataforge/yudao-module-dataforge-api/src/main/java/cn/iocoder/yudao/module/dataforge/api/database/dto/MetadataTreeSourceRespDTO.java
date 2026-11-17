package cn.iocoder.yudao.module.dataforge.api.database.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "RPC 服务 - 元数据树连接名称和删除状态")
public class MetadataTreeSourceRespDTO {

    private Long id;
    private String name;
    private Boolean deleted;
}
