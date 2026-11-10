<script setup lang="ts">
import type { SensitiveListApi } from '#/api/asset/sensitive';

import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  reactive,
  ref,
  watch,
} from 'vue';

import { Page, useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { formatDate } from '@vben/utils';

import {
  Alert,
  Button,
  message,
  Modal,
  Switch,
  Tag,
  Tooltip,
} from 'antdv-next';

import { ACTION_ICON, NativeVxeButton, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import { useVbenForm } from '#/adapter/form';
import {
  changeSensitiveListStatus,
  deleteSensitiveList,
  deleteSensitiveLists,
  getSensitiveListCategories,
  getSensitiveListPage,
} from '#/api/asset/sensitive';
import ListPagination from '#/components/list-pagination/index.vue';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { createLatestWins } from '#/utils/resource-id';

import { categoryTree, statusOptions } from './data';
import Detail from './modules/detail.vue';
import Form from './modules/form.vue';
import LevelBadge from './modules/level-badge.vue';
import SourceIcon from './modules/source-icon.vue';
import { useSensitiveAccess } from './use-sensitive-access';

defineOptions({ name: 'AssetSensitiveList' });
const { owner, can } = useSensitiveAccess();
const filters = reactive<{
  categoryKey?: string;
  keyword: string;
  status?: 0 | 1;
}>({ keyword: '' });
const rows = ref<SensitiveListApi.Record[]>([]);
const categories = ref<SensitiveListApi.Category[]>([]);
const selectedIds = ref<SensitiveListApi.Id[]>([]);
const selectedRows = ref<SensitiveListApi.Record[]>([]);
const total = ref(0);
const pageNo = ref(1);
const pageSize = ref(10);
const sortField = ref<'createTime' | 'id'>('id');
const sortOrder = ref<'asc' | 'desc'>('desc');
const loading = ref(false);
const failed = ref(false);
const categoryFailed = ref(false);
const showFilters = ref(true);
const pending = ref(new Set<string>());
const confirmations = new Set<{ destroy: () => void }>();
const reads = createLatestWins();
const categoryReads = createLatestWins();
let alive = false;
let active = true;
let activation = 0;
function actionContext() {
  return JSON.stringify([owner.value, activation]);
}
const tree = computed(() => categoryTree(categories.value));
const [FilterForm, filterFormApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' }, labelWidth: 100 },
  layout: 'horizontal',
  wrapperClass: 'grid-cols-1 md:grid-cols-2 lg:grid-cols-3',
  showCollapseButton: true,
  submitOnEnter: true,
  resetButtonOptions: { show: false },
  submitButtonOptions: { show: false },
  schema: [
    {
      fieldName: 'keyword',
      label: '资产名称',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入资产名称' },
    },
    {
      fieldName: 'categoryKey',
      label: '数据分类',
      component: 'TreeSelect',
      componentProps: () => ({
        allowClear: true,
        showSearch: true,
        treeNodeFilterProp: 'title',
        treeData: tree.value,
        placeholder: '请选择数据分类',
      }),
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: statusOptions,
        placeholder: '请选择状态',
      },
    },
  ],
  handleValuesChange(values) {
    filters.keyword = values.keyword ?? '';
    filters.categoryKey = values.categoryKey;
    filters.status = values.status;
  },
  handleSubmit: query,
  handleReset: resetFilters,
});
const [FormModal, formApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});
const [DetailModal, detailApi] = useVbenModal({
  connectedComponent: Detail,
  destroyOnClose: true,
});
const baseColumns = [
  { title: '编号', dataIndex: 'id', key: 'id', width: 75, sorter: true },
  { title: '资产名称/描述', key: 'asset', width: 290 },
  { title: '数据分类', key: 'classification', width: 170 },
  { title: '数据分级', key: 'level', width: 120 },
  { title: '表名', dataIndex: 'tableName', key: 'tableName', width: 240 },
  { title: '字段名', dataIndex: 'columnName', key: 'columnName', width: 140 },
  { title: '状态', key: 'status', width: 100 },
  { title: '创建人', key: 'creator', width: 120 },
  {
    title: '创建时间',
    key: 'createTime',
    dataIndex: 'createTime',
    width: 180,
    sorter: true,
  },
  { title: '操作', key: 'actions', width: 220, fixed: 'right' as const },
];
const [Grid, gridApi] = useVbenVxeGrid<SensitiveListApi.Record>({
  gridOptions: {
    columns: [
      { type: 'checkbox', width: 45 },
      ...baseColumns.map((column) => ({
        field: column.key, title: column.title, width: column.width,
        fixed: 'fixed' in column ? column.fixed : undefined,
        sortable: 'sorter' in column && column.sorter,
        slots: { default: 'body' },
      })),
    ],
    data: [], height: 'auto', pagerConfig: { enabled: false }, proxyConfig: { enabled: false },
    rowConfig: { keyField: 'id', isHover: true }, sortConfig: { remote: true, defaultSort: { field: 'id', order: 'desc' } }, showOverflow: false,
    toolbarConfig: { refresh: true, refreshOptions: { queryMethod: () => load() }, custom: true, zoom: false },
  },
  gridEvents: {
    checkboxChange: ({ records }: { records: SensitiveListApi.Record[] }) => handleSelection(records.map((row) => row.id), records),
    checkboxAll: ({ records }: { records: SensitiveListApi.Record[] }) => handleSelection(records.map((row) => row.id), records),
    sortChange: ({ field, order }: { field: string; order: null | string }) => handleTableChange({}, null, { field, order: order === 'asc' ? 'ascend' : order === 'desc' ? 'descend' : undefined }),
  },
});
watch(loading, (value) => gridApi.setLoading(value), { immediate: true });
watch(selectedIds, (value) => { if (!value.length) void gridApi.grid?.clearCheckboxRow(); });
function clearSelection() {
  selectedIds.value = [];
  selectedRows.value = [];
}
async function load() {
  if (!alive || !active || !can('query')) return;
  const ticket = reads.next();
  loading.value = true;
  failed.value = false;
  rows.value = [];
  clearSelection();
  try {
    const data = await getSensitiveListPage({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      keyword: filters.keyword.trim() || undefined,
      categoryKey: filters.categoryKey,
      status: filters.status,
      sortField: sortField.value,
      sortOrder: sortOrder.value,
    });
    if (!ticket.isCurrent() || !alive) return;
    rows.value = data.list;
    total.value = data.total;
  } catch {
    if (ticket.isCurrent()) {
      failed.value = true;
      total.value = 0;
    }
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}
async function loadCategories() {
  if (!alive || !active || !can('query')) return;
  const ticket = categoryReads.next();
  categoryFailed.value = false;
  try {
    const data = await getSensitiveListCategories();
    if (ticket.isCurrent() && alive) categories.value = data;
  } catch {
    if (ticket.isCurrent()) {
      categoryFailed.value = true;
      categories.value = [];
    }
  }
}
function query() {
  pageNo.value = 1;
  void load();
}
function resetFilters() {
  if (filterFormApi.isMounted) void filterFormApi.reset();
  Object.assign(filters, {
    keyword: '',
    categoryKey: undefined,
    status: undefined,
  });
  query();
}
function handleSelection(
  keys: SensitiveListApi.Id[],
  records: SensitiveListApi.Record[],
) {
  selectedIds.value = keys;
  selectedRows.value = records;
}
function changePage(page: number, size: number) {
  pageNo.value = size === pageSize.value ? page : 1;
  pageSize.value = size;
  void load();
}
function handleTableChange(
  pagination: { current?: number; pageSize?: number },
  _filters: unknown,
  sorter: unknown,
) {
  pageNo.value = pagination.current ?? 1;
  pageSize.value = pagination.pageSize ?? pageSize.value;
  const value =
    !Array.isArray(sorter) && sorter && typeof sorter === 'object'
      ? (sorter as { field?: string; order?: string })
      : undefined;
  if (value?.order && (value.field === 'id' || value.field === 'createTime')) {
    sortField.value = value.field;
    sortOrder.value = value.order === 'ascend' ? 'asc' : 'desc';
  } else {
    sortField.value = 'id';
    sortOrder.value = 'desc';
  }
  void load();
}
function create() {
  if (can('create')) formApi.setData({}).open();
}
function edit(row: SensitiveListApi.Record) {
  if (can('update') && row.status === 1 && !pending.value.has(String(row.id)))
    formApi.setData({ id: row.id }).open();
}
function detail(row: SensitiveListApi.Record) {
  if (can('query')) detailApi.setData({ id: row.id }).open();
}
function toggle(row: SensitiveListApi.Record, value: unknown) {
  const id = String(row.id);
  if (!can('update') || pending.value.has(id)) return;
  const actor = actionContext();
  const previous = row.status;
  const status: 0 | 1 = value === true ? 0 : 1;
  if (status === previous) return;
  const revision = row.revision;
  row.status = status;
  pending.value.add(id);
  const close = () => {
    if (actionContext() === actor) pending.value.delete(id);
    confirmations.delete(dialog);
  };
  const dialog = Modal.confirm({
    title: status === 0 ? '启用脱敏清单' : '禁用脱敏清单',
    content: `确认${status === 0 ? '启用' : '禁用'}“${row.assetName || row.tableName || id}”的${row.columnName || ''}字段记录？`,
    okText: '确定',
    cancelText: '取消',
    onCancel() {
      if (actionContext() === actor) row.status = previous;
      close();
    },
    async onOk() {
      if (!alive || actionContext() !== actor || !can('update')) {
        close();
        return;
      }
      try {
        await changeSensitiveListStatus({ id: row.id, revision, status });
        if (actionContext() === actor && alive) {
          message.success('操作成功');
          await load();
        }
      } catch {
        if (actionContext() === actor) {
          row.status = previous;
          void load();
        }
      } finally {
        close();
      }
    },
  });
  confirmations.add(dialog);
}
function remove(row: SensitiveListApi.Record) {
  if (!can('delete') || row.status !== 1 || pending.value.has(String(row.id)))
    return;
  const actor = actionContext();
  const id = row.id;
  const revision = row.revision;
  const dialog = Modal.confirm({
    title: '删除脱敏清单',
    content: `确认删除“${row.assetName || row.tableName || String(id)}”的${row.columnName || ''}字段记录？`,
    okText: '确定',
    cancelText: '取消',
    okButtonProps: { danger: true },
    onCancel() {
      confirmations.delete(dialog);
    },
    async onOk() {
      confirmations.delete(dialog);
      if (!alive || actionContext() !== actor || !can('delete')) return;
      await deleteSensitiveList(id, revision);
      if (alive && actionContext() === actor) {
        message.success('删除成功');
        await load();
      }
    },
  });
  confirmations.add(dialog);
}
function removeSelected() {
  if (!can('delete') || selectedRows.value.length === 0) return;
  const actor = actionContext();
  const items = selectedRows.value.map((row) => ({
    id: row.id,
    revision: row.revision,
  }));
  const dialog = Modal.confirm({
    title: '批量删除脱敏清单',
    content: `确认删除所选${items.length}条记录？启用记录将跳过。`,
    okText: '确定',
    cancelText: '取消',
    okButtonProps: { danger: true },
    onCancel() {
      confirmations.delete(dialog);
    },
    async onOk() {
      confirmations.delete(dialog);
      if (!alive || actionContext() !== actor || !can('delete')) return;
      const result = await deleteSensitiveLists(items);
      if (!alive || actionContext() !== actor) return;
      message.success(
        `已删除${result.deletedIds.length}条，跳过${result.skippedIds.length}条启用记录`,
      );
      await load();
    },
  });
  confirmations.add(dialog);
}
function closeOwnedDialogs() {
  for (const dialog of confirmations) dialog.destroy();
  confirmations.clear();
  formApi.close?.();
  detailApi.close?.();
}
watch(
  owner,
  () => {
    activation += 1;
    reads.invalidate();
    categoryReads.invalidate();
    closeOwnedDialogs();
    rows.value = [];
    categories.value = [];
    total.value = 0;
    pending.value.clear();
    clearSelection();
    if (alive) void Promise.allSettled([load(), loadCategories()]);
  },
  { flush: 'sync' },
);
onMounted(() => {
  alive = true;
  void Promise.allSettled([load(), loadCategories()]);
});
onActivated(() => {
  if (!active) {
    active = true;
    void Promise.allSettled([load(), loadCategories()]);
  }
});
onDeactivated(() => {
  active = false;
  activation += 1;
  reads.invalidate();
  categoryReads.invalidate();
  closeOwnedDialogs();
  rows.value = [];
  categories.value = [];
  total.value = 0;
  pending.value.clear();
  clearSelection();
});
onBeforeUnmount(() => {
  alive = false;
  activation += 1;
  reads.invalidate();
  categoryReads.invalidate();
  closeOwnedDialogs();
});

function syncFilterValues() {
  if (filterFormApi.isMounted)
    void filterFormApi.setValues({
      keyword: filters.keyword,
      categoryKey: filters.categoryKey,
      status: filters.status,
    });
}
</script>

<template>
  <Page
    auto-content-height
    content-class="flex min-h-0 flex-col overflow-hidden"
    class="sensitive-page"
  >
    <FormModal @success="load" /><DetailModal />
    <Alert
      v-if="!can('query')"
      type="info"
      message="暂无查看敏感清单的权限。"
    />
    <template v-else>
      <FilterForm
        @vue:mounted="syncFilterValues"
        v-show="showFilters"
        class="mb-4 shrink-0 rounded-lg bg-background p-4"
      >
        <template #reset-before>
          <Button @click="resetFilters">
            <template #icon><SourceIcon name="reset" /></template>重置
          </Button>
          <Button type="primary" @click="filterFormApi.validateAndSubmit()">
            <template #icon><SourceIcon name="query" /></template>查询
          </Button>
        </template>
      </FilterForm>
      <section class="sensitive-main">
        <Alert
          v-if="categoryFailed"
          type="warning"
          message="数据分类读取失败。"
          class="mb-2"
        >
          <template #action>
            <Button size="small" @click="loadCategories"> 重试 </Button>
          </template>
        </Alert>
        <Alert v-if="failed" type="error" message="列表读取失败。" class="mb-2">
          <template #action>
            <Button size="small" @click="load">重试</Button>
          </template>
        </Alert>
        <Grid class="sensitive-grid" table-title="敏感清单列表" :table-data="rows">
          <template #toolbar-tools>
            <TableAction
:actions="[
              { label: '新增', type: 'primary', icon: ACTION_ICON.ADD, ifShow: can('create'), onClick: create },
              { label: '批量删除', type: 'primary', danger: true, icon: ACTION_ICON.DELETE, ifShow: can('delete'), disabled: !selectedIds.length, onClick: removeSelected },
            ]"
/>
            <NativeVxeButton class="ml-2" aria-label="显示或隐藏筛选" title="搜索" icon="vxe-icon-search" circle :status="showFilters ? 'primary' : undefined" :aria-pressed="showFilters" @click="showFilters = !showFilters" />
          </template>
          <template #body="{ column, row: record }">
<template v-if="column.field === 'id'">
              {{ record.sourceRecordId || record.id }}
            </template>
            <template v-else-if="column.field === 'asset'">
              <div class="sensitive-asset">
                <div class="sensitive-asset__title">
                  <IconifyIcon
                    icon="lucide:file-lock"
                    class="sensitive-asset__icon"
                    aria-hidden="true"
                  />
                  <span>{{ record.assetName || '-' }}</span>
                </div>
                <Tooltip :title="record.assetDescription">
                  <TextEllipsisCell
                    class="text-muted-foreground text-xs"
                    :content="record.assetDescription || '-'"
                    :line="2"
                    center
                  />
                </Tooltip>
              </div>
            </template>
            <template v-else-if="column.field === 'classification'">
              <Tag :bordered="false" color="blue">
                {{ record.classificationName || '-' }}
              </Tag>
            </template>
            <template v-else-if="column.field === 'level'">
              <LevelBadge :name="record.levelName" :color="record.levelColor" />
            </template>
            <template v-else-if="column.field === 'status'">
              <Switch
                :checked="record.status === 0"
                :loading="pending.has(String(record.id))"
                :disabled="!can('update')"
                @change="
                  (value) => toggle(record as SensitiveListApi.Record, value)
                "
              />
            </template>
            <template v-else-if="column.field === 'creator'">
              {{ record.creatorName || record.sourceCreatorName || '-' }}
            </template>
            <template v-else-if="column.field === 'createTime'">
              {{ formatDate(record.createTime, 'YYYY-MM-DD HH:mm') || '-' }}
            </template>
            <template v-else-if="column.field === 'actions'">
              <div class="sensitive-actions">
                <Button
                  v-if="can('update')"
                  type="link"
                  size="small"
                  :disabled="
                    record.status === 0 || pending.has(String(record.id))
                  "
                  @click="edit(record as SensitiveListApi.Record)"
                >
                  <template #icon><SourceIcon name="edit" /></template>修改
                </Button>
                <Button
                  v-if="can('delete')"
                  type="link"
                  size="small"
                  danger
                  :disabled="
                    record.status === 0 || pending.has(String(record.id))
                  "
                  @click="remove(record as SensitiveListApi.Record)"
                >
                  <template #icon><SourceIcon name="delete" /></template>删除
                </Button>
                <Button
                  type="link"
                  size="small"
                  @click="detail(record as SensitiveListApi.Record)"
                >
                  <template #icon><SourceIcon name="view" /></template>详情
                </Button>
              </div>
            </template>
          </template>
          <template #pager>
            <ListPagination :current="pageNo" :page-size="pageSize" :total="total" @change="changePage" />
          </template>
        </Grid>
      </section>
    </template>
  </Page>
</template>

<style scoped>
.sensitive-page {
  background: hsl(var(--muted));
}

.sensitive-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  padding: 12px 14px 20px;
  overflow: hidden;
  background: hsl(var(--background));
  border-radius: var(--radius);
}

.sensitive-grid {
  flex: 1;
  min-height: 0;
}

.sensitive-actions {
  display: inline-flex;
  flex-wrap: nowrap;
  gap: 4px;
  align-items: center;
  white-space: nowrap;
}

.sensitive-asset {
  min-height: 48px;
}

.sensitive-asset__title {
  display: flex;
  gap: 6px;
  align-items: flex-start;
  overflow-wrap: anywhere;
}

.sensitive-asset__icon {
  flex: none;
  width: 16px;
  height: 16px;
  margin-top: 3px;
  color: hsl(var(--primary));
}

.sensitive-asset p {
  margin: 4px 0 0;
  line-height: 18px;
  color: hsl(var(--muted-foreground));
  overflow-wrap: anywhere;
  white-space: normal;
}
</style>

<style scoped src="../table-font.css"></style>
