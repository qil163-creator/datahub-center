import type { Graph } from '@antv/x6';

import { History } from '@antv/x6-plugin-history';

/** 与保存契约一致，运行着色、运行时读写行数和选择状态不属于业务配置。 */
function businessData(value: Record<string, unknown> = {}) {
  const {
    selected: _selected,
    runStatus: _runStatus,
    readCount: _readCount,
    writeCount: _writeCount,
    ...data
  } = value;
  return data;
}

function businessPorts(value?: { items?: { group?: string; id?: string }[] }) {
  return (
    value?.items?.map((port) => ({ id: port.id, group: port.group })) ?? []
  );
}

export function createEtlHistory(canRecord: () => boolean) {
  const tracked = new Set(['data', 'ports', 'position', 'source', 'target']);
  return new History({
    enabled: true,
    stackSize: 100,
    beforeAddCommand(event, args) {
      const metadata = args as {
        key?: string;
        options?: { history?: boolean };
      };
      if (!canRecord() || metadata.options?.history === false) return false;
      if (
        event !== 'cell:added' &&
        event !== 'cell:removed' &&
        !tracked.has(metadata.key ?? '')
      )
        return false;
      if (metadata.key === 'data') {
        const change = args as {
          current?: Record<string, unknown>;
          previous?: Record<string, unknown>;
        };
        return (
          JSON.stringify(businessData(change.previous)) !==
          JSON.stringify(businessData(change.current))
        );
      }
      if (metadata.key === 'ports') {
        const change = args as {
          current?: Parameters<typeof businessPorts>[0];
          previous?: Parameters<typeof businessPorts>[0];
        };
        // 端口悬停时 X6 更新 attrs；只记录端口身份与归属的改变。
        return (
          JSON.stringify(businessPorts(change.previous)) !==
          JSON.stringify(businessPorts(change.current))
        );
      }
      return true;
    },
  });
}

/** 还原业务配置后保留当前运行/选中态，防止历史命令恢复旧的着色信息。 */
export function applyEtlHistory(
  graph: Graph,
  history: History,
  action: 'redo' | 'undo',
) {
  const transient = new Map(
    graph.getNodes().map((node) => {
      const data = node.getData() ?? {};
      return [
        node.id,
        {
          selected: data.selected,
          runStatus: data.runStatus,
          readCount: data.readCount,
          writeCount: data.writeCount,
        },
      ];
    }),
  );
  history[action]();
  for (const node of graph.getNodes()) {
    node.setData(
      { ...businessData(node.getData()), ...transient.get(node.id) },
      { overwrite: true, history: false },
    );
  }
}
