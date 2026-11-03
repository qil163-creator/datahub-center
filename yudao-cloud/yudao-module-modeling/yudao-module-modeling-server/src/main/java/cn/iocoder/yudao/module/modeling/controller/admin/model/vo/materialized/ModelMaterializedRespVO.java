package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.materialized;

import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelHierarchyRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 模型物化发布 Response VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class ModelMaterializedRespVO extends ModelHierarchyRespVO {

    @Schema(description = "物化记录编号（最新一条；未发布为空）")
    private Long id;

    @Schema(description = "物化记录编号（同 id，便于前端）")
    private Long materializedId;

    @Schema(description = "逻辑模型编号（列表主键）")
    private Long modelId;

    private Long datasourceId;

    /** 本次部署准备时的设计修订标记，未知历史记录为空。 */
    private Long designRevision;

    private Long attemptId;

    private String datasourceType;

    @Schema(description = "最近发布目标的数据源名称，与 datasourceId/datasourceType 对应")
    private String datasourceName;

    @Schema(description = "模型英文名/编码")
    private String modelName;

    @Schema(description = "模型中文名")
    private String modelAlias;

    private String schemaName;

    private String tableName;

    private String publishMode;

    @Schema(description = "发布状态 UNPUBLISHED/SUCCESS/FAIL/RUNNING/UNKNOWN")
    private String publishStatus;

    @Schema(description = "发布状态（同 publishStatus，兼容旧字段）")
    private String status;

    private String ddlSql;

    private String message;

    private String description;

    private String remark;

    private String creator;

    private String creatorName;

    private String creatorPhone;

    private String updater;

    private String updaterName;

    private String updaterPhone;

    @Schema(description = "最近部署意图的发起人编号，与模型创建人独立")
    private String publisher;

    private String publisherName;

    private String publisherPhone;

    @Schema(description = "最近部署开始时间")
    private LocalDateTime publishStartTime;

    @Schema(description = "最近部署完成时间；执行中或结果未知时为空")
    private LocalDateTime publishTime;

    @Schema(description = "最近部署耗时，单位毫秒；尚未完成时为空")
    private Long publishDuration;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer tableCase;

    private String tableNameNotation;

    private String documentType;

    private Long documentId;

    private String contactNumber;

    @Schema(description = "仅详情提供；部署任务或历史产物的字段输入，无物化时使用已发布当前设计")
    private List<ModelFieldItem> fields;
}
