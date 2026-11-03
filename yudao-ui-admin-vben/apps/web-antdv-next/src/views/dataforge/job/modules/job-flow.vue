<script setup lang="ts">
import type { Node } from '@antv/x6';

import type { JobFlow, JobNode } from '../graph-model';

import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  shallowRef,
  watch,
} from 'vue';

import { Graph } from '@antv/x6';
import { Export } from '@antv/x6-plugin-export';
import { Keyboard } from '@antv/x6-plugin-keyboard';
import { Selection } from '@antv/x6-plugin-selection';
import { Snapline } from '@antv/x6-plugin-snapline';
import { Button, Tooltip } from 'antdv-next';

import ReferenceIcon from '../../integration/modules/reference-icon.vue';
import { canConnectJobNodes, jobTaskIcon, parseJobFlow } from '../graph-model';

const props = withDefaults(
  defineProps<{
    canToggleTask?: (node: JobNode) => boolean;
    flowJson?: string;
    readonly?: boolean;
    taskOptions?: {
      dbType?: string;
      description?: string;
      id: number | string;
      kind: JobNode['kind'];
      name: string;
      status?: number;
      taskType?: string;
    }[];
  }>(),
  {
    flowJson: '',
    readonly: false,
    canToggleTask: undefined,
    taskOptions: () => [],
  },
);
const emit = defineEmits<{
  error: [message: string];
  inspect: [node: JobNode];
  refresh: [];
  toggleStatus: [node: JobNode];
  'update:flowJson': [value: string];
}>();
const canvas = ref<HTMLElement>();
const graph = shallowRef<Graph>();
const context = ref<{ edge: boolean; id: string; x: number; y: number }>();
let original: JobFlow = { nodes: [], edges: [] };
let lastEmitted = '';
let loading = false;
let resizeObserver: ResizeObserver | undefined;
const contextNode = computed(() => {
  const cell = context.value && graph.value?.getCellById(context.value.id);
  if (!cell || !cell.isNode()) return undefined;
  const node = cell.getData<JobNode>();
  const task = props.taskOptions.find(
    (item) =>
      item.kind === node.kind && String(item.id) === String(node.taskId),
  );
  return task ? { ...node, status: task.status } : node;
});
function toggleContextStatus() {
  const node = contextNode.value;
  if (node && !props.readonly && props.canToggleTask?.(node))
    emit('toggleStatus', node);
  context.value = undefined;
}

function closeContext(event: Event) {
  if (
    !(event.target instanceof Element) ||
    !event.target.closest('.qj-context')
  )
    context.value = undefined;
}
function escapeContext(event: KeyboardEvent) {
  if (event.key === 'Escape') context.value = undefined;
}

function snapshot(): JobFlow {
  const instance = graph.value;
  return {
    ...original,
    nodes:
      instance?.getNodes().map((node) => ({
        ...node.getData<JobNode>(),
        id: node.id,
        ...node.position(),
      })) ?? [],
    edges:
      instance?.getEdges().map((edge) => ({
        ...edge.getData(),
        source: edge.getSourceCellId(),
        target: edge.getTargetCellId(),
      })) ?? [],
  };
}
function changed() {
  if (loading || props.readonly) return;
  lastEmitted = JSON.stringify(snapshot());
  emit('update:flowJson', lastEmitted);
}
function shape(data: JobNode): Node.Metadata {
  const live = props.taskOptions.find(
    (task) =>
      task.kind === data.kind && String(task.id) === String(data.taskId),
  );
  if (live)
    data = {
      ...data,
      name: live.name,
      status: live.status,
      description: live.description,
      taskType: live.taskType,
      dbType: live.dbType,
    };
  const stateText =
    data.status === undefined
      ? '未知'
      : (['未上线', '已上线'][data.status] ?? '未知');
  const integration = data.kind === 'INTEGRATION';
  const color = integration ? '#6271f2' : '#12b369';
  const source = jobTaskIcon(data);
  const metadata: Node.Metadata = {
    id: data.id,
    shape: 'rect',
    x: data.x,
    y: data.y,
    width: 320,
    height: 122,
    data,
    markup: [
      { tagName: 'rect', selector: 'body' },
      { tagName: 'rect', selector: 'stripe' },
      { tagName: 'rect', selector: 'content' },
      { tagName: 'text', selector: 'kind' },
      { tagName: 'image', selector: 'icon' },
      { tagName: 'text', selector: 'name' },
      { tagName: 'image', selector: 'stateIcon' },
      { tagName: 'text', selector: 'stateLabel' },
      { tagName: 'rect', selector: 'stateBackground' },
      { tagName: 'text', selector: 'state' },
      { tagName: 'image', selector: 'descriptionIcon' },
      { tagName: 'text', selector: 'description' },
    ],
    attrs: {
      body: {
        fill: integration ? '#eaeeff' : '#eff9f6',
        stroke: 'transparent',
        rx: 3,
      },
      stripe: {
        x: 0,
        y: 0,
        width: 21,
        height: 98,
        fill: color,
        stroke: 'none',
      },
      content: {
        x: 29,
        y: 34,
        width: 219,
        height: 56,
        fill: '#fff',
        stroke: 'none',
        rx: 3,
      },
      kind: {
        text: integration ? '数\n据\n集\n成' : '数\n据\n开\n发',
        x: 10.5,
        y: 49,
        textAnchor: 'middle',
        textVerticalAnchor: 'middle',
        fontSize: 11,
        lineHeight: 16,
        fontWeight: 600,
        fill: '#fff',
      },
      icon: { x: 31, y: 12, width: 12, height: 12, 'xlink:href': source },
      name: {
        text: data.name,
        x: 48,
        y: 18,
        textAnchor: 'start',
        textVerticalAnchor: 'middle',
        fill: '#111',
        fontSize: 12,
        fontWeight: 600,
        textWrap: { width: 195, height: 18, ellipsis: true },
      },
      stateIcon: {
        x: 36,
        y: 45,
        width: 10,
        height: 10,
        'xlink:href': `/assets/qdata-map/icon-da-lineage-LastExecutionStatus${integration ? 's' : ''}.svg`,
      },
      stateLabel: {
        text: '状态:',
        x: 50,
        y: 50,
        textAnchor: 'start',
        textVerticalAnchor: 'middle',
        fontSize: 9,
        fill: '#999',
      },
      stateBackground: {
        x: 82,
        y: 43,
        width: 35,
        height: 16,
        rx: 1,
        fill: data.status === 1 ? '#e9f7e9' : '#edf6ff',
        stroke: 'none',
      },
      state: {
        text: stateText,
        x: 99.5,
        y: 51,
        textAnchor: 'middle',
        textVerticalAnchor: 'middle',
        fontSize: 8,
        fill: data.status === 1 ? '#16a542' : '#3b77ff',
      },
      descriptionIcon: {
        x: 36,
        y: 69,
        width: 10,
        height: 10,
        'xlink:href': '/assets/qdata-integration/icon-file.png',
      },
      description: {
        text: `描述:    ${data.description || '-'}`,
        x: 50,
        y: 75,
        textAnchor: 'start',
        textVerticalAnchor: 'middle',
        fontSize: 9,
        fill: '#999',
        textWrap: { width: 190, height: 18, ellipsis: true },
      },
    },
    ports: {
      groups: {
        in: {
          position: 'top',
          attrs: {
            circle: {
              r: 4,
              magnet: 'passive',
              stroke: '#bdcfea',
              fill: '#fff',
              visibility: props.readonly ? 'hidden' : 'visible',
            },
          },
        },
        out: {
          position: 'bottom',
          attrs: {
            circle: {
              r: 4,
              magnet: true,
              stroke: '#bdcfea',
              fill: '#fff',
              visibility: props.readonly ? 'hidden' : 'visible',
            },
          },
        },
      },
      items: [
        { id: 'in', group: 'in' },
        { id: 'out', group: 'out' },
      ],
    },
  };
  const attributes = metadata.attrs as Record<string, Record<string, unknown>>;
  for (const selector of ['kind', 'name', 'stateLabel', 'state', 'description'])
    Object.assign(attributes[selector]!, { refX: 0, refY: 0 });
  for (const [selector, values] of Object.entries(attributes)) {
    if (selector === 'body') continue;
    for (const field of [
      'x',
      'y',
      'width',
      'height',
      'fontSize',
      'lineHeight',
    ]) {
      if (typeof values[field] === 'number') values[field] *= 1.25;
    }
    if (values.textWrap && typeof values.textWrap === 'object') {
      const wrap = values.textWrap as Record<string, unknown>;
      for (const field of ['width', 'height'])
        if (typeof wrap[field] === 'number') wrap[field] *= 1.25;
    }
  }
  return metadata;
}
function edgeShape(source: string, target: string, data: object = {}) {
  return {
    shape: 'edge',
    source: { cell: source, port: 'out' },
    target: { cell: target, port: 'in' },
    data,
    router: {
      name: 'manhattan',
      args: {
        padding: 20,
        startDirections: ['bottom'],
        endDirections: ['top'],
      },
    },
    connector: { name: 'rounded', args: { radius: 8 } },
    attrs: {
      line: {
        stroke: '#c4d5ed',
        strokeWidth: 1,
        targetMarker: { name: 'block', width: 7, height: 5 },
      },
    },
  };
}
function center() {
  graph.value?.zoomToFit({ padding: 65, maxScale: 1, minScale: 0.3 });
  graph.value?.centerContent();
}
function updateTask(data: JobNode) {
  const instance = graph.value;
  if (!instance) return;
  for (const node of instance.getNodes()) {
    const current = node.getData<JobNode>();
    if (
      current.kind !== data.kind ||
      String(current.taskId) !== String(data.taskId)
    )
      continue;
    const updated = {
      ...current,
      ...(current.id === data.id ? { priority: data.priority } : {}),
      name: data.name,
      status: data.status,
      description: data.description,
    };
    node.setData(updated);
    node.setAttrs(shape(updated).attrs ?? {});
  }
  changed();
}
function remove() {
  if (!props.readonly && context.value)
    graph.value?.getCellById(context.value.id)?.remove();
  context.value = undefined;
}
function inspectContext() {
  const node = context.value && graph.value?.getCellById(context.value.id);
  if (node?.isNode()) emit('inspect', node.getData<JobNode>());
  context.value = undefined;
}
function setup(flow: JobFlow) {
  if (!canvas.value) return;
  graph.value?.dispose();
  original = flow;
  loading = true;
  const instance: Graph = new Graph({
    container: canvas.value,
    autoResize: true,
    background: { color: '#fff' },
    panning: true,
    mousewheel: {
      enabled: true,
      modifiers: ['ctrl', 'meta'],
      minScale: 0.3,
      maxScale: 2,
    },
    interacting: () => !props.readonly,
    connecting: {
      allowBlank: false,
      allowLoop: false,
      allowMulti: false,
      allowNode: false,
      snap: { radius: 16 },
      validateConnection({ sourceCell, targetCell, sourcePort, targetPort }) {
        if (
          props.readonly ||
          !sourceCell ||
          !targetCell ||
          sourcePort !== 'out' ||
          targetPort !== 'in'
        )
          return false;
        return canConnectJobNodes(
          snapshot().edges,
          sourceCell.id,
          targetCell.id,
        );
      },
      createEdge: () => instance.createEdge(edgeShape('', '')),
    },
  });
  graph.value = instance;
  instance.use(new Export());
  instance.use(new Snapline({ enabled: !props.readonly }));
  instance.use(
    new Selection({
      enabled: !props.readonly,
      multiple: true,
      rubberband: true,
      showNodeSelectionBox: false,
    }),
  );
  instance.use(new Keyboard({ enabled: !props.readonly }));
  instance.fromJSON({
    nodes: flow.nodes.map(shape),
    edges: flow.edges.map((edge) => edgeShape(edge.source, edge.target, edge)),
  });
  instance.on('node:dblclick', ({ node }) =>
    emit('inspect', node.getData<JobNode>()),
  );
  instance.on('cell:contextmenu', ({ cell, e }) => {
    e.preventDefault();
    context.value = {
      id: cell.id,
      edge: cell.isEdge(),
      x: Math.max(5, Math.min(e.clientX, window.innerWidth - 145)),
      y: Math.max(5, Math.min(e.clientY, window.innerHeight - 85)),
    };
  });
  instance.on('blank:click', () => {
    context.value = undefined;
  });
  instance.on('node:selected', ({ node }) =>
    node.attr('body/stroke', '#2663ff'),
  );
  instance.on('node:unselected', ({ node }) =>
    node.attr('body/stroke', 'transparent'),
  );
  instance.on('node:change:position', changed);
  instance.on('cell:added', changed);
  instance.on('cell:removed', changed);
  instance.on('edge:connected', changed);
  instance.bindKey(['backspace', 'delete'], () => {
    if (!props.readonly) instance.removeCells(instance.getSelectedCells());
  });
  resizeObserver?.disconnect();
  resizeObserver = new ResizeObserver(() =>
    instance.resize(
      canvas.value?.clientWidth ?? 0,
      canvas.value?.clientHeight ?? 0,
    ),
  );
  resizeObserver.observe(canvas.value);
  loading = false;
  void nextTick(center);
}
function addTask(
  task: Omit<JobNode, 'id' | 'x' | 'y'>,
  client?: { x: number; y: number },
) {
  if (!graph.value || props.readonly) return;
  const position = client
    ? graph.value.clientToLocal(client.x, client.y)
    : graph.value.clientToLocal(
        (canvas.value?.getBoundingClientRect().x ?? 0) + 150,
        (canvas.value?.getBoundingClientRect().y ?? 0) + 140,
      );
  graph.value.addNode(
    shape({
      ...task,
      id: crypto.randomUUID(),
      x: position.x - 160,
      y: position.y - 61,
    } as JobNode),
  );
}
function drop(event: DragEvent) {
  if (props.readonly) return;
  const source = event.dataTransfer?.getData('application/qdata-job-task');
  if (!source) return;
  try {
    const task: unknown = JSON.parse(source);
    if (!task || typeof task !== 'object') return;
    const node = task as JobNode;
    if (
      !['DEVELOPMENT', 'INTEGRATION'].includes(node.kind) ||
      !node.taskId ||
      !node.name
    )
      return;
    addTask(node, { x: event.clientX, y: event.clientY });
  } catch {
    emit('error', '拖入的任务信息无效，请重新选择');
  }
}
watch(
  () => props.flowJson,
  async (value) => {
    if (value === lastEmitted && graph.value) return;
    await nextTick();
    try {
      setup(parseJobFlow(value));
    } catch (error) {
      emit(
        'error',
        error instanceof Error ? error.message : '作业流程读取失败',
      );
    }
  },
  { immediate: true },
);
watch(
  () => props.taskOptions,
  () => {
    for (const node of graph.value?.getNodes() ?? [])
      node.setAttrs(shape(node.getData<JobNode>()).attrs ?? {});
  },
);
onMounted(() => {
  document.addEventListener('pointerdown', closeContext);
  document.addEventListener('keydown', escapeContext);
});
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', closeContext);
  document.removeEventListener('keydown', escapeContext);
  resizeObserver?.disconnect();
  graph.value?.dispose();
});
defineExpose({ addTask, center, snapshot, updateTask });
</script>

<template>
  <div class="qj-flow" @dragover.prevent @drop.prevent="drop">
    <div ref="canvas" class="qj-flow-canvas"></div>
    <div class="qj-flow-toolbar">
      <Tooltip title="缩小">
        <Button aria-label="缩小流程" @click="graph?.zoom(-0.1)">
          <ReferenceIcon name="zoom-out" />
        </Button>
      </Tooltip>
      <Tooltip title="放大">
        <Button aria-label="放大流程" @click="graph?.zoom(0.1)">
          <ReferenceIcon name="zoom-in" />
        </Button>
      </Tooltip>
      <Tooltip title="居中">
        <Button aria-label="居中流程" @click="center">
          <ReferenceIcon name="center-ref" />
        </Button>
      </Tooltip>
      <Tooltip v-if="readonly" title="刷新">
        <Button aria-label="刷新作业流程" @click="emit('refresh')">
          <ReferenceIcon name="refresh" />
        </Button>
      </Tooltip>
      <Tooltip v-else title="导出图片">
        <Button
          aria-label="导出流程图片"
          @click="graph?.exportPNG('作业流程', { backgroundColor: '#fff' })"
        >
          <ReferenceIcon name="export-ref" />
        </Button>
      </Tooltip>
    </div>
    <Teleport to="body">
      <div
        v-if="context"
        class="qj-context"
        role="menu"
        :style="{ left: `${context.x}px`, top: `${context.y}px` }"
        @keydown.esc="context = undefined"
      >
        <button v-if="!context.edge" role="menuitem" @click="inspectContext">
          <ReferenceIcon name="eye" />查看任务
        </button>
        <button
          v-if="contextNode && !readonly && canToggleTask?.(contextNode)"
          role="menuitem"
          @click="toggleContextStatus"
        >
          {{ contextNode.status === 1 ? '下线' : '上线' }}
        </button>
        <button v-if="!readonly" role="menuitem" @click="remove">
          <ReferenceIcon name="delete" />删除{{
            context.edge ? '连线' : '节点'
          }}
        </button>
        <button role="menuitem" @click="context = undefined">关闭</button>
      </div>
    </Teleport>
  </div>
</template>
