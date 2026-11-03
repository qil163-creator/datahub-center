<script setup lang="ts">
import type { IntegrationApi as Api } from '#/api/dataforge/integration';

import { computed, ref, watch } from 'vue';

import { formatDate } from '@vben/utils';

import { Button, Empty } from 'antdv-next';

import {
  cronSummary,
  labelOf,
  priorityOptions,
  readFlow,
  runOptions,
  strategyOptions,
} from '../model';
import ReferenceIcon from './reference-icon.vue';

const props = defineProps<{ task: Api.Task }>();
defineEmits<{ back: [] }>();
const tab = ref('flow');
const zoom = ref(1);
watch(
  () => props.task.id,
  () => {
    tab.value = 'flow';
    zoom.value = 1;
  },
);
const graph = computed(() => readFlow(props.task.flowJson));
const graphSize = computed(() => ({
  width: Math.max(190, ...graph.value.nodes.map((node) => node.x + 80)),
  height: Math.max(100, ...graph.value.nodes.map((node) => node.y + 90)),
}));
const engineRows = computed(() => {
  const config = props.task.engineConfig;
  if (props.task.engine === 'SPARK')
    return [
      ['Driver核心数', config.driverCores, 'Driver内存数', config.driverMemory],
      [
        'Executor数量',
        config.executorCount,
        'Executor内存数',
        config.executorMemory,
      ],
      ['Executor核心数', config.executorCores, '', ''],
    ];
  if (props.task.engine === 'FLINK')
    return [
      [
        'JobManager内存数',
        config.jobManagerMemory,
        'TaskManager内存数',
        config.taskManagerMemory,
      ],
      ['Slot数量', config.slots, 'TaskManager数量', config.taskManagerCount],
      ['并行度', config.parallelism, '', ''],
    ];
  return [
    [
      'JVM初始内存',
      `${config.jvmInitialMemory ?? '-'} MB`,
      'JVM最大内存',
      `${config.jvmMaximumMemory ?? '-'} MB`,
    ],
    ['Channel并发数', config.channel, '字节限速', config.byteSpeed],
    ['记录限速', config.recordSpeed, '脏数据上限', config.errorLimit],
  ];
});
const date = (value?: null | number) =>
  value ? formatDate(value, 'YYYY-MM-DD HH:mm') : '-';
function edgePosition(edge: { source: string; target: string }) {
  const source = graph.value.nodes.find((node) => node.id === edge.source);
  const target = graph.value.nodes.find((node) => node.id === edge.target);
  return {
    x1: (source?.x ?? 0) + 56,
    y1: (source?.y ?? 0) + 18,
    x2: (target?.x ?? 0) + 24,
    y2: (target?.y ?? 0) + 18,
  };
}
</script>

<template>
  <div class="qi-detail">
    <section class="qi-detail-header">
      <div class="qi-detail-title">
        <div>
          <b>{{ task.id }}</b><strong>{{ task.name }}</strong><span class="qi-category-tag">{{
            task.status === 1 ? '已上线' : '未上线'
          }}</span>
        </div>
        <Button size="small" @click="$emit('back')">
          <ReferenceIcon name="back" />返回
        </Button>
      </div>
      <table class="qi-info-table qi-summary-table">
        <tbody>
          <tr>
            <th>数据集成类目</th>
            <td>{{ task.categoryName || '-' }}</td>
            <th>调度状态</th>
            <td>{{ task.scheduleStatus === 1 ? '开启' : '关闭' }}</td>
            <th>责任人</th>
            <td>{{ task.ownerName || '-' }}</td>
          </tr>
          <tr>
            <th>描述</th>
            <td colspan="5">{{ task.description || '-' }}</td>
          </tr>
          <tr>
            <th>创建人</th>
            <td>{{ task.creatorName || '-' }}</td>
            <th>创建时间</th>
            <td>{{ date(task.createTime) }}</td>
            <th>更新时间</th>
            <td>{{ date(task.updateTime) }}</td>
          </tr>
        </tbody>
      </table>
    </section>
    <section class="qi-detail-body">
      <nav class="qi-tabs" aria-label="任务详情">
        <button
          :class="{ active: tab === 'flow' }"
          type="button"
          @click="tab = 'flow'"
        >
          任务流程
</button><button
          :class="{ active: tab === 'info' }"
          type="button"
          @click="tab = 'info'"
        >
          详细信息
        </button>
      </nav>
      <div v-if="tab === 'flow'" class="qi-flow-view">
        <div class="qi-flow-toolbar">
          <Button
            size="small"
            aria-label="缩小"
            @click="zoom = Math.max(0.4, zoom - 0.1)"
          >
            <ReferenceIcon name="zoom-out" />
</Button><Button
            size="small"
            aria-label="放大"
            @click="zoom = Math.min(2, zoom + 0.1)"
          >
            <ReferenceIcon name="zoom-in" />
</Button><Button size="small" aria-label="居中" @click="zoom = 1">
            <ReferenceIcon name="center" />
          </Button>
        </div>
        <div v-if="graph.nodes.length" class="qi-flow-center">
          <div
            class="qi-flow-graph"
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
                  id="qi-arrow"
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
                v-bind="edgePosition(edge)"
                stroke="#d6dbed"
                stroke-width="1"
                marker-end="url(#qi-arrow)"
              />
            </svg>
            <div
              v-for="node in graph.nodes"
              :key="node.id"
              class="qi-flow-node"
              :style="{ left: `${node.x}px`, top: `${node.y}px` }"
            >
              <span class="qi-node-icon">
                <img
                  :src="`/assets/qdata-integration/node-${node.type}.svg`"
                  alt=""
/></span><span>{{ node.label }}</span>
            </div>
          </div>
        </div>
        <Empty v-else description="尚未配置任务流程" />
      </div>
      <table v-else class="qi-info-table qi-detail-table">
        <tbody>
          <tr>
            <th>配置状态</th>
            <td>
              <span
                class="qi-run-badge"
                :class="[
                  task.configurationStatus === 'COMPLETE' ? 'success' : 'idle',
                ]"
                >{{
                  task.configurationStatus === 'COMPLETE' ? '完成' : '未配置'
                }}</span>
            </td>
            <th>策略与调度</th>
            <td>{{ cronSummary(task.cronExpression) }}</td>
          </tr>
          <tr>
            <th>执行策略</th>
            <td>{{ labelOf(strategyOptions, task.executionStrategy) }}</td>
            <th>最近运行时间</th>
            <td>{{ date(task.lastRunTime) }}</td>
          </tr>
          <tr>
            <th>最近执行结果</th>
            <td>
              {{ labelOf(runOptions, task.lastRunStatus) }}
            </td>
            <th>任务优先级</th>
            <td>{{ labelOf(priorityOptions, task.priority) }}</td>
          </tr>
          <tr>
            <th>Worker分组</th>
            <td>{{ task.workerGroup || '-' }}</td>
            <th>Yarn队列</th>
            <td>{{ task.engineConfig.yarnQueue || '-' }}</td>
          </tr>
          <tr>
            <th>失败重试次数</th>
            <td>{{ task.retryCount }}</td>
            <th>失败重试间隔</th>
            <td>{{ task.retryInterval }}</td>
          </tr>
          <tr>
            <th>延迟执行时间</th>
            <td>{{ task.delayMinutes }}</td>
            <th>执行引擎</th>
            <td>{{ task.engine }}</td>
          </tr>
          <tr v-for="(row, index) in engineRows" :key="index">
            <th>{{ row[0] }}</th>
            <td>{{ row[1] ?? '-' }}</td>
            <th v-if="row[2]">{{ row[2] }}</th>
            <td v-else class="qi-empty-cell"></td>
            <td :class="{ 'qi-empty-cell': !row[2] }">{{ row[3] ?? '-' }}</td>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>
