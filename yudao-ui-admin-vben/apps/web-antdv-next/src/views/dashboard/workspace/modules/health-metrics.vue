<script lang="ts" setup>
import type { HealthLevel, HealthMetric } from '../../shared/data';
import type { RouteTarget } from '../../shared/route-resolver';

import { IconifyIcon } from '@vben/icons';

import { Card, Skeleton } from 'antdv-next';

/** 今日运行概况：任务运行 / API 调用 / 采集成功率 / 落标率 / 待处理工单 */
defineOptions({ name: 'WorkspaceHealthMetrics' });

defineProps<{
  loading: boolean;
  metrics: HealthMetric[];
  updatedAt?: string;
}>();

const emit = defineEmits<{
  navigate: [target: RouteTarget];
}>();

const LEVEL_STYLE: Record<HealthLevel, { color: string; label: string }> = {
  good: { color: '#18a058', label: '正常' },
  warn: { color: '#f59e0b', label: '关注' },
  bad: { color: '#d03050', label: '异常' },
  idle: { color: '#94a3b8', label: '暂无数据' },
  none: { color: '#94a3b8', label: '不可用' },
};
</script>

<template>
  <Card title="今日运行概况">
    <template #extra>
      <span v-if="updatedAt" class="text-muted-foreground text-xs">
        更新于 {{ updatedAt }}
      </span>
    </template>
    <Skeleton :loading="loading" active :paragraph="{ rows: 5 }">
      <ul class="divide-border -my-2 divide-y">
        <li
          v-for="metric in metrics"
          :key="metric.key"
          class="hover:bg-accent/60 -mx-2 flex cursor-pointer items-center gap-3 rounded-md px-2 py-3"
          @click="metric.target && emit('navigate', metric.target)"
        >
          <span
            class="size-2.5 shrink-0 rounded-full"
            :style="{ backgroundColor: LEVEL_STYLE[metric.level].color }"
          ></span>
          <div class="min-w-0 flex-1">
            <div class="flex items-center gap-2 text-sm font-medium">
              <span>{{ metric.title }}</span>
              <span
                class="rounded px-1.5 py-0.5 text-[11px] leading-none"
                :style="{
                  backgroundColor: `${LEVEL_STYLE[metric.level].color}1a`,
                  color: LEVEL_STYLE[metric.level].color,
                }"
              >
                {{ LEVEL_STYLE[metric.level].label }}
              </span>
            </div>
            <div class="text-muted-foreground mt-1 truncate text-xs">
              {{ metric.description }}
            </div>
          </div>
          <div class="shrink-0 text-right text-lg font-semibold tabular-nums">
            {{ metric.value }}
          </div>
          <IconifyIcon
            icon="lucide:chevron-right"
            class="text-muted-foreground/60 size-4 shrink-0"
          />
        </li>
      </ul>
    </Skeleton>
  </Card>
</template>
