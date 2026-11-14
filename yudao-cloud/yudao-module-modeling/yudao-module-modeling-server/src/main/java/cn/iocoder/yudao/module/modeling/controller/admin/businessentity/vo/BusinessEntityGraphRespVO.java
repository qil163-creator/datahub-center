package cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "管理后台 - 业务实体关系图 Response VO")
@Data
public class BusinessEntityGraphRespVO {

    @Schema(description = "中心节点键", example = "ENTITY:1")
    private String centerKey;

    @Schema(description = "节点")
    private List<Node> nodes = new ArrayList<>();

    @Schema(description = "边，方向为关系发出方 → 关系对端")
    private List<Edge> edges = new ArrayList<>();

    @Schema(description = "关联逻辑表是否因数量上限被截断", example = "false")
    private Boolean modelsTruncated = false;

    @Schema(description = "关系图节点")
    @Data
    public static class Node {

        @Schema(description = "节点键：ENTITY:编号 或 MODEL:编号", example = "ENTITY:1")
        private String key;

        @Schema(description = "节点类型：ENTITY、MODEL", example = "ENTITY")
        private String kind;

        @Schema(description = "实体或逻辑表编号", example = "1")
        private Long refId;

        @Schema(description = "名称", example = "客户")
        private String name;

        @Schema(description = "编码或表名", example = "customer")
        private String code;

        @Schema(description = "实体类型：OBJECT、ACTIVITY；逻辑表节点为空", example = "OBJECT")
        private String entityType;

        @Schema(description = "实体子类型；逻辑表节点为表类型", example = "NORMAL")
        private String subtype;

        @Schema(description = "实体 0 上线 1 下线；逻辑表为设计状态", example = "0")
        private String status;
    }

    @Schema(description = "关系图边")
    @Data
    public static class Edge {

        @Schema(description = "起点节点键", example = "ENTITY:1")
        private String source;

        @Schema(description = "终点节点键", example = "ENTITY:2")
        private String target;

        @Schema(description = "关系类型：INHERITANCE、ASSOCIATION、PREDECESSOR、PROCESS_EVENT、LOGIC_MODEL",
                example = "ASSOCIATION")
        private String relationType;

        @Schema(description = "关系名称", example = "关联")
        private String label;
    }
}
