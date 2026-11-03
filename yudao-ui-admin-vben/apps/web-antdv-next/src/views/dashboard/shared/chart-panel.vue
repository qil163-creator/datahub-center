<script lang="ts" setup>
import type { EChartsOption, EchartsUIType } from '@vben/plugins/echarts';

import { ref, watch } from 'vue';

import { EchartsUI, useEcharts } from '@vben/plugins/echarts';

import { Empty, Skeleton } from 'antdv-next';

/**
 * 图表面板：options 为空时展示空态（不渲染图表，避免对隐藏元素反复重试），
 * loading 时展示骨架屏。图表容器始终占位，切换数据时高度不跳动。
 */
defineOptions({ name: 'DashboardChartPanel' });

const props = withDefaults(
  defineProps<{
    emptyText?: string;
    height?: string;
    loading?: boolean;
    /** undefined 表示数据不可用 / 为空 */
    options?: EChartsOption;
  }>(),
  {
    emptyText: '暂无数据',
    height: '320px',
    loading: false,
    options: undefined,
  },
);

const chartRef = ref<EchartsUIType>();
const { renderEcharts } = useEcharts(chartRef);

watch(
  () => [props.options, props.loading] as const,
  ([options, loading]) => {
    if (!loading && options) {
      renderEcharts(options);
    }
  },
  { immediate: true },
);
</script>

<template>
  <div class="relative" :style="{ height }">
    <Skeleton
      v-if="loading"
      active
      :paragraph="{ rows: 6 }"
      :title="false"
      class="pt-2"
    />
    <template v-else>
      <EchartsUI v-show="!!options" ref="chartRef" :height="height" />
      <div
        v-if="!options"
        class="absolute inset-0 flex items-center justify-center"
      >
        <Empty :description="emptyText" />
      </div>
    </template>
  </div>
</template>
