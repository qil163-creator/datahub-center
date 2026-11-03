package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LogicFlow 画布 JSON → 引擎 DAG 模型转换器
 *
 * 画布节点结构：{id, type, x, y, properties: {name, toolCode, toolType, nodeJson, failGoOn, overTimes}}
 * 画布连线结构：{sourceNodeId, targetNodeId, properties: {edgeType, outputPort}}
 *
 * @author Li
 */
public class EtlGraphConverter {

    /**
     * 限制ETL图工具的实例化，统一通过静态能力使用。
     */
    private EtlGraphConverter() {
    }

    /**
     * 转换为节点定义集合。
     *
     * @param lfNodes 换行符节点
     * @return 转换为节点定义集合结果集合
     */
    public static List<EtlNodeDef> toNodeDefs(List<Map<String, Object>> lfNodes) {
        List<EtlNodeDef> nodes = new ArrayList<>();
        if (lfNodes == null) {
            return nodes;
        }
        for (Map<String, Object> lfNode : lfNodes) {
            JSONObject json = new JSONObject(lfNode);
            JSONObject properties = json.getJSONObject("properties");
            if (properties == null) {
                properties = new JSONObject();
            }
            EtlNodeDef node = new EtlNodeDef();
            node.setNo(json.getStr("id"));
            node.setName(StrUtil.blankToDefault(properties.getStr("name"), json.getStr("id")));
            node.setToolCode(properties.getStr("toolCode"));
            node.setToolType(properties.getInt("toolType"));
            Object nodeJson = properties.get("nodeJson");
            node.setNodeJson(
                    nodeJson == null ? "{}" : nodeJson instanceof String text ? text : JSONUtil.toJsonStr(nodeJson));
            node.setFailGoOn(properties.getInt("failGoOn", 0));
            node.setOverTimeMinutes(properties.getInt("overTimes", 0));
            nodes.add(node);
        }
        return nodes;
    }

    /**
     * 转换为边定义集合。
     *
     * @param lfEdges 换行符边
     * @return 转换为边定义集合结果集合
     */
    public static List<EtlEdgeDef> toEdgeDefs(List<Map<String, Object>> lfEdges) {
        List<EtlEdgeDef> edges = new ArrayList<>();
        if (lfEdges == null) {
            return edges;
        }
        for (Map<String, Object> lfEdge : lfEdges) {
            JSONObject json = new JSONObject(lfEdge);
            JSONObject properties = json.getJSONObject("properties");
            EtlEdgeDef edge = new EtlEdgeDef();
            edge.setSourceNo(json.getStr("sourceNodeId"));
            edge.setTargetNo(json.getStr("targetNodeId"));
            if (properties != null) {
                edge.setEdgeType(properties.getStr("edgeType"));
                edge.setOutputPort(properties.getStr("outputPort"));
            }
            String port = json.getStr("sourcePort");
            if (StrUtil.isNotBlank(port)) {
                edge.setOutputPort("out".equals(port) ? "main" : port);
            }
            edge.setInputPort(json.getStr("targetPort"));
            edges.add(edge);
        }
        return edges;
    }

}
