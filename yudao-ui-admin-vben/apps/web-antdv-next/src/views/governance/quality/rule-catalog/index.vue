<script setup lang="ts">
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { QualityCatalogApi } from '#/api/governance/quality/catalog';

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

import { useAccess } from '@vben/access';
import { Page, useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDate } from '@vben/utils';

import {
  Alert,
  Button,
  Checkbox,
  Input,
  Popover,
  Spin,
  Tag,
  Tree,
} from 'antdv-next';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  getRuleCatalogCategories,
  getRuleCatalogDetail,
  getRuleCatalogPage,
} from '#/api/governance/quality/catalog';
import TreeToolbar from '#/components/tree-toolbar/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';

import { useTreeExpansion } from '../../common/use-tree-expansion';
import StandardListLayout from '../../standardcommon/standard-list-layout.vue';
import CatalogIcon from './catalog-icon.vue';
import { catalogCopy, catalogTree, treeKeys } from './data';

import './catalog.css';

const props = defineProps<{ kind: QualityCatalogApi.Kind }>();
const copy = computed(() => catalogCopy[props.kind]);
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const canQuery = computed(() =>
  hasAccessByCodes([`governance:${props.kind}-rule:query`]),
);
const identity = computed(() =>
  JSON.stringify([props.kind, user.userInfo?.userId, access.accessCodes]),
);
const collapsed = ref(false);
const categorySearch = ref('');
const selectedCategory = ref<string>();
const expandedKeys = ref<string[]>(['all']);
const categories = ref<QualityCatalogApi.Category[]>([]);
const categoryLoading = ref(false);
const rows = ref<QualityCatalogApi.Rule[]>([]);
const loading = ref(false);
const failed = ref(false);
const pagination = reactive({ pageNo: 1, pageSize: 10, total: 0 });
const filters = reactive({ name: '', description: '' });
const applied = reactive({ name: '', description: '' });
const sorting = reactive<
  Pick<QualityCatalogApi.PageReq, 'sortField' | 'sortOrder'>
>({ sortField: 'createTime', sortOrder: 'desc' });
const visibleColumns = ref([
  'id',
  'name',
  'description',
  'category',
  'createTime',
  'actions',
]);
const detail = ref<QualityCatalogApi.Rule>();
const detailId = ref<QualityCatalogApi.Id>();
const detailLoading = ref(false);
const detailError = ref('');
let mounted = false;
let active = true;
let detailOpen = false;

const listRequest = useObjectRequest(
  () => identity.value,
  () => resetContext(),
);
const categoryRequest = useObjectRequest(() => identity.value);
const detailRequest = useObjectRequest(() =>
  JSON.stringify([identity.value, detailId.value]),
);
const treeData = computed(() => [
  {
    key: 'all',
    title: copy.value.category,
    children: catalogTree(categories.value, categorySearch.value),
  },
]);
const allColumns = computed(() => [
  { field: 'id', title: '编号', width: 80, sortable: true },
  { field: 'name', title: '规则名称', minWidth: 220, align: 'left' as const },
  {
    field: 'description',
    title: '描述',
    minWidth: 320,
    align: 'left' as const,
    cellRender: {
      name: 'CellTextEllipsis',
      props: { line: 1, center: true },
    },
  },
  {
    field: 'category',
    title: copy.value.category,
    minWidth: 160,
    slots: { default: 'category' },
  },
  {
    field: 'createTime',
    title: '创建时间',
    width: 180,
    sortable: true,
    formatter: ({ cellValue }: { cellValue?: number | string }) =>
      displayTime(cellValue),
  },
  {
    field: 'actions',
    title: '操作',
    width: 110,
    fixed: 'right' as const,
    showOverflow: false,
    slots: { default: 'actions' },
  },
]);
const columns = computed(() =>
  allColumns.value.filter((column) =>
    visibleColumns.value.includes(column.field),
  ),
);

const [Grid, gridApi] = useVbenVxeGrid<QualityCatalogApi.Rule>({
  formOptions: {
    schema: [
      {
        fieldName: 'name',
        label: '规则名称',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入规则名称' },
      },
      {
        fieldName: 'description',
        label: '描述',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入描述' },
      },
    ],
    handleValuesChange: (values) =>
      Object.assign(filters, {
        name: values.name ?? '',
        description: values.description ?? '',
      }),
    handleSubmit: (values) => {
      Object.assign(filters, {
        name: values.name ?? '',
        description: values.description ?? '',
      });
      search();
    },
    handleReset: async () => {
      await gridApi.formApi.reset();
      reset();
    },
  },
  gridOptions: {
    showOverflow: false,
    columns: columns.value,
    data: [],
    height: 'auto',
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
      refreshOptions: { queryMethod: loadRows },
      search: true,
      custom: false,
    },
  },
  gridEvents: {
    pageChange: ({
      currentPage,
      pageSize,
    }: {
      currentPage: number;
      pageSize: number;
    }) => changePage(currentPage, pageSize),
    sortChange: ({ field, order }: { field: string; order?: null | string }) =>
      sortTable(field, order),
  },
});
watch(
  [
    rows,
    loading,
    columns,
    () => pagination.pageNo,
    () => pagination.pageSize,
    () => pagination.total,
    () => sorting.sortField,
    () => sorting.sortOrder,
  ],
  async () => {
    gridApi.setGridOptions({
      columns:
        columns.value as VxeTableGridOptions<QualityCatalogApi.Rule>['columns'],
      data: rows.value,
      loading: loading.value,
      pagerConfig: {
        currentPage: pagination.pageNo,
        pageSize: pagination.pageSize,
        total: pagination.total,
      },
    });
    // 列更新后恢复服务端排序标记，避免刷新或显隐列时丢失当前排序。
    await nextTick();
    await nextTick();
    if (active) void gridApi.grid.sort?.(sorting.sortField, sorting.sortOrder);
  },
  { immediate: true },
);
const displayTime = (value?: number | string) =>
  value === undefined ? '-' : formatDate(value, 'YYYY-MM-DD HH:mm') || '-';
const categoryName = (row: QualityCatalogApi.Rule) =>
  (props.kind === 'audit' ? row.dimensionName : row.categoryName) || '-';

async function loadCategories() {
  if (!active || !canQuery.value) return;
  const current = categoryRequest.begin();
  categoryLoading.value = true;
  try {
    const result = await getRuleCatalogCategories(props.kind);
    if (!current() || !active) return;
    categories.value = result;
  } catch {
    // 保留已有分类，可通过树工具栏重新加载。
  } finally {
    if (current() && active) categoryLoading.value = false;
  }
}
async function loadRows() {
  if (!active || !canQuery.value) return;
  const current = listRequest.begin();
  loading.value = true;
  failed.value = false;
  rows.value = [];
  pagination.total = 0;
  try {
    const result = await getRuleCatalogPage(props.kind, {
      pageNo: pagination.pageNo,
      pageSize: pagination.pageSize,
      name: applied.name || undefined,
      description: applied.description || undefined,
      ...sorting,
      ...(props.kind === 'audit'
        ? { dimensionId: selectedCategory.value }
        : { categoryId: selectedCategory.value }),
    });
    if (!current() || !active) return;
    rows.value = result.list;
    pagination.total = result.total;
  } catch {
    if (current() && active) failed.value = true;
  } finally {
    if (current() && active) loading.value = false;
  }
}
function search() {
  Object.assign(applied, filters);
  pagination.pageNo = 1;
  void loadRows();
}
function reset() {
  sorting.sortField = 'createTime';
  sorting.sortOrder = 'desc';
  filters.name = '';
  filters.description = '';
  selectedCategory.value = undefined;
  categorySearch.value = '';
  expandedKeys.value = ['all', ...treeKeys(catalogTree(categories.value))];
  search();
}
function selectCategory(keys: (number | string)[]) {
  selectedCategory.value =
    keys[0] === undefined || keys[0] === 'all' ? undefined : String(keys[0]);
  pagination.pageNo = 1;
  void loadRows();
}
function changePage(page: number, size: number) {
  pagination.pageNo = size === pagination.pageSize ? page : 1;
  pagination.pageSize = size;
  void loadRows();
}
function sortTable(field: string, order?: null | string) {
  sorting.sortField = field === 'id' ? 'id' : 'createTime';
  sorting.sortOrder = order === 'asc' ? 'asc' : 'desc';
  pagination.pageNo = 1;
  void loadRows();
}
function toggleColumn(key: string, checked: boolean) {
  visibleColumns.value = checked
    ? [...visibleColumns.value, key]
    : visibleColumns.value.filter((entry) => entry !== key);
}
function clearDetail() {
  detailOpen = false;
  detailRequest.begin();
  detail.value = undefined;
  detailId.value = undefined;
  detailLoading.value = false;
  detailError.value = '';
}
const [DetailModal, detailModal] = useVbenModal({
  onOpenChange(open: boolean) {
    if (!open) clearDetail();
  },
});
async function openDetail(id: QualityCatalogApi.Id) {
  if (!active || !canQuery.value) return;
  clearDetail();
  detailOpen = true;
  detailId.value = id;
  const current = detailRequest.begin();
  detailLoading.value = true;
  detailModal.open();
  try {
    const result = await getRuleCatalogDetail(props.kind, id);
    if (!current() || !detailOpen || !active) return;
    detail.value = result ?? undefined;
    if (!result) detailError.value = '该规则不存在或已被删除。';
  } catch {
    if (current() && detailOpen && active)
      detailError.value = '规则详情加载失败，请重试。';
  } finally {
    if (current() && detailOpen && active) detailLoading.value = false;
  }
}
function resetContext() {
  listRequest.begin();
  categoryRequest.begin();
  clearDetail();
  void detailModal.close();
  rows.value = [];
  categories.value = [];
  loading.value = false;
  categoryLoading.value = false;
  failed.value = false;
  pagination.pageNo = 1;
  pagination.total = 0;
  filters.name = '';
  filters.description = '';
  applied.name = '';
  applied.description = '';
  void gridApi.formApi.setValues({ name: '', description: '' });
  selectedCategory.value = undefined;
  categorySearch.value = '';
  sorting.sortField = 'createTime';
  sorting.sortOrder = 'desc';
  const current = listRequest.begin();
  // 等所有会话监听器完成失效处理后，再为新会话发起请求。
  void nextTick(() => {
    if (mounted && active && current()) {
      void loadCategories();
      void loadRows();
    }
  });
}
onMounted(() => {
  mounted = true;
  void loadCategories();
  void loadRows();
});
onDeactivated(() => {
  active = false;
  resetContext();
});
onActivated(() => {
  if (!active) {
    active = true;
    void loadCategories();
    void loadRows();
  }
});
const { allExpanded, toggleExpand } = useTreeExpansion(
  treeData,
  expandedKeys,
  categorySearch,
);
</script>

<template>
  <Page
    auto-content-height
    content-class="rule-catalog-page"
    :class="`rule-catalog-${kind}`"
  >
    <Alert v-if="!canQuery" message="暂无此规则目录的查询权限" type="warning" />
    <StandardListLayout
      v-else
      v-model:collapsed="collapsed"
      class="min-h-0 flex-1"
      @resize="active && gridApi.grid?.recalculate?.(true)"
    >
      <template #tree>
        <div data-testid="catalog-sidebar">
          <div class="standard-tree-header flex items-center gap-2">
            <Input
              class="catalog-search-input"
              v-model:value="categorySearch"
              placeholder="搜索类目"
              :title="`请输入${copy.category}`"
              allow-clear
              aria-label="筛选分类树"
            >
              <template #prefix><CatalogIcon name="search" /></template>
            </Input>
            <TreeToolbar
              :expanded="allExpanded"
              :loading="categoryLoading"
              @toggle-expand="toggleExpand"
              @refresh="loadCategories"
            />
          </div>
          <Spin :spinning="categoryLoading">
            <Tree
              v-model:expanded-keys="expandedKeys"
              :tree-data="treeData"
              :selected-keys="[selectedCategory ?? 'all']"
              block-node
              @select="selectCategory"
            >
              <template #titleRender="node">
                <span
                  class="rule-catalog-tree-title"
                  :title="String(node.title)"
                >
                  <img
                    :src="`/images/rule-catalog-reference/${
                      node.children?.length || node.key === 'all'
                        ? 'tree-folder'
                        : 'tree-leaf'
                    }.png`"
                    alt=""
                  />
                  <span>{{ node.title }}</span>
                </span>
              </template>
            </Tree>
            <span
              v-if="categorySearch && !treeData[0]?.children.length"
              class="rule-catalog-tree-empty"
              >暂无匹配分类</span>
          </Spin>
        </div>
      </template>
      <Grid class="min-h-0 flex-1" :table-title="`${copy.title}列表`">
        <template #toolbar-tools>
          <Popover trigger="click" placement="bottomRight" title="显示列">
            <template #content>
              <div class="rule-catalog-column-options">
                <Checkbox
                  v-for="column in allColumns"
                  :key="column.field"
                  :checked="visibleColumns.includes(column.field)"
                  :disabled="
                    visibleColumns.length === 1 &&
                    visibleColumns.includes(column.field)
                  "
                  @update:checked="toggleColumn(column.field, $event)"
                >
                  {{ column.title }}
                </Checkbox>
              </div>
            </template>
            <Button aria-label="设置显示列" shape="circle">
              <CatalogIcon name="columns" />
            </Button>
          </Popover>
        </template>
        <template #top>
          <Alert
            v-if="failed"
            type="error"
            message="规则目录加载失败，请重新查询"
            class="mb-3"
          >
            <template #action>
              <Button @click="loadRows">
                <template #icon>
                  <IconifyIcon icon="lucide:refresh-cw" />
                </template>
                重试
              </Button>
            </template>
          </Alert>
        </template>
        <template #category="{ row }">
          <Tag color="blue">{{ categoryName(row) }}</Tag>
        </template>
        <template #actions="{ row }">
          <Button v-if="canQuery" type="link" @click="openDetail(row.id)">
            <template #icon><CatalogIcon name="eye" /></template>
            详情
          </Button>
        </template>
      </Grid>
    </StandardListLayout>
    <DetailModal
      :title="`${copy.title}详情`"
      class="rule-catalog-modal"
      :fullscreen-button="false"
      header-class="rule-catalog-dialog-header"
      content-class="rule-catalog-dialog-content"
      footer-class="rule-catalog-dialog-footer"
      :show-confirm-button="false"
      :show-cancel-button="false"
    >
      <Spin :spinning="detailLoading">
        <Alert v-if="detailError" :message="detailError" type="error">
          <template #action>
            <Button v-if="detailId !== undefined" @click="openDetail(detailId)">
              <template #icon>
                <IconifyIcon icon="lucide:refresh-cw" />
              </template>
              重试
            </Button>
          </template>
        </Alert>
        <dl
          v-if="detail"
          class="rule-catalog-detail"
          data-testid="catalog-detail"
        >
          <dt>编号</dt>
          <dd>{{ detail.id }}</dd>
          <dt>规则名称</dt>
          <dd>{{ detail.name || '-' }}</dd>
          <dt>{{ copy.category }}</dt>
          <dd>
            <span class="rule-catalog-tag">{{ categoryName(detail) }}</span>
          </dd>
          <dt>使用场景</dt>
          <dd class="rule-catalog-long-text">{{ detail.useCase || '-' }}</dd>
          <dt>示例</dt>
          <dd class="rule-catalog-long-text">{{ detail.example || '-' }}</dd>
          <dt>描述</dt>
          <dd class="rule-catalog-long-text">
            {{ detail.description || '-' }}
          </dd>
          <dt>创建人</dt>
          <dd>{{ detail.creatorName || detail.creator || '-' }}</dd>
          <dt>创建时间</dt>
          <dd>{{ displayTime(detail.createTime) }}</dd>
        </dl>
      </Spin>
      <template #footer>
        <Button @click="detailModal.close()">关闭</Button>
      </template>
    </DetailModal>
  </Page>
</template>
