<script lang="ts" setup>
import type { OverviewMetric } from '../data';

import { CountTo } from '@vben/common-ui';

import { Card } from 'antdv-next';

import MetricIcon from './metric-icon.vue';

defineOptions({ name: 'SupplyOverviewMetricCards' });

defineProps<{
  items: OverviewMetric[];
}>();
</script>

<template>
  <div
    class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6"
  >
    <Card v-for="item in items" :key="item.title">
      <div class="flex items-center justify-between gap-3">
        <div class="min-w-0">
          <CountTo
            class="text-2xl font-semibold leading-none"
            :duration="900"
            :end-val="item.value"
            :style="{ color: item.color }"
          />
          <div class="text-muted-foreground mt-2 truncate text-sm">
            {{ item.title }}
          </div>
        </div>
        <MetricIcon
          class="size-10 shrink-0"
          :color="item.color"
          :icon="item.icon"
        />
      </div>
    </Card>
  </div>
</template>
