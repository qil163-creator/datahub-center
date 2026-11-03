<script lang="ts" setup generic="Row extends { id: number | string }">
import { computed, nextTick, onMounted, ref, watch } from 'vue';

import { useMutationObserver, useResizeObserver } from '@vueuse/core';
import { Alert, Button, CheckboxGroup, Dropdown, Table } from 'antdv-next';

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
const visibleKeys = ref(props.columns.map((column) => column.key));
const filtersVisible = ref(!props.filtersCollapsed);
const results = ref<HTMLElement>();
const pagination = ref<HTMLElement>();
const measured = ref<HTMLElement[]>([]);
const bodyHeight = ref<number>();

function measureTableHeight() {
  const card = results.value;
  const footer = pagination.value;
  if (props.height || !card || !footer || !card.clientHeight) return;
  const header = card.querySelector<HTMLElement>('.ant-table-thead');
  const body = card.querySelector<HTMLElement>('.ant-table-body');
  const contentTop =
    body?.getBoundingClientRect().top ?? header?.getBoundingClientRect().bottom;
  if (contentTop === undefined) return;
  const paddingBottom =
    Number.parseFloat(getComputedStyle(card).paddingBottom) || 0;
  // 以卡片实际底边扣除分页和表头上方区域，兼容查询区折叠、工具栏换行及分页换行。
  const contentBottom =
    card.getBoundingClientRect().top +
    card.clientTop +
    card.clientHeight -
    paddingBottom;
  bodyHeight.value = Math.max(
    0,
    Math.floor(contentBottom - contentTop - footer.offsetHeight),
  );
}
function observeTableSections() {
  const card = results.value;
  const targets = [
    card,
    pagination.value,
    ...(card?.querySelectorAll<HTMLElement>(
      '.category-toolbar, .ant-alert, .ant-table-thead',
    ) ?? []),
  ].filter((element): element is HTMLElement => !!element);
  if (
    targets.length !== measured.value.length ||
    targets.some((element, index) => element !== measured.value[index])
  ) {
    measured.value = targets;
  }
  void nextTick(measureTableHeight);
}
useResizeObserver(measured, measureTableHeight);
useMutationObserver(results, observeTableSections, {
  childList: true,
  subtree: true,
});
onMounted(observeTableSections);
watch(
  () => props.columns,
  () => {
    visibleKeys.value = props.columns.map((column) => column.key);
  },
);
const columns = computed(() =>
  props.columns.filter((column) => visibleKeys.value.includes(column.key)),
);
const rowSelection = computed(() =>
  props.selection
    ? {
        columnWidth: props.selectionWidth || 45,
        selectedRowKeys: selected.value,
        onChange: (keys: (number | string)[]) => {
          selected.value = keys.map(String);
        },
      }
    : undefined,
);
const icon = (name: string) => `url(/assets/qdata-tag/${name}.svg)`;
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
      <section
        ref="results"
        class="category-results"
        :class="{ 'safety-compact-results': compact }"
        :style="{
          '--safety-table-body-height':
            height || (bodyHeight === undefined ? undefined : `${bodyHeight}px`),
        }"
      >
        <div class="category-toolbar">
          <strong>{{ title }}</strong>
          <div><slot name="actions"></slot></div>
          <div>
            <Button
              v-if="$slots.filters || showSearch"
              aria-label="显示或隐藏查询"
              @click="filtersVisible = !filtersVisible"
            >
              <i
                class="category-icon"
                :style="{ maskImage: icon('search') }"
              ></i>
</Button><Button aria-label="刷新列表" @click="emit('refresh')">
              <i
                class="category-icon"
                :style="{ maskImage: icon('refresh') }"
              ></i>
</Button><Dropdown :trigger="['click']">
              <Button aria-label="选择显示列">
                <i
                  class="category-icon"
                  :style="{ maskImage: icon('columns') }"
                ></i>
</Button><template #popupRender>
                <div class="category-column-picker">
                  <CheckboxGroup
                    v-model:value="visibleKeys"
                    :options="
                      props.columns.map((column) => ({
                        value: column.key,
                        label: column.title,
                      }))
                    "
                  />
                </div>
              </template>
            </Dropdown>
          </div>
        </div>
        <Alert v-if="error" :message="error" type="error">
          <template #action>
            <Button size="small" @click="emit('refresh')"> 重试 </Button>
          </template>
        </Alert>
        <Table
          class="category-table"
          size="small"
          :columns="columns"
          :data-source="rows"
          :row-key="(row: Row) => String(row.id)"
          :row-selection="rowSelection"
          :loading="loading"
          :pagination="false"
          :scroll="{ x: minWidth || 1400, y: height || bodyHeight }"
          :custom-row="
            (row: Row) => ({ onDblclick: () => emit('detail', row) })
          "
          @change="(_page, _filter, sorter) => emit('sort', sorter)"
        >
          <template #bodyCell="slot">
            <slot name="bodyCell" v-bind="slot"></slot>
</template><template v-if="$slots.empty" #emptyText>
            <slot name="empty"></slot>
          </template>
        </Table>
        <footer ref="pagination" class="category-pagination">
          <ReferencePagination
            v-model:page="page"
            v-model:page-size="pageSize"
            :total="total"
            @change="emit('change')"
          />
        </footer>
      </section>
    </main>
  </div>
</template>

<style scoped>
.safety-workbench {
  flex-direction: column;
}

.safety-compact-results {
  flex: none;
}

.safety-workbench .category-results {
  overflow: auto;
}

.safety-workbench :deep(.ant-table-thead > tr > th) {
  color: hsl(var(--foreground));
  white-space: nowrap;
  background: hsl(var(--accent));
  border-bottom: 1px solid hsl(var(--border));
}

.safety-workbench :deep(.ant-table-container) {
  overflow: hidden;
  border: 1px solid hsl(var(--border));
  border-radius: var(--radius);
}

.safety-workbench :deep(.ant-table-thead > tr > th:not(:last-child)::before) {
  background-color: hsl(var(--border));
}
</style>
