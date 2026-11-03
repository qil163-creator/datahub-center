<script setup lang="ts">
import type { PublicFlowNode } from '../model';

import type { OpsInstanceApi as Api } from '#/api/dataforge/ops';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { Alert, Button, Input, Spin } from 'antdv-next';

import { getOpsInstanceLog } from '#/api/dataforge/ops';
import { useObjectRequest } from '#/hooks/use-object-request';

import {
  displayDate,
  isActiveOpsInstance,
  layoutFlow,
  requestIdentity,
  rowIdentity,
} from '../model';
import LogViewer from './log-viewer.vue';
import NodeConfigModal from './node-config-modal.vue';
import ReferenceIcon from './reference-icon.vue';
import StatusBadge from './status-badge.vue';

const props = defineProps<{ record: Api.Instance }>();
const emit = defineEmits<{ back: []; refresh: [] }>();
const tab = ref(props.record.kind === 'FULL_SYNC' ? 'log' : 'flow');
const zoom = ref(1);
const keyword = ref('');
const message = ref('');
const loading = ref(false);
const failed = ref(false);
const selectedNode = ref('');
const configNode = ref<PublicFlowNode>();
let timer: ReturnType<typeof setTimeout> | undefined;
const graph = computed(() => layoutFlow(props.record.flowJson));
const graphSize = computed(() => ({
  width: Math.max(190, ...graph.value.nodes.map((node) => node.x + 80)),
  height: Math.max(100, ...graph.value.nodes.map((node) => node.y + 65)),
}));
const request = useObjectRequest(
  () => rowIdentity(props.record),
  () => {
    message.value = '';
    loading.value = false;
  },
);
watch(
  () => rowIdentity(props.record),
  () => {
    tab.value = props.record.kind === 'FULL_SYNC' ? 'log' : 'flow';
    zoom.value = 1;
    keyword.value = '';
    message.value = '';
    failed.value = false;
  },
);
watch(tab, (value) => {
  if (value === 'log') void loadLog();
});
function scheduleRefresh() {
  if (timer) clearTimeout(timer);
  timer = undefined;
  if (!isActiveOpsInstance(props.record)) return;
  timer = setTimeout(() => {
    emit('refresh');
    scheduleRefresh();
  }, 3000);
}
watch(
  () => props.record,
  () => {
    scheduleRefresh();
    if (tab.value === 'log') void loadLog();
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  if (timer) clearTimeout(timer);
});
function inspectNode(node: { id: string; label: string; type: string }) {
  selectedNode.value = node.id;
  configNode.value = node;
}
async function loadLog() {
  const current = request.begin();
  loading.value = true;
  failed.value = false;
  try {
    const log = await getOpsInstanceLog(requestIdentity(props.record));
    if (current()) message.value = log.message;
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
function position(edge: { source: string; target: string }) {
  const source = graph.value.nodes.find((node) => node.id === edge.source);
  const target = graph.value.nodes.find((node) => node.id === edge.target);
  return {
    x1: (source?.x ?? 0) + 56,
    y1: (source?.y ?? 0) + 17,
    x2: (target?.x ?? 0) + 24,
    y2: (target?.y ?? 0) + 17,
  };
}
const date = displayDate;
</script>

<template>
  <div class="qo-detail">
    <section class="qo-detail-header">
      <div class="qo-detail-title">
        <div>
          <b>{{ record.id }}</b><strong>{{ record.taskName }}</strong><StatusBadge :status="record.status" :queue-state="record.queueState" :queue-reason="record.queueReason" detail />
        </div>
        <Button size="small" @click="$emit('back')">
          <ReferenceIcon name="back" />返回
        </Button>
      </div>
      <table class="qo-info-table qo-detail-summary">
        <tbody>
          <tr>
            <th>任务实例名称</th>
            <td>{{ record.instanceName }}</td>
            <th>创建时间</th>
            <td>{{ date(record.createTime) }}</td>
            <th>创建人</th>
            <td>{{ record.creatorName || '-' }}</td>
          </tr>
          <tr>
            <th>描述</th>
            <td colspan="5">{{ record.description || '-' }}</td>
          </tr>
          <tr>
            <th>创建人</th>
            <td>{{ record.creatorName || '-' }}</td>
            <th>创建时间</th>
            <td>{{ date(record.createTime) }}</td>
            <th>更新时间</th>
            <td>{{ date(record.updateTime) }}</td>
          </tr>
        </tbody>
      </table>
    </section>
    <section class="qo-detail-body">
      <nav class="qo-tabs" aria-label="任务实例详情">
        <button
          v-if="record.kind !== 'FULL_SYNC'"
          type="button"
          :class="{ active: tab === 'flow' }"
          @click="tab = 'flow'"
        >
          任务流程
</button><button
          type="button"
          :class="{ active: tab === 'log' }"
          @click="tab = 'log'"
        >
          任务日志
        </button>
      </nav>
      <div v-if="tab === 'flow'" class="qo-flow-view">
        <div class="qo-flow-toolbar">
          <Button
            size="small"
            aria-label="缩小流程"
            @click="zoom = Math.max(0.4, zoom - 0.1)"
          >
            <ReferenceIcon name="zoom-out" />
</Button><Button
            size="small"
            aria-label="放大流程"
            @click="zoom = Math.min(2, zoom + 0.1)"
          >
            <ReferenceIcon name="zoom-in" />
</Button><Button size="small" aria-label="居中流程" @click="zoom = 1">
            <ReferenceIcon name="center" />
          </Button>
        </div>
        <div class="qo-flow-center">
          <div
            v-if="graph.nodes.length"
            class="qo-flow-graph"
            :style="{
              width: `${graphSize.width}px`,
              height: `${graphSize.height}px`,
              transform: `scale(${zoom})`,
            }"
          >
            <svg
              :width="graphSize.width"
              :height="graphSize.height"
              aria-hidden="true"
            >
              <defs>
                <marker
                  id="qo-arrow"
                  marker-width="8"
                  marker-height="8"
                  refX="7"
                  refY="4"
                  orient="auto"
                >
                  <path d="M0,0 L8,4 L0,8 Z" fill="#d6dbed" />
                </marker>
              </defs>
              <line
                v-for="(edge, index) in graph.edges"
                :key="index"
                v-bind="position(edge)"
                stroke="#d6dbed"
                stroke-width="1"
                marker-end="url(#qo-arrow)"
              />
            </svg>
            <div
              v-for="node in graph.nodes"
              :key="node.id"
              class="qo-flow-node"
              :class="{ selected: selectedNode === node.id }"
              role="button"
              tabindex="0"
              :aria-label="`${node.label}，双击查看配置`"
              :style="{ left: `${node.x}px`, top: `${node.y}px` }"
              @click="selectedNode = node.id"
              @dblclick="inspectNode(node)"
              @keydown.enter="inspectNode(node)"
            >
              <span class="qo-node-icon"><img
                  :src="`/assets/qdata-integration/node-${node.type}.svg`"
                  alt=""
/><ReferenceIcon name="pause" /></span><span>{{ node.label }}</span>
            </div>
          </div>
          <span v-else class="qo-muted">暂无任务流程</span>
        </div>
      </div>
      <div v-else class="qo-detail-log">
        <Input
          v-model:value="keyword"
          placeholder="搜索日志内容"
          aria-label="搜索任务日志"
          allow-clear
        >
          <template #prefix><ReferenceIcon name="search" /></template>
</Input><Alert v-if="failed" type="error" message="日志加载失败，请重试">
          <template #action>
            <Button size="small" @click="loadLog">重试</Button>
          </template>
</Alert><Spin :spinning="loading">
          <LogViewer :message="message" :keyword="keyword" />
        </Spin>
      </div>
    </section>
    <NodeConfigModal :node="configNode" @close="configNode = undefined" />
  </div>
</template>
