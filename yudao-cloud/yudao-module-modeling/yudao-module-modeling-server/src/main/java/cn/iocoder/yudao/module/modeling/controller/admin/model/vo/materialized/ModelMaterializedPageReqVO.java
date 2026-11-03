package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.materialized;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.List;

@Schema(description = "管理后台 - 模型物化发布分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ModelMaterializedPageReqVO extends PageParam {

    @Schema(description = "逻辑模型编号")
    private Long modelId;

    @Schema(description = "模型中文名")
    private String name;

    @Schema(description = "模型英文名/编码")
    private String code;

    @Schema(description = "物理表名")
    private String tableName;

    @Schema(description = "数仓分层")
    private Long layerId;

    @Size(max = 500)
    @Schema(description = "数仓分类下实际分层编号")
    private List<Long> layerIds;

    @Pattern(regexp = "SOURCE|DETAIL|DIMENSION|SUMMARY|APPLICATION", message = "表类型不合法")
    @Schema(description = "表类型")
    private String tableType;

    @Schema(description = "来源系统编号")
    private Long sourceSystemId;

    @Schema(description = "业务分类")
    private Long bizCategoryId;

    @Schema(description = "数据域")
    private Long dataDomainId;

    @Schema(description = "主题域")
    private Long subjectDomainId;

    @Schema(description = "数据源")
    private Long datasourceId;

    @Schema(description = "发布状态 UNPUBLISHED/SUCCESS/FAIL/RUNNING")
    private String publishStatus;

    @Schema(description = "发布状态（兼容旧参数，等同 publishStatus）", deprecated = true)
    private String status;

    @Schema(description = "发布模式")
    private String publishMode;

    @Pattern(regexp = "id|publishTime", message = "排序字段不合法")
    @Schema(description = "排序字段：id/publishTime")
    private String sortField;

    @Pattern(regexp = "asc|desc", message = "排序方向不合法")
    @Schema(description = "排序方向：asc/desc")
    private String sortOrder;
}
