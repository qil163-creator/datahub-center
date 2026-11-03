<script lang="ts" setup>
import type { PressureLevel } from '../pipeline/runtime-metrics';

import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { computed } from 'vue';

import { Alert, Table, Tag } from 'antdv-next';

import { formatDurationMs } from '../pipeline/node-run-status';
import {
  findBottleneck,
  formatBytes,
  formatRatio,
  nodeNameMap,
  summarizeRuntimeEdges,
  throughputTrend,
} from '../pipeline/runtime-metrics';

const props = defineProps<{
  metrics?: DataforgeEtlApi.RuntimeMetrics | null;
  nodeRecords: DataforgeEtlApi.NodeRecord[];
}>();

const SPARK_WIDTH = 320;
const SPARK_HEIGHT = 48;
const LEVEL_TAGS: Record<PressureLevel, string> = {
  high: 'error',
  medium: 'warning',
  normal: 'default',
};

const edges = computed(() =>
  summarizeRuntimeEdges(props.metrics, nodeNameMap(props.nodeRecords)),
);
const bottleneck = computed(() => findBottleneck(edges.value));
const trend = computed(() => throughputTrend(props.metrics?.series));
const peak = computed(() =>
  Math.max(0, ...trend.value.map((point) => point.rowsPerSecond)),
);
const latest = computed(() => trend.value.at(-1)?.rowsPerSecond);
const sparkline = computed(() => {
  const points = trend.value;
  const first = points[0];
  const last = points.at(-1);
  if (!first || !last || points.length < 2) return '';
  const span = Math.max(1, last.elapsedMillis - first.elapsedMillis);
  const top = peak.value || 1;
  return points
    .map((point) => {
      const x =
        ((point.elapsedMillis - first.elapsedMillis) / span) * SPARK_WIDTH;
      const y =
        SPARK_HEIGHT - (point.rowsPerSecond / top) * (SPARK_HEIGHT - 4) - 2;
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');
});

const columns = [
  { title: '连线', key: 'edge' },
  { title: '当前队列', key: 'queue', width: 110 },
  { title: '上游等待（背压）', key: 'blocked', width: 150 },
  { title: '下游等待', key: 'starved', width: 130 },
  { title: '已投递行', dataIndex: 'delivered', key: 'delivered', width: 100 },
];
</script>

<template>
  <section v-if="metrics" class="runtime-observation">
    <div class="runtime-observation__head">
      <strong>运行观测</strong>
      <Tag :color="metrics.finished ? 'default' : 'processing'">
        {{ metrics.finished ? '最终快照' : '运行中采样' }}
      </Tag>
      <span class="text-muted-foreground">
        已运行 {{ formatDurationMs(metrics.elapsedMillis) }} · 累计产生
        {{ metrics.transferredRows }} 行（{{
          formatBytes(metrics.transferredBytes)
        }}） · 当前中间缓存 {{ formatBytes(metrics.bufferedBytes) }}
      </span>
    </div>
    <Alert
      v-if="bottleneck"
      class="mb-2"
      type="warning"
      show-icon
      :message="`下游【${bottleneck.targetName}】处理较慢：上游【${bottleneck.sourceName}】在该连线累计等待 ${formatDurationMs(bottleneck.blockedMillis)}，占已运行时间 ${formatRatio(bottleneck.blockedRatio)}`"
    />
    <div class="runtime-observation__trend">
      <span>吞吐趋势（行/秒）</span>
      <svg
        v-if="sparkline"
        :height="SPARK_HEIGHT"
        :viewBox="`0 0 ${SPARK_WIDTH} ${SPARK_HEIGHT}`"
        :width="SPARK_WIDTH"
        aria-label="吞吐趋势"
        role="img"
      >
        <polyline
          :points="sparkline"
          fill="none"
          stroke="#2563eb"
          stroke-width="1.5"
        />
      </svg>
      <span v-else class="text-muted-foreground">采样点不足，暂无趋势</span>
      <span v-if="latest !== undefined" class="text-muted-foreground">
        峰值 {{ peak.toFixed(1) }} · 最近 {{ latest.toFixed(1) }}
      </span>
    </div>
    <Table
      v-if="edges.length"
      :columns="columns"
      :data-source="edges"
      :pagination="false"
      row-key="key"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'edge'">
          {{ record.sourceName }} → {{ record.targetName }}
        </template>
        <Tag
          v-else-if="column.key === 'queue'"
          :color="LEVEL_TAGS[record.level as PressureLevel]"
        >
          {{ record.queued }}/{{ record.capacity }}
        </Tag>
        <template v-else-if="column.key === 'blocked'">
          {{ formatDurationMs(record.blockedMillis) }}（{{
            formatRatio(record.blockedRatio)
          }}）
        </template>
        <template v-else-if="column.key === 'starved'">
          {{ formatDurationMs(record.starvedMillis) }}（{{
            formatRatio(record.starvedRatio)
          }}）
        </template>
      </template>
    </Table>
    <p class="text-muted-foreground mt-1 text-xs">
      计数与等待时间为采样时刻的近似值，用于定位瓶颈，不作为提交、质量或计量依据。上游等待占比高说明下游处理慢；下游等待占比高说明上游供数慢。
    </p>
  </section>
  <p v-else class="text-muted-foreground mt-2 text-xs">
    该运行未记录运行观测：升级前的历史运行，或尚未产生首个采样。
  </p>
</template>

<style scoped>
.runtime-observation {
  padding: 10px 12px;
  margin-top: 12px;
  background: hsl(var(--muted) / 40%);
  border-radius: 6px;
}

.runtime-observation__head,
.runtime-observation__trend {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}
</style>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
