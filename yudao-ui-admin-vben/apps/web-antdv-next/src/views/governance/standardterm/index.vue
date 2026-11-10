<script lang="ts" setup>
import type { GovernanceCategoryTermApi } from '#/api/governance/categoryterm';
import type { GovernanceStandardTermApi } from '#/api/governance/standardterm';

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

import { useAccess } from '@vben/access';
import { confirm, Page, useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { isEmpty } from '@vben/utils';

import { Input, message, Spin, Tree } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  deleteStandardTerm,
  deleteStandardTermList,
  getStandardTermCategories,
  getStandardTermPage,
} from '#/api/governance/standardterm';
import TreeToolbar from '#/components/tree-toolbar/index.vue';
import { useObjectDetailQuery } from '#/hooks/use-object-detail-query';
import { useObjectRequest } from '#/hooks/use-object-request';
import { $t } from '#/locales';
import { parseResourceId } from '#/utils/resource-id';

import { useTreeExpansion } from '../common/use-tree-expansion';
import { standardCategoryTree } from '../standardcommon/data';
import StandardListLayout from '../standardcommon/standard-list-layout.vue';
import { loadTermUserList, useGridColumns, useGridFormSchema } from './data';
import Detail from './modules/detail.vue';
import Form from './modules/form.vue';

defineOptions({ name: 'GovernanceStandardTerm' });

const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const canQuery = () =>
  !!access.accessToken &&
  !access.loginExpired &&
  !!parseResourceId(user.userInfo?.userId) &&
  hasAccessByCodes(['governance:standard-term:query']);
const active = ref(true);
const selectedCategoryId = ref<string>();
const categories = ref<GovernanceCategoryTermApi.Category[]>([]);
const categorySearch = ref('');
const expanded = ref<string[]>(['all']);
const collapsed = ref(false);
const categoryLoading = ref(false);
const categoryFailed = ref(false);
const rows = ref<GovernanceStandardTermApi.StandardTerm[]>([]);
const loading = ref(false);
const listFailed = ref(false);
const page = ref(1);
const pageSize = ref(10);
const total = ref(0);
const filters = reactive<{ name?: string; status?: number }>({});
const scope = () =>
  JSON.stringify([
    active.value,
    access.accessToken,
    access.refreshToken,
    access.loginExpired,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]);
const requests = useObjectRequest(
  () =>
    JSON.stringify([
      scope(),
      page.value,
      pageSize.value,
      selectedCategoryId.value,
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
const tree = computed(() =>
  standardCategoryTree(categories.value, categorySearch.value).map((root) => ({
    ...root,
    title: '业务术语类目',
  })),
);

function categoryName(id?: null | number | string) {
  if (id === null || id === undefined || id === '') return '未分类';
  return (
    categories.value.find((item) => String(item.id) === String(id))
      ?.categoryName || String(id)
  );
}

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});
const [DetailModal, detailModalApi] = useVbenModal({
  connectedComponent: Detail,
  destroyOnClose: true,
});
useObjectDetailQuery((id) => detailModalApi.setData({ id }).open());

function handleRefresh() {
  void load();
}
function handleCreate() {
  formModalApi.setData({ termCategoryId: selectedCategoryId.value }).open();
}
function handleEdit(row: GovernanceStandardTermApi.StandardTerm) {
  formModalApi.setData(row).open();
}
function handleDetail(row: GovernanceStandardTermApi.StandardTerm) {
  detailModalApi.setData(row).open();
}
async function handleDelete(row: GovernanceStandardTermApi.StandardTerm) {
  await deleteStandardTerm(row.id!);
  message.success($t('ui.actionMessage.deleteSuccess', [row.name]));
  handleRefresh();
}
const checkedIds = ref<(number | string)[]>([]);
function handleRowCheckboxChange({
  records,
}: {
  records: GovernanceStandardTermApi.StandardTerm[];
}) {
  checkedIds.value = records.map((item) => item.id!);
}
async function handleDeleteBatch() {
  await confirm($t('ui.actionMessage.deleteBatchConfirm'));
  await deleteStandardTermList(checkedIds.value);
  checkedIds.value = [];
  message.success($t('ui.actionMessage.deleteSuccess'));
  handleRefresh();
}

const [Grid, gridApi] = useVbenVxeGrid<GovernanceStandardTermApi.StandardTerm>({
  formOptions: {
    schema: useGridFormSchema(),
    handleSubmit: query,
    handleReset: reset,
  },
  gridOptions: {
    showOverflow: false,
    columns: useGridColumns(),
    data: [],
    height: 'auto',
    scrollbarConfig: { height: 6 },
    keepSource: true,
    proxyConfig: { enabled: false, autoLoad: false },
    pagerConfig: { currentPage: 1, pageSize: 10, total: 0 },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: {
      refresh: true,
      search: true,
      refreshOptions: { queryMethod: load },
    },
  },
  gridEvents: {
    checkboxAll: handleRowCheckboxChange,
    checkboxChange: handleRowCheckboxChange,
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
  if (!active.value || !canQuery()) return;
  const current = requests.begin();
  loading.value = true;
  listFailed.value = false;
  try {
    const [result] = await Promise.all([
      getStandardTermPage({
        ...filters,
        termCategoryId: selectedCategoryId.value,
        pageNo: page.value,
        pageSize: pageSize.value,
      }),
      loadTermUserList().catch(() => []),
    ]);
    if (current()) {
      rows.value = result.list;
      total.value = result.total;
    }
  } catch {
    if (current()) listFailed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
async function loadCategories() {
  if (!active.value || !canQuery()) return;
  const current = categoryRequests.begin();
  categoryLoading.value = true;
  categoryFailed.value = false;
  try {
    const result = await getStandardTermCategories();
    if (current()) {
      categories.value = result;
      if (
        selectedCategoryId.value &&
        !result.some((item) => String(item.id) === selectedCategoryId.value)
      ) {
        selectedCategoryId.value = undefined;
        page.value = 1;
        void load();
      }
    }
  } catch {
    if (current()) categoryFailed.value = true;
  } finally {
    if (current()) categoryLoading.value = false;
  }
}
async function query() {
  const values = await gridApi.formApi.getValues();
  filters.name = values.name || undefined;
  filters.status = values.status;
  page.value = 1;
  await load();
}
async function reset() {
  await gridApi.formApi.reset();
  filters.name = undefined;
  filters.status = undefined;
  page.value = 1;
  await load();
}
function selectCategory(keys: (number | string)[]) {
  const key = String(keys[0] ?? 'all');
  selectedCategoryId.value = key === 'all' ? undefined : key;
  checkedIds.value = [];
  page.value = 1;
  void load();
}
watch(
  scope,
  (currentScope) => {
    requests.begin();
    categoryRequests.begin();
    selectedCategoryId.value = undefined;
    checkedIds.value = [];
    rows.value = [];
    total.value = 0;
    categories.value = [];
    listFailed.value = false;
    categoryFailed.value = false;
    formModalApi.close();
    detailModalApi.close();
    if (active.value && canQuery()) {
      void nextTick().then(() => {
        if (scope() === currentScope && active.value && canQuery()) {
          void load();
          void loadCategories();
        }
      });
    }
  },
  { flush: 'sync' },
);
onMounted(() => {
  void load();
  void loadCategories();
});
onDeactivated(() => {
  active.value = false;
});
onBeforeUnmount(() => {
  active.value = false;
});
onActivated(() => {
  active.value = true;
});
const { allExpanded, toggleExpand } = useTreeExpansion(
  tree,
  expanded,
  categorySearch,
);
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="handleRefresh" />
    <DetailModal />
    <StandardListLayout
      v-model:collapsed="collapsed"
      class="term-layout"
      @resize="gridApi.grid?.recalculate()"
    >
      <template #tree>
        <div class="standard-tree-header flex items-center gap-2">
          <Input
            class="min-w-0 flex-1"
            v-model:value="categorySearch"
            allow-clear
            placeholder="请输入业务术语类目"
            aria-label="搜索业务术语类目"
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
            :selected-keys="[selectedCategoryId || 'all']"
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
        </Spin>
      </template>
      <Grid class="subtle-grid-scrollbar" table-title="业务术语列表">
        <template #table-title>
          <span>业务术语列表</span>
        </template>
        <template #category="{ row }">
          {{ categoryName(row.termCategoryId) }}
        </template>
        <template #toolbar-tools>
          <TableAction
            :actions="[
              {
                label: $t('common.create'),
                type: 'primary',
                icon: ACTION_ICON.ADD,
                auth: ['governance:standard-term:create'],
                onClick: handleCreate,
              },
              {
                label: $t('ui.actionTitle.deleteBatch'),
                type: 'primary',
                danger: true,
                icon: ACTION_ICON.DELETE,
                auth: ['governance:standard-term:delete'],
                disabled: isEmpty(checkedIds),
                onClick: handleDeleteBatch,
              },
            ]"
          />
        </template>
        <template #actions="{ row }">
          <TableAction
            :actions="[
              {
                label: $t('common.edit'),
                type: 'link',
                icon: ACTION_ICON.EDIT,
                auth: ['governance:standard-term:update'],
                onClick: handleEdit.bind(null, row),
              },
            ]"
            :drop-down-actions="[
              {
                label: $t('common.detail'),
                type: 'link',
                icon: ACTION_ICON.VIEW,
                auth: ['governance:standard-term:query'],
                onClick: handleDetail.bind(null, row),
              },
              {
                label: $t('common.delete'),
                type: 'link',
                danger: true,
                icon: ACTION_ICON.DELETE,
                auth: ['governance:standard-term:delete'],
                popConfirm: {
                  title: $t('ui.actionMessage.deleteConfirm', [row.name]),
                  confirm: handleDelete.bind(null, row),
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

.term-layout :deep(.standard-list-tree) {
  width: clamp(220px, 16.6667%, 260px);
}

@media (width >= 641px) and (width <= 900px) {
  .term-layout :deep(.standard-list-tree) {
    width: 210px;
    min-width: 210px;
  }
}

@media (width <= 640px) {
  .term-layout :deep(.standard-list-tree) {
    width: 100%;
  }
}
</style>
