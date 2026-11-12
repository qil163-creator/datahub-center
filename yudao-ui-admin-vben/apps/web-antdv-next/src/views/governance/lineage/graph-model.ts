import type { GovernanceLineageApi } from '#/api/governance/lineage';

import dagre from 'dagre';

/** 画布上的一个节点：表级图为表/外部数据集，字段级图为"表.字段"。 */
export interface LineageCanvasNode {
  /** 画布内唯一键：表级为节点编号，字段级为"节点编号:字段"。 */
  key: string;
  nodeId: string;
  column?: string;
  title: string;
  subtitle: string;
  kind: 'external' | 'table';
  root: boolean;
  depth: number;
  x: number;
  y: number;
  width: number;
  height: number;
}

/** 画布上的一条连线。 */
export interface LineageCanvasEdge {
  key: string;
  source: string;
  target: string;
  /** 字段归属不确定或 INDIRECT 依赖时虚线展示。 */
  dashed: boolean;
  label?: string;
}

export interface LineageCanvas {
  nodes: LineageCanvasNode[];
  edges: LineageCanvasEdge[];
}

export const NODE_WIDTH = 220;
export const NODE_HEIGHT = 56;

export const SOURCE_TYPE_LABELS: Record<GovernanceLineageApi.SourceType, string> = {
  DATA_ACCESS: '数据同步',
  DEV_TASK: '开发任务',
  ETL_PIPELINE: '可视化 ETL',
  INTEGRATION_TASK: '集成任务',
};

export const STATUS_LABELS: Record<
  GovernanceLineageApi.ReportStatus,
  { color: string; label: string }
> = {
  FAILED: { color: 'error', label: '解析失败' },
  PARTIAL: { color: 'warning', label: '部分解析' },
  REMOVED: { color: 'default', label: '来源已删除' },
  SUCCESS: { color: 'success', label: '解析成功' },
  UNSUPPORTED: { color: 'default', label: '暂不支持' },
};

export const TRANSFORM_LABELS: Record<GovernanceLineageApi.TransformSubtype, string> = {
  AGGREGATION: '聚合',
  CONDITIONAL: '条件判断',
  FILTER: '过滤条件',
  GROUP_BY: '分组',
  IDENTITY: '直接取值',
  JOIN: '关联条件',
  SORT: '排序',
  TRANSFORMATION: '表达式',
  WINDOW: '窗口',
};

export const ORIGIN_LABELS: Record<string, string> = {
  MANUAL: '手工维护',
  PARSED: '自动解析',
  RUNTIME: '运行记录',
};

/** 选项列表：用于筛选表单。 */
export function labelOptions<T extends string>(labels: Record<T, string | { label: string }>) {
  return (Object.keys(labels) as T[]).map((value) => {
    const entry = labels[value];
    return { label: typeof entry === 'string' ? entry : entry.label, value };
  });
}

/** 节点主标题：业务名称优先，其次表名，外部数据集取展示名。 */
export function nodeTitle(node?: GovernanceLineageApi.Node): string {
  if (!node) return '未知节点';
  if (node.nodeType === 'EXTERNAL')
    return node.displayName || node.externalKey || '外部数据集';
  return node.businessName || node.tableName || node.displayName || '未命名表';
}

/** 节点副标题：schema.table 或外部数据集类型。 */
export function nodeSubtitle(node?: GovernanceLineageApi.Node): string {
  if (!node) return '';
  if (node.nodeType === 'EXTERNAL')
    return `${node.externalKind ?? 'OTHER'} · ${node.externalKey ?? ''}`;
  const name = node.schemaName ? `${node.schemaName}.${node.tableName}` : (node.tableName ?? '');
  return node.businessName ? name : `数据源 ${String(node.datasourceId ?? '-')}`;
}

/** 下拉选项展示文本。 */
export function nodeOptionLabel(node: GovernanceLineageApi.Node): string {
  if (node.nodeType === 'EXTERNAL') return nodeTitle(node);
  const name = node.schemaName ? `${node.schemaName}.${node.tableName}` : (node.tableName ?? '');
  return node.businessName ? `${name}（${node.businessName}）` : name;
}

/** 合并两次查询结果：节点按编号、连线按上下游去重，已有节点保留原层级。 */
export function mergeGraph(
  base: GovernanceLineageApi.Graph,
  extra: GovernanceLineageApi.Graph,
): GovernanceLineageApi.Graph {
  const nodes = new Map(base.nodes.map((node) => [String(node.id), node]));
  for (const node of extra.nodes) {
    const key = String(node.id);
    const existing = nodes.get(key);
    nodes.set(
      key,
      existing
        ? {
            ...existing,
            downstreamExpandable: node.downstreamExpandable && existing.downstreamExpandable,
            upstreamExpandable: node.upstreamExpandable && existing.upstreamExpandable,
          }
        : node,
    );
  }
  const edges = new Map(
    base.edges.map((edge) => [`${String(edge.upstreamNodeId)}>${String(edge.downstreamNodeId)}`, edge]),
  );
  for (const edge of extra.edges) {
    const key = `${String(edge.upstreamNodeId)}>${String(edge.downstreamNodeId)}`;
    if (!edges.has(key)) edges.set(key, edge);
  }
  return {
    rootNodeId: base.rootNodeId,
    nodes: [...nodes.values()],
    edges: [...edges.values()],
    truncated: Boolean(base.truncated || extra.truncated),
  };
}

/** 表级图布局。 */
export function layoutTableGraph(graph: GovernanceLineageApi.Graph): LineageCanvas {
  const nodes = graph.nodes.map((node) => ({
    key: String(node.id),
    nodeId: String(node.id),
    title: nodeTitle(node),
    subtitle: nodeSubtitle(node),
    kind: node.nodeType === 'EXTERNAL' ? ('external' as const) : ('table' as const),
    root: String(node.id) === String(graph.rootNodeId),
    depth: node.depth,
  }));
  const edges = graph.edges.map((edge) => ({
    key: `${String(edge.upstreamNodeId)}>${String(edge.downstreamNodeId)}`,
    source: String(edge.upstreamNodeId),
    target: String(edge.downstreamNodeId),
    dashed: edge.origins.length > 0 && edge.origins.every((origin) => origin === 'MANUAL'),
    label: edge.sources.length > 1 ? `${edge.sources.length} 个任务` : undefined,
  }));
  return layout(nodes, edges);
}

/** 字段级图布局。 */
export function layoutColumnGraph(graph: GovernanceLineageApi.ColumnGraph): LineageCanvas {
  const key = (nodeId: GovernanceLineageApi.Id, column: string) => `${String(nodeId)}:${column}`;
  const nodes = graph.nodes.map((node) => ({
    key: key(node.nodeId, node.column),
    nodeId: String(node.nodeId),
    column: node.column,
    title: node.column === '*' ? '整表' : node.column,
    subtitle: node.node ? nodeOptionLabel(node.node) : `节点 ${String(node.nodeId)}`,
    kind: node.node?.nodeType === 'EXTERNAL' ? ('external' as const) : ('table' as const),
    root:
      String(node.nodeId) === String(graph.rootNodeId) && node.column === graph.rootColumn,
    depth: node.depth,
  }));
  const edges = graph.edges.map((edge) => ({
    key: `${key(edge.upstreamNodeId, edge.upstreamColumn)}>${key(edge.downstreamNodeId, edge.downstreamColumn)}`,
    source: key(edge.upstreamNodeId, edge.upstreamColumn),
    target: key(edge.downstreamNodeId, edge.downstreamColumn),
    dashed: Boolean(edge.ambiguous) || edge.transformType === 'INDIRECT',
    label: TRANSFORM_LABELS[edge.transformSubtype] ?? edge.transformSubtype,
  }));
  return layout(nodes, edges);
}

/** dagre 从左到右布局；只引用存在的节点，避免截断结果中的悬空连线。 */
function layout(
  nodes: Omit<LineageCanvasNode, 'height' | 'width' | 'x' | 'y'>[],
  edges: LineageCanvasEdge[],
): LineageCanvas {
  const graph = new dagre.graphlib.Graph();
  graph.setGraph({ rankdir: 'LR', nodesep: 28, ranksep: 90, marginx: 24, marginy: 24 });
  graph.setDefaultEdgeLabel(() => ({}));
  const sorted = nodes.toSorted((left, right) => left.key.localeCompare(right.key));
  for (const node of sorted)
    graph.setNode(node.key, { width: NODE_WIDTH, height: NODE_HEIGHT });
  const keys = new Set(sorted.map((node) => node.key));
  const visible = edges.filter(
    (edge) => keys.has(edge.source) && keys.has(edge.target) && edge.source !== edge.target,
  );
  for (const edge of visible.toSorted((left, right) => left.key.localeCompare(right.key)))
    graph.setEdge(edge.source, edge.target);
  dagre.layout(graph);
  return {
    nodes: sorted.map((node) => {
      const point = graph.node(node.key);
      return {
        ...node,
        x: Math.round(point.x - NODE_WIDTH / 2),
        y: Math.round(point.y - NODE_HEIGHT / 2),
        width: NODE_WIDTH,
        height: NODE_HEIGHT,
      };
    }),
    edges: visible,
  };
}
