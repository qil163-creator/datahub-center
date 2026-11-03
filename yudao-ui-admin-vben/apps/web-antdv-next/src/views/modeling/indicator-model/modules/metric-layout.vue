<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue';

import { useMutationObserver, useResizeObserver } from '@vueuse/core';
import { Card } from 'antdv-next';

import SidebarCollapse from '#/components/sidebar-collapse/index.vue';

/** 指标页面共用内容区，保持导航、筛选和列表各自的真实交互。 */
defineOptions({ name: 'ModelingMetricLayout' });
const emit = defineEmits<{ resize: [] }>();
const main = ref<HTMLElement>();
const collapsed = ref(false);
async function toggleSidebar() {
  collapsed.value = !collapsed.value;
  await nextTick();
  emit('resize');
}
const measured = ref<HTMLElement[]>([]);

function observeTableSections() {
  const sections = [
    ...(main.value?.querySelectorAll<HTMLElement>(
      '.vxe-grid--form-wrapper, .vxe-grid--toolbar-wrapper, .vxe-grid--pager-wrapper',
    ) ?? []),
  ];
  if (
    sections.length !== measured.value.length ||
    sections.some((section, index) => section !== measured.value[index])
  ) {
    measured.value = sections;
  }
}

// 异步筛选组件挂载后重新计算表格高度，避免初次测量遗漏搜索区或分页的真实高度。
useResizeObserver(measured, () => emit('resize'));
useMutationObserver(main, observeTableSections, {
  childList: true,
  subtree: true,
});
onMounted(observeTableSections);
</script>

<template>
  <div class="metric-layout flex h-full min-h-0 w-full overflow-hidden">
    <Card
      v-show="!collapsed"
      class="metric-layout__tree h-full shrink-0 overflow-hidden"
      :styles="{
        body: {
          display: 'flex',
          flexDirection: 'column',
          height: '100%',
          overflow: 'hidden',
          padding: '8px',
        },
      }"
    >
      <slot name="tree"></slot>
    </Card>
    <SidebarCollapse :collapsed="collapsed" label="类目" @toggle="toggleSidebar" />
    <main
      ref="main"
      class="flex min-h-0 min-w-0 flex-1 flex-col gap-3 overflow-hidden"
    >
      <slot></slot>
    </main>
  </div>
</template>

<style scoped>
.metric-layout__tree {
  width: 242px;
  min-width: 242px;
  border: 0;
  border-radius: var(--radius);
}

@media (max-width: 1024px) {
  .metric-layout__tree {
    width: 190px;
    min-width: 190px;
  }
}

@media (max-width: 640px) {
  .metric-layout {
    flex-direction: column;
  }

  .metric-layout :deep(.sidebar-collapse) {
    flex-basis: 12px;
    width: 100%;
    min-height: 12px;
  }


  .metric-layout__tree {
    width: 100%;
    height: 190px;
    max-height: 190px;
  }
}
</style>
