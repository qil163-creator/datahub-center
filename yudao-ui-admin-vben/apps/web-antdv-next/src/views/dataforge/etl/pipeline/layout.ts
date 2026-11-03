import type { Graph } from '@antv/x6';

import dagre from 'dagre';

/** 布局只改变坐标，节点配置、业务端口及连线顺序保持原样。 */
export function autoLayoutEtlGraph(graph: Graph) {
  const nodes = graph
    .getNodes()
    .toSorted((left, right) => left.id.localeCompare(right.id));
  if (nodes.length === 0) return false;
  const layout = new dagre.graphlib.Graph();
  layout.setGraph({
    rankdir: 'LR',
    nodesep: 56,
    ranksep: 100,
    marginx: 40,
    marginy: 40,
  });
  layout.setDefaultEdgeLabel(() => ({}));
  const ids = new Set(nodes.map((node) => node.id));
  for (const node of nodes) {
    const size = node.getSize();
    layout.setNode(node.id, {
      width: Math.max(size.width, 1),
      height: Math.max(size.height, 1),
    });
  }
  for (const edge of graph
    .getEdges()
    .toSorted((left, right) => left.id.localeCompare(right.id))) {
    const source = edge.getSourceCellId();
    const target = edge.getTargetCellId();
    if (source !== target && ids.has(source) && ids.has(target))
      layout.setEdge(source, target);
  }
  dagre.layout(layout);
  const moves = nodes.flatMap((node) => {
    const point = layout.node(node.id);
    const x = Math.round(point.x - point.width / 2);
    const y = Math.round(point.y - point.height / 2);
    if (!Number.isFinite(x) || !Number.isFinite(y))
      throw new Error('Invalid layout position');
    const current = node.position();
    return current.x === x && current.y === y ? [] : [{ node, x, y }];
  });
  if (moves.length === 0) return false;
  graph.batchUpdate('etl-auto-layout', () => {
    for (const { node, x, y } of moves) node.position(x, y);
  });
  return true;
}
