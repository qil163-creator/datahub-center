package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic;

import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelHierarchyRespVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 逻辑模型 Response VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class LogicModelRespVO extends ModelHierarchyRespVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    private Long revision;

    private Long layoutRevision;

    private String grain;

    @Schema(description = "所属数据域归属编号", example = "1")
    private Long dataDomainId;

    @Schema(description = "模型名称", example = "aaa")
    private String name;

    @Schema(description = "模型编码", example = "aaa")
    private String code;

    @Schema(description = "创建方式：FORWARD/REVERSE", example = "FORWARD")
    private String createMode;


    @Schema(description = "状态：DRAFT/PUBLISHED/DISCARDED", example = "PUBLISHED")
    private String status;

    @Schema(description = "分类确认状态：UNCLASSIFIED-待分类 CONFIRMED-已确认", example = "CONFIRMED")
    private String classificationStatus;

    @Schema(description = "建模风格：RELATIONAL/DIMENSIONAL/WIDE/TIME_SERIES/SPATIAL/EVENT_STREAM",
            example = "RELATIONAL")
    private String modelStyle;

    @Schema(description = "数仓分层编码（派生自所属分层）：NONE/ODS/DWD/DIM/DWS/ADS", example = "DWD")
    private String warehouseLayer;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "完整表名大小写：1 大写，2 小写")
    private Integer tableCase;

    @Schema(description = "标准类型")
    private String documentType;

    @Schema(description = "标准登记编号")
    private Long documentId;

    @Schema(description = "责任人用户编号")
    private Long contactUserId;

    @Schema(description = "责任人联系电话")
    private String contactNumber;

    @Schema(description = "是否启用：0 禁用，1 启用")
    private Integer enabled;

    private String tableNameNotation;

    private Long sourceSystemId;

    private String sourceSystemName;

    private String sourceTableName;

    private String tableName;

    private Long datasourceId;

    private String dbType;

    private String dbName;

    private String schemaName;

    private Long entityId;

    private String entityName;

    private String objType;

    private String businessType;

    private String dataTimeliness;

    private String statisticsStr;

    private java.util.List<Long> grainEntityIds;

    private String ownerName;

    private String ownerPhone;

    private String creatorName;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建人")
    private String creator;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新人")
    private String updater;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

}
