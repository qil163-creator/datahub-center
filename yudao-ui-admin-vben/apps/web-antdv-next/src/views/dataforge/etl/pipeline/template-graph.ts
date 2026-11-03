import type { Graph } from '@antv/x6';

import type { EtlTemplateGraph } from '#/api/dataforge/etl/template';

import { getVisualSourcePort } from './graph-contract';
import { getNodePorts, NODE_HEIGHT, NODE_WIDTH } from './x6-config';

/** Selected subgraphs retain internal edges only; saving never changes the draft. */
export function captureEtlTemplate(
  graph: EtlTemplateGraph,
  selectedIds: string[],
): EtlTemplateGraph {
  const selected = new Set(selectedIds);
  const nodes =
    selected.size > 0
      ? graph.nodes.filter((node) => selected.has(node.id))
      : graph.nodes;
  const ids = new Set(nodes.map((node) => node.id));
  return JSON.parse(
    JSON.stringify({
      nodes,
      edges: graph.edges.filter(
        (edge) => ids.has(edge.sourceNodeId) && ids.has(edge.targetNodeId),
      ),
    }),
  );
}

/** One reversible append; the normal draft save still owns CAS and validation. */
export function appendEtlTemplate(
  graph: Graph,
  fragment: EtlTemplateGraph,
  newId: () => string = () => crypto.randomUUID(),
) {
  if (
    fragment.nodes.length === 0 ||
    graph.getNodes().length + fragment.nodes.length > 32 ||
    graph.getEdges().length + fragment.edges.length > 128
  )
    throw new Error('应用后最多允许 32 个节点、128 条连线。');
  const ids = new Map<string, string>();
  for (const node of fragment.nodes) {
    if (!node.id || ids.has(node.id)) throw new Error('模板节点编号无效。');
    ids.set(node.id, newId());
  }
  for (const edge of fragment.edges)
    if (!ids.has(edge.sourceNodeId) || !ids.has(edge.targetNodeId))
      throw new Error('模板连线引用无效。');
  const left = Math.min(...fragment.nodes.map((node) => Number(node.x) || 0));
  const top = Math.min(...fragment.nodes.map((node) => Number(node.y) || 0));
  const offset =
    graph.getNodes().length > 0
      ? Math.max(...graph.getNodes().map((node) => node.getBBox().right)) + 100
      : 40;
  const cells = fragment.nodes.map((node) =>
    graph.createNode({
      id: ids.get(node.id),
      shape: 'etl-task',
      x: (Number(node.x) || 0) - left + offset,
      y: (Number(node.y) || 0) - top + 40,
      width: NODE_WIDTH,
      height: NODE_HEIGHT,
      data: JSON.parse(JSON.stringify(node.properties)),
      ports: getNodePorts(node.properties.toolCode, node.properties.nodeJson),
    }),
  );
  const nodes = new Map(fragment.nodes.map((node) => [node.id, node]));
  const edges = fragment.edges.map((edge) =>
    graph.createEdge({
      id: newId(),
      shape: 'etl-edge',
      source: {
        cell: ids.get(edge.sourceNodeId)!,
        port: getVisualSourcePort(
          edge,
          nodes.get(edge.sourceNodeId)?.properties.toolCode,
        ),
      },
      target: {
        cell: ids.get(edge.targetNodeId)!,
        port: edge.targetPort ?? 'in',
      },
      data: JSON.parse(JSON.stringify(edge.properties ?? {})),
    }),
  );
  graph.batchUpdate('etl-apply-template', () =>
    graph.addCell([...cells, ...edges]),
  );
}
