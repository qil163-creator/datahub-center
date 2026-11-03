<script lang="ts" setup>
import type { Graph as GraphType, Node } from '@antv/x6';

import type {
  ErConnectPayload,
  ErNodePosition,
  ErRelationEdge,
  ErTableNode,
} from './types';

import {
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  shallowRef,
  watch,
} from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Graph } from '@antv/x6';
import { Export } from '@antv/x6-plugin-export';
import { History } from '@antv/x6-plugin-history';
import { Keyboard } from '@antv/x6-plugin-keyboard';
import { Snapline } from '@antv/x6-plugin-snapline';
import { Button, Space, Tooltip } from 'antdv-next';
import dagre from 'dagre';

import {
  buildPorts,
  calcNodeHeight,
  ER_NODE_WIDTH,
  parsePortField,
  registerErShapes,
  setupErInteraction,
} from './er-config';

defineOptions({ name: 'ErDesigner' });

const props = withDefaults(
  defineProps<{
    /** 连线模式：node-实体级（概念层） field-字段级（逻辑层） none-禁止连线 */
    connectMode?: 'field' | 'node' | 'none';
    edges: ErRelationEdge[];
    /** 导出文件名（默认 er-diagram） */
    exportName?: string;
    nodes: ErTableNode[];
    readonly?: boolean;
  }>(),
  { connectMode: 'none', readonly: false, exportName: 'er-diagram' },
);

const emit = defineEmits<{
  (e: 'connect', payload: ErConnectPayload): void;
  (e: 'dirtyChange', dirty: boolean): void;
  (e: 'edgeDblclick' | 'edgeRemove', edge: ErRelationEdge): void;
  (e: 'nodeDblclick', node: ErTableNode): void;
  (e: 'saveLayout', positions: ErNodePosition[]): void;
}>();

const containerRef = ref<HTMLElement>();
const graph = shallowRef<GraphType>();
const layoutDirty = ref(false);
watch(layoutDirty, (dirty) => emit('dirtyChange', dirty));

/** 业务边索引：X6 edge id -> 业务边 */
let edgeMap = new Map<string, ErRelationEdge>();
let nodeMap = new Map<string, ErTableNode>();
let resizeObserver: ResizeObserver | undefined;
let lastCanvasArea = 0;

onMounted(async () => {
  await nextTick();
  initGraph();
  renderGraph();
  observeCanvasSize();
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  graph.value?.dispose();
});

watch(
  () => [props.nodes, props.edges],
  () => renderGraph(),
  { flush: 'post' },
);

function initGraph() {
  if (!containerRef.value) {
    return;
  }
  registerErShapes();
  const connectable = !props.readonly && props.connectMode !== 'none';
  const instance: GraphType = new Graph({
    container: containerRef.value,
    autoResize: true,
    background: { color: '#f8fafc' },
    grid: { visible: true, size: 12, type: 'dot', args: { color: '#e2e8f0' } },
    mousewheel: { enabled: true, modifiers: ['ctrl', 'meta'] },
    panning: { enabled: true, eventTypes: ['leftMouseDown'] },
    interacting: {
      nodeMovable: !props.readonly,
      edgeMovable: false,
      edgeLabelMovable: false,
    },
    connecting: {
      snap: { radius: 24 },
      allowBlank: false,
      allowNode: false,
      allowEdge: false,
      allowLoop: props.connectMode === 'field',
      allowMulti: true,
      highlight: true,
      router: { name: 'manhattan', args: { padding: 24 } },
      connector: { name: 'rounded', args: { radius: 8 } },
      validateMagnet: () => connectable,
      validateConnection({ sourcePort, targetPort, sourceCell, targetCell }) {
        if (!connectable || !sourcePort || !targetPort) {
          return false;
        }
        // 概念层（node 模式）不允许自身连自身；字段级允许自表关联（如 parent_id -> id）
        if (props.connectMode === 'node' && sourceCell?.id === targetCell?.id) {
          return false;
        }
        if (
          props.connectMode === 'field' &&
          sourceCell?.id === targetCell?.id &&
          parsePortField(sourcePort) === parsePortField(targetPort)
        ) {
          return false;
        }
        return true;
      },
      createEdge() {
        return instance.createEdge({
          shape: 'er-edge',
          attrs: { line: { stroke: '#2563eb', strokeDasharray: '4 3' } },
        });
      },
    },
  });

  instance.use(new Snapline({ enabled: !props.readonly }));
  instance.use(new Export());
  instance.use(new Keyboard({ enabled: true }));
  if (!props.readonly) {
    instance.use(new History({ enabled: true }));
    instance.bindKey(['ctrl+z', 'meta+z'], () => {
      instance.undo();
      return false;
    });
    instance.bindKey(['ctrl+shift+z', 'meta+shift+z'], () => {
      instance.redo();
      return false;
    });
  }

  setupErInteraction(instance, {
    connectable,
    onEdgeRemove: props.readonly
      ? undefined
      : (edgeId) => {
          const edge = edgeMap.get(edgeId);
          if (edge) {
            emit('edgeRemove', edge);
          }
        },
  });

  // 连线完成：抛给上层确认落库，临时边移除（确认成功后由数据刷新渲染真实边）
  instance.on('edge:connected', ({ edge, isNew }) => {
    if (!isNew) {
      return;
    }
    const sourceNode = edge.getSourceCellId();
    const targetNode = edge.getTargetCellId();
    const payload: ErConnectPayload = {
      sourceId: nodeMap.get(sourceNode)?.id ?? sourceNode,
      targetId: nodeMap.get(targetNode)?.id ?? targetNode,
      sourceField: parsePortField(edge.getSourcePortId()),
      targetField: parsePortField(edge.getTargetPortId()),
    };
    instance.removeEdge(edge.id, { silent: true });
    emit('connect', payload);
  });

  instance.on('node:dblclick', ({ node }) => {
    const data = nodeMap.get(node.id);
    if (data) {
      emit('nodeDblclick', data);
    }
  });
  instance.on('edge:dblclick', ({ edge }) => {
    const data = edgeMap.get(edge.id);
    if (data) {
      emit('edgeDblclick', data);
    }
  });
  instance.on('node:moved', () => {
    layoutDirty.value = true;
  });

  graph.value = instance;
}

/** 全量渲染（清空重建） */
function renderGraph() {
  const instance = graph.value;
  if (!instance) {
    return;
  }
  instance.clearCells({ silent: true });
  nodeMap = new Map();
  edgeMap = new Map();

  const needLayout = props.nodes.some(
    (node) =>
      node.x === null ||
      node.x === undefined ||
      node.y === null ||
      node.y === undefined,
  );
  // 未布局节点的兜底网格坐标（右侧堆叠），已有坐标的节点保持原位
  let fallbackIndex = 0;
  const maxX = Math.max(
    0,
    ...props.nodes.map((node) =>
      node.x === null || node.x === undefined ? 0 : node.x + ER_NODE_WIDTH,
    ),
  );

  props.nodes.forEach((data) => {
    let x = data.x;
    let y = data.y;
    if (x === null || x === undefined || y === null || y === undefined) {
      x = maxX + 60 + (fallbackIndex % 3) * (ER_NODE_WIDTH + 60);
      y = 40 + Math.floor(fallbackIndex / 3) * 260;
      fallbackIndex++;
    }
    const node = instance.addNode({
      id: `n-${data.id}`,
      shape: 'er-table',
      x,
      y,
      width: ER_NODE_WIDTH,
      height: calcNodeHeight(data.rows.length),
      data,
      ports: buildPorts(data, props.connectMode),
    });
    nodeMap.set(node.id, data);
  });

  props.edges.forEach((data) => {
    const sourceCellId = `n-${data.sourceId}`;
    const targetCellId = `n-${data.targetId}`;
    if (
      !instance.getCellById(sourceCellId) ||
      !instance.getCellById(targetCellId)
    ) {
      return;
    }
    const labelText = [data.label, data.name].filter(Boolean).join(' ');
    const edge = instance.addEdge({
      id: `e-${data.id}`,
      shape: 'er-edge',
      source: buildTerminal(sourceCellId, data.sourceField, 'out'),
      target: buildTerminal(targetCellId, data.targetField, 'in'),
      attrs: data.virtual ? { line: { strokeDasharray: '5 4' } } : undefined,
      labels: labelText
        ? [
            {
              position: 0.5,
              attrs: {
                label: { text: labelText, fontSize: 11, fill: '#475569' },
                body: {
                  fill: '#fff',
                  stroke: '#cbd5e1',
                  rx: 3,
                  ry: 3,
                },
              },
            },
          ]
        : undefined,
      data,
    });
    edgeMap.set(edge.id, data);
  });

  if (needLayout && props.nodes.length > 0) {
    autoLayout();
  } else {
    scheduleFitView();
  }
  layoutDirty.value = false;
}

function observeCanvasSize() {
  const el = containerRef.value;
  if (!el || typeof ResizeObserver === 'undefined') {
    return;
  }
  lastCanvasArea = el.clientWidth * el.clientHeight;
  resizeObserver = new ResizeObserver(() => {
    const canvas = containerRef.value;
    if (!canvas) {
      return;
    }
    const area = canvas.clientWidth * canvas.clientHeight;
    const becameVisible = lastCanvasArea < 64 && area >= 64;
    lastCanvasArea = area;
    if (area < 64) {
      return;
    }
    graph.value?.resize();
    if (becameVisible && props.nodes.length > 0) {
      fitView();
    }
  });
  resizeObserver.observe(el);
}

/** 边端点：字段级连到对应行桩，否则连节点（自动锚定边缘） */
function buildTerminal(
  cellId: string,
  field: string | undefined,
  direction: 'in' | 'out',
) {
  if (props.connectMode === 'field' && field) {
    return { cell: cellId, port: `f:${field}:${direction}` };
  }
  if (props.connectMode === 'node') {
    return { cell: cellId, port: direction === 'out' ? 'n:out' : 'n:in' };
  }
  return { cell: cellId };
}

/** dagre 自动布局（从左到右） */
function autoLayout() {
  const instance = graph.value;
  if (!instance) {
    return;
  }
  const nodes = instance.getNodes();
  if (nodes.length === 0) {
    return;
  }
  const dagreGraph = new dagre.graphlib.Graph();
  dagreGraph.setGraph({
    rankdir: 'LR',
    nodesep: 48,
    ranksep: 96,
    marginx: 40,
    marginy: 40,
  });
  dagreGraph.setDefaultEdgeLabel(() => ({}));
  nodes.forEach((node: Node) => {
    const size = node.getSize();
    dagreGraph.setNode(node.id, { width: size.width, height: size.height });
  });
  instance.getEdges().forEach((edge) => {
    const source = edge.getSourceCellId();
    const target = edge.getTargetCellId();
    if (source && target && source !== target) {
      dagreGraph.setEdge(source, target);
    }
  });
  dagre.layout(dagreGraph);
  nodes.forEach((node: Node) => {
    const pos = dagreGraph.node(node.id);
    if (pos) {
      node.setPosition(pos.x - pos.width / 2, pos.y - pos.height / 2);
    }
  });
  layoutDirty.value = true;
  fitView();
}

function scheduleFitView() {
  nextTick(() => {
    requestAnimationFrame(() => fitView());
  });
}

function fitView() {
  const instance = graph.value;
  const el = containerRef.value;
  if (!instance || !el || el.clientWidth < 8 || el.clientHeight < 8) {
    return;
  }
  instance.resize();
  instance.zoomToFit({ padding: 32, maxScale: 1 });
}

function zoom(factor: number) {
  graph.value?.zoom(factor);
}

function exportPng() {
  graph.value?.exportPNG(props.exportName, {
    padding: 24,
    backgroundColor: '#ffffff',
    quality: 1,
  });
}

/** 当前节点坐标（业务 id） */
function getPositions(): ErNodePosition[] {
  const instance = graph.value;
  if (!instance) {
    return [];
  }
  return instance.getNodes().map((node: Node) => {
    const { x, y } = node.getPosition();
    const data = nodeMap.get(node.id);
    return { id: data?.id ?? node.id, x: Math.round(x), y: Math.round(y) };
  });
}

function handleSaveLayout() {
  emit('saveLayout', getPositions());
}

defineExpose({
  autoLayout,
  exportPng,
  fitView,
  getPositions,
  markLayoutSaved: () => {
    layoutDirty.value = false;
  },
});
</script>

<template>
  <div class="er-designer">
    <div class="er-designer__toolbar">
      <Space :size="4">
        <slot name="toolbar-left"></slot>
      </Space>
      <Space :size="4">
        <Tooltip title="放大">
          <Button size="small" type="text" @click="zoom(0.15)">
            <IconifyIcon icon="lucide:zoom-in" />
          </Button>
        </Tooltip>
        <Tooltip title="缩小">
          <Button size="small" type="text" @click="zoom(-0.15)">
            <IconifyIcon icon="lucide:zoom-out" />
          </Button>
        </Tooltip>
        <Tooltip title="适配画布">
          <Button size="small" type="text" @click="fitView()">
            <IconifyIcon icon="lucide:maximize" />
          </Button>
        </Tooltip>
        <Tooltip title="自动布局">
          <Button size="small" type="text" @click="autoLayout()">
            <IconifyIcon icon="lucide:layout-dashboard" />
          </Button>
        </Tooltip>
        <Tooltip title="导出图片">
          <Button size="small" type="text" @click="exportPng()">
            <IconifyIcon icon="lucide:image-down" />
          </Button>
        </Tooltip>
        <Tooltip v-if="!readonly" title="保存布局">
          <Button
            :type="layoutDirty ? 'primary' : 'text'"
            size="small"
            @click="handleSaveLayout"
          >
            <IconifyIcon icon="lucide:save" />
          </Button>
        </Tooltip>
        <slot name="toolbar-right"></slot>
      </Space>
    </div>
    <div ref="containerRef" class="er-designer__canvas"></div>
  </div>
</template>

<style lang="scss">
/* ER 表节点为 X6 HTML 节点（位于组件作用域外），样式需全局注册 */
.er-table {
  overflow: hidden;
  font-size: 12px;
  background: #fff;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  box-shadow: 0 1px 3px rgb(15 23 42 / 8%);

  &--selected {
    border-color: #2563eb;
    box-shadow: 0 0 0 2px rgb(37 99 235 / 20%);
  }

  &__header {
    display: flex;
    gap: 6px;
    align-items: center;
    height: 40px;
    padding: 0 10px;
    background: linear-gradient(180deg, #f1f5f9, #e2e8f0);
    border-bottom: 1px solid #cbd5e1;
  }

  &__titles {
    display: flex;
    flex: 1;
    flex-direction: column;
    min-width: 0;
  }

  &__title {
    overflow: hidden;
    text-overflow: ellipsis;
    font-weight: 600;
    color: #0f172a;
    white-space: nowrap;
  }

  &__subtitle {
    overflow: hidden;
    text-overflow: ellipsis;
    font-size: 11px;
    color: #64748b;
    white-space: nowrap;
  }

  &__badge {
    flex: none;
    padding: 1px 6px;
    font-size: 10px;
    color: #fff;
    border-radius: 8px;
  }

  &__row {
    display: flex;
    gap: 4px;
    align-items: center;
    height: 26px;
    padding: 0 8px;
    border-bottom: 1px dashed #f1f5f9;

    &:hover {
      background: #f8fafc;
    }
  }

  &__pk {
    display: inline-flex;
    flex: none;
    justify-content: center;
    width: 14px;
  }

  &__field {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    color: #1e293b;
    white-space: nowrap;
  }

  &__type {
    flex: none;
    max-width: 90px;
    overflow: hidden;
    text-overflow: ellipsis;
    font-size: 11px;
    color: #94a3b8;
    white-space: nowrap;
  }

  &__more {
    height: 22px;
    padding: 0 8px;
    font-size: 11px;
    line-height: 22px;
    color: #94a3b8;
    text-align: center;
    background: #f8fafc;
  }
}
</style>

<style lang="scss" scoped>
.er-designer {
  display: flex;
  flex-direction: column;
  height: 100%;

  &__toolbar {
    display: flex;
    flex: none;
    align-items: center;
    justify-content: space-between;
    padding: 4px 8px;
    background: #fff;
    border-bottom: 1px solid #e2e8f0;
  }

  &__canvas {
    flex: 1;
    min-height: 0;
  }
}
</style>
