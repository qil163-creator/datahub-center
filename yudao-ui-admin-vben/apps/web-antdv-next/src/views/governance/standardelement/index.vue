<script setup lang="ts">
import type { StandardId } from '../standardcommon/data';

import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceStandardElementApi } from '#/api/governance/standardelement';
import type { ActionItem } from '#/components/table-action';

import {
  nextTick,
  onActivated,
  onDeactivated,
  onMounted,
  reactive,
  ref,
  watch,
} from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { confirm, Page, useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';
import { downloadFileFromBlobPart, formatDate } from '@vben/utils';

import { Alert, Button, message, Switch, Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteStandardElement,
  deleteStandardElementList,
  deprecateStandardElement,
  exportStandardElement,
  getStandardElement,
  getStandardElementPage,
  getStandardElementStatistics,
  publishStandardElement,
  updateStandardElementStatus,
} from '#/api/governance/standardelement';
import { useObjectRequest } from '#/hooks/use-object-request';

import StandardHeaderHelp from '../standardcommon/standard-header-help.vue';
import StandardListLayout from '../standardcommon/standard-list-layout.vue';
import CategoryTree from './components/category-tree.vue';
import {
  formatCategoryName,
  formatPersonName,
  LIFECYCLE_STATUS_OPTIONS,
  loadElementNameCaches,
} from './data';
import Form from './modules/form.vue';
import ImportForm from './modules/import-form.vue';

type Element = GovernanceStandardElementApi.StandardElement;
const router = useRouter();
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const can = (action: string) =>
  hasAccessByCodes([`governance:standard-element:${action}`]);
const rows = ref<Element[]>([]);
const page = ref(1);
const pageSize = ref(10);
const total = ref(0);
const category = ref<StandardId>();
const categoryTree = ref<InstanceType<typeof CategoryTree>>();
const collapsed = ref(false);
const loading = ref(false);
const failed = ref(false);
const changing = ref<number>();
const bulkMode = ref(false);
const selected = ref<number[]>([]);
const active = ref(true);
const applied = reactive({
  name: '',
  engName: '',
  code: '',
  lifecycleStatus: undefined as string | undefined,
  status: undefined as number | undefined,
});
const sorting = reactive<{
  sortField: 'createTime' | 'id';
  sortOrder: 'asc' | 'desc';
}>({ sortField: 'createTime', sortOrder: 'desc' });
const requests = useObjectRequest(
  () =>
    JSON.stringify([
      active.value,
      access.accessCodes,
      page.value,
      pageSize.value,
      category.value,
      applied,
      sorting,
    ]),
  () => {
    rows.value = [];
    total.value = 0;
    selected.value = [];
    loading.value = false;
  },
);
const mutation = useObjectRequest(
  () => `${active.value}/${access.accessCodes.join(',')}`,
  () => {
    changing.value = undefined;
  },
);
const statistics = ref<GovernanceStandardElementApi.Statistics>();
const statsRequest = useObjectRequest();
const [FormModal, formApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});
const [ImportModal, importApi] = useVbenModal({
  connectedComponent: ImportForm,
  destroyOnClose: true,
});
const [StatsModal, statsApi] = useVbenModal({
  showConfirmButton: false,
  cancelText: '关闭',
});
function gridColumns(): VxeTableGridOptions<Element>['columns'] {
  return [
    {
      field: 'selection',
      type: 'checkbox',
      width: 48,
      visible: bulkMode.value,
    },
    { field: 'id', title: '编号', width: 90, sortable: true },
    {
      field: 'name',
      title: '中文名称',
      minWidth: 200,
      align: 'left',
      slots: { default: 'name' },
    },
    { field: 'engName', title: '英文名称', minWidth: 200, align: 'left' },
    { field: 'description', title: '描述', minWidth: 220, align: 'left' },
    {
      field: 'categoryId',
      title: '标准数据元类目',
      minWidth: 160,
      slots: { default: 'category' },
    },
    {
      field: 'type',
      title: '类型',
      width: 100,
      slots: { default: 'type', header: 'typeHeader' },
    },
    {
      field: 'status',
      title: '状态',
      width: 100,
      slots: { default: 'status', header: 'statusHeader' },
    },
    {
      field: 'creator',
      title: '创建人',
      width: 140,
      slots: { default: 'creator' },
    },
    {
      field: 'createTime',
      title: '创建时间',
      width: 180,
      sortable: true,
      formatter: ({ cellValue }) =>
        cellValue ? formatDate(cellValue, 'YYYY-MM-DD HH:mm:ss') : '-',
    },
    {
      title: '操作',
      width: 260,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}
const [Grid, gridApi] = useVbenVxeGrid<Element>({
  formOptions: {
    schema: [
      {
        fieldName: 'name',
        label: '中文名称',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入中文名称' },
      },
      {
        fieldName: 'engName',
        label: '英文名称',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入英文名称' },
      },
      {
        fieldName: 'code',
        label: '数据元编码',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入数据元编码' },
      },
      {
        fieldName: 'lifecycleStatus',
        label: '生命周期',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: LIFECYCLE_STATUS_OPTIONS,
          placeholder: '请选择生命周期',
        },
      },
      {
        fieldName: 'status',
        label: '状态',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: [
            { label: '启用', value: 0 },
            { label: '禁用', value: 1 },
          ],
          placeholder: '请选择状态',
        },
      },
    ],
    handleSubmit: query,
    handleReset: reset,
  },
  gridOptions: {
    columns: gridColumns(),
    data: [],
    height: 'auto',
    showOverflow: false,
    rowConfig: { keyField: 'id', isHover: true },
    checkboxConfig: {
      checkMethod: ({ row }: { row: Element }) =>
        row.status !== 0 && row.lifecycleStatus !== 'PUBLISHED',
    },
    sortConfig: {
      remote: true,
      multiple: false,
      defaultSort: { field: 'createTime', order: 'desc' },
    },
    proxyConfig: { enabled: false, autoLoad: false },
    pagerConfig: { currentPage: 1, pageSize: 10, total: 0 },
    toolbarConfig: {
      refresh: true,
      search: true,
      refreshOptions: { queryMethod: load },
    },
  },
  gridEvents: {
    pageChange: ({
      currentPage,
      pageSize: size,
    }: {
      currentPage: number;
      pageSize: number;
    }) => {
      page.value = currentPage;
      pageSize.value = size;
      void load();
    },
    sortChange: ({
      field,
      order,
    }: {
      field: string;
      order?: null | string;
    }) => {
      sorting.sortField = field === 'id' ? 'id' : 'createTime';
      sorting.sortOrder = order === 'asc' ? 'asc' : 'desc';
      page.value = 1;
      void load();
    },
    checkboxAll: ({ records }: { records: Element[] }) => {
      selected.value = records.map((row) => row.id);
    },
    checkboxChange: ({ records }: { records: Element[] }) => {
      selected.value = records.map((row) => row.id);
    },
    cellDblclick: ({ row }: { row: Element }) => {
      if (can('query')) detail(row);
    },
  },
});
watch(
  [rows, loading, page, pageSize, total, bulkMode],
  async () => {
    gridApi.setGridOptions({
      data: rows.value,
      loading: loading.value,
      pagerConfig: {
        currentPage: page.value,
        pageSize: pageSize.value,
        total: total.value,
      },
    });
    // 表格适配器更新配置时会重建列，待列更新完成后同步服务端排序状态。
    await nextTick();
    await nextTick();
    void gridApi.grid.sort?.(sorting.sortField, sorting.sortOrder);
  },
  { immediate: true },
);
function toggleBulkMode() {
  bulkMode.value = !bulkMode.value;
  selected.value = [];
  void gridApi.grid.clearCheckboxRow();
  gridApi.setGridOptions({ columns: gridColumns() });
}
async function load() {
  if (!active.value) return;
  const current = requests.begin();
  selected.value = [];
  void gridApi.grid.clearCheckboxRow?.();
  loading.value = true;
  failed.value = false;
  try {
    const response = await getStandardElementPage({
      ...applied,
      ...sorting,
      categoryId: category.value,
      pageNo: page.value,
      pageSize: pageSize.value,
    });
    if (current()) {
      rows.value = response.list;
      total.value = response.total;
    }
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
async function query() {
  const values = await gridApi.formApi.getValues();
  Object.assign(applied, {
    name: values.name ?? '',
    engName: values.engName ?? '',
    code: values.code ?? '',
    lifecycleStatus: values.lifecycleStatus,
    status: values.status,
  });
  page.value = 1;
  await load();
}
async function reset() {
  await gridApi.formApi.reset();
  Object.assign(sorting, { sortField: 'createTime', sortOrder: 'desc' });
  category.value = undefined;
  categoryTree.value?.reset();
  await query();
}
function selectCategory(id?: StandardId) {
  category.value = id;
  page.value = 1;
  void load();
}
function detail(row: Element) {
  void router.push({
    name: 'GovernanceStandardElementDetail',
    params: { id: row.id },
  });
}
async function remove(row: Element) {
  const current = mutation.begin();
  await confirm(`是否确认删除数据元编号为“${row.id}”的数据项？`);
  if (!current()) return;
  await deleteStandardElement(row.id);
  if (current()) {
    message.success('删除成功');
    void load();
  }
}
async function removeSelected() {
  const ids = [...selected.value];
  if (ids.length === 0) return;
  const current = mutation.begin();
  await confirm(`是否确认删除选中的 ${ids.length} 项数据元？`);
  if (!current()) return;
  await deleteStandardElementList(ids);
  if (current()) {
    selected.value = [];
    message.success('删除成功');
    void load();
  }
}
async function status(row: Element) {
  const current = mutation.begin();
  const nextStatus = row.status === 0 ? 1 : 0;
  await confirm(
    `确认要${nextStatus === 0 ? '启用' : '禁用'}“${row.name}”数据元吗？`,
  );
  if (!current()) return;
  changing.value = row.id;
  try {
    const record = await getStandardElement(row.id);
    if (!current()) return;
    if (record.revision === undefined) {
      message.error('版本信息缺失，请刷新后重试');
      return;
    }
    await updateStandardElementStatus(row.id, nextStatus, record.revision);
    if (current()) {
      message.success('操作成功');
      void load();
    }
  } finally {
    if (current()) changing.value = undefined;
  }
}
async function lifecycle(row: Element, action: 'deprecate' | 'publish') {
  const current = mutation.begin();
  const actions = {
    deprecate: ['废止', deprecateStandardElement],
    publish: ['发布', publishStandardElement],
  } as const;
  await confirm(`确认${actions[action][0]}数据元“${row.name}”吗？`);
  if (!current()) return;
  await actions[action][1](row.id);
  if (current()) {
    message.success('操作成功');
    void load();
  }
}
function rowActions(row: Element): ActionItem[] {
  return [
    {
      label: '修改',
      type: 'link',
      icon: ACTION_ICON.EDIT,
      auth: ['governance:standard-element:update'],
      disabled: row.lifecycleStatus === 'DEPRECATED',
      onClick: () => formApi.setData(row).open(),
    },
    {
      label: '删除',
      type: 'link',
      danger: true,
      icon: ACTION_ICON.DELETE,
      auth: ['governance:standard-element:delete'],
      disabled: row.status === 0 || row.lifecycleStatus === 'PUBLISHED',
      onClick: () => remove(row),
    },
    {
      label: '详情',
      type: 'link',
      icon: ACTION_ICON.VIEW,
      auth: ['governance:standard-element:query'],
      onClick: () => detail(row),
    },
  ];
}
function lifecycleActions(row: Element): ActionItem[] {
  return [
    {
      label: '发布',
      icon: 'lucide:rocket',
      ifShow: row.lifecycleStatus === 'DRAFT',
      auth: ['governance:standard-element:update'],
      onClick: () => lifecycle(row, 'publish'),
    },
    {
      label: '废止',
      icon: 'lucide:circle-off',
      ifShow: row.lifecycleStatus === 'PUBLISHED',
      auth: ['governance:standard-element:update'],
      onClick: () => lifecycle(row, 'deprecate'),
    },
  ];
}
async function exportRows() {
  const current = mutation.begin();
  const source = await exportStandardElement({
    ...applied,
    ...sorting,
    categoryId: category.value,
  });
  if (current())
    downloadFileFromBlobPart({ fileName: '标准数据元.xls', source });
}
async function overview() {
  statsApi.open();
  statistics.value = undefined;
  const current = statsRequest.begin();
  const result = await getStandardElementStatistics();
  if (current()) statistics.value = result;
}
const statLabels: Record<string, string> = {
  totalCount: '数据元总数',
  draftCount: '草稿',
  publishedCount: '已发布',
  deprecatedCount: '已废止',
  mappedElementCount: '已落标',
  modelMappedElementCount: '建模落标',
  mappingRate: '落标率（%）',
  codeSetBoundCount: '已绑码表',
};
watch(
  () => [access.accessToken, access.refreshToken, access.accessCodes],
  async () => {
    await nextTick();
    void load();
  },
  { deep: true },
);
onDeactivated(() => {
  active.value = false;
});
onActivated(() => {
  if (!active.value) {
    active.value = true;
    void load();
  }
});
onMounted(() => {
  void load();
  void loadElementNameCaches().catch(() => {});
});
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="load" />
    <ImportModal @success="load" />
    <StandardListLayout v-model:collapsed="collapsed">
      <template #tree>
        <CategoryTree ref="categoryTree" @select="selectCategory" />
      </template>
      <Grid table-title="数据元管理" class="min-h-0 flex-1">
        <template #toolbar-tools>
          <TableAction
            :actions="[
              {
                label: '新增',
                type: 'primary',
                icon: ACTION_ICON.ADD,
                auth: ['governance:standard-element:create'],
                onClick: () => formApi.setData({ categoryId: category }).open(),
              },
              {
                label: '导入',
                icon: ACTION_ICON.UPLOAD,
                auth: ['governance:standard-element:import'],
                onClick: () => importApi.open(),
              },
              {
                label: '批量删除',
                icon: ACTION_ICON.DELETE,
                danger: true,
                ifShow: bulkMode,
                disabled: !selected.length,
                auth: ['governance:standard-element:delete'],
                onClick: removeSelected,
              },
            ]"
            :drop-down-actions="[
              {
                label: bulkMode ? '退出批量操作' : '批量操作',
                icon: 'lucide:list-checks',
                auth: ['governance:standard-element:delete'],
                onClick: toggleBulkMode,
              },
              {
                label: '导出',
                icon: ACTION_ICON.DOWNLOAD,
                auth: ['governance:standard-element:export'],
                onClick: exportRows,
              },
              {
                label: '统计概览',
                icon: 'lucide:chart-no-axes-combined',
                auth: ['governance:standard-element:query'],
                onClick: overview,
              },
            ]"
          />
        </template>
        <template #top>
          <Alert
            v-if="failed"
            message="数据元加载失败，请重试"
            type="error"
            show-icon
            class="mb-3"
          >
            <template #action>
              <Button size="small" @click="load">
                <template #icon>
                  <IconifyIcon icon="lucide:refresh-cw" />
                </template>
                重试
              </Button>
            </template>
          </Alert>
        </template>
        <template #name="{ row }">
          <Button
            class="standard-name-link h-auto whitespace-normal break-words text-left"
            type="link"
            :disabled="!can('query')"
            @click="detail(row)"
          >
            {{ row.name }}
          </Button>
        </template>
        <template #category="{ row }">
          {{ row.categoryName || formatCategoryName(row.categoryId) }}
        </template>
        <template #typeHeader><StandardHeaderHelp label="类型" /></template>
        <template #statusHeader>
          <StandardHeaderHelp label="状态" status-hint="启用且发布后可被其他模块引用" />
        </template>
        <template #type><Tag color="green">数据元</Tag></template>
        <template #status="{ row }">
          <Switch
            :checked="row.status === 0"
            :disabled="!can('update')"
            :loading="changing === row.id"
            @change="status(row)"
          />
        </template>
        <template #creator="{ row }">
          {{ row.creatorName || formatPersonName(row.creator) }}
        </template>
        <template #actions="{ row }">
          <TableAction
            :actions="rowActions(row)"
            :drop-down-actions="lifecycleActions(row)"
          />
        </template>
      </Grid>
    </StandardListLayout>
    <StatsModal title="数据元统计概览" :fullscreen-button="false">
      <dl class="grid grid-cols-2 gap-3">
        <template v-for="(label, key) in statLabels" :key="key">
          <dt class="text-muted-foreground">{{ label }}</dt>
          <dd>
            {{
              statistics?.[
                key as keyof GovernanceStandardElementApi.Statistics
              ] ?? '-'
            }}
          </dd>
        </template>
      </dl>
    </StatsModal>
  </Page>
</template>

<style scoped>
.standard-list-layout :deep(.vxe-grid .vxe-body--column) {
  font-size: 12px;
}

.standard-list-layout :deep(.standard-name-link) {
  font-size: inherit;
}
</style>
