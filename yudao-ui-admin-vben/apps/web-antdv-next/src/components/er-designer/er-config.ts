import type { ErTableNode } from './types';

import { Graph, Shape } from '@antv/x6';

/** 连接桩元数据（结构兼容 X6 Node.Metadata.ports） */
interface ErPortsMetadata {
  groups: Record<string, any>;
  items: { args?: { x: number; y: number }; group: string; id: string }[];
}

export const ER_NODE_WIDTH = 240;
export const ER_HEADER_HEIGHT = 40;
export const ER_ROW_HEIGHT = 26;
export const ER_MAX_ROWS = 15;
export const ER_MORE_HEIGHT = 22;

const PORT_COLOR = '#94a3b8';
const PORT_ACTIVE = '#2563eb';
const EDGE_COLOR = '#64748b';
const EDGE_HOVER = '#2563eb';

const PK_ICON = `<svg viewBox="0 0 16 16" width="12" height="12" style="flex:none"><path d="M10.5 1.5a4 4 0 0 0-3.9 4.9L1.5 11.5V14.5h3l.75-.75v-1.5h1.5l.75-.75v-1.5h1.1a4 4 0 1 0 1.9-8.5Zm1.2 4.05a1.25 1.25 0 1 1 0-2.5 1.25 1.25 0 0 1 0 2.5Z" fill="#f59e0b"/></svg>`;

/** 计算节点可见行数（超出部分折叠为 "+N"） */
export function visibleRowCount(total: number) {
  return Math.min(total, ER_MAX_ROWS);
}

/** 计算节点高度 */
export function calcNodeHeight(rowTotal: number) {
  const rows = visibleRowCount(rowTotal);
  const more = rowTotal > ER_MAX_ROWS ? ER_MORE_HEIGHT : 0;
  return ER_HEADER_HEIGHT + Math.max(rows, 1) * ER_ROW_HEIGHT + more + 2;
}

/**
 * 生成节点连接桩：
 * - node 模式：节点左右各一个桩（概念层实体级关系）
 * - field 模式：每个可见字段行左右各一个桩（逻辑层字段级关系），absolute 定位对齐行
 */
export function buildPorts(
  node: ErTableNode,
  mode: 'field' | 'node' | 'none',
): ErPortsMetadata {
  const groups: ErPortsMetadata['groups'] = {
    left: {
      position: { name: 'absolute' },
      attrs: {
        circle: {
          r: 4,
          magnet: true,
          stroke: PORT_COLOR,
          fill: '#fff',
          strokeWidth: 1.5,
          style: { visibility: 'hidden' },
        },
      },
    },
    right: {
      position: { name: 'absolute' },
      attrs: {
        circle: {
          r: 4,
          magnet: true,
          stroke: PORT_COLOR,
          fill: '#fff',
          strokeWidth: 1.5,
          style: { visibility: 'hidden' },
        },
      },
    },
  };
  const items: ErPortsMetadata['items'] = [];
  if (mode === 'node') {
    const midY = calcNodeHeight(node.rows.length) / 2;
    items.push(
      { id: 'n:in', group: 'left', args: { x: 0, y: midY } },
      { id: 'n:out', group: 'right', args: { x: ER_NODE_WIDTH, y: midY } },
    );
  } else if (mode === 'field') {
    node.rows.slice(0, ER_MAX_ROWS).forEach((row, index) => {
      const y = ER_HEADER_HEIGHT + index * ER_ROW_HEIGHT + ER_ROW_HEIGHT / 2;
      items.push(
        { id: `f:${row.name}:in`, group: 'left', args: { x: 0, y } },
        {
          id: `f:${row.name}:out`,
          group: 'right',
          args: { x: ER_NODE_WIDTH, y },
        },
      );
    });
  }
  return { groups, items };
}

/** 从桩编号解析字段名（f:name:in/out -> name） */
export function parsePortField(portId?: null | string) {
  if (!portId || !portId.startsWith('f:')) {
    return undefined;
  }
  return portId.slice(2).replace(/:(in|out)$/, '');
}

function escapeHtml(text: string) {
  return String(text)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

let registered = false;

/** 注册 ER 表节点（HTML：标题栏 + 字段行 + 折叠提示）与关系边 */
export function registerErShapes() {
  if (registered) {
    return;
  }
  registered = true;

  Shape.HTML.register({
    shape: 'er-table',
    effect: ['data'],
    html(cell) {
      const data = (cell.getData() ?? {}) as ErTableNode & {
        selected?: boolean;
      };
      const rows = data.rows ?? [];
      const visible = rows.slice(0, ER_MAX_ROWS);
      const hidden = rows.length - visible.length;

      const wrapper = document.createElement('div');
      wrapper.className = `er-table${data.selected ? ' er-table--selected' : ''}`;
      wrapper.style.width = `${ER_NODE_WIDTH}px`;

      const badge = data.badge
        ? `<span class="er-table__badge" style="background:${data.badgeColor ?? '#64748b'}">${escapeHtml(data.badge)}</span>`
        : '';
      const rowsHtml = visible
        .map(
          (row) => `
        <div class="er-table__row" data-field="${escapeHtml(row.name)}" title="${escapeHtml(row.changeDetail ?? '')}"
          style="${row.change ? `background:${{ ADDED: '#dcfce7', MODIFIED: '#fef3c7', REMOVED: '#fee2e2' }[row.change]};${row.change === 'REMOVED' ? 'text-decoration:line-through;' : ''}` : ''}">
          <span class="er-table__pk">${row.pk ? PK_ICON : ''}</span>
          <span class="er-table__field" title="${escapeHtml(row.label ?? row.name)}">${escapeHtml(row.name)}</span>
          <span class="er-table__type">${escapeHtml(row.type ?? row.label ?? '')}</span>
        </div>`,
        )
        .join('');
      const moreHtml =
        hidden > 0
          ? `<div class="er-table__more">+${hidden} 更多字段</div>`
          : '';

      wrapper.innerHTML = `
        <div class="er-table__header">
          <div class="er-table__titles">
            <span class="er-table__title" title="${escapeHtml(data.title ?? '')}">${escapeHtml(data.title ?? '')}</span>
            <span class="er-table__subtitle" title="${escapeHtml(data.subtitle ?? '')}">${escapeHtml(data.subtitle ?? '')}</span>
          </div>
          ${badge}
        </div>
        <div class="er-table__body">${rowsHtml}${moreHtml}</div>`;
      return wrapper;
    },
  });

  Graph.registerEdge(
    'er-edge',
    {
      inherit: 'edge',
      attrs: {
        line: {
          stroke: EDGE_COLOR,
          strokeWidth: 1.5,
          targetMarker: { name: 'block', width: 9, height: 6 },
        },
      },
      router: { name: 'manhattan', args: { padding: 24 } },
      connector: { name: 'rounded', args: { radius: 8 } },
      zIndex: 0,
    },
    true,
  );
}

/** 端口显隐与连线悬停交互 */
export function setupErInteraction(
  graph: Graph,
  options: { connectable: boolean; onEdgeRemove?: (edgeId: string) => void },
) {
  if (options.connectable) {
    graph.on('node:mouseenter', ({ node }) => {
      node.getPorts().forEach((port) => {
        node.setPortProp(port.id!, 'attrs/circle/style/visibility', 'visible');
        node.setPortProp(port.id!, 'attrs/circle/stroke', PORT_ACTIVE);
      });
    });
    graph.on('node:mouseleave', ({ node }) => {
      node.getPorts().forEach((port) => {
        node.setPortProp(port.id!, 'attrs/circle/style/visibility', 'hidden');
        node.setPortProp(port.id!, 'attrs/circle/stroke', PORT_COLOR);
      });
    });
  }
  graph.on('edge:mouseenter', ({ edge }) => {
    edge.attr('line/stroke', EDGE_HOVER);
    edge.attr('line/strokeWidth', 2);
    if (options.onEdgeRemove) {
      // 覆盖默认删除行为：交给上层确认后调接口删除，成功后重载画布
      edge.addTools({
        name: 'button-remove',
        args: {
          distance: '50%',
          onClick: () => options.onEdgeRemove?.(edge.id),
        },
      });
    }
  });
  graph.on('edge:mouseleave', ({ edge }) => {
    edge.attr('line/stroke', EDGE_COLOR);
    edge.attr('line/strokeWidth', 1.5);
    edge.removeTools();
  });
}
