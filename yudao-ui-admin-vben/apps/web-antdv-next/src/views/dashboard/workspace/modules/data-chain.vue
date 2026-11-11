<script lang="ts" setup>
import type { ChainStage } from '../../shared/data';
import type { RouteTarget } from '../../shared/route-resolver';

import { CountTo } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import { Card, Skeleton, Tooltip } from 'antdv-next';

/** 数据全链路总览：数据源 → 集成 → 元数据 → 建模 → 资产 → 服务 */
defineOptions({ name: 'WorkspaceDataChain' });

defineProps<{
  loading: boolean;
  stages: ChainStage[];
}>();

const emit = defineEmits<{
  navigate: [target: RouteTarget];
}>();
</script>

<template>
  <Card title="数据全链路总览">
    <template #extra>
      <span class="text-muted-foreground hidden text-xs sm:inline">
        数据源 → 集成 → 元数据 → 建模 → 资产 → 服务
      </span>
    </template>
    <Skeleton :loading="loading" active :paragraph="{ rows: 3 }">
      <!-- 说明文字最多两行，悬停可看全文；超宽屏才排成一行并显示箭头 -->
      <div class="grid grid-cols-2 gap-3 sm:grid-cols-3 2xl:grid-cols-6">
        <div
          v-for="(stage, index) in stages"
          :key="stage.key"
          :title="stage.hint"
          class="chain-stage group border-border relative flex cursor-pointer flex-col rounded-lg border p-3.5 transition-shadow hover:shadow-md"
          @click="emit('navigate', stage.target)"
        >
          <div class="flex items-center justify-between">
            <span
              class="inline-flex size-9 items-center justify-center rounded-lg"
              :style="{
                backgroundColor: `${stage.color}1a`,
                color: stage.color,
              }"
            >
              <IconifyIcon :icon="stage.icon" class="size-5" />
            </span>
            <span class="text-muted-foreground text-xs">
              {{ String(index + 1).padStart(2, '0') }}
            </span>
          </div>
          <div class="mt-3 text-2xl font-semibold leading-none">
            <CountTo
              v-if="stage.value !== undefined"
              :duration="900"
              :end-val="stage.value"
            />
            <Tooltip v-else title="该模块暂不可用或无访问权限">
              <span class="text-muted-foreground">--</span>
            </Tooltip>
          </div>
          <div class="mt-2 text-sm font-medium">{{ stage.title }}</div>
          <div class="text-muted-foreground mt-1 line-clamp-2 text-xs">
            {{ stage.hint }}
          </div>
          <IconifyIcon
            v-if="index < stages.length - 1"
            icon="lucide:chevron-right"
            class="chain-stage__arrow text-muted-foreground/60 absolute top-1/2 -right-3.5 hidden size-4 -translate-y-1/2 2xl:block"
          />
        </div>
      </div>
    </Skeleton>
  </Card>
</template>

<style scoped>
.chain-stage:hover {
  border-color: hsl(var(--primary) / 45%);
}

.chain-stage__arrow {
  z-index: 1;
}
</style>
