<script lang="ts" setup>
import { IconifyIcon } from '@vben/icons';

import { Card } from 'antdv-next';

/** 快捷入口：紧凑图标网格，只展示当前用户菜单里真实存在的页面 */
defineOptions({ name: 'WorkspaceQuickNav' });

defineProps<{
  items: { color: string; icon: string; title: string; url: string }[];
}>();

const emit = defineEmits<{
  navigate: [url: string];
}>();
</script>

<template>
  <Card title="快捷入口">
    <div class="grid grid-cols-4 gap-1 lg:grid-cols-3 xl:grid-cols-4">
      <button
        v-for="item in items"
        :key="item.url"
        type="button"
        class="quick-nav-item hover:bg-accent flex min-w-0 flex-col items-center gap-2 rounded-lg px-1 py-3 transition-colors"
        @click="emit('navigate', item.url)"
      >
        <span
          class="inline-flex size-10 items-center justify-center rounded-xl transition-transform"
          :style="{ backgroundColor: `${item.color}1a`, color: item.color }"
        >
          <IconifyIcon :icon="item.icon" class="size-5" />
        </span>
        <span class="w-full truncate text-center text-xs">
          {{ item.title }}
        </span>
      </button>
    </div>
  </Card>
</template>

<style scoped>
.quick-nav-item:hover > span:first-child {
  transform: translateY(-2px);
}

.quick-nav-item:focus-visible {
  outline: 2px solid hsl(var(--primary));
  outline-offset: 2px;
}
</style>
