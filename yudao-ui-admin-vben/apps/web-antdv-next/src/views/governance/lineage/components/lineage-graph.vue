<script setup lang="ts">
import type { LineageCanvas, LineageCanvasNode } from '../graph-model';

import { nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';

import { Graph } from '@antv/x6';
import { Button, Empty, Tooltip } from 'antdv-next';

defineOptions({ name: 'LineageGraph' });

const props = defineProps<{
  canvas: LineageCanvas;
  selectedKey?: string;
}>();

const emit = defineEmits<{
  selectEdgeKey: [key: string];
  selectNodeKey: [key: string];
}>();

const container = ref<HTMLElement>();
const graph = shallowRef<Graph>();
let resizeObserver: ResizeObserver | undefined;

const COLORS = {
  border: '#d9dde5',
  external: '#fff7e6',
  externalBorder: '#ffd591',
  root: '#e8f0ff',
  rootBorder: '#2663ff',
  selected: '#2663ff',
  table: '#ffffff',
  text: '#1f2329',
  muted: '#86909c',
  edge: '#a9b4c4',
};

function truncate(text: string, max: number) {
  return text.length > max ? `${text.slice(0, max - 1)}…` : text;
}

function nodeShape(node: LineageCanvasNode) {
  const selected = node.key === props.selectedKey;
  const fill = node.root ? COLORS.root : node.kind === 'external' ? COLORS.external : COLORS.table;
  const stroke = selected
    ? COLORS.selected
    : node.root
      ? COLORS.rootBorder
      : node.kind === 'external'
        ? COLORS.externalBorder
        : COLORS.border;
  return {
    id: node.key,
    shape: 'rect',
    x: node.x,
    y: node.y,
    width: node.width,
    height: node.height,
    data: node,
    attrs: {
      body: { fill, stroke, strokeWidth: selected ? 2 : 1, rx: 6, ry: 6, cursor: 'pointer' },
      label: {
        text: `${truncate(node.title, 24)}\n${truncate(node.subtitle, 30)}`,
        fill: COLORS.text,
        fontSize: 12,
        lineHeight: 18,
        cursor: 'pointer',
      },
    },
  };
}

function render() {
  const instance = graph.value;
  if (!instance) return;
  instance.fromJSON({
    nodes: props.canvas.nodes.map(nodeShape),
    edges: props.canvas.edges.map((edge) => ({
      id: edge.key,
      shape: 'edge',
      source: edge.source,
      target: edge.target,
      connector: { name: 'smooth' },
      attrs: {
        line: {
          stroke: COLORS.edge,
          strokeWidth: 1.4,
          strokeDasharray: edge.dashed ? '5 4' : undefined,
          targetMarker: { name: 'block', width: 8, height: 6 },
        },
      },
      labels: edge.label
        ? [{ attrs: { label: { text: edge.label, fill: COLORS.muted, fontSize: 11 } } }]
        : [],
    })),
  });
  void nextTick(fit);
}

function fit() {
  graph.value?.zoomToFit({ padding: 32, maxScale: 1, minScale: 0.2 });
}

function zoom(delta: number) {
  graph.value?.zoom(delta, { minScale: 0.2, maxScale: 2 });
}

onMounted(() => {
  if (!container.value) return;
  const instance = new Graph({
    container: container.value,
    autoResize: true,
    background: { color: '#fafbfc' },
    panning: true,
    mousewheel: { enabled: true, modifiers: ['ctrl', 'meta'], minScale: 0.2, maxScale: 2 },
    interacting: false,
  });
  instance.on('node:click', ({ node }) => emit('selectNodeKey', String(node.id)));
  instance.on('edge:click', ({ edge }) => emit('selectEdgeKey', String(edge.id)));
  graph.value = instance;
  resizeObserver = new ResizeObserver(() =>
    instance.resize(container.value?.clientWidth ?? 0, container.value?.clientHeight ?? 0),
  );
  resizeObserver.observe(container.value);
  render();
});

watch(() => props.canvas, render);
watch(
  () => props.selectedKey,
  () => {
    for (const node of graph.value?.getNodes() ?? []) {
      const data = node.getData<LineageCanvasNode>();
      node.setAttrs(nodeShape(data).attrs);
    }
  },
);

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  graph.value?.dispose();
});
</script>

<template>
  <div class="lineage-graph">
    <div ref="container" class="lineage-graph__canvas"></div>
    <Empty
      v-if="canvas.nodes.length === 0"
      class="lineage-graph__empty"
      description="暂无血缘关系"
    />
    <div class="lineage-graph__tools">
      <Tooltip title="适应画布"><Button size="small" @click="fit">适应</Button></Tooltip>
      <Button size="small" aria-label="放大" @click="zoom(0.1)">+</Button>
      <Button size="small" aria-label="缩小" @click="zoom(-0.1)">−</Button>
    </div>
  </div>
</template>

<style scoped>
.lineage-graph {
  position: relative;
  height: 100%;
  min-height: 360px;
  border: 1px solid #eceef2;
  border-radius: 6px;
  overflow: hidden;
}

.lineage-graph__canvas {
  width: 100%;
  height: 100%;
}

.lineage-graph__empty {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  pointer-events: none;
}

.lineage-graph__tools {
  position: absolute;
  top: 8px;
  right: 8px;
  display: flex;
  gap: 4px;
}
</style>
