<script lang="ts" setup>
import type { ModelNavFilter } from './layer-navigation';

import { Card } from 'antdv-next';

import LayerNavTree from './layer-nav-tree.vue';

/**
 * 模型管理列表页骨架：左侧导航 + 右侧 VxeGrid。
 */
defineOptions({ name: 'ModelingModelListLayout' });

const emit = defineEmits<{ select: [] }>();
const selectedLayer = defineModel<number | string | undefined>('selectedLayer');
const navFilter = defineModel<ModelNavFilter>('navFilter', {
  default: () => ({}),
});
</script>

<template>
  <div class="flex h-full min-h-0 w-full gap-3 overflow-hidden">
    <Card
      class="h-full w-1/6 shrink-0 overflow-hidden"
      :styles="{
        body: {
          display: 'flex',
          flexDirection: 'column',
          height: '100%',
          overflow: 'hidden',
          padding: '12px',
        },
      }"
    >
      <div class="min-h-0 flex-1 overflow-hidden">
        <LayerNavTree
          v-model:selected="selectedLayer"
          v-model:filter="navFilter"
          @select="emit('select')"
        />
      </div>
    </Card>
    <div class="min-h-0 min-w-0 flex-1 overflow-hidden">
      <slot></slot>
    </div>
  </div>
</template>
