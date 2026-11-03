<script setup lang="ts">
import { onMounted, ref } from 'vue';

import { useMutationObserver, useResizeObserver } from '@vueuse/core';
import { Card } from 'antdv-next';

import SidebarCollapse from '#/components/sidebar-collapse/index.vue';

const emit = defineEmits<{ resize: [] }>();
const collapsed = defineModel<boolean>('collapsed', { default: false });
const main = ref<HTMLElement>();
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

// 筛选组件异步挂载后通知列表重算高度，防止初次打开时分页被父容器裁切。
useResizeObserver(measured, () => emit('resize'));
useMutationObserver(main, observeTableSections, {
  childList: true,
  subtree: true,
});
onMounted(observeTableSections);
</script>

<template>
  <div
    class="standard-list-layout relative flex h-full min-h-0 w-full overflow-hidden"
  >
    <Card
      v-show="!collapsed"
      class="standard-list-tree h-full w-1/6 shrink-0 overflow-hidden"
      :classes="{ body: 'subtle-tree-scrollbar' }"
      :styles="{
        body: {
          display: 'flex',
          flexDirection: 'column',
          height: '100%',
          overflow: 'auto',
          padding: '12px',
        },
      }"
    >
      <slot name="tree"></slot>
    </Card>
    <SidebarCollapse
      :collapsed="collapsed"
      label="类目"
      @toggle="collapsed = !collapsed"
    />
    <div
      ref="main"
      class="flex min-h-0 min-w-0 flex-1 flex-col gap-3 overflow-hidden"
    >
      <slot></slot>
    </div>
  </div>
</template>

<style scoped>
.standard-list-layout :deep(.vxe-body--column),
.standard-list-layout :deep(.ant-tree-title) {
  font-size: 14px;
}

.standard-list-layout :deep(.standard-tree-header) {
  display: flex;
  flex-shrink: 0;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}

.standard-list-layout :deep(.standard-tree-header > .ant-input-affix-wrapper) {
  flex: 1;
  min-width: 0;
}

.standard-list-layout :deep(.standard-tree-header > .ant-btn) {
  flex-shrink: 0;
}

.standard-list-layout :deep(.standard-tree-title) {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  max-width: 100%;
  vertical-align: middle;
}

.standard-list-layout :deep(.standard-tree-title > img) {
  flex-shrink: 0;
  width: 16px;
  height: 16px;
}

.standard-list-layout :deep(.standard-tree-title > span) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (width >= 641px) and (width <= 900px) {
  .standard-list-tree {
    width: 210px;
    min-width: 210px;
  }
}

@media (width <= 640px) {
  .standard-list-layout {
    flex-direction: column;
    overflow: auto;
  }

  .standard-list-tree {
    width: 100%;
    height: 190px;
  }

  .standard-list-layout :deep(.sidebar-collapse) {
    flex: 0 0 12px;
    width: 100%;
    height: 12px;
  }

}
</style>
