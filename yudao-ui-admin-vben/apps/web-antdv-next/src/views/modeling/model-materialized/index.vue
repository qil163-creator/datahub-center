<script setup lang="ts">
import type { ModelMaterializedApi } from '#/api/modeling/model-materialized';

import {
  computed,
  nextTick,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  reactive,
  ref,
  watch,
} from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { useAntdDesignTokens } from '@vben/hooks';
import { useAccessStore } from '@vben/stores';
import { formatDate } from '@vben/utils';

import { Button, Checkbox, ConfigProvider } from 'antdv-next';

import { NativeVxeButton } from '#/adapter/business-vxe-table';
import { useVbenForm } from '#/adapter/form';
import {
  getModelMaterializedCatTree,
  getModelMaterializedPage,
} from '#/api/modeling/model-materialized';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';

import TableCustomization from '../components/table-customization.vue';
import { useTableCustomization } from '../components/use-table-customization';
import dmIcon from '../logictable/assets/db-dm.svg';
import dorisIcon from '../logictable/assets/db-doris.svg';
import postgresqlIcon from '../logictable/assets/db-postgresql.svg';
import { LOGIC_TABLE_PAGES } from '../logictable/config';
import { QdIcon } from '../logictable/icons';
import republishIcon from './assets/republish.svg';
import publishIcon from './assets/send-plane.svg';
import CategoryTree from './modules/category-tree.vue';
import PublishModal from './modules/publish-modal.vue';
import RecoveryModal from './modules/recovery-modal.vue';
import ReferencePagination from './modules/reference-pagination.vue';
import {
  isUnresolvedMaterialization,
  MATERIALIZED_STATUS_OPTIONS,
  materializedStatus,
  recoveryAction,
} from './state';

import '../logictable/logic-table.css';
import './materialized.css';

defineOptions({ name: 'ModelingModelMaterialized' });

const { tokens } = useAntdDesignTokens();

type Row = ModelMaterializedApi.Materialized;
type Column =
  | 'action'
  | 'belong'
  | 'connection'
  | 'id'
  | 'model'
  | 'publisher'
  | 'publishTime'
  | 'release'
  | 'selection';
const router = useRouter();
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const canQuery = computed(() =>
  hasAccessByCodes(['modeling:model-materialized:query']),
);
const canPublish = computed(() =>
  hasAccessByCodes(['modeling:model-materialized:publish']),
);
const publishModal = ref<InstanceType<typeof PublishModal>>();
const recoveryModal = ref<InstanceType<typeof RecoveryModal>>();
const categoryTree = ref<InstanceType<typeof CategoryTree>>();
const rows = ref<Row[]>([]);
const total = ref(0);
const pageNo = ref(1);
const pageSize = ref(10);
const loading = ref(false);
const failed = ref(false);
const showSearch = ref(true);
const selectedIds = ref(new Set<string>());
const nodes = ref<ModelMaterializedApi.CatNode[]>([]);
const treeLoading = ref(false);
const treeFailed = ref(false);
const treeFilter = ref<ModelMaterializedApi.CatNode>();
const query = reactive({
  name: '',
  tableType: undefined as string | undefined,
  publishStatus: undefined as string | undefined,
});
const sortField = ref<'id' | 'publishTime'>('id');
const sortOrder = ref<'asc' | 'desc'>('desc');
const defaultColumns: Array<{ align?: 'center' | 'left' | 'right'; fixed?: 'left' | 'right'; key: Column; label: string; width: number }> = [
  { key: 'selection', label: '选择', width: 55, align: 'center' },
  { key: 'id', label: '编号', width: 60 },
  { key: 'model', label: '模型信息', width: 390 },
  { key: 'belong', label: '归属信息', width: 260 },
  { key: 'connection', label: '数据连接信息', width: 200 },
  { key: 'release', label: '发布信息', width: 200 },
  { key: 'publisher', label: '发布人', width: 150 },
  { key: 'publishTime', label: '发布时间', width: 160 },
  { key: 'action', label: '操作', width: 175, align: 'center', fixed: 'right' },
];
const { columns, visibleColumns: shownColumns, open: customizationOpen, apply: applyCustomization, reset: resetCustomization } =
  useTableCustomization('modeling-model-materialized', () => defaultColumns);
const tableWidth = computed(() =>
  shownColumns.value.reduce((sum, column) => sum + column.width, 0),
);
const firstRightColumn = computed(() =>
  shownColumns.value.find((column) => column.fixed === 'right')?.key,
);
const tableWrapper = ref<HTMLDivElement>();
const hasFixedColumnShadow = ref(false);
const fixedOffsets = ref<Record<string, number>>({});
let tableResizeObserver: ResizeObserver | undefined;
function measureTable() {
  const element = tableWrapper.value;
  if (!element) return;
  hasFixedColumnShadow.value =
    element.scrollLeft < element.scrollWidth - element.clientWidth - 1;
  const widths = new Map<string, number>();
  element.querySelectorAll<HTMLElement>('thead [data-column-key]').forEach((cell) => {
    const key = cell.dataset.columnKey;
    if (key) widths.set(key, cell.getBoundingClientRect().width);
  });
  const offsets: Record<string, number> = {};
  let left = 0;
  for (const column of shownColumns.value) {
    if (column.fixed !== 'left') continue;
    offsets[column.key] = left;
    left += widths.get(column.key) || column.width;
  }
  let right = 0;
  for (const column of [...shownColumns.value].reverse()) {
    if (column.fixed !== 'right') continue;
    offsets[column.key] = right;
    right += widths.get(column.key) || column.width;
  }
  fixedOffsets.value = offsets;
}
function columnStyle(column: (typeof shownColumns.value)[number], header = false) {
  if (!column.fixed) return undefined;
  return { position: 'sticky' as const, [column.fixed]: `${fixedOffsets.value[column.key] ?? 0}px`, zIndex: header ? 3 : 2 };
}
onMounted(() => {
  if (tableWrapper.value && typeof ResizeObserver !== 'undefined') {
    tableResizeObserver = new ResizeObserver(measureTable);
    tableResizeObserver.observe(tableWrapper.value);
  }
  void nextTick(measureTable);
});
watch([rows, shownColumns], () => {
  if (!shownColumns.value.some((column) => column.key === 'selection')) {
    selectedIds.value = new Set();
  }
  void nextTick(measureTable);
});
const selectedRows = computed(() =>
  rows.value.filter((row) => selectedIds.value.has(String(row.modelId))),
);
const selectableRows = computed(() =>
  rows.value.filter((row) => !isUnresolvedMaterialization(row)),
);
const allSelected = computed(
  () =>
    selectableRows.value.length > 0 &&
    selectableRows.value.every((row) =>
      selectedIds.value.has(String(row.modelId)),
    ),
);
const tableOptions = Object.values(LOGIC_TABLE_PAGES).map((page) => ({
  label: page.label,
  value: page.tableType,
}));
let sequence = 0;
let treeSequence = 0;
let active = true;
async function load(reset = false) {
  if (reset) pageNo.value = 1;
  const current = ++sequence;
  loading.value = true;
  failed.value = false;
  try {
    const node = treeFilter.value;
    const result = await getModelMaterializedPage({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      name: query.name.trim() || undefined,
      tableType: query.tableType || node?.tableType,
      publishStatus: query.publishStatus,
      layerId: node?.layerId,
      layerIds: node?.layerIds,
      dataDomainId: node?.dataDomainId,
      subjectDomainId: node?.subjectDomainId,
      sourceSystemId: node?.sourceSystemId,
      sortField: sortField.value,
      sortOrder: sortOrder.value,
    });
    if (current !== sequence) return;
    rows.value = result.list;
    total.value = result.total;
    const available = new Set(
      result.list
        .filter((row) => !isUnresolvedMaterialization(row))
        .map((row) => String(row.modelId)),
    );
    selectedIds.value = new Set(
      [...selectedIds.value].filter((id) => available.has(id)),
    );
  } catch {
    if (current === sequence) {
      failed.value = true;
      rows.value = [];
      total.value = 0;
    }
  } finally {
    if (current === sequence) loading.value = false;
  }
}
async function loadTree() {
  const current = ++treeSequence;
  treeLoading.value = true;
  treeFailed.value = false;
  try {
    const result = await getModelMaterializedCatTree();
    if (current === treeSequence) nodes.value = result;
  } catch {
    if (current === treeSequence) treeFailed.value = true;
  } finally {
    if (current === treeSequence) treeLoading.value = false;
  }
}
function chooseTree(node?: ModelMaterializedApi.CatNode) {
  treeFilter.value = node;
  selectedIds.value = new Set();
  void load(true);
}
function reset() {
  treeFilter.value = undefined;
  selectedIds.value = new Set();
  categoryTree.value?.reset();
  Object.assign(query, {
    name: '',
    tableType: undefined,
    publishStatus: undefined,
  });
  void load(true);
}
function sort(field: 'id' | 'publishTime') {
  sortOrder.value =
    sortField.value === field && sortOrder.value === 'desc' ? 'asc' : 'desc';
  sortField.value = field;
  void load(true);
}
function select(row: Row, checked: boolean) {
  const next = new Set(selectedIds.value);
  if (checked) next.add(String(row.modelId));
  else next.delete(String(row.modelId));
  selectedIds.value = next;
}
function selectAll(checked: boolean) {
  selectedIds.value = new Set(
    checked ? selectableRows.value.map((row) => String(row.modelId)) : [],
  );
}
function openDetail(row: Row) {
  void router.push({
    name: 'ModelingMaterializedDetail',
    params: { modelId: String(row.modelId) },
  });
}
function modelTitle(row: Row) {
  return row.modelAlias && row.modelName
    ? `${row.modelAlias} (${row.modelName})`
    : row.modelAlias || row.modelName || '-';
}
function tableConfig(row: Row) {
  return Object.values(LOGIC_TABLE_PAGES).find(
    (page) => page.tableType === row.tableType,
  );
}
function belonging(row: Row) {
  return (
    [row.dataDomainName, row.subjectDomainName].filter(Boolean).join(' / ') ||
    row.layerName ||
    row.hierarchyPath ||
    '-'
  );
}
function statusLabel(row: Row) {
  const status = materializedStatus(row);
  if (status === 'SUCCESS') return '发布成功';
  if (status === 'FAIL') return '发布失败';
  return (
    MATERIALIZED_STATUS_OPTIONS.find((option) => option.value === status)
      ?.label || status
  );
}
function databaseIcon(row: Row) {
  const type = row.datasourceType || '';
  if (/doris/i.test(type)) return dorisIcon;
  if (/postgres/i.test(type)) return postgresqlIcon;
  if (/dm|达梦/i.test(type)) return dmIcon;
  return undefined;
}
function releasedAgo(value?: number) {
  if (value === undefined) return '-';
  const seconds = Math.max(0, Math.floor((Date.now() - value) / 1000));
  const days = Math.floor(seconds / 86_400);
  const hours = Math.floor((seconds % 86_400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  let prefix = '';
  if (days > 0) prefix = `${days} 天前 `;
  else if (hours > 0) prefix = `${hours} 小时前 `;
  return `${prefix}${minutes}m${seconds % 60}s`;
}
function published() {
  selectedIds.value = new Set();
  void load();
}
function changePage(page: number, size: number) {
  pageNo.value = page;
  pageSize.value = size;
  selectedIds.value = new Set();
  void load();
}
watch(
  () => `${access.accessToken}/${access.refreshToken}`,
  () => {
    rows.value = [];
    nodes.value = [];
    selectedIds.value = new Set();
    treeFilter.value = undefined;
    void load(true);
    void loadTree();
  },
  { immediate: true },
);
onActivated(() => {
  void nextTick(measureTable);
  if (!active) {
    active = true;
    void load();
    void loadTree();
  }
});
onDeactivated(() => {
  active = false;
  sequence++;
  treeSequence++;
});
onBeforeUnmount(() => {
  tableResizeObserver?.disconnect();
  sequence++;
  treeSequence++;
});

const [SearchForm, searchFormApi] = useVbenForm({
  submitOnEnter: true,
  layout: 'horizontal',
  compact: true,
  showCollapseButton: true,
  wrapperClass: 'grid-cols-1 md:grid-cols-2 lg:grid-cols-3',
  commonConfig: { componentProps: { class: 'w-full' } },
  submitButtonOptions: { content: '搜索' },
  schema: [
    {
      fieldName: 'name',
      label: '模型名称',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入模型名称' },
    },
    {
      fieldName: 'tableType',
      label: '表类型',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: tableOptions,
        placeholder: '请选择表类型',
      },
    },
    {
      fieldName: 'publishStatus',
      label: '状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: [...MATERIALIZED_STATUS_OPTIONS],
        placeholder: '请选择状态',
      },
    },
  ],
  handleSubmit: async (values) => {
    Object.assign(query, values);
    await load(true);
  },
  handleReset: resetNativeSearch,
});
async function resetNativeSearch() {
  await searchFormApi.reset();
  reset();
}
const themeConfig = computed(() => ({
  token: { borderRadius: tokens.borderRadius },
}));
</script>

<template>
  <ConfigProvider :theme="themeConfig">
    <Page auto-content-height content-class="p-0">
    <div class="qd-logic-page qd-materialized">
      <section class="qd-logic-body">
        <CategoryTree
          ref="categoryTree"
          :nodes="nodes"
          :loading="treeLoading"
          :failed="treeFailed"
          @select="chooseTree"
          @retry="loadTree"
        />
        <main class="qd-logic-main">
          <div v-if="showSearch" class="qd-wrap-search">
            <SearchForm />
          </div>
          <div class="qd-wrap-content">
            <div class="qd-wrap-actions">
              <h3 class="qd-list-title">发布模型</h3>
              <div class="qd-data-actions">
                <Button
                  v-if="canPublish"
                  type="primary"
                  :disabled="selectedRows.length === 0"
                  @click="publishModal?.openBatch(selectedRows)"
                >
                  <template #icon>
                    <span
                      class="materialized-action-icon"
                      :style="{ maskImage: `url(&quot;${publishIcon}&quot;)` }"
                    ></span>
                  </template>
                  批量发布
                </Button>
              </div>
              <div class="qd-table-actions">
                <NativeVxeButton
                  aria-label="显示或隐藏查询条件"
                  :title="showSearch ? '隐藏搜索' : '显示搜索'"
                  icon="vxe-icon-search"
                  size="small"
                  circle
                  :status="showSearch ? 'primary' : undefined"
                  :aria-pressed="showSearch"
                  @click="showSearch = !showSearch"
                />
                <div class="vxe-tools--operate">
                <NativeVxeButton
                  aria-label="刷新"
                  title="刷新"
                  :icon="loading ? 'vxe-icon-repeat roll' : 'vxe-icon-repeat'"
                  size="small"
                  circle
                  :disabled="loading"
                  @click="load()"
                />
                <NativeVxeButton
                  aria-label="个性化设置"
                  title="个性化设置"
                  icon="vxe-icon-setting"
                  size="small"
                  circle
                  @click="customizationOpen = true"
                />
                </div>
              </div>
            </div>
            <div class="qd-table" :aria-busy="loading">
              <div ref="tableWrapper" class="qd-table__wrapper" @scroll="measureTable">
                <table
                  class="qd-table__inner materialized-table"
                  :style="{ width: `max(100%, ${tableWidth}px)` }"
                >
                  <colgroup>
                    <col
                      v-for="column in shownColumns"
                      :key="column.key"
                      :style="{ width: `${column.width}px` }"
                    />
                  </colgroup>
                  <thead>
                    <tr>
                      <th
                        v-for="column in shownColumns"
                        :key="column.key"
                        :data-column-key="column.key"
                        :style="columnStyle(column, true)"
                        :class="{
                          [`is-${column.align || 'left'}`]: true,
                          'is-fixed-right': column.fixed === 'right',
                          'has-shadow': column.fixed === 'right' && column.key === firstRightColumn && hasFixedColumnShadow,
                          'is-sortable': ['id', 'publishTime'].includes(
                            column.key,
                          ),
                          ascending:
                            sortField === column.key && sortOrder === 'asc',
                          descending:
                            sortField === column.key && sortOrder === 'desc',
                        }"
                        :aria-sort="
                          sortField === column.key
                            ? sortOrder === 'asc'
                              ? 'ascending'
                              : 'descending'
                            : undefined
                        "
                      >
                        <div class="qd-cell">
                        <Checkbox
                          v-if="column.key === 'selection'"
                          :checked="allSelected"
                          :indeterminate="
                            selectedRows.length > 0 && !allSelected
                          "
                          aria-label="全选模型"
                          @change="selectAll(!allSelected)"
                        />
                          <button
                            v-else-if="
                              column.key === 'id' ||
                              column.key === 'publishTime'
                            "
                            type="button"
                            class="materialized-sort"
                            @click="sort(column.key)"
                          >
                            {{ column.label
                            }}<span class="qd-caret-wrapper"><i class="qd-sort-caret ascending"></i><i class="qd-sort-caret descending"></i></span>
</button><span v-else>{{ column.label }}</span>
                        </div>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr
                      v-for="(row, index) in rows"
                      :key="String(row.modelId)"
                      :class="{ 'is-striped': index % 2 === 1 }"
                    >
                      <td
                        v-for="column in shownColumns"
                        :key="column.key"
                        :style="columnStyle(column)"
                        :class="{
                          [`is-${column.align || 'left'}`]: true,
                          'is-fixed-right': column.fixed === 'right',
                          'has-shadow': column.fixed === 'right' && column.key === firstRightColumn && hasFixedColumnShadow,
                        }"
                      >
                        <div class="qd-cell" :class="{ 'materialized-row-actions': column.key === 'action' }">
                        <Checkbox
                          v-if="column.key === 'selection'"
                          :checked="selectedIds.has(String(row.modelId))"
                          :disabled="isUnresolvedMaterialization(row)"
                          :aria-label="`选择模型 ${row.modelAlias || row.modelId}`"
                          @change="
                            select(row, !selectedIds.has(String(row.modelId)))
                          "
                        />
                          <span v-else-if="column.key === 'id'">{{
                            row.modelId
                          }}</span>
                          <div
                            v-else-if="column.key === 'model'"
                            class="qd-table-info"
                          >
                            <div class="qd-table-info__main">
                              <img
                                v-if="tableConfig(row)"
                                class="qd-table-info__icon"
                                :src="tableConfig(row)?.infoIcon"
                                alt=""
                              /><button
                                type="button"
                                class="qd-table-info__title materialized-model-title"
                                :title="modelTitle(row)"
                                @click="openDetail(row)"
                              >
                                {{ modelTitle(row) }}
                              </button>
                            </div>
                            <TextEllipsisCell class="qd-table-info__desc" :content="row.description" :line="2" />
                          </div>
                          <div
                            v-else-if="column.key === 'belong'"
                            class="materialized-cell-info"
                          >
                            <span class="qd-ellipsis" :title="belonging(row)">{{
                              belonging(row)
                            }}</span><span
                              class="materialized-type-tag"
                              :data-type="row.tableType"
                              ><img
                                v-if="tableConfig(row)"
                                :src="tableConfig(row)?.infoIcon"
                                alt=""
                              />{{
                                tableConfig(row)?.label || row.tableType || '-'
                              }}</span>
                          </div>
                          <div
                            v-else-if="column.key === 'connection'"
                            class="materialized-cell-info"
                          >
                            <span class="qd-ellipsis">{{
                              row.datasourceName || '-'
                            }}</span><span
                              v-if="row.datasourceType"
                              class="materialized-database-tag"
                              ><img
                                v-if="databaseIcon(row)"
                                :src="databaseIcon(row)"
                                alt=""
                              />{{ row.datasourceType }}</span><span v-else>-</span>
                          </div>
                          <div
                            v-else-if="column.key === 'release'"
                            class="materialized-cell-info"
                          >
                            <span
                              class="materialized-status-tag"
                              :data-status="materializedStatus(row)"
                              >{{ statusLabel(row) }}</span><span>{{ releasedAgo(row.publishTime) }}</span>
                          </div>
                          <div
                            v-else-if="column.key === 'publisher'"
                            class="materialized-cell-info"
                          >
                            <span
                              class="qd-ellipsis"
                              :title="row.publisherName"
                              >{{
                                row.publisherName || row.publisher || '-'
                              }}</span><span>{{ row.publisherPhone || '-' }}</span>
                          </div>
                          <span v-else-if="column.key === 'publishTime'">{{
                            row.publishTime === undefined
                              ? '-'
                              : formatDate(
                                  row.publishTime,
                                  'YYYY-MM-DD HH:mm',
                                )
                          }}</span>
                          <template v-else-if="column.key === 'action'">
                          <Button
type="link"
                            v-if="canQuery"
                            @click="openDetail(row)"
                          >
                            <QdIcon name="view" /><span>详情</span>
</Button><Button
type="link"
                            v-if="canPublish && recoveryAction(row)"
                            @click="recoveryModal?.open(row)"
                          >
                            <QdIcon name="search" /><span>核验结果</span>
</Button><Button
type="link"
                            v-else-if="
                              canPublish && !isUnresolvedMaterialization(row)
                            "
                            @click="
                              materializedStatus(row) === 'UNPUBLISHED'
                                ? publishModal?.open(row.modelId)
                                : publishModal?.openRepublish(row)
                            "
                          >
                            <span
                              class="materialized-action-icon"
                              :style="{
                                maskImage: `url(&quot;${materializedStatus(row) === 'UNPUBLISHED' ? publishIcon : republishIcon}&quot;)`,
                              }"
                            ></span><span>{{
                              materializedStatus(row) === 'UNPUBLISHED'
                                ? '发布模型'
                                : '重新发布'
                            }}</span>
                          </Button>
                          </template>
                        </div>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div v-if="rows.length === 0 && !loading" class="qd-table__empty">
                {{ failed ? '加载失败' : '暂无数据'
                }}<Button
type="link"
                  v-if="failed"
                  @click="load()"
                >
                  ，重试
                </Button>
              </div>
              <div
                v-if="loading"
                class="qd-table__mask"
                role="status"
                aria-label="加载中"
              >
                <span class="qd-table__spinner"></span>
              </div>
            </div>
            <ReferencePagination
              :page-no="pageNo"
              :page-size="pageSize"
              :total="total"
              @change="changePage"
            />
          </div>
        </main>
      </section>
      <TableCustomization
        v-model:open="customizationOpen"
        :columns="columns"
        @apply="applyCustomization"
        @reset="resetCustomization"
      />
      <PublishModal ref="publishModal" @success="published" />
      <RecoveryModal ref="recoveryModal" @refresh="load()" />
    </div>
    </Page>
  </ConfigProvider>
</template>
