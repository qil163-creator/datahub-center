package cn.iocoder.yudao.module.governance.api.metadata.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Schema(description = "RPC 服务 - 元数据运行指标回写")
@Data
public class MetadataRuntimeMetricsReqDTO {

    @NotNull(message = "数据源不能为空")
    private Long datasourceId;

    private List<TableMetric> tables;

    @Data
    public static class TableMetric {

        private String tableName;

        private Long rowCount;

        private Long storageSize;
    }

}
