<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue';

import { useMutationObserver, useResizeObserver } from '@vueuse/core';
import { Table } from 'antdv-next';

defineOptions({ inheritAttrs: false });
const props = withDefaults(
  defineProps<{
    scroll?: { x?: number | string | true; y?: number | string };
    size?: 'large' | 'middle' | 'small';
  }>(),
  { scroll: () => ({}), size: 'small' },
);
const viewport = ref<HTMLElement>();
const observed = ref<HTMLElement[]>([]);
const bodyHeight = ref(1);

function measure() {
  const host = viewport.value;
  const body = host?.querySelector<HTMLElement>('.ant-table-body');
  if (!host || !body || !host.clientHeight) return;
  // 按实际容器扣除表头，搜索换行、窗口缩放、页签切换均随布局重新测量。
  const bottom =
    host.getBoundingClientRect().top + host.clientTop + host.clientHeight;
  bodyHeight.value = Math.max(
    1,
    Math.floor(bottom - body.getBoundingClientRect().top),
  );
}
function observe() {
  const host = viewport.value;
  const targets = [
    host,
    host?.querySelector<HTMLElement>('.ant-table-header'),
  ].filter((value): value is HTMLElement => !!value);
  if (
    targets.length !== observed.value.length ||
    targets.some((value, index) => value !== observed.value[index])
  ) {
    observed.value = targets;
  }
  void nextTick(measure);
}
useResizeObserver(observed, measure);
useMutationObserver(viewport, observe, { childList: true, subtree: true });
onMounted(observe);
</script>

<template>
  <div
    ref="viewport"
    class="table-viewport"
    :style="{ '--table-body-height': `${bodyHeight}px` }"
  >
    <Table
      v-bind="$attrs"
      :size="props.size"
      :scroll="{ ...props.scroll, y: bodyHeight }"
    >
      <template v-for="(_, name) in $slots" #[name]="data">
        <slot :name="name" v-bind="data ?? {}"></slot>
      </template>
    </Table>
  </div>
</template>

<style scoped>
.table-viewport {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}

.table-viewport :deep(.ant-table-body) {
  min-height: var(--table-body-height);
  overscroll-behavior: contain;
}

.table-viewport :deep(.ant-table-container) {
  overflow: hidden;
  border: 1px solid hsl(var(--border));
  border-radius: var(--radius);
}

.table-viewport :deep(.ant-table-thead > tr > th) {
  color: hsl(var(--foreground));
  white-space: nowrap;
  background: hsl(var(--accent));
  border-bottom: 1px solid hsl(var(--border));
}

.table-viewport :deep(.ant-table-thead > tr > th:not(:last-child)::before) {
  background-color: hsl(var(--border));
}

.table-viewport :deep(.ant-table-tbody > tr > td) {
  overflow-wrap: anywhere;
}
</style>
