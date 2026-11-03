import type { Graph } from '@antv/x6';

import { Model, Node } from '@antv/x6';
import { describe, expect, it, vi } from 'vitest';

import { applyEtlHistory, createEtlHistory } from './history';

function harness() {
  const model = new Model();
  const graph = {
    model,
    trigger: vi.fn(),
    getNodes: () => model.getNodes(),
  } as unknown as Graph;
  let editable = true;
  const history = createEtlHistory(() => editable);
  history.init(graph);
  const node = model.addNode(
    new Node({
      id: 'source',
      shape: 'rect',
      data: { nodeJson: { count: 1 }, name: '记录源' },
      ports: {
        items: [
          { id: 'in', group: 'in' },
          { id: 'out', group: 'out' },
        ],
      },
    }),
  );
  history.clean();
  return {
    graph,
    model,
    node,
    history,
    setEditable: (value: boolean) => {
      editable = value;
    },
  };
}

describe('etl 真实 X6 撤销记录', () => {
  it('撤销/重做配置而保留最新运行和选择状态，临时状态不增加历史', () => {
    const { graph, node, history } = harness();
    node.setData({ nodeJson: { count: 2 } });
    node.setData({ runStatus: 3 });
    node.setData({ readCount: 120, writeCount: '9007199254740993' });
    node.setData({ selected: true });
    expect(history.getUndoSize()).toBe(1);
    applyEtlHistory(graph, history, 'undo');
    expect(node.getData()).toMatchObject({
      nodeJson: { count: 1 },
      runStatus: 3,
      readCount: 120,
      writeCount: '9007199254740993',
      selected: true,
    });
    expect(history.canUndo()).toBe(false);
    applyEtlHistory(graph, history, 'redo');
    expect(node.getData()).toMatchObject({
      nodeJson: { count: 2 },
      runStatus: 3,
      readCount: 120,
      writeCount: '9007199254740993',
      selected: true,
    });
    history.dispose();
  });

  it('记录业务端口变更而忽略悬停样式，将批次编辑归为一次撤销', () => {
    const { graph, model, node, history } = harness();
    node.setPortProp('out', 'attrs/circle/fill', '#000');
    expect(history.canUndo()).toBe(false);
    model.startBatch('update');
    node.setData({ name: '改名' });
    node.addPort({ id: 'reject', group: 'out' });
    model.stopBatch('update');
    expect(history.getUndoSize()).toBe(1);
    applyEtlHistory(graph, history, 'undo');
    expect(node.getData().name).toBe('记录源');
    expect(node.hasPort('reject')).toBe(false);
    history.dispose();
  });

  it('初始化及只读/运行锁期间不记录，清理后不能跨编辑窗口撤销', () => {
    const { node, history, setEditable } = harness();
    setEditable(false);
    node.position(40, 60);
    node.setData({ nodeJson: { count: 4 } });
    expect(history.canUndo()).toBe(false);
    setEditable(true);
    node.position(100, 120);
    expect(history.canUndo()).toBe(true);
    history.clean();
    expect(history.canUndo()).toBe(false);
    expect(history.canRedo()).toBe(false);
    history.dispose();
  });
});
