<script lang="ts" setup generic="Row extends { id: number | string }">
import { computed, ref, watch } from 'vue';

import { Alert, Button } from 'antdv-next';

import { NativeVxeButton, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import ReferencePagination from '#/views/governance/standardcommon/standard-pagination.vue';

import '../category/reference.css';

export interface SafetyColumn {
  key: string;
  title: string;
  width?: number;
  minWidth?: number;
  align?: 'center' | 'left' | 'right';
  fixed?: 'left' | 'right';
  sorter?: boolean;
}
const props = defineProps<{
  columns: SafetyColumn[];
  compact?: boolean;
  error?: string;
  filtersCollapsed?: boolean;
  height?: string;
  loading?: boolean;
  minWidth?: number;
  rows: Row[];
  selection?: boolean;
  selectionWidth?: number;
  showSearch?: boolean;
  title?: string;
  total: number;
}>();
const emit = defineEmits<{
  change: [];
  detail: [row: Row];
  refresh: [];
  sort: [value: unknown];
}>();
const page = defineModel<number>('page', { required: true });
const pageSize = defineModel<number>('pageSize', { required: true });
const selected = defineModel<string[]>('selected', { default: () => [] });
const filtersVisible = ref(!props.filtersCollapsed);
const gridColumns = computed(() => [
  ...(props.selection ? [{ type: 'checkbox' as const, width: props.selectionWidth || 45 }] : []),
  ...props.columns.map((column) => ({
    field: column.key,
    title: column.title,
    width: column.width,
    minWidth: column.minWidth,
    align: column.align,
    fixed: column.fixed,
    sortable: column.sorter,
    slots: { default: 'body' },
  })),
]);
const [Grid, gridApi] = useVbenVxeGrid<Row>({
  gridOptions: {
    columns: gridColumns.value,
    data: [],
    height: props.height || 'auto',
    pagerConfig: { enabled: false },
    proxyConfig: { enabled: false },
    rowConfig: { keyField: 'id', isHover: true },
    sortConfig: { remote: true },
    showOverflow: false,
    toolbarConfig: {
      refresh: true,
      refreshOptions: { queryMethod: () => emit('refresh') },
      custom: true,
      zoom: false,
    },
  },
  gridEvents: {
    checkboxChange: ({ records }: { records: Row[] }) => { selected.value = records.map((row) => String(row.id)); },
    checkboxAll: ({ records }: { records: Row[] }) => { selected.value = records.map((row) => String(row.id)); },
    sortChange: ({ field, order }: { field: string; order: null | string }) => emit('sort', { columnKey: field, order: order === 'asc' ? 'ascend' : order === 'desc' ? 'descend' : undefined }),
    cellDblclick: ({ row }: { row: Row }) => emit('detail', row),
  },
});
watch(() => props.loading, (value) => gridApi.setLoading(!!value), { immediate: true });
watch(gridColumns, (value) => gridApi.setGridOptions({ columns: value }), { deep: true });
watch(selected, (value) => { if (!value.length) void gridApi.grid?.clearCheckboxRow(); });
</script>

<template>
  <div class="qdata-category safety-workbench">
    <main class="category-main">
      <section
        v-if="$slots.filters"
        v-show="filtersVisible"
        class="category-filters"
      >
        <slot name="filters"></slot>
      </section>
      <section class="category-results" :class="{ 'safety-compact-results': compact }">
        <Alert v-if="error" :message="error" type="error">
          <template #action><Button size="small" @click="emit('refresh')">重试</Button></template>
        </Alert>
        <Grid class="safety-grid" :table-title="title" :table-data="rows">
          <template #toolbar-tools>
            <slot name="actions"></slot>
            <NativeVxeButton
              v-if="$slots.filters || showSearch"
              class="ml-2"
              aria-label="显示或隐藏查询"
              title="搜索"
              icon="vxe-icon-search"
              circle
              :status="filtersVisible ? 'primary' : undefined"
              :aria-pressed="filtersVisible"
              @click="filtersVisible = !filtersVisible"
            />
          </template>
          <template #body="{ row, column }">
            <slot name="bodyCell" :column="props.columns.find((item) => item.key === column.field)!" :record="row">
              {{ row[column.field as keyof Row] ?? '-' }}
            </slot>
          </template>
          <template v-if="$slots.empty" #empty><slot name="empty"></slot></template>
          <template #pager>
            <ReferencePagination v-model:page="page" v-model:page-size="pageSize" :total="total" @change="emit('change')" />
          </template>
        </Grid>
      </section>
    </main>
  </div>
</template>

<style scoped>
.safety-workbench { flex-direction: column; }
.safety-compact-results { flex: none; }
.safety-grid { min-height: 0; flex: 1; }
</style>
