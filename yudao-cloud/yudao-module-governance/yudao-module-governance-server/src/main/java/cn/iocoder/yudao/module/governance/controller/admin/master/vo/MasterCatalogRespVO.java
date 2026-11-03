package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 主数据目录 Response VO")
@Data
public class MasterCatalogRespVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "父级编号，0 表示根节点", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    private Long parentId;

    @Schema(description = "节点类型：0-普通目录 1-主数据", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    private Integer type;

    @Schema(description = "节点名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "人口主数据")
    private String name;

    @Schema(description = "同级排序号", example = "0")
    private Integer orderNo;

    @Schema(description = "描述", example = "人口相关主数据")
    private String description;

    @Schema(description = "完整路径", example = "根目录/人口主数据")
    private String path;

    @Schema(description = "所属机构编号", example = "100")
    private Long orgId;

    @Schema(description = "模型状态（主数据节点时返回）：null-未建模 0-未发布 1-已发布", example = "1")
    private Integer modelStatus;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
