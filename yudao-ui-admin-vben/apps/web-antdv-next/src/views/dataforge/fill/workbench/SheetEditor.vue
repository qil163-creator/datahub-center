<script lang="ts" setup>
import type { DomainOperation, FillFieldSchema, FillRowData } from './types';

import { onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { UniverSheetAdapter } from './univer-adapter';

defineOptions({ name: 'DataforgeFillSheetEditor' });

const props = defineProps<{
  fields: FillFieldSchema[];
  readonly?: boolean;
  rows: FillRowData[];
}>();

const emit = defineEmits<{
  operation: [op: DomainOperation];
  ready: [elapsedMs: number];
  selection: [cell?: { fieldCode: string; rowId: string }];
}>();

const containerRef = ref<HTMLDivElement>();

// Univer 实例绝不能被 Vue 响应式代理，用普通变量持有
let adapter: null | UniverSheetAdapter = null;

onMounted(() => {
  if (!containerRef.value) return;
  const start = performance.now();
  adapter = new UniverSheetAdapter({
    container: containerRef.value,
    fields: props.fields,
    onOperation: (op) => emit('operation', op),
    onSelectionChange: (cell) => emit('selection', cell),
    readonly: props.readonly,
    rows: props.rows,
  });
  emit('ready', Math.round(performance.now() - start));
});

onBeforeUnmount(() => {
  adapter?.dispose();
  adapter = null;
});

watch(
  () => props.readonly,
  (value) => adapter?.setReadonly(!!value),
);

defineExpose({
  getAdapter: () => adapter,
});
</script>

<template>
  <div ref="containerRef" class="size-full"></div>
</template>
