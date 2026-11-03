<script lang="ts" setup>
import type { RunItem } from '../../shared/data';
import type { RouteTarget } from '../../shared/route-resolver';

import { formatPast } from '@vben/utils';

import { Button, Card, Empty, Skeleton, Tag } from 'antdv-next';

import { DASHBOARD_TARGETS } from '../../shared/route-resolver';

/** 最近运行动态：开发任务实例 + 数据同步执行记录 */
defineOptions({ name: 'WorkspaceRecentRuns' });

defineProps<{
  /** 两个来源都不可用（无权限 / 服务未启动） */
  available: boolean;
  items: RunItem[];
  loading: boolean;
}>();

const emit = defineEmits<{
  navigate: [target: RouteTarget];
}>();

function relativeTime(time?: number | string) {
  return time ? formatPast(new Date(time), 'MM-DD HH:mm') : '-';
}
</script>

<template>
  <Card title="最近运行动态">
    <template #extra>
      <Button
        size="small"
        type="link"
        @click="emit('navigate', DASHBOARD_TARGETS.devInstance)"
      >
        任务实例
      </Button>
    </template>
    <Skeleton :loading="loading" active :paragraph="{ rows: 6 }">
      <Empty
        v-if="items.length === 0"
        :description="
          available
            ? '暂无任务运行记录'
            : '任务运行记录不可用（无权限或服务未启动）'
        "
      />
      <ul v-else class="divide-border -my-1 divide-y">
        <li
          v-for="item in items"
          :key="item.key"
          class="hover:bg-accent/60 -mx-2 flex cursor-pointer items-center gap-3 rounded-md px-2 py-2.5"
          @click="emit('navigate', item.target)"
        >
          <Tag
            :color="item.source === 'dev' ? 'purple' : 'cyan'"
            class="!mr-0 shrink-0"
          >
            {{ item.sourceLabel }}
          </Tag>
          <div class="min-w-0 flex-1">
            <div class="truncate text-sm font-medium">{{ item.name }}</div>
            <div class="text-muted-foreground mt-0.5 truncate text-xs">
              {{ item.trigger }} · 耗时 {{ item.duration }}
            </div>
          </div>
          <span class="text-muted-foreground shrink-0 text-xs tabular-nums">
            {{ relativeTime(item.startTime) }}
          </span>
          <Tag
            :color="item.statusColor"
            class="!mr-0 w-16 shrink-0 text-center"
          >
            {{ item.statusLabel }}
          </Tag>
        </li>
      </ul>
    </Skeleton>
  </Card>
</template>
