package cn.iocoder.yudao.module.supply.api.dataapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/**
 * 供资产申请核验的 API 绑定摘要。不含 SQL 脚本、转发地址或密钥。
 */
@Schema(description = "RPC 服务 - 数据 API 绑定摘要")
@Data
public class SupplyApiBindingRespDTO {

    private Long id;

    private String name;

    private String path;

    private String version;

    private Integer status;

    private String apiType;

    private String configType;

    private Long databaseId;

    private String tableName;

    /** 已发布合同；旧数据未确认发布时为空，不能用当前草稿补齐。 */
    private String contractHash;

    private Long activeReleaseId;

    private String schemaName;

    /** 已发布输出名称到物理字段的映射；未核验时为空，不表示全字段。 */
    private Map<String, String> outputFieldMapping = Map.of();

    private boolean fieldMappingVerified;

}
