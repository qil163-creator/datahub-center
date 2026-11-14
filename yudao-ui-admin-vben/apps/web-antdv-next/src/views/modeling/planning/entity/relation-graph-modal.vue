<script setup lang="ts">
import type { EntityGraphNode } from './graph-state';

import type { BusinessEntityApi } from '#/api/modeling/business-entity';

import { nextTick, onBeforeUnmount, ref, shallowRef } from 'vue';
import { useRouter } from 'vue-router';

import { useAntdDesignTokens } from '@vben/hooks';

import { Graph } from '@antv/x6';
import { Alert, Button, ConfigProvider, Empty, Modal, Spin } from 'antdv-next';

import { getBusinessEntityGraph } from '#/api/modeling/business-entity';
import { useObjectRequest } from '#/hooks/use-object-request';

import { layoutEntityGraph } from './graph-state';

import '#/views/modeling/logictable/modal.css';

const emit = defineEmits<{ openEntity: [id: BusinessEntityApi.Id] }>();

const { tokens } = useAntdDesignTokens();
const router = useRouter();

const COLORS = {
  center: '#e8f0ff',
  centerBorder: '#2663ff',
  entity: '#ffffff',
  entityBorder: '#d9dde5',
  model: '#f0f9eb',
  modelBorder: '#b3e19d',
  text: '#1f2329',
  edge: '#a9b4c4',
  muted: '#86909c',
};

const visible = ref(false);
const loading = ref(false);
const title = ref('关系图');
const empty = ref(false);
const truncated = ref(false);
const container = ref<HTMLElement>();
const graph = shallowRef<Graph>();
const entityId = ref<BusinessEntityApi.Id>();
const guard = useObjectRequest(() => String(entityId.value ?? ''));

function truncate(text: string, max: number) {
  return text.length > max ? `${text.slice(0, max - 1)}…` : text;
}

function nodeShape(node: EntityGraphNode) {
  const fill = node.center ? COLORS.center : node.kind === 'MODEL' ? COLORS.model : COLORS.entity;
  const stroke = node.center
    ? COLORS.centerBorder
    : node.kind === 'MODEL'
      ? COLORS.modelBorder
      : COLORS.entityBorder;
  return {
    id: node.key,
    shape: 'rect',
    x: node.x,
    y: node.y,
    width: node.width,
    height: node.height,
    data: node,
    attrs: {
      body: { fill, stroke, strokeWidth: node.center ? 2 : 1, rx: 6, ry: 6, cursor: 'pointer' },
      label: {
        text: `${truncate(node.title, 16)}\n${truncate(node.subtitle, 26)}`,
        fill: COLORS.text,
        fontSize: 12,
        lineHeight: 18,
        cursor: 'pointer',
      },
    },
  };
}

function ensureGraph() {
  if (graph.value || !container.value) return graph.value;
  const instance = new Graph({
    container: container.value,
    autoResize: true,
    background: { color: '#fafbfc' },
    panning: true,
    mousewheel: { enabled: true, modifiers: ['ctrl', 'meta'], minScale: 0.2, maxScale: 2 },
    interacting: false,
  });
  instance.on('node:click', ({ node }) => {
    const data = node.getData<EntityGraphNode>();
    if (!data || data.center) return;
    if (data.kind === 'MODEL') {
      visible.value = false;
      void router.push({ name: 'ModelingTableModelDetail', params: { id: String(data.refId) } });
    } else {
      emit('openEntity', data.refId);
    }
  });
  graph.value = instance;
  return instance;
}

function render(data: BusinessEntityApi.Graph) {
  const instance = ensureGraph();
  if (!instance) return;
  const layout = layoutEntityGraph(data);
  instance.fromJSON({
    nodes: layout.nodes.map(nodeShape),
    edges: layout.edges.map((edge) => ({
      id: edge.key,
      shape: 'edge',
      source: edge.source,
      target: edge.target,
      connector: { name: 'smooth' },
      attrs: {
        line: {
          stroke: COLORS.edge,
          strokeWidth: 1.4,
          strokeDasharray: edge.relationType === 'LOGIC_MODEL' ? '5 4' : undefined,
          targetMarker: { name: 'block', width: 8, height: 6 },
        },
      },
      labels: [{ attrs: { label: { text: edge.label, fill: COLORS.muted, fontSize: 11 } } }],
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

async function open(id: BusinessEntityApi.Id, name = '') {
  // 弹窗关闭时销毁内容，画布必须绑定到本次打开新建的容器。
  graph.value?.dispose();
  graph.value = undefined;
  entityId.value = id;
  title.value = name ? `关系图 - ${name}` : '关系图';
  empty.value = false;
  truncated.value = false;
  visible.value = true;
  const current = guard.begin();
  loading.value = true;
  try {
    const data = await getBusinessEntityGraph(id);
    if (!current()) return;
    truncated.value = data.modelsTruncated === true;
    empty.value = data.nodes.length <= 1;
    await nextTick();
    if (current()) render(data);
  } finally {
    if (current()) loading.value = false;
  }
}

function close() {
  guard.begin();
  visible.value = false;
  graph.value?.dispose();
  graph.value = undefined;
}

onBeforeUnmount(() => graph.value?.dispose());

defineExpose({ open });
</script>

<template>
  <ConfigProvider :theme="{ token: { borderRadius: tokens.borderRadius } }">
    <Modal
      v-model:open="visible"
      :title="title"
      class="qd-logic-modal"
      :width="1000"
      :footer="null"
      :style="{ top: '68px' }"
      destroy-on-hidden
      @cancel="close"
    >
      <Spin :spinning="loading">
        <div class="qd-modal-scroll">
          <Alert
            v-if="truncated"
            type="info"
            show-icon
            message="关联的逻辑表较多，只展示前 50 张。"
            style="margin-bottom: 12px"
          />
          <div class="entity-graph">
            <div ref="container" class="entity-graph__canvas"></div>
            <Empty
              v-if="empty && !loading"
              class="entity-graph__empty"
              description="该实体还没有关联的实体或逻辑表"
            />
            <div class="entity-graph__tools">
              <Button size="small" @click="fit">适应</Button>
              <Button size="small" aria-label="放大" @click="zoom(0.1)">+</Button>
              <Button size="small" aria-label="缩小" @click="zoom(-0.1)">−</Button>
            </div>
          </div>
          <div class="entity-graph__legend">
            <span><i class="is-center"></i>当前实体</span>
            <span><i class="is-entity"></i>关联实体（点击查看详情）</span>
            <span><i class="is-model"></i>逻辑表（点击打开）</span>
          </div>
        </div>
      </Spin>
      <footer class="qd-modal-footer">
        <Button @click="close">关闭</Button>
      </footer>
    </Modal>
  </ConfigProvider>
</template>

<style scoped>
.entity-graph {
  position: relative;
  height: 520px;
  overflow: hidden;
  border: 1px solid #eceef2;
  border-radius: 6px;
}

.entity-graph__canvas {
  width: 100%;
  height: 100%;
}

.entity-graph__empty {
  position: absolute;
  inset: 50% auto auto 50%;
  transform: translate(-50%, -50%);
}

.entity-graph__tools {
  position: absolute;
  top: 10px;
  right: 10px;
  display: flex;
  gap: 6px;
}

.entity-graph__legend {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  margin-top: 12px;
  font-size: 13px;
  color: #606266;
}

.entity-graph__legend i {
  display: inline-block;
  width: 14px;
  height: 10px;
  margin-right: 6px;
  vertical-align: -1px;
  border: 1px solid #d9dde5;
  border-radius: 2px;
}

.entity-graph__legend i.is-center {
  background: #e8f0ff;
  border-color: #2663ff;
}

.entity-graph__legend i.is-model {
  background: #f0f9eb;
  border-color: #b3e19d;
}

@media (max-width: 768px) {
  .entity-graph {
    height: 380px;
  }
}
</style>
