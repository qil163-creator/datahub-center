<script lang="ts" setup>
import type { AssetOperationApi } from '#/api/asset/operation';

import { computed, ref } from 'vue';

import { Descriptions, DescriptionsItem, Drawer, RadioGroup } from 'antdv-next';

import { getAssetOperationTrend } from '#/api/asset/operation';

import ChartPanel from '../../../../dashboard/shared/chart-panel.vue';
import {
  formatIdle,
  formatScore,
  SUGGESTIONS,
  trendOption,
  VALUE_LEVELS,
} from '../../shared';

const visible = ref(false);
const loading = ref(false);
const days = ref(30);
const metric = ref<AssetOperationApi.Metric>();
const trend = ref<AssetOperationApi.Trend>();
let seq = 0;

const options = computed(() =>
  trend.value?.points.length ? trendOption(trend.value.points) : undefined,
);

async function load() {
  const assetId = metric.value?.assetId;
  if (assetId === undefined || !visible.value) return;
  const current = ++seq;
  loading.value = true;
  try {
    const result = await getAssetOperationTrend(assetId, days.value);
    if (current === seq && visible.value) trend.value = result;
  } finally {
    if (current === seq) loading.value = false;
  }
}

function open(row: AssetOperationApi.Metric) {
  metric.value = row;
  trend.value = undefined;
  days.value = 30;
  visible.value = true;
  void load();
}

function close() {
  ++seq;
  visible.value = false;
  metric.value = undefined;
  trend.value = undefined;
}

defineExpose({ open, close });
</script>

<template>
  <Drawer
    v-model:open="visible"
    :width="860"
    destroy-on-hidden
    :title="`使用趋势 · ${metric?.assetName ?? ''}`"
    @close="close"
  >
    <template v-if="metric">
      <Descriptions :column="3" size="small" bordered class="mb-4">
        <DescriptionsItem label="热度分">
          {{ formatScore(metric.heatScore) }}
        </DescriptionsItem>
        <DescriptionsItem label="价值分">
          {{ formatScore(metric.valueScore) }}（{{
            VALUE_LEVELS[metric.valueLevel]?.label ?? metric.valueLevel
          }}）
        </DescriptionsItem>
        <DescriptionsItem label="运营建议">
          {{ SUGGESTIONS[metric.suggestion]?.label ?? metric.suggestion }}
        </DescriptionsItem>
        <DescriptionsItem label="使用广度">
          {{ formatScore(metric.breadthScore) }}
        </DescriptionsItem>
        <DescriptionsItem label="质量分">
          {{ formatScore(metric.qualityScore) }}
        </DescriptionsItem>
        <DescriptionsItem label="完整度">
          {{ formatScore(metric.completenessScore) }}
        </DescriptionsItem>
        <DescriptionsItem label="有效授权">
          {{ metric.activeGrantCount }}（{{ metric.grantDeptCount }} 个部门）
        </DescriptionsItem>
        <DescriptionsItem label="接口调用">
          {{ metric.apiCallCount }}（{{ metric.apiAppCount }} 个应用）
        </DescriptionsItem>
        <DescriptionsItem label="闲置">
          {{ formatIdle(metric.idleDays) }}
        </DescriptionsItem>
      </Descriptions>
    </template>
    <div class="mb-2 flex justify-end">
      <RadioGroup
        v-model:value="days"
        option-type="button"
        size="small"
        :options="[
          { label: '近 30 天', value: 30 },
          { label: '近 90 天', value: 90 },
          { label: '近 180 天', value: 180 },
        ]"
        @change="load"
      />
    </div>
    <ChartPanel
      :loading="loading"
      :options="options"
      height="360px"
      empty-text="暂无使用记录"
    />
    <div class="text-muted-foreground mt-2 text-xs">
      柱为每日浏览、使用（数据预览/查询）、申请次数；折线为每日快照的热度分与价值分，未生成快照的日期留空。
    </div>
  </Drawer>
</template>
