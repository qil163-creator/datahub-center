export interface LineageNode {
  id: string;
  label: string;
  subLabel: string;
  task: boolean;
  current: boolean;
  x: number;
  y: number;
}
export interface LineageEdge {
  id: string;
  source: string;
  target: string;
}
function objects(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value)
    ? value.filter(
        (item): item is Record<string, unknown> =>
          !!item && typeof item === 'object',
      )
    : [];
}

/** 使用脱敏后的不透明节点身份还原拓扑，不解析来源连接字符串。 */
export function lineageGraph(value?: Record<string, unknown>) {
  const nodes: LineageNode[] = [
    ...objects(value?.tables).map((item) => ({ item, task: false })),
    ...objects(value?.tasks).map((item) => ({ item, task: true })),
  ]
    .map(({ item, task }) => ({
      id: String(item.graphId ?? item.id ?? ''),
      label: String(
        item.label ?? item.name ?? item.tableName ?? item.taskCode ?? '-',
      ),
      subLabel: String(item.subLabel ?? item.datasourceType ?? item.type ?? ''),
      current: item.current === true,
      task,
      x: 0,
      y: 0,
    }))
    .filter((node) => node.id);
  const nodeIds = new Set(nodes.map((node) => node.id));
  const edges: LineageEdge[] = objects(value?.rels)
    .map((item, index) => ({
      id: String(item.graphId ?? item.id ?? index),
      source: String(item.source ?? item.startNodeId ?? ''),
      target: String(item.target ?? item.endNodeId ?? ''),
    }))
    .filter((edge) => nodeIds.has(edge.source) && nodeIds.has(edge.target));
  const depths = new Map(nodes.map((node) => [node.id, 0]));
  const incoming = new Map(nodes.map((node) => [node.id, 0]));
  edges.forEach((edge) =>
    incoming.set(edge.target, (incoming.get(edge.target) ?? 0) + 1),
  );
  const queue = nodes
    .filter((node) => incoming.get(node.id) === 0)
    .map((node) => node.id);
  const visited = new Set<string>();
  while (queue.length > 0) {
    const id = queue.shift()!;
    if (visited.has(id)) continue;
    visited.add(id);
    edges
      .filter((edge) => edge.source === id)
      .forEach((edge) => {
        depths.set(
          edge.target,
          Math.max(depths.get(edge.target) ?? 0, (depths.get(id) ?? 0) + 1),
        );
        incoming.set(edge.target, (incoming.get(edge.target) ?? 1) - 1);
        if (incoming.get(edge.target) === 0) queue.push(edge.target);
      });
  }
  // 环和孤立节点保留，避免来源图不完整时丢弃真实数据。
  const groups = new Map<number, LineageNode[]>();
  nodes.forEach((node) => {
    const depth = Math.min(depths.get(node.id) ?? 0, nodes.length);
    const group = groups.get(depth) ?? [];
    group.push(node);
    groups.set(depth, group);
  });
  const maxRows = Math.max(
    1,
    ...[...groups.values()].map((group) => group.length),
  );
  groups.forEach((group, depth) =>
    group.forEach((node, index) => {
      node.x = 40 + depth * 300;
      node.y = 35 + index * 96 + (maxRows - group.length) * 48;
    }),
  );
  return {
    nodes,
    edges,
    width: Math.max(700, ...nodes.map((node) => node.x + 240)),
    height: Math.max(420, maxRows * 96 + 50),
    global:
      (value?.scope as Record<string, unknown> | undefined)
        ?.globalLineageSeed === true,
    hasMore: value?.hasMore === true,
  };
}
