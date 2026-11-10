<script setup lang="ts">
import type { CustomizableColumn } from '../../components/use-table-customization';
import type { LogicColumn, LogicTableKind } from '../config';

import type { ModelingLogicTableApi } from '#/api/modeling/logictable';
import type { SystemUserApi } from '#/api/system/user';

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
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { confirm, Page } from '@vben/common-ui';
import { DICT_TYPE } from '@vben/constants';
import { getDictOptions, useAntdDesignTokens } from '@vben/hooks';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';
import { formatDate } from '@vben/utils';

import {
  Button,
  Checkbox,
  ConfigProvider,
  message,
  Popover,
  Tooltip,
} from 'antdv-next';

import { ACTION_ICON, NativeVxeButton, TableAction } from '#/adapter/business-vxe-table';
import { useVbenForm } from '#/adapter/form';
import {
  checkLogicTableDelete,
  deleteLogicTables,
  getLogicTableCatTree,
  getLogicTablePage,
  getLogicTableSourceSystems,
  updateLogicTableEnabled,
} from '#/api/modeling/logictable';
import { getSimpleUserList } from '#/api/system/user';
import ListPagination from '#/components/list-pagination/index.vue';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';

import TableCustomization from '../../components/table-customization.vue';
import { useTableCustomization } from '../../components/use-table-customization';
import sectionIcon from '../assets/data-section-default-icon-small.svg';
import dbIcon from '../assets/db-dm.svg';
import syncIcon from '../assets/sync-structure.svg';
import versionIcon from '../assets/version.svg';
import { LOGIC_TABLE_PAGES } from '../config';
import { QdIcon } from '../icons';
import CatTree from './cat-tree.vue';
import SourceModal from './source-modal.vue';
import SummaryModal from './summary-modal.vue';

import '../logic-table.css';

const props = defineProps<{ kind: LogicTableKind }>();

const { tokens } = useAntdDesignTokens();

type Row = ModelingLogicTableApi.LogicTable;

const PERM = {
  query: 'modeling:logic-table:query',
  create: 'modeling:logic-table:create',
  update: 'modeling:logic-table:update',
  delete: 'modeling:logic-table:delete',
};
const PAGE_SIZES = [10, 20, 30, 50];

const page = computed(() => LOGIC_TABLE_PAGES[props.kind]);
const router = useRouter();
const route = useRoute();
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const can = (code: string) => hasAccessByCodes([code]);

// ---------- 查询条件 ----------
const query = reactive({
  name: '',
  sourceSystemId: undefined as number | string | undefined,
  enabled: undefined as number | undefined,
  contactUserId: undefined as number | string | undefined,
  dataTimeliness: undefined as string | undefined,
  businessType: undefined as string | undefined,
});
const treeFilter = ref<{
  dataDomainId?: number | string;
  sourceSystemId?: number | string;
  subjectDomainId?: number | string;
}>({});
const showSearch = ref(true);
const {
  columns: customizedColumns,
  visibleColumns,
  open: settingsOpen,
  apply: applySettings,
  reset: resetSettings,
} = useTableCustomization<CustomizableColumn & Omit<LogicColumn, 'align'>>(`modeling-logic-table-${props.kind}`, () =>
  page.value.columns.map((column) => ({
    ...column,
    fixed: column.fixedRight ? 'right' as const : undefined,
  })),
);

// ---------- 选项 ----------
const sourceSystems = ref<ModelingLogicTableApi.SourceSystem[]>([]);
const users = ref<SystemUserApi.User[]>([]);
const userOptions = computed(() =>
  users.value.map((user) => ({
    label: user.nickname || user.username,
    value: String(user.id),
  })),
);
const sourceOptions = computed(() =>
  sourceSystems.value.map((item) => ({
    label: item.name,
    value: String(item.id),
  })),
);
const timelinessOptions = computed(() =>
  getDictOptions(DICT_TYPE.MODELING_LOGIC_DATA_TIMELINESS, 'string'),
);
const businessTypeOptions = computed(() =>
  getDictOptions(DICT_TYPE.MODELING_LOGIC_BUSINESS_TYPE, 'string'),
);
const objTypeOptions = computed(() =>
  getDictOptions(DICT_TYPE.MODELING_LOGIC_OBJ_TYPE, 'string'),
);
function dictLabel(
  options: Array<{ label: string; value: boolean | number | string }>,
  value?: string,
) {
  if (!value) return '';
  return options.find((item) => String(item.value) === value)?.label ?? value;
}

// ---------- 类目树 ----------
const treeNodes = ref<ModelingLogicTableApi.CatNode[]>([]);
const treeLoading = ref(false);
const treeFailed = ref(false);
const treeRef = ref<InstanceType<typeof CatTree>>();
let treeSequence = 0;
async function loadTree() {
  const current = ++treeSequence;
  treeLoading.value = true;
  treeFailed.value = false;
  try {
    const nodes = await getLogicTableCatTree(page.value.tableType);
    if (current === treeSequence) treeNodes.value = nodes;
  } catch {
    if (current === treeSequence) treeFailed.value = true;
  } finally {
    if (current === treeSequence) treeLoading.value = false;
  }
}
function onTreeSelect(node?: ModelingLogicTableApi.CatNode) {
  treeFilter.value = node
    ? {
        sourceSystemId: node.sourceSystemId,
        dataDomainId:
          node.type === 'DATA_DOMAIN' ? node.dataDomainId : undefined,
        subjectDomainId: node.subjectDomainId,
      }
    : {};
  void load(true);
}

// ---------- 列表 ----------
const rows = ref<Row[]>([]);
const total = ref(0);
const pageNo = ref(1);
const pageSize = ref(10);
const loading = ref(false);
const failed = ref(false);
const sort = ref<{
  asc: boolean;
  field: NonNullable<LogicColumn['sortField']>;
}>({
  field: 'createTime',
  asc: false,
});
const selectedIds = ref(new Set<string>());
const busyIds = ref(new Set<string>());
const deleting = ref(false);
const active = ref(true);
let listSequence = 0;

async function load(reset = false) {
  if (reset) pageNo.value = 1;
  const current = ++listSequence;
  loading.value = true;
  failed.value = false;
  try {
    const result = await getLogicTablePage({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      tableType: page.value.tableType,
      name: query.name.trim() || undefined,
      sourceSystemId: query.sourceSystemId ?? treeFilter.value.sourceSystemId,
      enabled: query.enabled,
      contactUserId: query.contactUserId,
      dataTimeliness: query.dataTimeliness,
      businessType: query.businessType,
      dataDomainId: treeFilter.value.dataDomainId,
      subjectDomainId: treeFilter.value.subjectDomainId,
      sortField: sort.value.field,
      asc: sort.value.asc,
    });
    if (current !== listSequence) return;
    rows.value = result.list;
    total.value = result.total;
    const ids = new Set(result.list.map((row) => String(row.id)));
    selectedIds.value = new Set(
      [...selectedIds.value].filter((id) => ids.has(id)),
    );
  } catch {
    if (current === listSequence) {
      failed.value = true;
      rows.value = [];
      total.value = 0;
    }
  } finally {
    if (current === listSequence) loading.value = false;
  }
}

function resetSearch() {
  Object.assign(query, {
    name: '',
    sourceSystemId: undefined,
    enabled: undefined,
    contactUserId: undefined,
    dataTimeliness: undefined,
    businessType: undefined,
  });
  treeFilter.value = {};
  treeRef.value?.reset();
  void load(true);
}

function changeSort(column: Pick<LogicColumn, 'sortField'>, asc?: boolean) {
  if (!column.sortField) return;
  if (asc === undefined) {
    // 点击表头：升序 → 降序 → 恢复默认
    if (sort.value.field !== column.sortField) {
      sort.value = { field: column.sortField, asc: true };
    } else if (sort.value.asc) {
      sort.value = { field: column.sortField, asc: false };
    } else {
      sort.value = { field: 'createTime', asc: false };
    }
  } else if (sort.value.field === column.sortField && sort.value.asc === asc) {
    sort.value = { field: 'createTime', asc: false };
  } else {
    sort.value = { field: column.sortField, asc };
  }
  void load(true);
}
function sortState(column: Pick<LogicColumn, 'sortField'>) {
  if (!column.sortField || sort.value.field !== column.sortField) return '';
  return sort.value.asc ? 'ascending' : 'descending';
}

// ---------- 选择 ----------
const allChecked = computed(
  () =>
    rows.value.length > 0 &&
    rows.value.every((row) => selectedIds.value.has(String(row.id))),
);
const someChecked = computed(
  () => selectedIds.value.size > 0 && !allChecked.value,
);
function toggleAll(checked: boolean) {
  selectedIds.value = new Set(
    checked ? rows.value.map((row) => String(row.id)) : [],
  );
}
function toggleRow(row: Row, checked: boolean) {
  const next = new Set(selectedIds.value);
  if (checked) next.add(String(row.id));
  else next.delete(String(row.id));
  selectedIds.value = next;
}

// ---------- 展示 ----------
function infoTitle(row: Row) {
  const name = row.name || '-';
  if (!row.tableNameNotation) return name;
  return page.value.fullWidthTitle
    ? `${name}（${row.tableNameNotation}）`
    : `${name} (${row.tableNameNotation})`;
}
function formatTime(value?: number | string) {
  return value ? String(formatDate(value, 'YYYY-MM-DD HH:mm')) : '-';
}
function ownerLines(row: Row) {
  return page.value.ownerMode === 'creator'
    ? [row.creatorName || row.ownerName || '-', row.contactNumber || '-']
    : [row.ownerName || '-', row.ownerPhone || row.contactNumber || '-'];
}
function statisticsTags(row: Row) {
  const value = row.statisticsStr?.trim();
  return value ? value.split(',').filter(Boolean) : ['-'];
}
function enabled(row: Row) {
  return row.enabled === 1;
}

// ---------- 列宽（el-table fit：仅无固定宽度的列分配剩余宽度） ----------
const tableWrapper = ref<HTMLDivElement>();
const tableElement = ref<HTMLTableElement>();
const renderedWidths = ref<number[]>([]);
const wrapperWidth = ref(0);
const scrollLeft = ref(0);
const scrollMax = ref(0);
const columnWidths = computed(() => {
  const columns = visibleColumns.value;
  const fixed = columns.reduce((sum, col) => sum + (col.width ?? 0), 0);
  const flex = columns.filter((col) => col.width === undefined);
  const flexMin = flex.reduce((sum, col) => sum + (col.minWidth ?? 80), 0);
  const extra = Math.max(0, wrapperWidth.value - fixed - flexMin);
  const widths = columns.map((col) => {
    if (col.width !== undefined) return col.width;
    const min = col.minWidth ?? 80;
    return flexMin > 0 ? Math.floor(min + (extra * min) / flexMin) : min;
  });
  if (!flex.length && extra > 0) {
    const expandable = Math.max(0, columns.findIndex((column) => !column.fixed));
    if (widths.length) widths[expandable] = (widths[expandable] ?? 0) + extra;
  }
  return widths;
});
const tableWidth = computed(() =>
  columnWidths.value.reduce((sum, width) => sum + width, 0),
);
function fixedStyle(index: number) {
  const column = visibleColumns.value[index];
  const widths = renderedWidths.value.length === visibleColumns.value.length
    ? renderedWidths.value
    : columnWidths.value;
  if (column?.fixed === 'left') {
    return { left: `${widths.slice(0, index).reduce((sum, width) => sum + width, 0)}px` };
  }
  if (column?.fixed === 'right') {
    return { right: `${widths.slice(index + 1).reduce((sum, width) => sum + width, 0)}px` };
  }
  return {};
}
watch(visibleColumns, (columns) => {
  if (!columns.some((column) => column.key === 'selection')) selectedIds.value = new Set();
  void nextTick(measureTable);
});
let resizeObserver: ResizeObserver | undefined;
function measureTable() {
  const el = tableWrapper.value;
  if (!el) return;
  wrapperWidth.value = el.clientWidth;
  scrollLeft.value = el.scrollLeft;
  scrollMax.value = el.scrollWidth - el.clientWidth;
  renderedWidths.value = [...(tableElement.value?.querySelectorAll('thead th') ?? [])].map((cell) => cell.getBoundingClientRect().width);
}
onMounted(() => {
  if (tableWrapper.value) {
    resizeObserver = new ResizeObserver(() => measureTable());
    resizeObserver.observe(tableWrapper.value);
    if (tableElement.value) resizeObserver.observe(tableElement.value);
  }
  void nextTick(measureTable);
});
onBeforeUnmount(() => resizeObserver?.disconnect());
watch(rows, () => nextTick(measureTable));

// ---------- 分页 ----------
const pageCount = computed(() =>
  Math.max(1, Math.ceil(total.value / pageSize.value)),
);
function goPage(target: number) {
  const next = Math.min(Math.max(1, target), pageCount.value);
  if (next === pageNo.value) return;
  pageNo.value = next;
  void load();
}
function changePageSize(size: number) {
  pageSize.value = size;
  void load(true);
}

// ---------- 操作 ----------
const sourceModal = ref<InstanceType<typeof SourceModal>>();
const summaryModal = ref<InstanceType<typeof SummaryModal>>();
function openDetail(row: Row) {
  void router.push({
    name: 'ModelingTableModelDetail',
    params: { id: String(row.id) },
    query: { from: route.path },
  });
}
function openCreate() {
  if (props.kind === 'source') void sourceModal.value?.open();
  else if (props.kind === 'summary') void summaryModal.value?.open();
  else
    void router.push({
      name: 'ModelingLogicTableEdit',
      params: { kind: props.kind },
      query: { from: route.path },
    });
}
function openEdit(row: Row) {
  if (props.kind === 'source') void sourceModal.value?.open(row.id);
  else if (props.kind === 'summary') void summaryModal.value?.open(row.id);
  else
    void router.push({
      name: 'ModelingLogicTableEdit',
      params: { kind: props.kind },
      query: { id: String(row.id), from: route.path },
    });
}
function developing() {
  message.info('功能开发中');
}

/** 确认框打开期间的行，避免重复点击叠加多个确认框。 */
const confirmingIds = new Set<string>();
async function changeStatus(row: Row) {
  const id = String(row.id);
  if (busyIds.value.has(id) || confirmingIds.has(id) || !can(PERM.update)) {
    return;
  }
  confirmingIds.add(id);
  try {
    await toggleStatus(row, id);
  } finally {
    confirmingIds.delete(id);
  }
}
async function toggleStatus(row: Row, id: string) {
  const next = enabled(row) ? 0 : 1;
  const mode = page.value.statusMode;
  if (mode === 'confirm') {
    try {
      await confirm(
        `确认${next === 1 ? '启用' : '禁用'}“${row.name}”吗？`,
        '状态变更确认',
        { icon: 'warning' },
      );
    } catch {
      return;
    }
  } else if (mode === 'summary') {
    try {
      await confirm(
        `确认要"${next === 1 ? '启用' : '禁用'}","${row.name}"汇总逻辑表吗？`,
        '系统提示',
        { icon: 'warning' },
      );
    } catch {
      return;
    }
  }
  busyIds.value = new Set([...busyIds.value, id]);
  try {
    await updateLogicTableEnabled(row.id, next);
    row.enabled = next;
    if (mode === 'direct')
      message.success(next === 1 ? '发布成功' : '已取消发布');
    else if (mode === 'summary') message.success('操作成功');
    else message.success(next === 1 ? '已启用' : '已禁用');
    void load();
  } finally {
    const rest = new Set(busyIds.value);
    rest.delete(id);
    busyIds.value = rest;
  }
}

async function removeRow(row: Row) {
  if (deleting.value || !can(PERM.delete)) return;
  if (page.value.kind === 'source') {
    if (enabled(row)) {
      message.warning('开启状态的贴源逻辑表不可删除');
      return;
    }
    try {
      await confirm(
        `确认删除贴源逻辑表「${row.name || row.code}」吗？`,
        '系统提示',
        {
          icon: 'warning',
        },
      );
    } catch {
      return;
    }
    await runDelete([row.id], '删除成功');
    return;
  }
  if (page.value.kind === 'summary') {
    await removeChecked([row.id], '系统提示');
    return;
  }
  try {
    await confirm(`确认删除“${row.name}”吗？`, '删除确认', { icon: 'warning' });
  } catch {
    return;
  }
  await runDelete([row.id], '删除成功');
}

async function removeSelected() {
  if (deleting.value || selectedIds.value.size === 0 || !can(PERM.delete))
    return;
  const selected = rows.value.filter((row) =>
    selectedIds.value.has(String(row.id)),
  );
  if (page.value.kind === 'source') {
    const deletable = selected.filter((row) => !enabled(row));
    try {
      await confirm(
        `可删除${deletable.length}个，不可删除${selected.length - deletable.length}个，是否删除可删除部分？`,
        '系统提示',
        { icon: 'warning' },
      );
    } catch {
      return;
    }
    if (deletable.length === 0) return;
    await runDelete(
      deletable.map((row) => row.id),
      `批量删除成功，可删除${deletable.length}个`,
    );
    return;
  }
  await removeChecked(
    selected.map((row) => row.id),
    page.value.kind === 'summary' ? '系统提示' : '批量删除',
  );
}

/** 先由后端检查启用状态，再删除可删除部分。 */
async function removeChecked(ids: Array<number | string>, title: string) {
  deleting.value = true;
  try {
    const check = await checkLogicTableDelete(ids);
    const suffix = page.value.kind === 'summary' ? '？' : '';
    try {
      await confirm(
        `可删除${check.canDeleteCount}个，不可删除${check.cannotDeleteCount}个，是否删除可删部分${suffix}`,
        title,
        { icon: 'warning' },
      );
    } catch {
      return;
    }
    if (check.canDeleteIds.length === 0) {
      message.success('删除成功');
      return;
    }
    await deleteLogicTables(check.canDeleteIds);
    message.success('删除成功');
    selectedIds.value = new Set();
    await load();
  } finally {
    deleting.value = false;
  }
}

async function runDelete(ids: Array<number | string>, successText: string) {
  deleting.value = true;
  try {
    await deleteLogicTables(ids);
    message.success(successText);
    selectedIds.value = new Set();
    await load();
  } finally {
    deleting.value = false;
  }
}

// ---------- 生命周期 ----------
async function loadOptions() {
  const [sources, userList] = await Promise.all([
    page.value.kind === 'source'
      ? getLogicTableSourceSystems().catch(() => [])
      : Promise.resolve([]),
    page.value.search.some((item) => item.key === 'owner')
      ? getSimpleUserList().catch(() => [])
      : Promise.resolve([]),
  ]);
  sourceSystems.value = sources;
  users.value = userList;
}
watch(
  () => `${access.accessToken}/${props.kind}`,
  () => {
    selectedIds.value = new Set();
    treeFilter.value = {};
    void loadOptions();
    void loadTree();
    void load(true);
  },
  { immediate: true },
);
onActivated(() => {
  if (!active.value) {
    active.value = true;
    void load();
  }
});
onDeactivated(() => {
  active.value = false;
});

function searchOptions(key: string) {
  if (key === 'sourceSystem') return sourceOptions.value;
  if (key === 'owner') return userOptions.value;
  if (key === 'dataTimeliness') return timelinessOptions.value;
  if (key === 'businessType') return businessTypeOptions.value;
  return page.value.enabledOptions;
}

const [SearchForm, searchFormApi] = useVbenForm({
  submitOnEnter: true,
  layout: 'horizontal',
  compact: true,
  showCollapseButton: true,
  wrapperClass: 'grid-cols-1 md:grid-cols-2 lg:grid-cols-3',
  commonConfig: { componentProps: { class: 'w-full' } },
  submitButtonOptions: { content: '搜索' },
  schema: page.value.search.map((item) => ({
    fieldName:
      (
        { sourceSystem: 'sourceSystemId', owner: 'contactUserId' } as Record<
          string,
          string
        >
      )[item.key] ?? item.key,
    label: item.label,
    component: item.key === 'name' ? ('Input' as const) : ('Select' as const),
    componentProps: () => ({
      allowClear: true,
      placeholder: item.placeholder,
      ...(item.key === 'name'
        ? {}
        : {
            showSearch: true,
            optionFilterProp: 'label',
            options: searchOptions(item.key),
          }),
    }),
  })),
  handleSubmit: async (values) => {
    Object.assign(query, values);
    await load(true);
  },
  handleReset: resetNativeSearch,
});
async function resetNativeSearch() {
  await searchFormApi.reset();
  resetSearch();
}
const themeConfig = computed(() => ({
  token: { borderRadius: tokens.borderRadius },
}));
</script>

<template>
  <ConfigProvider :theme="themeConfig">
    <Page auto-content-height content-class="p-0">
    <div class="qd-logic-page">
      <section class="qd-logic-body">
        <CatTree
          ref="treeRef"
          :nodes="treeNodes"
          :loading="treeLoading"
          :failed="treeFailed"
          :root-label="page.treeRoot"
          :placeholder="page.treePlaceholder"
          @retry="loadTree"
          @select="onTreeSelect"
        />
        <main class="qd-logic-main">
          <div v-if="showSearch" class="qd-wrap-search">
            <SearchForm />
          </div>
          <div class="qd-wrap-content">
            <div class="qd-wrap-actions">
              <h3 class="qd-list-title">{{ page.label }}</h3>
              <div class="qd-data-actions">
                <TableAction
                  :actions="[
                    { label: page.addLabel, type: 'primary', icon: ACTION_ICON.ADD, auth: [PERM.create], onClick: openCreate },
                    { label: '批量删除', type: 'primary', danger: true, icon: ACTION_ICON.DELETE, auth: [PERM.delete], disabled: selectedIds.size === 0 || deleting, onClick: removeSelected },
                  ]"
                />
              </div>
              <div class="qd-table-actions">
                <NativeVxeButton :aria-label="showSearch ? '隐藏搜索' : '显示搜索'" :title="showSearch ? '隐藏搜索' : '显示搜索'" icon="vxe-icon-search" size="small" circle :status="showSearch ? 'primary' : undefined" :aria-pressed="showSearch" @click="showSearch = !showSearch" />
                <div class="vxe-tools--operate">
                  <NativeVxeButton aria-label="刷新" title="刷新" :icon="loading ? 'vxe-icon-repeat roll' : 'vxe-icon-repeat'" size="small" circle :disabled="loading" @click="load()" />
                  <NativeVxeButton aria-label="个性化设置" title="个性化设置" icon="vxe-icon-setting" size="small" circle @click="settingsOpen = true" />
                </div>
              </div>
            </div>

            <div class="qd-table" :class="{ 'is-loading': loading }">
              <div
                ref="tableWrapper"
                class="qd-table__wrapper"
                @scroll="measureTable"
              >
                <table
                  ref="tableElement"
                  class="qd-table__inner"
                  :style="{ width: `${Math.max(tableWidth, wrapperWidth)}px` }"
                >
                  <colgroup>
                    <col
                      v-for="(col, index) in visibleColumns"
                      :key="col.key"
                      :style="{ width: `${columnWidths[index]}px` }"
                    />
                  </colgroup>
                  <thead>
                    <tr>
                      <th
                        v-for="(col, colIndex) in visibleColumns"
                        :key="col.key"
                        :data-column-key="col.key"
                        :class="[
                          `is-${col.align}`,
                          {
                            'is-sortable': col.sortField,
                            'is-fixed-left': col.fixed === 'left',
                            'is-fixed-right': col.fixed === 'right',
                            'has-shadow': col.fixed === 'right' && visibleColumns[colIndex - 1]?.fixed !== 'right' && scrollLeft < scrollMax - 1,
                          },
                          sortState(col),
                        ]"
                        :style="fixedStyle(colIndex)"
                        @click="changeSort(col)"
                      >
                        <div class="qd-cell">
                          <Checkbox
                            v-if="col.key === 'selection'"
                            :checked="allChecked"
                            :indeterminate="someChecked"
                            :disabled="rows.length === 0"
                            @click.stop
                            @change="toggleAll($event.target.checked)"
                          />
                          <template v-else-if="col.tip">
                            <span class="qd-th-tip">
                              {{ col.label }}
                              <Tooltip :title="col.tip">
                                <span class="qd-th-tip__icon" @click.stop>
                                  <QdIcon name="info" />
                                </span>
                              </Tooltip>
                            </span>
                          </template>
                          <template v-else>{{ col.label }}</template>
                          <span v-if="col.sortField" class="qd-caret-wrapper">
                            <i
                              class="qd-sort-caret ascending"
                              @click.stop="changeSort(col, true)"
                            ></i>
                            <i
                              class="qd-sort-caret descending"
                              @click.stop="changeSort(col, false)"
                            ></i>
                          </span>
                        </div>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr
                      v-for="(row, rowIndex) in rows"
                      :key="String(row.id)"
                      :class="{ 'is-striped': rowIndex % 2 === 1 }"
                      @dblclick="page.kind !== 'source' && openDetail(row)"
                    >
                      <td
                        v-for="(col, colIndex) in visibleColumns"
                        :key="col.key"
                        :data-column-key="col.key"
                        :class="[
                          `is-${col.align}`,
                          {
                            'is-fixed-left': col.fixed === 'left',
                            'is-fixed-right': col.fixed === 'right',
                            'has-shadow': col.fixed === 'right' && visibleColumns[colIndex - 1]?.fixed !== 'right' && scrollLeft < scrollMax - 1,
                          },
                        ]"
                        :style="fixedStyle(colIndex)"
                      >
                        <div class="qd-cell" :class="{ 'qd-row-actions': col.key === 'action' }">
                          <Checkbox
                            v-if="col.key === 'selection'"
                            :checked="selectedIds.has(String(row.id))"
                            @change="toggleRow(row, $event.target.checked)"
                          />
                          <template v-else-if="col.key === 'id'">{{ row.id }}</template>
                          <div v-else-if="col.key === 'info'" class="qd-table-info">
                            <div class="qd-table-info__main">
                              <img :src="page.infoIcon" class="qd-table-info__icon" alt="" />
                              <a
                                class="qd-table-info__title"
                                :title="infoTitle(row)"
                                @click="openDetail(row)"
                              >{{ infoTitle(row) }}</a>
                            </div>
                            <TextEllipsisCell class="qd-table-info__desc" :content="row.description" :line="2" />
                          </div>
                          <div v-else-if="col.key === 'source'" class="qd-flex-col">
                            <div class="qd-ellipsis">{{ row.sourceSystemName || '-' }}</div>
                            <div class="qd-ellipsis qd-flex-center">
                              <span v-if="row.dbType" class="qd-tag qd-tag--db qd-tag--small">
                                <img :src="dbIcon" class="qd-tag__icon" alt="" />
                                <span>{{ row.dbType }}</span>
                              </span>
                              <span v-else>-</span>
                            </div>
                          </div>
                          <span
                            v-else-if="col.key === 'objType' && row.objType"
                            class="qd-tag qd-tag--primary qd-tag--dict"
                          >{{ dictLabel(objTypeOptions, row.objType) }}</span>
                          <span
                            v-else-if="col.key === 'businessType' && row.businessType"
                            class="qd-tag qd-tag--primary qd-tag--dict"
                          >{{ dictLabel(businessTypeOptions, row.businessType) }}</span>
                          <div v-else-if="col.key === 'belong'" class="qd-flex-col">
                            <div class="qd-flex-center qd-ellipsis">
                              <img
                                :src="row.dataDomainIcon || sectionIcon"
                                class="qd-section-icon"
                                alt=""
                              />
                              <span class="qd-ellipsis" :title="row.dataDomainName || '-'">{{
                                row.dataDomainName || '-'
                              }}</span>
                            </div>
                            <div class="qd-flex-center qd-ellipsis">
                              <span class="qd-ellipsis" :title="row.subjectDomainName || '-'">{{
                                row.subjectDomainName || '-'
                              }}</span>
                            </div>
                          </div>
                          <div
                            v-else-if="
                              col.key === 'timelinessRelation' ||
                              col.key === 'timelinessGranularity'
                            "
                            class="qd-flex-col"
                          >
                            <div class="qd-flex-center qd-ellipsis">
                              <span
                                v-if="row.dataTimeliness"
                                class="qd-tag qd-tag--success qd-tag--small qd-tag--mini-text"
                              >{{ dictLabel(timelinessOptions, row.dataTimeliness) }}</span>
                            </div>
                            <div class="qd-flex-center qd-ellipsis">
                              <div class="qd-table-tags">
                                <span
                                  v-for="(tag, index) in col.key === 'timelinessRelation' ? [row.entityName || '-'] : statisticsTags(row)"
                                  :key="`${col.key}-${index}`"
                                  class="qd-tag qd-tag--primary qd-tag--small"
                                >{{ tag }}</span>
                              </div>
                            </div>
                          </div>
                          <template v-else-if="col.key === 'columnCount'">
                            {{ row.columnCount ?? 0 }}个
                          </template>
                          <template v-else-if="col.key === 'status'">
                            <Tooltip
                              :title="page.statusMode === 'confirm' ? (enabled(row) ? '已启用' : '已停用') : undefined"
                            >
                              <button
                                type="button"
                                role="switch"
                                class="qd-switch"
                                :class="{
                                  'is-checked': enabled(row),
                                  'is-loading': busyIds.has(String(row.id)),
                                }"
                                :aria-checked="enabled(row)"
                                :aria-label="`${enabled(row) ? '停用' : '启用'}${row.name}`"
                                :disabled="!can(PERM.update)"
                                @click="changeStatus(row)"
                              >
                                <span class="qd-switch__core">
                                  <span class="qd-switch__action"></span>
                                </span>
                              </button>
                            </Tooltip>
                          </template>
                          <div v-else-if="col.key === 'owner'" class="qd-flex-col qd-fz14">
                            <div
                              v-for="(line, index) in ownerLines(row)"
                              :key="index"
                              class="qd-flex-center"
                              :title="line"
                            >
                              <span class="qd-ellipsis">{{ line }}</span>
                            </div>
                          </div>
                          <template v-else-if="col.key === 'updateTime'">
                            {{ formatTime(row.updateTime) }}
                          </template>
                          <template v-else-if="col.key === 'createTime'">
                            {{ formatTime(row.createTime) }}
                          </template>
                          <template v-else-if="col.key === 'action'">
                            <Button
type="link"
                              @click="openDetail(row)"
                            >
                              <QdIcon name="view" /><span>详情</span>
                            </Button>
                            <Button
type="link"
                              v-if="can(PERM.update)"
                              :disabled="enabled(row)"
                              @click="openEdit(row)"
                            >
                              <QdIcon name="edit" /><span>修改</span>
                            </Button>
                            <Popover
                              trigger="click"
                              placement="bottom"
                              :arrow="true"
                              :classes="{ root: 'qd-action-popover', container: 'qd-action-popover__inner' }"
                            >
                              <template #content>
                                <div class="qd-action-list">
                                  <template v-if="page.kind === 'source'">
                                    <Button
type="link"
                                      @click="developing"
                                    >
                                      <img :src="syncIcon" class="qd-action-list__img" alt="" />
                                      同步结构
                                    </Button>
                                    <Button
type="link"
                                      @click="developing"
                                    >
                                      <img :src="versionIcon" class="qd-action-list__img" alt="" />
                                      版本比对
                                    </Button>
                                  </template>
                                  <Button
type="link"
                                    v-else
                                    @click="openDetail(row)"
                                  >
                                    <QdIcon name="menu" /><span>属性字段</span>
                                  </Button>
                                  <Button
type="link" danger
                                    v-if="can(PERM.delete)"
                                    :disabled="enabled(row) || deleting"
                                    @click="removeRow(row)"
                                  >
                                    <QdIcon name="delete" /><span>删除</span>
                                  </Button>
                                </div>
                              </template>
                              <Button type="link">
                                <IconifyIcon icon="lucide:ellipsis-vertical" /><span>更多</span>
                              </Button>
                            </Popover>
                          </template>
                        </div>
                      </td>
                    </tr>
                  </tbody>
                </table>
                <div v-if="!loading && rows.length === 0" class="qd-table__empty">
                  {{ failed ? '数据加载失败' : '暂无数据' }}
                  <Button type="link" v-if="failed" @click="load()">
                    重试
                  </Button>
                </div>
              </div>
              <div v-if="loading" class="qd-table__mask">
                <span class="qd-table__spinner"></span>
              </div>
              <ListPagination
                :current="pageNo" :page-size="pageSize" :total="total"
                :page-size-options="PAGE_SIZES"
                @change="(page, size) => size !== pageSize ? changePageSize(size) : goPage(page)"
              />
            </div>
          </div>
        </main>
      </section>
      <SourceModal v-if="kind === 'source'" ref="sourceModal" @saved="load()" />
      <SummaryModal v-if="kind === 'summary'" ref="summaryModal" @saved="load()" />
      <TableCustomization v-model:open="settingsOpen" :columns="customizedColumns" @apply="applySettings" @reset="resetSettings" />
    </div>
    </Page>
  </ConfigProvider>
</template>
