<script lang="ts" setup>
import type { TodoKind, TodoSummary } from '../../shared/data';
import type { RouteTarget } from '../../shared/route-resolver';

import { Button, Card, Empty, Skeleton, Tag } from 'antdv-next';

import { DASHBOARD_TARGETS } from '../../shared/route-resolver';

/** 待办事项：流程审批 + 质量工单 + 元数据采集失败 */
defineOptions({ name: 'WorkspaceTodoPanel' });

defineProps<{
  loading: boolean;
  todo: TodoSummary;
}>();

const emit = defineEmits<{
  navigate: [target: RouteTarget];
}>();

const KIND_TAG: Record<TodoKind, { color: string; label: string }> = {
  approval: { color: 'blue', label: '审批' },
  workOrder: { color: 'orange', label: '工单' },
  collectFail: { color: 'red', label: '采集失败' },
};
</script>

<template>
  <Card title="待办事项">
    <template #extra>
      <Button
        size="small"
        type="link"
        @click="emit('navigate', DASHBOARD_TARGETS.bpmTodo)"
      >
        全部审批
      </Button>
    </template>
    <Skeleton :loading="loading" active :paragraph="{ rows: 5 }">
      <Empty v-if="todo.items.length === 0" description="暂无待办，一切正常" />
      <ul v-else class="divide-border -my-1 divide-y">
        <li
          v-for="item in todo.items"
          :key="item.key"
          class="hover:bg-accent/60 -mx-2 flex cursor-pointer items-start gap-3 rounded-md px-2 py-2.5"
          @click="emit('navigate', item.target)"
        >
          <Tag :color="KIND_TAG[item.kind].color" class="!mr-0 mt-0.5 shrink-0">
            {{ KIND_TAG[item.kind].label }}
          </Tag>
          <div class="min-w-0 flex-1">
            <div class="truncate text-sm font-medium">{{ item.title }}</div>
            <div class="text-muted-foreground mt-0.5 truncate text-xs">
              {{ item.description }}
            </div>
          </div>
          <span
            v-if="item.time"
            class="text-muted-foreground mt-0.5 shrink-0 text-xs tabular-nums"
          >
            {{ item.time }}
          </span>
        </li>
      </ul>
    </Skeleton>
  </Card>
</template>
