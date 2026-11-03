package cn.iocoder.yudao.module.dataforge.api.etl.dto;

import lombok.Data;

import java.util.List;

/** 仅包含不可变批次身份和状态摘要，不返回连接凭据或完整执行配置。 */
@Data
public class EtlTargetBatchDescribeRespDTO {

    private Long batchId;

    private Long recordId;

    private String nodeNo;

    private Long databaseId;

    private String schemaName;

    private String tableName;

    private String payloadHash;

    private Integer runStatus;

    private String commitStatus;

    private String qualityStatus;

    private Long qualityInstanceId;

    private Long inputCount;

    private Long outputCount;

    private Long passCount;

    private Long warnCount;

    private Long rejectCount;

    private Boolean available;

    private List<String> fields;
}
