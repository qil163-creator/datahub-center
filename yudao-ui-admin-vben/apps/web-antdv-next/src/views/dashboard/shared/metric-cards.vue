<script lang="ts" setup>
import type { MetricCardItem } from './data';
import type { RouteTarget } from './route-resolver';

import { CountTo } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import { Card, Skeleton, Tooltip } from 'antdv-next';

/** 核心指标卡片（治理总览 / 资产盘点复用） */
defineOptions({ name: 'DashboardMetricCards' });

defineProps<{
  items: MetricCardItem[];
  loading: boolean;
}>();

const emit = defineEmits<{
  navigate: [target: RouteTarget];
}>();
</script>

<template>
  <div class="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-6">
    <Card
      v-for="item in items"
      :key="item.key"
      :class="{ 'cursor-pointer': !!item.target }"
      class="metric-card"
      @click="item.target && emit('navigate', item.target)"
    >
      <Skeleton :loading="loading" active :paragraph="{ rows: 1 }">
        <div class="flex items-start justify-between gap-3">
          <div class="min-w-0">
            <div class="text-muted-foreground truncate text-sm">
              {{ item.title }}
            </div>
            <div class="mt-2 text-2xl font-semibold leading-none tabular-nums">
              <template v-if="item.value !== undefined">
                <CountTo :duration="900" :end-val="item.value" />
                <span
                  v-if="item.unit"
                  class="text-muted-foreground ml-1 text-xs font-normal"
                >
                  {{ item.unit }}
                </span>
              </template>
              <Tooltip v-else title="该模块暂不可用或无访问权限">
                <span class="text-muted-foreground">--</span>
              </Tooltip>
            </div>
            <div class="text-muted-foreground mt-2 truncate text-xs">
              {{ item.hint }}
            </div>
          </div>
          <span
            class="inline-flex size-10 shrink-0 items-center justify-center rounded-xl"
            :style="{ backgroundColor: `${item.color}1a`, color: item.color }"
          >
            <IconifyIcon :icon="item.icon" class="size-[1.375rem]" />
          </span>
        </div>
      </Skeleton>
    </Card>
  </div>
</template>

<style scoped>
.metric-card {
  transition: box-shadow 0.2s;
}

.metric-card:hover {
  box-shadow: 0 6px 16px rgb(47 119 241 / 12%);
}
</style>
