import type { Graph } from '@antv/x6';

import { Model } from '@antv/x6';
import { describe, expect, it, vi } from 'vitest';

import { createEtlHistory } from './history';
import { appendEtlTemplate, captureEtlTemplate } from './template-graph';

vi.mock('./x6-config', () => ({
  getNodePorts: () => ({}),
  NODE_HEIGHT: 68,
  NODE_WIDTH: 220,
}));
const fragment = {
  nodes: [
    {
      id: 'a',
      x: 5,
      y: 6,
      properties: {
        toolCode: 'quality-check',
        nodeJson: { disposition: 'ROUTE' },
      },
    },
    {
      id: 'b',
      x: 200,
      y: 6,
      properties: { toolCode: 'add-constant', nodeJson: { fields: [] } },
    },
  ],
  edges: [
    {
      id: 'edge',
      sourceNodeId: 'a',
      targetNodeId: 'b',
      sourcePort: 'reject',
      targetPort: 'input-2',
      properties: { outputPort: 'reject' },
    },
  ],
};
describe('团队模板应用', () => {
  it('选中片段只保留内部连线，配置独立于草稿', () => {
    const captured = captureEtlTemplate(fragment, ['b']);
    expect(captured.nodes).toHaveLength(1);
    expect(captured.edges).toHaveLength(0);
    captured.nodes[0]!.properties.nodeJson.fields.push({ name: 'new' });
    expect(fragment.nodes[1]!.properties.nodeJson.fields).toEqual([]);
  });
  it('重编号并保留端口，追加可一次撤销且原画布不受影响', () => {
    const model = new Model();
    const graph = {
      model,
      trigger: vi.fn(),
      getNodes: () => model.getNodes(),
      getEdges: () => model.getEdges(),
      createNode: (options: any) =>
        model.createNode({ ...options, shape: 'rect' }),
      createEdge: (options: any) =>
        model.createEdge({ ...options, shape: 'edge' }),
      addCell: (cells: any[]) => model.addCells(cells),
      batchUpdate: (_name: string, callback: () => void) =>
        model.batchUpdate('update', callback),
    } as unknown as Graph;
    model.addNode({ id: 'a', x: 10, y: 20, width: 220, height: 68 });
    const history = createEtlHistory(() => true);
    history.init(graph);
    history.clean();
    let sequence = 0;
    appendEtlTemplate(graph, fragment, () => `new-${++sequence}`);
    expect(model.getNodes()).toHaveLength(3);
    expect(history.getUndoSize()).toBe(1);
    expect(model.getEdges()[0]!.getSourcePortId()).toBe('reject');
    expect(model.getEdges()[0]!.getTargetPortId()).toBe('input-2');
    expect(
      model.getCell('new-1')!.isNode() && model.getCell('new-1')!.getBBox().x,
    ).toBeGreaterThan(230);
    history.undo();
    expect(model.getNodes().map((node) => node.id)).toEqual(['a']);
    expect(model.getEdges()).toHaveLength(0);
    history.redo();
    expect(model.getNodes()).toHaveLength(3);
    history.dispose();
  });
  it('悬空引用在任何画布变更前被拒绝', () => {
    const graph = {
      getNodes: () => [],
      getEdges: () => [],
      batchUpdate: vi.fn(),
    } as unknown as Graph;
    expect(() =>
      appendEtlTemplate(graph, {
        ...fragment,
        edges: [{ sourceNodeId: 'a', targetNodeId: 'missing' }],
      }),
    ).toThrow('引用无效');
    expect(graph.batchUpdate).not.toHaveBeenCalled();
  });
});
