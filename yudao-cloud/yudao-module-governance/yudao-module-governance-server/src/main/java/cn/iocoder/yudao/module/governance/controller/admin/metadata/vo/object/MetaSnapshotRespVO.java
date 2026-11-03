package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 表结构快照 Response VO（列表接口不返回快照体，详情接口返回）
 *
 * @author Li
 */
@Schema(description = "管理后台 - 表结构快照 Response VO")
@Data
public class MetaSnapshotRespVO {

    @Schema(description = "是否当前定版结构版本")
    private Boolean finalized;


    @Schema(description = "快照编号", example = "1")
    private Long id;

    @Schema(description = "表元数据编号", example = "1")
    private Long tableId;

    @Schema(description = "结构语义版本", example = "v1.1.0")
    private String structVersion;

    @Schema(description = "采集实例编号", example = "1")
    private Long instanceId;

    @Schema(description = "快照来源：1-采集 2-手工 3-导入 4-回滚", example = "1")
    private Integer changeSource;

    @Schema(description = "全结构快照 JSON（仅详情接口返回）")
    private String snapshot;

    @Schema(description = "结构哈希", example = "e3b0c44298fc1c149afbf4c8996fb924...")
    private String snapshotHash;

    @Schema(description = "变更摘要 JSON", example = "{\"colAdd\":1,\"colUpdate\":0,\"colDelete\":0,\"breaking\":false}")
    private String changeSummary;

    @Schema(description = "命名基线引用标记：0-否 1-是", example = "0")
    private Integer baselineFlag;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
