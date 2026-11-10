<script setup lang="ts">
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceStandardCodeSetApi as Api } from '#/api/governance/standardcodeset';

import {
  computed,
  nextTick,
  onActivated,
  onDeactivated,
  reactive,
  ref,
  watch,
} from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page, useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDate } from '@vben/utils';

import {
  Alert,
  Button,
  Input,
  message,
  Modal,
  Switch,
  Tag,
  Tree,
} from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  deleteStandardCodeSet,
  deleteStandardCodeSetList,
  getStandardCodeSet,
  getStandardCodeSetCategories,
  getStandardCodeSetPage,
  updateStandardCodeSetStatus,
} from '#/api/governance/standardcodeset';
import TreeToolbar from '#/components/tree-toolbar/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';

import { useTreeExpansion } from '../common/use-tree-expansion';
import { standardCategoryTree } from '../standardcommon/data';
import StandardHeaderHelp from '../standardcommon/standard-header-help.vue';
import StandardListLayout from '../standardcommon/standard-list-layout.vue';
import Form from './modules/form.vue';
import ImportForm from './modules/import-form.vue';

defineOptions({ name: 'GovernanceStandardCodeSet' });
const router = useRouter();
const { hasAccessByCodes } = useAccess();
const can = (action: string) =>
  hasAccessByCodes([`governance:standard-code-set:${action}`]);
const access = useAccessStore();
const user = useUserStore();
const scope = computed(
  () =>
    `${access.accessToken}/${access.refreshToken}/${user.userInfo?.userId}/${access.accessCodes.join(',')}`,
);
const categories = ref<Api.Category[]>([]);
const categorySearch = ref('');
const categoryId = ref<Api.Id>();
const selectedKeys = ref<string[]>(['all']);
const expandedKeys = ref<string[]>([]);
const collapsed = ref(false);
const applied = reactive({ name: '', type: '' });
const page = ref(1);
const pageSize = ref(10);
const total = ref(0);
const rows = ref<Api.StandardCodeSet[]>([]);
const loading = ref(false);
const error = ref('');
const categoryError = ref('');
const categoryLoading = ref(false);
const sortField = ref<'createTime' | 'id'>('createTime');
const sortOrder = ref<'asc' | 'desc'>('desc');
const selectedRows = ref<Api.Id[]>([]);
const active = ref(true);
const request = useObjectRequest(
  () => scope.value,
  () => {
    rows.value = [];
    total.value = 0;
    selectedRows.value = [];
  },
);
const categoryRequest = useObjectRequest(
  () => scope.value,
  () => {
    categories.value = [];
  },
);
const actionRequest = useObjectRequest(() => scope.value);
const tree = computed(() =>
  standardCategoryTree(categories.value, categorySearch.value),
);
function gridColumns(): VxeTableGridOptions<Api.StandardCodeSet>['columns'] {
  return [
    { type: 'checkbox', width: 48 },
    { field: 'id', title: '编号', width: 90, sortable: true },
    {
      field: 'name',
      title: '中文名称',
      minWidth: 200,
      align: 'left',
      slots: { default: 'name' },
    },
    { field: 'type', title: '英文名称', minWidth: 180, align: 'left' },
    { field: 'description', title: '描述', minWidth: 200, align: 'left' },
    { field: 'categoryName', title: '类目', width: 160 },
    {
      field: 'kind',
      title: '类型',
      width: 90,
      slots: { default: 'kind', header: 'kindHeader' },
    },
    {
      field: 'status',
      title: '状态',
      width: 90,
      slots: { default: 'status', header: 'statusHeader' },
    },
    { field: 'creatorName', title: '创建人', width: 130 },
    {
      field: 'createTime',
      title: '创建时间',
      width: 180,
      sortable: true,
      formatter: ({ cellValue }) =>
        cellValue ? formatDate(cellValue, 'YYYY-MM-DD HH:mm') : '-',
    },
    {
      title: '操作',
      width: 300,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}
const [Grid, gridApi] = useVbenVxeGrid<Api.StandardCodeSet>({
  formOptions: {
    schema: [
      {
        fieldName: 'name',
        label: '中文名称',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入中文名称' },
      },
      {
        fieldName: 'type',
        label: '英文名称',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入英文名称' },
      },
    ],
    handleSubmit: async (values) => {
      Object.assign(applied, {
        name: values.name ?? '',
        type: values.type ?? '',
      });
      page.value = 1;
      await load();
    },
    handleReset: async () => {
      await gridApi.formApi.reset();
      reset();
    },
  },
  gridOptions: {
    columns: gridColumns(),
    data: [],
    height: 'auto',
    showOverflow: false,
    rowConfig: { keyField: 'id', isHover: true },
    sortConfig: {
      remote: true,
      multiple: false,
      defaultSort: { field: 'createTime', order: 'desc' },
    },
    pagerConfig: { currentPage: 1, pageSize: 10, total: 0 },
    proxyConfig: { enabled: false, autoLoad: false },
    toolbarConfig: {
      refresh: true,
      refreshOptions: { queryMethod: load },
      search: true,
    },
  },
  gridEvents: {
    checkboxAll: ({ records }: { records: Api.StandardCodeSet[] }) => {
      selectedRows.value = records.map((row) => row.id);
    },
    checkboxChange: ({ records }: { records: Api.StandardCodeSet[] }) => {
      selectedRows.value = records.map((row) => row.id);
    },
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
      sortField.value = field === 'id' ? 'id' : 'createTime';
      sortOrder.value = order === 'asc' ? 'asc' : 'desc';
      page.value = 1;
      void load();
    },
  },
});
watch(
  [rows, loading, page, pageSize, total],
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
    void gridApi.grid.sort?.(sortField.value, sortOrder.value);
  },
  { immediate: true },
);
const [FormModal, formApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});
const [ImportModal, importApi] = useVbenModal({
  connectedComponent: ImportForm,
  destroyOnClose: true,
});
async function load() {
  const current = request.begin();
  rows.value = [];
  selectedRows.value = [];
  void gridApi.grid?.clearCheckboxRow?.();
  error.value = '';
  if (!active.value || !can('query')) {
    loading.value = false;
    total.value = 0;
    return;
  }
  loading.value = true;
  try {
    const result = await getStandardCodeSetPage({
      pageNo: page.value,
      pageSize: pageSize.value,
      name: applied.name || undefined,
      type: applied.type || undefined,
      categoryId: categoryId.value,
      sortField: sortField.value,
      sortOrder: sortOrder.value,
    });
    if (!current()) return;
    rows.value = result.list;
    total.value = result.total;
    if (page.value > 1 && result.list.length === 0 && result.total) {
      page.value = Math.ceil(result.total / pageSize.value);
      await load();
    }
  } catch {
    if (current()) error.value = '代码表加载失败，请重试';
  } finally {
    if (current()) loading.value = false;
  }
}
async function loadCategories() {
  const current = categoryRequest.begin();
  categoryError.value = '';
  if (!can('query') || !active.value) return;
  categoryLoading.value = true;
  try {
    const result = await getStandardCodeSetCategories();
    if (current()) {
      categories.value = result;
    }
  } catch {
    if (current()) categoryError.value = '类目加载失败';
  } finally {
    if (current()) categoryLoading.value = false;
  }
}
function reset() {
  Object.assign(applied, { name: '', type: '' });
  categoryId.value = undefined;
  selectedKeys.value = ['all'];
  categorySearch.value = '';
  page.value = 1;
  void load();
}
function selectCategory(keys: (number | string)[]) {
  selectedKeys.value = keys.map(String);
  categoryId.value = keys[0] && keys[0] !== 'all' ? keys[0] : undefined;
  page.value = 1;
  void load();
}
function openDetail(row: Api.StandardCodeSet) {
  void router.push({
    name: 'GovernanceStandardCodeSetDetail',
    params: { id: String(row.id) },
  });
}
function remove(row?: Api.StandardCodeSet) {
  const ids = row ? [row.id] : [...selectedRows.value];
  const current = actionRequest.begin();
  Modal.confirm({
    title: row
      ? `确定删除代码表“${row.name}”吗？`
      : `确定删除选中的 ${ids.length} 个代码表吗？`,
    okText: '确定',
    cancelText: '取消',
    async onOk() {
      if (!current() || !can('delete')) return;
      await (row
        ? deleteStandardCodeSet(row.id)
        : deleteStandardCodeSetList(ids));
      if (current()) {
        message.success('删除成功');
        await load();
      }
    },
  });
}
function toggle(row: Api.StandardCodeSet) {
  const current = actionRequest.begin();
  const status = row.status === 0 ? 1 : 0;
  Modal.confirm({
    title: `确定${status === 0 ? '启用' : '停用'}代码表“${row.name}”吗？`,
    okText: '确定',
    cancelText: '取消',
    async onOk() {
      if (!current() || !can('update')) return;
      const latest = await getStandardCodeSet(row.id);
      if (!current()) return;
      if (latest.revision === undefined)
        throw new Error('代码表版本缺失，请刷新后重试');
      await updateStandardCodeSetStatus(latest.id, status, latest.revision);
      if (current()) {
        message.success('状态已更新');
        await load();
      }
    },
  });
}
watch(
  scope,
  () => {
    categoryId.value = undefined;
    selectedKeys.value = ['all'];
    page.value = 1;
    void formApi.close?.();
    void importApi.close?.();
    void loadCategories();
    void load();
  },
  { immediate: true },
);
onDeactivated(() => {
  active.value = false;
  request.begin();
  categoryRequest.begin();
  actionRequest.begin();
});
onActivated(() => {
  if (!active.value) {
    active.value = true;
    void loadCategories();
    void load();
  }
});
const { allExpanded, toggleExpand } = useTreeExpansion(
  tree,
  expandedKeys,
  categorySearch,
);
</script>
<template>
  <Page auto-content-height>
    <FormModal @success="load" />
    <ImportModal @success="load" />
    <StandardListLayout v-model:collapsed="collapsed">
      <template #tree>
        <div class="standard-tree-header flex items-center gap-2">
          <Input
            class="min-w-0 flex-1"
            v-model:value="categorySearch"
            allow-clear
            placeholder="请输入关键字进行过滤"
          >
            <template #prefix><IconifyIcon icon="lucide:search" /></template>
          </Input>

          <TreeToolbar
            :expanded="allExpanded"
            :loading="categoryLoading"
            @toggle-expand="toggleExpand"
            @refresh="loadCategories"
          />
        </div>
        <Alert v-if="categoryError && (!/(?:加载|读取|获取).*失败/.test(categoryError) || /保存|冲突|未确认/.test(categoryError))" type="error" :message="categoryError">
          <template #action>
            <Button size="small" @click="loadCategories">
              <template #icon>
                <IconifyIcon icon="lucide:refresh-cw" />
              </template>
              重试
            </Button>
          </template>
        </Alert>
        <Tree
          v-model:expanded-keys="expandedKeys"
          :selected-keys="selectedKeys"
          :tree-data="tree"
          block-node
          @select="selectCategory"
        >
          <template #titleRender="node">
            <span class="standard-tree-title">
              <IconifyIcon icon="lucide:folder" class="size-4 text-primary" />
              <span>{{ node.title }}</span>
            </span>
          </template>
        </Tree>
      </template>
      <Alert
        v-if="error && (!/(?:加载|读取|获取).*失败/.test(error) || /保存|冲突|未确认/.test(error))"
        :message="error"
        type="error"
        show-icon
        class="mb-3"
      />
      <Grid class="min-h-0 flex-1" table-title="代码表管理">
        <template #kindHeader>
          <StandardHeaderHelp label="类型" />
        </template>
        <template #statusHeader>
          <StandardHeaderHelp label="状态" status-hint="启用后可被其他模块引用" />
        </template>
        <template #toolbar-tools>
          <TableAction
            :actions="[
              {
                label: '新增',
                type: 'primary',
                icon: ACTION_ICON.ADD,
                auth: ['governance:standard-code-set:create'],
                onClick: () => formApi.setData({ categoryId }).open(),
              },
              {
                label: '批量删除',
                danger: true,
                icon: ACTION_ICON.DELETE,
                auth: ['governance:standard-code-set:delete'],
                disabled: !selectedRows.length || loading,
                onClick: () => remove(),
              },
              {
                label: '导入码表/码值',
                type: 'primary',
                icon: ACTION_ICON.UPLOAD,
                auth: ['governance:standard-code-set:import'],
                onClick: () => importApi.open(),
              },
            ]"
          />
        </template>
        <template #empty>
          {{ can('query') ? '暂无记录' : '暂无查询权限' }}
        </template>
        <template #name="{ row }">
          <Button
            class="standard-name-link h-auto whitespace-normal break-words text-left"
            type="link"
            size="small"
            @click="openDetail(row)"
          >
            {{ row.name || '-' }}
          </Button>
        </template>
        <template #kind>
          <Tag color="blue">代码表</Tag>
        </template>
        <template #status="{ row }">
          <Switch
            :checked="row.status === 0"
            :disabled="!can('update')"
            size="small"
            @click="toggle(row)"
          />
        </template>
        <template #actions="{ row }">
          <TableAction
            :actions="[
              {
                label: '修改',
                type: 'link',
                icon: ACTION_ICON.EDIT,
                auth: ['governance:standard-code-set:update'],
                onClick: () => formApi.setData(row).open(),
              },
              {
                label: '删除',
                type: 'link',
                danger: true,
                icon: ACTION_ICON.DELETE,
                auth: ['governance:standard-code-set:delete'],
                onClick: () => remove(row),
              },
              {
                label: '详情',
                type: 'link',
                icon: ACTION_ICON.VIEW,
                auth: ['governance:standard-code-set:query'],
                onClick: () => openDetail(row),
              },
            ]"
          />
        </template>
      </Grid>
    </StandardListLayout>
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
