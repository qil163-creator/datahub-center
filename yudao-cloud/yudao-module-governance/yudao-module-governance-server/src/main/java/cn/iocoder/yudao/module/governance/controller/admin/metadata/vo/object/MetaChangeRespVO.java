package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 元数据变更明细 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 元数据变更明细 Response VO")
@Data
public class MetaChangeRespVO {

    @Schema(description = "变更编号", example = "1")
    private Long id;

    @Schema(description = "采集实例编号（手工修改为空）", example = "1")
    private Long instanceId;

    @Schema(description = "变更来源：1-采集 2-手工 3-导入 4-回滚", example = "1")
    private Integer changeSource;

    @Schema(description = "对象类型：1-库 2-表 3-字段", example = "3")
    private Integer objectType;

    @Schema(description = "对象编号", example = "100")
    private Long objectId;

    @Schema(description = "归属表编号", example = "1")
    private Long tableId;

    @Schema(description = "归属库编号", example = "1")
    private Long dbId;

    @Schema(description = "变更类型：1-新增 2-修改 3-删除", example = "2")
    private Integer changeType;

    @Schema(description = "变更属性", example = "COMMENT")
    private String property;

    @Schema(description = "修改前值")
    private String beforeValue;

    @Schema(description = "修改后值")
    private String afterValue;

    @Schema(description = "变更前语义版本", example = "v1.0.0")
    private String versionFrom;

    @Schema(description = "变更后语义版本", example = "v1.0.1")
    private String versionTo;

    @Schema(description = "操作人（手工变更）", example = "1")
    private String operator;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
