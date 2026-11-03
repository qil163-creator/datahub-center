import type { Graph } from '@antv/x6';

import { Model } from '@antv/x6';
import { describe, expect, it, vi } from 'vitest';

import { applyEtlHistory, createEtlHistory } from './history';
import { autoLayoutEtlGraph } from './layout';

function createGraph() {
  const model = new Model();
  const graph = {
    model,
    trigger: vi.fn(),
    getNodes: () => model.getNodes(),
    getEdges: () => model.getEdges(),
    batchUpdate: (_name: string, update: () => void) =>
      model.batchUpdate('update', update),
  } as unknown as Graph;
  const history = createEtlHistory(() => true);
  history.init(graph);
  return { graph, model, history };
}

describe('etl 自动布局', () => {
  it('分支、合流和独立节点均不重叠，节点与端口语义保持不变，一次撤销恢复全部坐标', () => {
    const { graph, model, history } = createGraph();
    for (const id of ['source', 'pass', 'reject', 'sink', 'isolated'])
      model.addNode({
        id,
        shape: 'rect',
        x: 10,
        y: 20,
        width: 160,
        height: 60,
        data: { toolCode: id, runStatus: 3 },
      });
    for (const [source, target, port] of [
      ['source', 'pass', 'pass'],
      ['source', 'reject', 'reject'],
      ['pass', 'sink', 'out'],
      ['reject', 'sink', 'out'],
    ] as const)
      model.addEdge({
        shape: 'edge',
        source: { cell: source, port },
        target: { cell: target, port: 'in' },
      });
    const edgeSnapshot = graph.getEdges().map((edge) => edge.toJSON());
    history.clean();
    expect(autoLayoutEtlGraph(graph)).toBe(true);
    expect(history.getUndoSize()).toBe(1);
    const boxes = graph.getNodes().map((node) => node.getBBox());
    for (const [index, box] of boxes.entries())
      for (const other of boxes.slice(index + 1))
        expect(box.isIntersectWithRect(other)).toBe(false);
    for (const edge of graph.getEdges())
      expect(edge.getSourceNode()!.position().x).toBeLessThan(
        edge.getTargetNode()!.position().x,
      );
    expect(graph.getEdges().map((edge) => edge.toJSON())).toEqual(edgeSnapshot);
    expect(autoLayoutEtlGraph(graph)).toBe(false);
    expect(history.getUndoSize()).toBe(1);
    applyEtlHistory(graph, history, 'undo');
    for (const node of graph.getNodes()) {
      expect(node.position()).toEqual({ x: 10, y: 20 });
      expect(node.getData().runStatus).toBe(3);
    }
    applyEtlHistory(graph, history, 'redo');
    expect(graph.getNodes().some((node) => node.position().x !== 10)).toBe(
      true,
    );
    history.dispose();
  });

  it('空画布与尚未连接到节点的连线不会产生虚拟节点', () => {
    const { graph, model, history } = createGraph();
    expect(autoLayoutEtlGraph(graph)).toBe(false);
    model.addNode({ id: 'source', shape: 'rect', width: 160, height: 60 });
    model.addEdge({
      shape: 'edge',
      source: 'source',
      target: { x: 300, y: 200 },
    });
    expect(autoLayoutEtlGraph(graph)).toBe(true);
    expect(graph.getNodes()).toHaveLength(1);
    history.dispose();
  });
});
