<script setup lang="ts">
import type { StandardDocApi } from '#/api/governance/doc';

import {
  computed,
  nextTick,
  onActivated,
  onDeactivated,
  onMounted,
  reactive,
  ref,
  watch,
} from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page, useVbenModal } from '@vben/common-ui';
import { DICT_TYPE } from '@vben/constants';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import { Alert, Button, Input, message, Spin, Tree } from 'antdv-next';

import { TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import { deleteDoc, getDocCategories, getDocPage } from '#/api/governance/doc';
import TreeToolbar from '#/components/tree-toolbar/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';

import { useTreeExpansion } from '../common/use-tree-expansion';
import { standardCategoryTree } from '../standardcommon/data';
import StandardListLayout from '../standardcommon/standard-list-layout.vue';
import { useGridFormSchema } from './data';
import Form from './modules/form.vue';
import Preview from './modules/preview.vue';
import StandardVersionModal from './modules/versions.vue';

defineOptions({ name: 'StandardDocRegister' });
const router = useRouter();
const route = useRoute();
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const can = (action: string) => hasAccessByCodes([`governance:doc:${action}`]);
const active = ref(true);
const categoryId = ref<string>();
const categories = ref<StandardDocApi.Category[]>([]);
const search = ref('');
const expanded = ref<string[]>(['all']);
const collapsed = ref(false);
const categoryLoading = ref(false);
const categoryFailed = ref(false);
const rows = ref<StandardDocApi.Doc[]>([]);
const page = ref(1);
const pageSize = ref(10);
const total = ref(0);
const loading = ref(false);
const failed = ref(false);
const deleting = ref<string>();
const filters = reactive<
  Pick<
    StandardDocApi.DocPageParams,
    'implementStatus' | 'nameCn' | 'stdCode' | 'stdType'
  >
>({});
const tree = computed(() =>
  standardCategoryTree(categories.value, search.value).map((root) => ({
    ...root,
    title: '标准类目',
  })),
);
const scope = () => JSON.stringify([active.value, access.accessCodes]);
const requests = useObjectRequest(
  () =>
    JSON.stringify([
      scope(),
      page.value,
      pageSize.value,
      categoryId.value,
      filters,
    ]),
  () => {
    rows.value = [];
    total.value = 0;
    loading.value = false;
  },
);
const categoryRequests = useObjectRequest(scope, () => {
  categories.value = [];
  categoryLoading.value = false;
});
const mutations = useObjectRequest(scope, () => {
  deleting.value = undefined;
});
const [FormModal, formApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});
const [PreviewModal, previewApi] = useVbenModal({
  connectedComponent: Preview,
  destroyOnClose: true,
});
const [VersionModal, versionApi] = useVbenModal({
  connectedComponent: StandardVersionModal,
  destroyOnClose: true,
});
const [Grid, gridApi] = useVbenVxeGrid<StandardDocApi.Doc>({
  formOptions: {
    schema: useGridFormSchema(),
    handleSubmit: query,
    handleReset: reset,
  },
  gridOptions: {
    showOverflow: false,
    columns: [
      { field: 'id', title: '编号', width: 100 },
      { field: 'stdCode', title: '标准号', minWidth: 150 },
      { field: 'nameCn', title: '标准名称', minWidth: 220 },
      {
        field: 'categoryName',
        title: '标准类目',
        minWidth: 160,
        formatter: ({ row }: { row: StandardDocApi.Doc }) =>
          row.categoryName || row.categoryId || '未分类',
      },
      {
        field: 'stdType',
        title: '标准类型',
        width: 120,
        cellRender: {
          name: 'CellDict',
          props: { type: DICT_TYPE.STANDARD_STD_TYPE },
        },
      },
      {
        field: 'implementStatus',
        title: '标准状态',
        width: 120,
        cellRender: {
          name: 'CellDict',
          props: { type: DICT_TYPE.STANDARD_IMPLEMENT_STATUS },
        },
      },
      {
        field: 'creatorName',
        title: '创建人',
        width: 120,
        formatter: ({ row }: { row: StandardDocApi.Doc }) =>
          row.creatorName || row.creator || '-',
      },
      {
        field: 'createTime',
        title: '创建时间',
        width: 180,
        formatter: 'formatDateTime',
      },
      {
        title: '操作',
        width: 260,
        fixed: 'right',
        showOverflow: false,
        slots: { default: 'actions' },
      },
    ],
    data: [],
    height: 'auto',
    rowConfig: { keyField: 'id', isHover: true },
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
  },
});
watch([rows, loading, page, pageSize, total], () =>
  gridApi.setGridOptions({
    data: rows.value,
    loading: loading.value,
    pagerConfig: {
      currentPage: page.value,
      pageSize: pageSize.value,
      total: total.value,
    },
  }),
);
async function load() {
  if (!active.value || !can('query')) return;
  const current = requests.begin();
  loading.value = true;
  failed.value = false;
  try {
    const result = await getDocPage({
      ...filters,
      categoryId: categoryId.value,
      pageNo: page.value,
      pageSize: pageSize.value,
    });
    if (current()) {
      rows.value = result.list;
      total.value = result.total;
    }
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
async function loadCategories() {
  if (!active.value || !can('query')) return;
  const current = categoryRequests.begin();
  categoryLoading.value = true;
  categoryFailed.value = false;
  try {
    const result = await getDocCategories();
    if (current()) categories.value = result;
  } catch {
    if (current()) categoryFailed.value = true;
  } finally {
    if (current()) categoryLoading.value = false;
  }
}
async function query() {
  const values = await gridApi.formApi.getValues();
  filters.nameCn = values.nameCn || undefined;
  filters.stdCode = values.stdCode || undefined;
  filters.stdType = values.stdType || undefined;
  filters.implementStatus = values.implementStatus || undefined;
  page.value = 1;
  await load();
}
async function reset() {
  for (const key of Object.keys(filters) as (keyof typeof filters)[])
    filters[key] = undefined;
  page.value = 1;
  await load();
}
function selectCategory(keys: (number | string)[]) {
  const key = String(keys[0] ?? 'all');
  categoryId.value = key === 'all' ? undefined : key;
  page.value = 1;
  void load();
}
function detail(row: StandardDocApi.Doc) {
  void router.push({
    name: 'GovernanceStandardDocRegisterDetail',
    params: { id: String(row.id) },
    query: { back: route.path },
  });
}
async function remove(row: StandardDocApi.Doc) {
  if (deleting.value) return;
  const current = mutations.begin();
  deleting.value = String(row.id);
  try {
    await deleteDoc(row.id);
    if (!current()) return;
    message.success('删除成功');
    if (rows.value.length === 1 && page.value > 1) page.value--;
    await load();
  } finally {
    if (current()) deleting.value = undefined;
  }
}
watch(
  () => [access.accessToken, access.refreshToken, access.accessCodes],
  async () => {
    categoryId.value = undefined;
    await nextTick();
    void load();
    void loadCategories();
  },
  { deep: true },
);
onMounted(() => {
  void load();
  void loadCategories();
});
onDeactivated(() => {
  active.value = false;
});
onActivated(() => {
  if (!active.value) {
    active.value = true;
    void load();
    void loadCategories();
  }
});
const { allExpanded, toggleExpand } = useTreeExpansion(tree, expanded, search);
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="load" />
    <PreviewModal />
    <VersionModal />
    <Alert v-if="!can('query')" type="warning" message="没有标准登记查询权限" />
    <StandardListLayout
      v-else
      v-model:collapsed="collapsed"
      @resize="gridApi.grid?.recalculate()"
    >
      <template #tree>
        <div class="standard-tree-header flex items-center gap-2">
          <Input
            class="min-w-0 flex-1"
            v-model:value="search"
            allow-clear
            placeholder="请输入标准类目"
            aria-label="搜索标准类目"
          />

          <TreeToolbar
            :expanded="allExpanded"
            :loading="categoryLoading"
            @toggle-expand="toggleExpand"
            @refresh="loadCategories"
          />
        </div>
        <Spin :spinning="categoryLoading">
          <template v-if="categoryFailed"></template>
          <Tree
            v-else
            v-model:expanded-keys="expanded"
            :selected-keys="[categoryId || 'all']"
            :tree-data="tree"
            block-node
            @select="selectCategory"
          />
        </Spin>
      </template>
      <Grid>
        <template #toolbar-tools>
          <Button
            v-if="can('create')"
            type="primary"
            @click="
              formApi.setData({ categoryId, stdType: filters.stdType }).open()
            "
          >
            <template #icon><IconifyIcon icon="lucide:plus" /></template>
            新增
          </Button>
        </template>
        <template #table-title>
          <span>数据标准列表</span>
        </template>
        <template #actions="{ row }">
          <TableAction
            :actions="[
              {
                label: '详情',
                type: 'link',
                icon: 'lucide:eye',
                auth: ['governance:doc:query'],
                onClick: () => detail(row),
              },
              {
                label: '修改',
                type: 'link',
                icon: 'lucide:edit',
                auth: ['governance:doc:update'],
                onClick: () => formApi.setData({ id: row.id }).open(),
              },
            ]"
            :drop-down-actions="[
              {
                label: '预览原文',
                type: 'link',
                icon: 'lucide:file-search',
                auth: ['governance:doc:query'],
                disabled: !row.fileId && !row.fileUrl,
                onClick: () => previewApi.setData(row).open(),
              },
              {
                label: '版本快照',
                type: 'link',
                icon: 'lucide:history',
                auth: ['governance:doc:query'],
                onClick: () =>
                  versionApi
                    .setData({
                      objectType: 'DOC',
                      id: row.id,
                      name: row.nameCn,
                    })
                    .open(),
              },
              {
                label: '删除',
                type: 'link',
                icon: 'lucide:trash-2',
                auth: ['governance:doc:delete'],
                danger: true,
                disabled: !!deleting,
                popConfirm: {
                  title: '确认删除该标准？',
                  confirm: () => remove(row),
                },
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
</style>
