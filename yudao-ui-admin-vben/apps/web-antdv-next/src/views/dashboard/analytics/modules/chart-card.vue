<script lang="ts" setup>
import type { EChartsOption } from '@vben/plugins/echarts';

import { Card } from 'antdv-next';

import ChartPanel from '../../shared/chart-panel.vue';

/** 带标题的单图卡片 */
defineOptions({ name: 'AnalyticsChartCard' });

withDefaults(
  defineProps<{
    emptyText?: string;
    extra?: string;
    height?: string;
    loading: boolean;
    options?: EChartsOption;
    title: string;
  }>(),
  {
    emptyText: '暂无数据',
    extra: '',
    height: '300px',
    options: undefined,
  },
);
</script>

<template>
  <Card :title="title">
    <template v-if="extra" #extra>
      <span class="text-muted-foreground text-xs">{{ extra }}</span>
    </template>
    <ChartPanel
      :empty-text="emptyText"
      :height="height"
      :loading="loading"
      :options="options"
    />
  </Card>
</template>
