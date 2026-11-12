package cn.iocoder.yudao.module.governance.api.lineage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 血缘上报结果。
 */
@Schema(description = "RPC 服务 - 血缘上报结果")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LineageReportRespDTO {

    @Schema(description = "处理结果：ACCEPTED 已替换 / NOOP 未变化 / STALE 落后未改动", requiredMode = Schema.RequiredMode.REQUIRED)
    private String result;

    @Schema(description = "处理后服务端保存的来源修订号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long storedRevision;

    @Schema(description = "处理后服务端保存的解析器版本", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer storedAnalyzerVersion;

}
