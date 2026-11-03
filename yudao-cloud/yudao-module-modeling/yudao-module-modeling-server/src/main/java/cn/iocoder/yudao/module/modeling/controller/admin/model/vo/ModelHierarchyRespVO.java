package cn.iocoder.yudao.module.modeling.controller.admin.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 模型规范归属字段基类
 *
 * 统一设计/部署目标的 RespVO 均继承该类，由
 * {@link cn.iocoder.yudao.module.modeling.service.model.ModelHierarchyResolver#fillHierarchy}
 * 批量回填展示字段，避免三处重复查询。
 *
 * @author Li
 */
@Data
public abstract class ModelHierarchyRespVO {

    @Schema(description = "所属数仓分层编号", example = "6")
    private Long layerId;

    @Schema(description = "数仓分层编码", example = "DWD")
    private String layerCode;

    @Schema(description = "数仓分层名称", example = "明细数据层")
    private String layerName;

    @Schema(description = "所属主题域编号", example = "1")
    private Long subjectDomainId;

    @Schema(description = "所属主题域名称", example = "水资源管理")
    private String subjectDomainName;

    @Schema(description = "模型直接归属的数据域编号")
    private Long dataDomainId;

    @Schema(description = "模型数据域归属名称")
    private String dataDomainName;

    @Schema(description = "业务分类编号")
    private Long bizCategoryId;

    @Schema(description = "业务分类名称")
    private String bizCategoryName;

    @Schema(description = "业务分类编码")
    private String bizCategoryCode;


    @Schema(description = "归属链展示文本", example = "数据域：水资源 ｜ 技术分层：明细数据层(DWD) ｜ 分析主题：水资源管理")
    private String hierarchyPath;

    @Schema(description = "表类型：SOURCE/DETAIL/DIMENSION/SUMMARY/APPLICATION", example = "DETAIL")
    private String tableType;

}
