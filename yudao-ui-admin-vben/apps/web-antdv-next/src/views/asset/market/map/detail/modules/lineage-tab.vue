<script setup lang="ts">
import type { ImportedAssetApi } from '#/api/asset/dataasset/imported';

import { computed, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { useResizeObserver } from '@vueuse/core';
import { Button, Empty, Tooltip } from 'antdv-next';

import { lineageGraph } from './lineage-data';

defineOptions({ name: 'AssetMapLineageTab' });
const props = defineProps<{ snapshot?: ImportedAssetApi.Snapshot }>();
const emit = defineEmits<{ refresh: [] }>();
const graph = computed(() => lineageGraph(props.snapshot?.lineage));
const documentIcon = '/assets/qdata-market/icon-document.png';
const zoom = ref(1);
const canvas = ref<HTMLElement>();
const size = ref({ width: 0, height: 0 });
useResizeObserver(canvas, ([entry]) => {
  if (entry)
    size.value = {
      width: entry.contentRect.width,
      height: entry.contentRect.height,
    };
});
const scale = computed(
  () =>
    Math.max(
      0.05,
      Math.min(
        (size.value.width - 24) / graph.value.width,
        (size.value.height - 24) / graph.value.height,
        1,
      ),
    ) * zoom.value,
);
const byId = computed(
  () => new Map(graph.value.nodes.map((node) => [node.id, node])),
);
function edgePath(source: string, target: string) {
  const a = byId.value.get(source);
  const b = byId.value.get(target);
  if (!a || !b) return '';
  const middle = (a.x + 200 + b.x) / 2;
  return `M${a.x + 200},${a.y + 32} L${middle},${a.y + 32} L${middle},${b.y + 32} L${b.x},${b.y + 32}`;
}
function exportGraph() {
  const escape = (value: string) =>
    value
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;');
  const lines = graph.value.edges
    .map(
      (edge) =>
        `<path d="${edgePath(edge.source, edge.target)}" fill="none" stroke="#c5d2f3"/>`,
    )
    .join('');
  const nodes = graph.value.nodes
    .map(
      (node) =>
        `<g transform="translate(${node.x},${node.y})"><rect width="200" height="64" fill="#f3f6ff" stroke="#dbe3fc"/><text x="10" y="25" font-size="12">${escape(node.label)}</text><text x="10" y="48" fill="#777" font-size="11">${escape(node.subLabel)}</text></g>`,
    )
    .join('');
  const blob = new Blob(
    [
      `<svg xmlns="http://www.w3.org/2000/svg" width="${graph.value.width}" height="${graph.value.height}"><rect width="100%" height="100%" fill="white"/>${lines}${nodes}</svg>`,
    ],
    { type: 'image/svg+xml;charset=utf-8' },
  );
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = 'asset-lineage.svg';
  link.click();
  URL.revokeObjectURL(url);
}
watch(
  () => props.snapshot,
  () => {
    zoom.value = 1;
  },
);
</script>

<template>
  <div class="lineage-tab">
    <Empty
      v-if="!graph.nodes.length"
      class="lineage-tab__empty"
      description="暂无血缘数据"
    />
    <template v-else>
      <div class="lineage-tab__toolbar">
        <Tooltip title="缩小">
          <Button aria-label="缩小" @click="zoom = Math.max(0.4, zoom - 0.2)">
            <IconifyIcon icon="lucide:zoom-out" />
          </Button>
        </Tooltip>
        <Tooltip title="放大">
          <Button aria-label="放大" @click="zoom = Math.min(2.4, zoom + 0.2)">
            <IconifyIcon icon="lucide:zoom-in" />
          </Button>
        </Tooltip>
        <Tooltip title="适应画布">
          <Button aria-label="适应画布" @click="zoom = 1">
            <IconifyIcon icon="lucide:crosshair" />
          </Button>
        </Tooltip>
        <Tooltip title="导出图片">
          <Button aria-label="导出图片" @click="exportGraph">
            <IconifyIcon icon="lucide:upload" />
          </Button>
        </Tooltip>
        <Tooltip title="刷新">
          <Button aria-label="刷新" @click="emit('refresh')">
            <IconifyIcon icon="lucide:refresh-cw" />
          </Button>
        </Tooltip>
        <span class="lineage-tab__scope">{{ graph.global ? '来源平台血缘参考（全局初始图）' : '资产血缘'
          }}<template v-if="graph.hasMore"> · 当前为部分节点</template></span>
      </div>
      <div ref="canvas" class="lineage-tab__canvas">
        <svg
          :viewBox="`0 0 ${graph.width} ${graph.height}`"
          :style="{
            width: `${graph.width * scale}px`,
            height: `${graph.height * scale}px`,
          }"
          role="img"
          aria-label="资产血缘关系图"
        >
          <defs>
            <marker
              id="asset-lineage-arrow"
              viewBox="0 0 10 10"
              refX="9"
              refY="5"
              markerWidth="5"
              markerHeight="5"
              orient="auto-start-reverse"
            >
              <path d="M 0 0 L 10 5 L 0 10" fill="#c5d2f3" />
            </marker>
          </defs>
          <path
            v-for="edge in graph.edges"
            :key="edge.id"
            :d="edgePath(edge.source, edge.target)"
            fill="none"
            stroke="#d7e1f9"
            stroke-width="1.2"
            marker-end="url(#asset-lineage-arrow)"
          />
          <g
            v-for="node in graph.nodes"
            :key="node.id"
            :transform="`translate(${node.x},${node.y})`"
          >
            <title>{{ node.label }} {{ node.subLabel }}</title>
            <rect
              width="200"
              height="64"
              :fill="node.task ? '#edeeff' : '#f0f5ff'"
              :stroke="node.current ? '#69b582' : '#edf2ff'"
              rx="2"
            />
            <rect v-if="node.task" width="13" height="64" fill="#7471ee" />
            <image
              :href="documentIcon"
              :x="node.task ? 20 : 8"
              y="10"
              width="11"
              height="13"
            />
            <foreignObject
              :x="node.task ? 35 : 23"
              y="7"
              :width="node.task ? 155 : 166"
              height="24"
              ><div
                xmlns="http://www.w3.org/1999/xhtml"
                class="lineage-tab__label"
              >
                {{ node.label }}
              </div></foreignObject>
            <rect
              :x="node.task ? 20 : 7"
              y="31"
              :width="node.task ? 172 : 186"
              height="25"
              fill="white"
            />
            <foreignObject
              :x="node.task ? 26 : 13"
              y="34"
              width="164"
              height="20"
              ><div
                xmlns="http://www.w3.org/1999/xhtml"
                class="lineage-tab__sub"
              >
                {{ node.subLabel || '-' }}
              </div></foreignObject>
          </g>
        </svg>
      </div>
    </template>
  </div>
</template>

<style scoped>
.lineage-tab {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 400px;
}

.lineage-tab__empty {
  padding-top: 100px;
}

.lineage-tab__toolbar {
  display: flex;
  gap: 5px;
  align-items: center;
  padding: 12px 16px;
}

.lineage-tab__scope {
  margin-left: 12px;
  color: hsl(var(--muted-foreground));
}

.lineage-tab__canvas {
  display: grid;
  flex: 1;
  place-items: center;
  width: 100%;
  min-height: 0;
  overflow: auto;
}

.lineage-tab__canvas svg {
  display: block;
  flex-shrink: 0;
}

.lineage-tab__label {
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 11px;
  color: hsl(var(--foreground));
  white-space: nowrap;
}

.lineage-tab__sub {
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 10px;
  color: #666;
  white-space: nowrap;
}
</style>
