package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 贴源表结构同步预览 Response VO")
@Data
public class SourceSyncPreviewRespVO {

    @Schema(description = "逻辑模型编号", example = "1")
    private Long modelId;

    @Schema(description = "预览时模型的修订号，执行同步时原样提交", example = "8")
    private Long revision;

    @Schema(description = "模型设计状态：DRAFT、PUBLISHED", example = "PUBLISHED")
    private String status;

    @Schema(description = "来源数据源编号", example = "3")
    private Long datasourceId;

    @Schema(description = "来源表名", example = "t_accept_record")
    private String sourceTable;

    @Schema(description = "当前设计 → 源表当前结构的差异")
    private ModelDiffRespVO diff;
}
