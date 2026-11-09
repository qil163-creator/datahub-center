<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { SupplyApiCategoryApi } from '#/api/supply/apicategory';
import type { SupplyApiLogApi } from '#/api/supply/apilog';

import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { handleTree } from '@vben/utils';

import { Input, message, Tag, Tooltip, Tree } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import { getApiCategoryList } from '#/api/supply/apicategory';
import { deleteApiLog, getApiLogPage } from '#/api/supply/apilog';
import TreeToolbar from '#/components/tree-toolbar/index.vue';
import { $t } from '#/locales';
import { parseResourceId } from '#/utils/resource-id';

import { useGridColumns, useGridFormSchema } from '../data';

defineOptions({ name: 'SupplyApiCallLog' });

const { hasAccessByCodes } = useAccess();
const router = useRouter();
const canQueryCategories = computed(() =>
  hasAccessByCodes(['supply:api-category:query']),
);

/** 左侧分类树 */
const categories = ref<SupplyApiCategoryApi.ApiCategory[]>([]);
const searchValue = ref('');
const isExpanded = ref(true);
const sidebarOpen = ref(true);
const expandedKeys = ref<(number | string)[]>([]);
const selectedCategoryId = ref<number | string>();
const categoryLoading = ref(false);
let categoryRequest = 0;

const categoryTree = computed(() => {
  const keyword = searchValue.value.trim().toLowerCase();
  const list = keyword
    ? categories.value.filter((item) =>
        (item.name || '').toLowerCase().includes(keyword),
      )
    : categories.value;
  return handleTree(list);
});

function collectExpandableIds(nodes: any[]): (number | string)[] {
  const ids: (number | string)[] = [];
  for (const node of nodes) {
    if (node.children?.length) {
      ids.push(node.id, ...collectExpandableIds(node.children));
    }
  }
  return ids;
}

function applyExpandState() {
  expandedKeys.value = isExpanded.value
    ? collectExpandableIds(categoryTree.value)
    : [];
}

const allExpanded = computed(() => {
  const keys = collectExpandableIds(categoryTree.value);
  return (
    keys.length > 0 && keys.every((key) => expandedKeys.value.includes(key))
  );
});

function toggleExpandAll() {
  isExpanded.value = !allExpanded.value;
  applyExpandState();
}

function handleCategorySearch() {
  if (searchValue.value.trim()) {
    isExpanded.value = true;
  }
  applyExpandState();
}

watch(categoryTree, (nodes, previous) => {
  if (previous?.length) {
    const available = new Set(collectExpandableIds(nodes));
    expandedKeys.value = expandedKeys.value.filter((key) => available.has(key));
  } else {
    applyExpandState();
  }
});

function categoryName(categoryId?: number | string) {
  return (
    categories.value.find(
      (item) => String(item.id) === String(categoryId ?? ''),
    )?.name ?? '-'
  );
}

function categoryNode(node: unknown) {
  return node as SupplyApiCategoryApi.ApiCategory;
}

function handleCategorySelect(keys: (number | string)[]) {
  selectedCategoryId.value = keys.length > 0 ? keys[0] : undefined;
  handleRefresh();
}

function handleRefresh() {
  gridApi.query();
}

function handleDetail(row: SupplyApiLogApi.ApiLog) {
  const id = parseResourceId(row.id);
  if (!id) {
    message.warning('调用记录编号无效');
    return;
  }
  void router.push({ name: 'SupplyApiLogDetailPage', params: { id } });
}

async function handleDelete(row: SupplyApiLogApi.ApiLog) {
  await deleteApiLog(row.id!);
  message.success('记录已删除');
  handleRefresh();
}

const [Grid, gridApi] = useVbenVxeGrid({
  class: 'apilog-results',
  gridClass: 'p-0',
  separator: false,
  formOptions: {
    schema: useGridFormSchema(),
  },
  gridOptions: {
    columns: useGridColumns(),
    height: 'auto',
    showHeaderOverflow: 'tooltip',
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          return await getApiLogPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
            categoryId: selectedCategoryId.value,
          } as any);
        },
      },
    },
    rowConfig: {
      keyField: 'id',
      isHover: true,
    },
    toolbarConfig: {
      refresh: true,
      search: true,
    },
  } as VxeTableGridOptions<SupplyApiLogApi.ApiLog>,
});

async function loadCategories() {
  const request = ++categoryRequest;
  if (!canQueryCategories.value) return;
  categoryLoading.value = true;
  try {
    const data = await getApiCategoryList({});
    if (request === categoryRequest) categories.value = data;
  } finally {
    if (request === categoryRequest) categoryLoading.value = false;
  }
}

watch(
  canQueryCategories,
  () => {
    ++categoryRequest;
    categories.value = [];
    categoryLoading.value = false;
    if (canQueryCategories.value) void loadCategories();
  },
  { immediate: true },
);

onBeforeUnmount(() => {
  ++categoryRequest;
});
</script>

<template>
  <div class="flex h-full min-h-0 flex-1 flex-col">
    <div
      class="apilog-layout"
      :class="{ 'apilog-layout--collapsed': !sidebarOpen }"
    >
      <!-- 左侧服务分类树 -->
      <aside v-if="canQueryCategories" v-show="sidebarOpen" class="apilog-tree">
        <div class="flex h-full min-h-0 flex-col overflow-hidden">
          <div class="mb-2 flex shrink-0 flex-wrap items-center gap-2">
            <Input
              v-model:value="searchValue"
              allow-clear
              class="min-w-[140px] flex-[1_1_140px]"
              placeholder="搜索类目"
              title="请输入 API 服务类目"
              aria-label="搜索 API 服务类目"
              @change="handleCategorySearch"
            >
              <template #prefix>
                <IconifyIcon class="size-4" icon="lucide:search" />
              </template>
            </Input>
            <TreeToolbar
              :expanded="allExpanded"
              :loading="categoryLoading"
              refresh-label="刷新 API 服务类目"
              @toggle-expand="toggleExpandAll"
              @refresh="loadCategories"
            />
          </div>
          <div
            class="subtle-tree-scrollbar min-h-0 flex-1 overflow-y-auto overflow-x-hidden"
          >
            <Tree
              v-if="categoryTree.length > 0"
              v-model:expanded-keys="expandedKeys"
              :field-names="{ children: 'children', title: 'name', key: 'id' }"
              :selected-keys="
                selectedCategoryId === undefined || selectedCategoryId === null
                  ? []
                  : [selectedCategoryId]
              "
              :tree-data="categoryTree as any"
              block-node
              class="pt-2"
              @select="handleCategorySelect"
            >
              <template #titleRender="node">
                <span class="apilog__node">
                  <IconifyIcon
                    class="size-4 shrink-0 text-blue-500"
                    icon="lucide:folder"
                  />
                  <span class="apilog__node-name">
                    <Tooltip :title="categoryNode(node).name">
                      {{ categoryNode(node).name }}
                    </Tooltip>
                  </span>
                </span>
              </template>
            </Tree>
            <div v-else class="py-4 text-center text-xs text-gray-500">
              暂无类目
            </div>
          </div>
        </div>
      </aside>
      <button
        v-if="canQueryCategories"
        class="apilog-tree__collapse"
        :aria-label="sidebarOpen ? '收起 API 服务类目' : '展开 API 服务类目'"
        @click="sidebarOpen = !sidebarOpen"
      >
        <IconifyIcon
          :icon="sidebarOpen ? 'lucide:chevron-left' : 'lucide:chevron-right'"
        />
      </button>

      <!-- 右侧列表 -->
      <div class="min-h-0 min-w-0 flex-1 overflow-hidden">
        <Grid table-title="调用日志">
          <template #categoryId="{ row }">
            {{ categoryName(row.categoryId) }}
          </template>
          <template #status="{ row }">
            <Tag :color="row.status === 1 ? 'success' : 'error'">
              {{ row.status === 1 ? '成功' : '失败' }}
            </Tag>
          </template>
          <template #actions="{ row }">
            <TableAction
              :actions="[
                {
                  label: '详情',
                  type: 'link',
                  icon: ACTION_ICON.VIEW,
                  onClick: handleDetail.bind(null, row),
                },
                {
                  label: $t('common.delete'),
                  type: 'link',
                  danger: true,
                  icon: ACTION_ICON.DELETE,
                  auth: ['supply:api-log:delete'],
                  popConfirm: {
                    title: $t('ui.actionMessage.deleteConfirm', [
                      row.apiName || row.id,
                    ]),
                    confirm: handleDelete.bind(null, row),
                  },
                },
              ]"
            />
          </template>
        </Grid>
      </div>
    </div>
  </div>
</template>

<style scoped>
.apilog-layout {
  position: relative;
  display: flex;
  flex: 1;
  gap: 12px;
  min-width: 0;
  min-height: 0;
}

.apilog-tree {
  position: relative;
  flex: none;
  width: 16.6667%;
  min-width: 0;
  min-height: 0;
  padding: 12px;
  background: hsl(var(--card));
  border: 1px solid hsl(var(--border));
  border-radius: var(--radius);
}

.apilog-tree__collapse {
  position: absolute;
  top: 48%;
  left: calc(16.6667% - 2px);
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 40px;
  color: hsl(var(--muted-foreground));
  background: hsl(var(--muted));
  border-radius: 3px;
}

.apilog-layout--collapsed .apilog-tree__collapse {
  left: 0;
}

.apilog-results {
  background: transparent;
}

.apilog-results :deep(.vxe-grid--form-wrapper) {
  display: flow-root;
}

.apilog-results :deep(.vxe-grid--form-wrapper > div) {
  padding: 16px;
  margin-bottom: 16px;
  background: hsl(var(--background));
  border-radius: var(--radius);
}

.apilog-results :deep(.vxe-grid--toolbar-wrapper) {
  padding: 8px 15px 0;
  background: hsl(var(--background));
  border-radius: var(--radius) var(--radius) 0 0;
}

.apilog-results :deep(.vxe-grid--table-wrapper) {
  padding: 0 15px;
  background: hsl(var(--background));
}

.apilog-results :deep(.vxe-body--column) {
  font-size: 12px;
}

.apilog-results :deep(.vxe-grid--pager-wrapper) {
  padding: 0 15px;
  background: hsl(var(--background));
  border-radius: 0 0 var(--radius) var(--radius);
}

.apilog-tree :deep(.ant-tree) {
  font-size: 14px;
  background: transparent;
}

.apilog-tree :deep(.ant-tree-treenode) {
  width: 100%;
}

.apilog-tree :deep(.ant-tree .ant-tree-node-content-wrapper) {
  flex: 1;
  min-width: 0;
}

.apilog-tree :deep(.ant-tree-title) {
  display: block;
  width: 100%;
}

.apilog__node {
  display: flex;
  gap: 6px;
  align-items: center;
  width: 100%;
}

.apilog__node-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (width <= 900px) {
  .apilog-tree {
    width: 210px;
    min-width: 210px;
  }

  .apilog-tree__collapse {
    left: 208px;
  }

  .apilog-layout--collapsed .apilog-tree__collapse {
    left: 0;
  }
}

@media (width <= 640px) {
  .apilog-layout {
    flex-direction: column;
    overflow: auto;
  }

  .apilog-tree {
    width: 100%;
    height: 190px;
    min-height: 190px;
    max-height: 190px;
  }

  .apilog-tree__collapse {
    top: 174px;
    right: 4px;
    left: auto;
    width: 24px;
    height: 24px;
  }

  .apilog-layout--collapsed .apilog-tree__collapse {
    top: 0;
    left: auto;
  }
}
</style>
