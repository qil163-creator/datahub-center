package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic;

import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 逻辑模型 ER 图 Response VO（按数仓分层筛选）")
@Data
public class LogicErDiagramRespVO {

    @Schema(description = "数仓分层编号", example = "1")
    private Long layerId;

    @Schema(description = "数仓分层名称")
    private String layerName;

    @Schema(description = "模型节点列表")
    private List<Node> models;

    @Schema(description = "关系列表（两端模型均在主题范围内）")
    private List<LogicRelationRespVO> relations;

    @Schema(description = "逻辑模型 ER 节点")
    @Data
    public static class Node {

        @Schema(description = "模型编号", example = "1")
        private Long id;

        private Long revision;

        private Long layoutRevision;

        @Schema(description = "模型名称", example = "企业信息")
        private String name;

        @Schema(description = "模型编码", example = "enterprise_info")
        private String code;

        @Schema(description = "状态：DRAFT/PUBLISHED/DISCARDED", example = "PUBLISHED")
        private String status;

        @Schema(description = "创建方式：FORWARD/REVERSE", example = "FORWARD")
        private String createMode;

        @Schema(description = "表类型：SOURCE/DETAIL/DIMENSION/SUMMARY/APPLICATION", example = "DETAIL")
        private String tableType;

        @Schema(description = "画布横坐标（空表示未布局）", example = "120")
        private Integer posX;

        @Schema(description = "画布纵坐标（空表示未布局）", example = "80")
        private Integer posY;

        @Schema(description = "当前字段列表")
        private List<ModelFieldItem> fields;

    }

}
