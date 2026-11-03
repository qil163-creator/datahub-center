<script lang="ts" setup>
import type { EChartsOption } from '@vben/plugins/echarts';

import type { DashboardApi } from '#/api/dashboard';
import type { MetaOverview } from '#/api/governance/metaobject';
import type { SupplyApiLogApi } from '#/api/supply/apilog';

import { computed, ref } from 'vue';

import { Card, RadioButton, RadioGroup } from 'antdv-next';

import {
  getAccessTrendOptions,
  getApiTrendOptions,
  getMetaChangeTrendOptions,
  getQualityTrendOptions,
  getWorkOrderTrendOptions,
} from '../../shared/chart-options';
import ChartPanel from '../../shared/chart-panel.vue';

/**
 * 运行趋势：API 调用 / 质量检核 / 质量工单 / 元数据变更 / 敏感数据访问。
 * 同一时刻只渲染当前 Tab 的图表，避免对隐藏容器反复重试。
 */
defineOptions({ name: 'AnalyticsTrendCard' });

const props = defineProps<{
  apiStats?: SupplyApiLogApi.Statistics;
  loading: boolean;
  meta?: MetaOverview;
  qualityTrend?: DashboardApi.QualityTrendPoint[];
  safety?: DashboardApi.SafetySummary;
  workOrderStats?: DashboardApi.WorkOrderStatistics;
}>();

/** 天数只影响 API 调用 / 质量检核 / 质量工单三个可按天查询的接口 */
const days = defineModel<number>('days', { default: 7 });

type TrendKey = 'access' | 'api' | 'meta' | 'quality' | 'workOrder';

const activeKey = ref<TrendKey>('api');

const TABS: { key: TrendKey; label: string }[] = [
  { key: 'api', label: 'API 调用趋势' },
  { key: 'quality', label: '质量检核趋势' },
  { key: 'workOrder', label: '质量工单趋势' },
  { key: 'meta', label: '元数据变更趋势' },
  { key: 'access', label: '敏感数据访问趋势' },
];

const DAYS_SELECTABLE: ReadonlySet<TrendKey> = new Set(['api', 'quality', 'workOrder']);

/** 当前 Tab 是否支持按天数切换 */
const daysSelectable = computed(() => DAYS_SELECTABLE.has(activeKey.value));

const rangeText = computed(() => {
  switch (activeKey.value) {
    case 'api':
    case 'quality':
    case 'workOrder': {
      return `近 ${days.value} 天`;
    }
    case 'meta': {
      return '近 30 天';
    }
    default: {
      return '';
    }
  }
});

function nonEmpty<T>(list?: T[]): list is T[] {
  return Array.isArray(list) && list.length > 0;
}

const options = computed<EChartsOption | undefined>(() => {
  switch (activeKey.value) {
    case 'access': {
      const trend = props.safety?.accessTrend;
      return nonEmpty(trend) ? getAccessTrendOptions(trend) : undefined;
    }
    case 'api': {
      const trend = props.apiStats?.trend;
      return nonEmpty(trend) ? getApiTrendOptions(trend) : undefined;
    }
    case 'meta': {
      const trend = props.meta?.changeTrend;
      return nonEmpty(trend) ? getMetaChangeTrendOptions(trend) : undefined;
    }
    case 'quality': {
      const trend = props.qualityTrend;
      return nonEmpty(trend) ? getQualityTrendOptions(trend) : undefined;
    }
    case 'workOrder': {
      const trend = props.workOrderStats?.trend;
      return nonEmpty(trend) ? getWorkOrderTrendOptions(trend) : undefined;
    }
    default: {
      return undefined;
    }
  }
});

const emptyText = computed(() => {
  const unavailable = {
    access: props.safety === undefined,
    api: props.apiStats === undefined,
    meta: props.meta === undefined,
    quality: props.qualityTrend === undefined,
    workOrder: props.workOrderStats === undefined,
  }[activeKey.value];
  return unavailable ? '该统计暂不可用（无权限或服务未启动）' : '暂无趋势数据';
});
</script>

<template>
  <Card v-model:active-tab-key="activeKey" :tab-list="TABS">
    <template #tabBarExtraContent>
      <div class="flex items-center gap-3">
        <span v-if="rangeText" class="text-muted-foreground text-xs">
          {{ rangeText }}
        </span>
        <RadioGroup
          v-if="daysSelectable"
          v-model:value="days"
          :disabled="loading"
          size="small"
        >
          <RadioButton :value="7">7 天</RadioButton>
          <RadioButton :value="15">15 天</RadioButton>
          <RadioButton :value="30">30 天</RadioButton>
        </RadioGroup>
      </div>
    </template>
    <ChartPanel
      :empty-text="emptyText"
      :loading="loading"
      :options="options"
      height="340px"
    />
  </Card>
</template>
