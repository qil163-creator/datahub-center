package cn.iocoder.yudao.module.governance.api.metadata.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 字段元数据 RPC DTO。
 */
@Schema(description = "RPC 服务 - 字段元数据信息")
@Data
public class MetadataColumnRespDTO {

    private Long id;

    private String resourceUrn;

    private Long tableId;

    private String columnName;

    private Integer ordinalPosition;

    private String sourceComment;

    private String customComment;

    private String businessName;

    private String dataType;

    private Long dataLength;

    private Integer dataPrecision;

    private Integer dataScale;

    private Integer nullableFlag;

    private String defaultValue;

    private Integer pkFlag;

    private Integer fkFlag;

    private Integer uniqueFlag;

    private Integer autoIncrementFlag;

    private String businessDefinition;

    private String measuringUnit;

    private Integer standardElementId;

    private Integer revision;

    private Long standardMappingId;

    private String standardElementCode;

    private String standardElementName;

    private Long safetyLevelId;

    private Integer sensitiveFlag;

    private Integer objectStatus;

    private String remark;

}
