import type { BusinessEntityApi } from '#/api/modeling/business-entity';

import dagre from 'dagre';

export const GRAPH_NODE_WIDTH = 200;
export const GRAPH_NODE_HEIGHT = 56;

export interface EntityGraphNode {
  key: string;
  kind: BusinessEntityApi.GraphNode['kind'];
  refId: BusinessEntityApi.Id;
  title: string;
  subtitle: string;
  center: boolean;
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface EntityGraphEdge {
  key: string;
  source: string;
  target: string;
  label: string;
  relationType: string;
}

const SUBTYPE_LABELS: Record<string, string> = {
  NORMAL: '普通对象',
  HIERARCHICAL: '层级对象',
  EVENT: '业务事件',
  PROCESS: '业务流程',
  SOURCE: '贴源表',
  DIMENSION: '维度表',
  DETAIL: '事实表',
  SUMMARY: '汇总表',
  APPLICATION: '应用表',
};

/** 节点副标题：实体显示编码与子类型，逻辑表显示表名与表类型。 */
function subtitle(node: BusinessEntityApi.GraphNode) {
  const type = SUBTYPE_LABELS[node.subtype || ''] || node.subtype || '';
  return [node.code, type].filter(Boolean).join(' · ');
}

/** dagre 从左到右布局，坐标为节点左上角。 */
export function layoutEntityGraph(graph: BusinessEntityApi.Graph) {
  const layout = new dagre.graphlib.Graph();
  layout.setGraph({ rankdir: 'LR', nodesep: 28, ranksep: 90, marginx: 20, marginy: 20 });
  layout.setDefaultEdgeLabel(() => ({}));
  for (const node of graph.nodes) {
    layout.setNode(node.key, { width: GRAPH_NODE_WIDTH, height: GRAPH_NODE_HEIGHT });
  }
  const keys = new Set(graph.nodes.map((node) => node.key));
  const edges = graph.edges.filter(
    (edge) => keys.has(edge.source) && keys.has(edge.target),
  );
  for (const edge of edges) layout.setEdge(edge.source, edge.target);
  dagre.layout(layout);
  const nodes: EntityGraphNode[] = graph.nodes.map((node) => {
    const position = layout.node(node.key);
    return {
      key: node.key,
      kind: node.kind,
      refId: node.refId,
      title: node.name || String(node.refId),
      subtitle: subtitle(node),
      center: node.key === graph.centerKey,
      x: (position?.x ?? 0) - GRAPH_NODE_WIDTH / 2,
      y: (position?.y ?? 0) - GRAPH_NODE_HEIGHT / 2,
      width: GRAPH_NODE_WIDTH,
      height: GRAPH_NODE_HEIGHT,
    };
  });
  return {
    nodes,
    edges: edges.map<EntityGraphEdge>((edge, index) => ({
      key: `${edge.source}->${edge.target}#${index}`,
      source: edge.source,
      target: edge.target,
      label: edge.label,
      relationType: edge.relationType,
    })),
  };
}
