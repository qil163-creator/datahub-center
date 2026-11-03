<script lang="ts" setup>
import type { EChartsOption } from '@vben/plugins/echarts';

import { Card } from 'antdv-next';

import ChartPanel from '../../dashboard/shared/chart-panel.vue';

/** 撑满父级剩余高度的图卡片（大盘用，避免 ChartCard 固定 300px 留白） */
defineOptions({ name: 'GovernanceFillChartCard' });

withDefaults(
  defineProps<{
    emptyText?: string;
    extra?: string;
    loading?: boolean;
    options?: EChartsOption;
    title: string;
  }>(),
  {
    emptyText: '暂无数据',
    extra: '',
    loading: false,
    options: undefined,
  },
);
</script>

<template>
  <Card :title="title" class="fill-chart-card" size="small">
    <template v-if="$slots.extra || extra" #extra>
      <slot name="extra">
        <span class="text-muted-foreground text-xs">{{ extra }}</span>
      </slot>
    </template>
    <div class="fill-chart-card__body">
      <slot>
        <ChartPanel
          :empty-text="emptyText"
          :loading="loading"
          :options="options"
          height="100%"
        />
      </slot>
    </div>
  </Card>
</template>

<style scoped>
.fill-chart-card {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.fill-chart-card :deep(.ant-card-head) {
  flex-shrink: 0;
  min-height: 40px;
}

.fill-chart-card :deep(.ant-card-body) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  padding: 8px 12px 12px;
}

.fill-chart-card__body {
  position: relative;
  flex: 1;
  min-height: 0;
}

.fill-chart-card__body > :deep(*) {
  height: 100%;
}
</style>
