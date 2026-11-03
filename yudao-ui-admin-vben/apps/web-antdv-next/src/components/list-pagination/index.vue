<script setup lang="ts">
import { defineAsyncComponent } from 'vue';

const props = withDefaults(
  defineProps<{
    current?: number;
    pageSize?: number;
    pageSizeOptions?: number[];
    total: number;
  }>(),
  {
    current: 1,
    pageSize: 10,
    pageSizeOptions: () => [10, 20, 30, 50, 100, 200],
  },
);
const emit = defineEmits<{
  change: [page: number, pageSize: number];
  'update:current': [page: number];
  'update:pageSize': [pageSize: number];
}>();
// 独立列表按需加载分页，避免仅解析页面路由时拉入整套 Grid 与表单适配器。
const Pager = defineAsyncComponent(async () => {
  const { NativeVxePager } = await import('#/adapter/vxe-table');
  if (!NativeVxePager.value) throw new Error('原生分页尚未注册');
  return NativeVxePager.value;
});
const layouts = [
  'Total',
  'Sizes',
  'Home',
  'PrevJump',
  'PrevPage',
  'Number',
  'NextPage',
  'NextJump',
  'End',
];
function change(value: { currentPage: number; pageSize: number }) {
  const page = value.pageSize === props.pageSize ? value.currentPage : 1;
  emit('update:current', page);
  emit('update:pageSize', value.pageSize);
  emit('change', page, value.pageSize);
}
</script>

<template>
  <Pager
    class="list-pagination"
    :current-page="current"
    :page-size="pageSize"
    :page-sizes="pageSizeOptions"
    :total="total"
    :layouts="layouts"
    background
    size="mini"
    @page-change="change"
  />
</template>

<style scoped>
.list-pagination {
  --vxe-ui-font-color: hsl(var(--foreground));
  --vxe-ui-font-primary-color: hsl(var(--primary));
  --vxe-ui-font-disabled-color: hsl(var(--foreground) / 50%);
  --vxe-ui-layout-background-color: hsl(var(--background));
  --vxe-ui-input-border-color: hsl(var(--border));

  flex: none;
  width: 100%;
  height: auto;
  margin-top: 8px;
}

.list-pagination :deep(.vxe-pager--wrapper) {
  flex-wrap: wrap;
  row-gap: 8px;
  height: auto;
}
</style>
