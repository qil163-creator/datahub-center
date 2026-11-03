import type { Edge, Node } from '@antv/x6';

import { Graph, Shape } from '@antv/x6';

import { getOutputPorts } from './graph-contract';

export const NODE_WIDTH = 220;
export const NODE_HEIGHT = 68;

const PORT_GRAY = '#c2c8d5';
const PORT_BLUE = '#5f95ff';
const EDGE_BLUE = '#5f95ff';
const EDGE_HOVER = '#2563eb';

/** 工具分类元信息（toolType：1 输入 / 2 转换 / 3 输出 / 4 脚本） */
const CATEGORY_META: Record<
  number,
  { badge: string; bg: string; color: string }
> = {
  1: { badge: '入', color: '#2563eb', bg: '#eff6ff' },
  2: { badge: '转', color: '#d97706', bg: '#fffbeb' },
  3: { badge: '出', color: '#059669', bg: '#ecfdf5' },
  4: { badge: '脚', color: '#7c3aed', bg: '#f5f3ff' },
};

const DEFAULT_CATEGORY = { badge: '组', color: '#64748b', bg: '#f1f5f9' };

/** 工具面板卡片图标配色（与画布节点分类配色一致） */
export function getCategoryStyle(type?: number) {
  const meta = CATEGORY_META[type ?? 0] ?? DEFAULT_CATEGORY;
  return { background: meta.bg, color: meta.color };
}

const SUCCESS_ICON = `<svg viewBox="0 0 16 16" width="18" height="18"><circle cx="8" cy="8" r="7" fill="none" stroke="#22c55e" stroke-width="1.6"/><path d="M4.8 8.4l2.2 2.2 4.2-4.8" fill="none" stroke="#22c55e" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>`;
const FAILED_ICON = `<svg viewBox="0 0 16 16" width="18" height="18"><circle cx="8" cy="8" r="7" fill="none" stroke="#ef4444" stroke-width="1.6"/><path d="M5.6 5.6l4.8 4.8M10.4 5.6l-4.8 4.8" fill="none" stroke="#ef4444" stroke-width="1.6" stroke-linecap="round"/></svg>`;
const STOPPED_ICON = `<svg viewBox="0 0 16 16" width="18" height="18"><circle cx="8" cy="8" r="7" fill="none" stroke="#94a3b8" stroke-width="1.6"/><path d="M5 8h6" fill="none" stroke="#94a3b8" stroke-width="1.6" stroke-linecap="round"/></svg>`;

/** 运行状态元信息（2 运行中 / 3 成功 / 4 失败 / 5 已停止） */
const STATUS_META: Record<number, { accent: string; icon: string }> = {
  2: { accent: '#3b82f6', icon: `<span class="etl-card__spin"></span>` },
  3: { accent: '#22c55e', icon: SUCCESS_ICON },
  4: { accent: '#ef4444', icon: FAILED_ICON },
  5: { accent: '#94a3b8', icon: STOPPED_ICON },
};

/** 左入右出端口（默认隐藏，悬停节点时显示，已连接常显蓝色） */
export const NODE_PORTS = {
  groups: {
    in: {
      position: 'left',
      attrs: {
        circle: {
          r: 4,
          magnet: true,
          stroke: PORT_GRAY,
          strokeWidth: 1,
          fill: PORT_GRAY,
          style: { visibility: 'hidden' },
        },
      },
    },
    out: {
      position: 'right',
      attrs: {
        circle: {
          r: 4,
          magnet: true,
          stroke: PORT_GRAY,
          strokeWidth: 1,
          fill: PORT_GRAY,
          style: { visibility: 'hidden' },
        },
      },
    },
  },
  items: [
    { id: 'in', group: 'in' },
    { id: 'out', group: 'out' },
  ],
};

export function getNodePorts(
  toolCode?: string,
  config: Record<string, any> = {},
) {
  if (toolCode === 'sub-pipeline')
    return {
      groups: NODE_PORTS.groups,
      items: [
        ...(config.inputs ?? []).map((port: { name: string }) => ({
          id: port.name,
          group: 'in',
          attrs: { text: { text: port.name } },
        })),
        ...(config.outputs ?? []).map((port: { name: string }) => ({
          id: port.name,
          group: 'out',
          attrs: { text: { text: port.name } },
        })),
      ],
    };
  if (toolCode === 'boundary-input' || toolCode === 'boundary-output')
    return {
      groups: NODE_PORTS.groups,
      items: NODE_PORTS.items.filter(
        (port) => port.group === (toolCode === 'boundary-input' ? 'out' : 'in'),
      ),
    };
  if (toolCode !== 'quality-check') return NODE_PORTS;
  const labels: Record<string, string> = {
    main: '全部',
    pass: '合格',
    warn: '警告',
    reject: '不合格',
  };
  return {
    groups: {
      ...NODE_PORTS.groups,
      out: {
        ...NODE_PORTS.groups.out,
        label: { position: { name: 'right', args: { x: 8 } } },
      },
    },
    items: [
      { id: 'in', group: 'in' },
      ...getOutputPorts(toolCode, config).map((id) => ({
        id,
        group: 'out',
        attrs: { text: { text: labels[id], fill: '#64748b', fontSize: 11 } },
      })),
    ],
  };
}

/** 运行时连线标签只用于画布展示，不写入连线 data，因此不会进入保存、校验、模板和撤销历史 */
export function runtimeEdgeLabel(text: string, color: string) {
  return {
    position: 0.5,
    attrs: {
      label: { text, fill: color, fontSize: 11 },
      body: { fill: '#ffffff', stroke: color, strokeWidth: 1, rx: 3, ry: 3 },
    },
  };
}

function escapeHtml(text: string) {
  return String(text)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;');
}

let registered = false;

/**
 * 注册 ETL 自定义节点（HTML 卡片：分类图标 + 节点名/组件名 + 运行状态图标 + 悬停删除按钮）
 * 与自定义连线样式
 */
export function registerEtlShapes() {
  if (registered) {
    return;
  }
  registered = true;

  Shape.HTML.register({
    shape: 'etl-task',
    width: NODE_WIDTH,
    height: NODE_HEIGHT,
    // data 变化时重新渲染（节点改名 / 运行状态 / 选中态）
    effect: ['data'],
    html(cell) {
      const data = (cell.getData() ?? {}) as Record<string, any>;
      const category =
        CATEGORY_META[data.toolType as number] ?? DEFAULT_CATEGORY;
      const status = STATUS_META[data.runStatus as number];
      const accent = status?.accent ?? category.color;
      // 运行时读写行数：轮询写入的临时字段，行数按字符串直接拼接（后端 Long 契约，不做数值转换）
      const hasCounts =
        (data.readCount !== undefined && data.readCount !== null) ||
        (data.writeCount !== undefined && data.writeCount !== null);
      const counts = hasCounts
        ? `<span class="etl-card__counts">${escapeHtml(`读 ${data.readCount ?? '-'} · 写 ${data.writeCount ?? '-'}`)}</span>`
        : '';

      const wrapper = document.createElement('div');
      wrapper.className = `etl-card${data.selected ? ' etl-card--selected' : ''}`;
      wrapper.style.width = `${NODE_WIDTH}px`;
      wrapper.style.height = `${NODE_HEIGHT}px`;
      wrapper.innerHTML = `
        <span class="etl-card__accent" style="background:${accent}"></span>
        <span class="etl-card__icon" style="background:${category.bg};color:${category.color}">${category.badge}</span>
        <span class="etl-card__meta">
          <span class="etl-card__name">${escapeHtml(data.name ?? '')}</span>
          <span class="etl-card__tool">${escapeHtml(data.toolName ?? data.toolCode ?? '')}</span>${counts}
        </span>
        <span class="etl-card__status">${status?.icon ?? ''}</span>
        <span class="etl-card__close" title="删除节点">&#10005;</span>`;

      const close = wrapper.querySelector('.etl-card__close') as HTMLElement;
      close.addEventListener('mousedown', (event) => event.stopPropagation());
      close.addEventListener('click', (event) => {
        event.stopPropagation();
        cell.remove();
      });
      return wrapper;
    },
  });

  Graph.registerEdge(
    'etl-edge',
    {
      inherit: 'edge',
      attrs: {
        line: {
          stroke: EDGE_BLUE,
          strokeWidth: 2,
          targetMarker: { name: 'block', width: 10, height: 7 },
        },
      },
      connector: { name: 'smooth' },
      zIndex: 0,
    },
    true,
  );
}

/**
 * 端口显隐与连线悬停交互：
 * - 悬停节点显示全部端口，移开后仅保留已连接端口（蓝色）
 * - 悬停连线高亮并出现删除按钮
 */
export function setupEtlInteraction(graph: Graph) {
  const isPortConnected = (node: Node, portId: string) =>
    graph
      .getConnectedEdges(node)
      .some(
        (edge) =>
          (edge.getSourceCellId() === node.id &&
            edge.getSourcePortId() === portId) ||
          (edge.getTargetCellId() === node.id &&
            edge.getTargetPortId() === portId),
      );

  const syncPort = (node: Node, portId: string, hover: boolean) => {
    const connected = isPortConnected(node, portId);
    const color = connected ? PORT_BLUE : PORT_GRAY;
    node.setPortProp(
      portId,
      'attrs/circle/style/visibility',
      hover || connected ? 'visible' : 'hidden',
    );
    node.setPortProp(portId, 'attrs/circle/fill', color);
    node.setPortProp(portId, 'attrs/circle/stroke', color);
  };

  const syncNodePorts = (node: Node, hover: boolean) => {
    node.getPorts().forEach((port) => syncPort(node, port.id!, hover));
  };

  const syncEdgeEndpoints = (edge: Edge) => {
    const terminals = [
      [edge.getSourceCellId(), edge.getSourcePortId()],
      [edge.getTargetCellId(), edge.getTargetPortId()],
    ] as const;
    for (const [cellId, portId] of terminals) {
      if (!cellId || !portId) {
        continue;
      }
      const cell = graph.getCellById(cellId);
      if (cell?.isNode()) {
        syncPort(cell, portId, false);
      }
    }
  };

  graph.on('node:mouseenter', ({ node }) => syncNodePorts(node, true));
  graph.on('node:mouseleave', ({ node }) => syncNodePorts(node, false));
  graph.on('edge:added', ({ edge }) => syncEdgeEndpoints(edge));
  graph.on('edge:connected', ({ edge }) => syncEdgeEndpoints(edge));
  graph.on('edge:removed', ({ edge }) => syncEdgeEndpoints(edge));
  graph.on('edge:mouseenter', ({ edge }) => {
    edge.attr('line/stroke', EDGE_HOVER);
    edge.addTools({ name: 'button-remove', args: { distance: '50%' } });
  });
  graph.on('edge:mouseleave', ({ edge }) => {
    edge.attr('line/stroke', EDGE_BLUE);
    edge.removeTools();
  });
}
