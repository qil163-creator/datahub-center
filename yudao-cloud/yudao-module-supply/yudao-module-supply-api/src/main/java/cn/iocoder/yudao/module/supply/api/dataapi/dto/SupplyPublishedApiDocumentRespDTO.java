package cn.iocoder.yudao.module.supply.api.dataapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 已发布 API 的公开文档投影。仅供内部服务按有效授权继续过滤，不能作为授权凭据。
 */
@Schema(description = "RPC 服务 - 已发布 API 公开文档")
@Data
public class SupplyPublishedApiDocumentRespDTO {

    private Long id;

    private String name;

    private String path;

    private String method;

    private String version;

    private String contractHash;

    private Long activeReleaseId;

    /** 仅供内部消费者核对冻结的物理绑定，不直接向浏览器透传。 */
    private Long databaseId;

    private String schemaName;

    private String tableName;

    private List<RequestParam> requestParams = List.of();

    private List<ResponseField> responseFields = List.of();

    /** 已发布输出名称到物理字段的映射；消费方必须按批准字段过滤后再返回浏览器。 */
    private Map<String, String> outputFieldMapping = Map.of();

    private boolean fieldMappingVerified;

    @Schema(description = "公开请求参数，不包含默认值或示例值")
    @Data
    public static class RequestParam {

        private String name;

        private String type;

        private boolean required;

        private String description;
    }

    @Schema(description = "公开输出字段，不包含转换规则或示例值")
    @Data
    public static class ResponseField {

        private String name;

        private String type;

        private String description;
    }

}
