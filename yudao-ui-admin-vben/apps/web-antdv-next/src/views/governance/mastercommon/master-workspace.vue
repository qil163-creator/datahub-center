<script setup lang="ts">
import { ref } from 'vue';

import { ColPage } from '@vben/common-ui';

import { useMediaQuery, useResizeObserver } from '@vueuse/core';
import { Card } from 'antdv-next';

import SidebarCollapse from '#/components/sidebar-collapse/index.vue';

const small = useMediaQuery('(max-width: 640px)');
const page = ref<InstanceType<typeof ColPage>>();
const mobileCollapsed = ref(false);
const sidebar = ref<HTMLElement>();
const sidebarWidth = ref(0);
useResizeObserver(sidebar, ([entry]) => {
  if (entry) sidebarWidth.value = entry.contentRect.width;
});
function toggleSidebar() {
  if (sidebarWidth.value < 1) page.value?.expandLeft();
  else page.value?.collapseLeft();
}
const sidebarStyles = {
  body: { height: '100%', padding: '0', overflow: 'hidden' },
};
</script>

<template>
  <div class="master-workspace">
    <div v-if="small" class="master-workspace__mobile">
      <Card v-show="!mobileCollapsed" class="max-h-52 shrink-0 overflow-auto" :styles="sidebarStyles">
        <slot name="sidebar"></slot>
      </Card>
      <SidebarCollapse class="master-workspace__mobile-toggle" :collapsed="mobileCollapsed" label="主数据域" @toggle="mobileCollapsed = !mobileCollapsed" />
      <div class="min-h-0 min-w-0 flex-1"><slot></slot></div>
    </div>
    <ColPage
      v-else
      ref="page"
      class="w-full"
      auto-content-height
      content-class="p-0"
      :left-width="18"
      :left-min-width="12"
      :left-max-width="30"
      :left-collapsed-width="0"
      left-collapsible
      :right-width="82"
      :right-min-width="50"
    >
      <template #left>
        <div ref="sidebar" class="h-full min-w-0">
          <Card class="h-full overflow-hidden border-0" :styles="sidebarStyles">
            <slot name="sidebar"></slot>
          </Card>
        </div>
      </template>
      <slot></slot>
    </ColPage>
    <SidebarCollapse
      v-if="!small"
      class="master-workspace__toggle"
      :style="{ left: `${Math.max(0, sidebarWidth)}px` }"
      :collapsed="sidebarWidth < 1"
      label="主数据域"
      @toggle="toggleSidebar"
    />
  </div>
</template>

<style scoped>
.master-workspace {
  position: relative;
}

.master-workspace__mobile {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.master-workspace__mobile-toggle {
  flex: 0 0 12px;
  width: 100%;
  height: 12px;
}

.master-workspace :deep(.pr-2) {
  padding-right: 6px;
}

.master-workspace :deep(.pl-2) {
  padding-left: 6px;
}

.master-workspace__toggle {
  position: absolute;
  top: 0;
  bottom: 0;
  z-index: 20;
}
</style>
