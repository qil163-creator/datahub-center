<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { SupplyApiCategoryApi } from '#/api/supply/apicategory';
import type { SupplyDataApiApi } from '#/api/supply/dataapi';

import {
  computed,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch,
} from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { confirm, Page, useVbenDrawer, useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';
import { handleTree } from '@vben/utils';

import {
  Button,
  Card,
  Dropdown,
  Input,
  Menu,
  message,
  Modal,
  Switch,
  Table,
  Tag,
  Tooltip,
  Tree,
} from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteApiCategory,
  getApiCategoryList,
} from '#/api/supply/apicategory';
import {
  deleteDataApi,
  getDataApi,
  getDataApiPage,
  getDataApiReleaseList,
  publishDataApi,
  rollbackDataApi,
  updateDataApiStatus,
} from '#/api/supply/dataapi';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import TreeToolbar from '#/components/tree-toolbar/index.vue';
import { $t } from '#/locales';
import { parseResourceId } from '#/utils/resource-id';

import { formatSupplyDateTime } from '../utils/format';
import {
  AUTH_TYPE_OPTIONS,
  METHOD_OPTIONS,
  optionColor,
  optionLabel,
  RESULT_FORMAT_OPTIONS,
  useGridColumns,
  useGridFormSchema,
} from './data';
import Doc from './doc.vue';
import CategoryForm from './modules/category-form.vue';
import Wizard from './wizard.vue';

defineOptions({ name: 'SupplyDataApi' });

const router = useRouter();
const { hasAccessByCodes } = useAccess();
const wizardRef = ref<InstanceType<typeof Wizard>>();
const wizardVisible = ref(false);
const docRef = ref<InstanceType<typeof Doc>>();

/** 左侧分类树 */
const categories = ref<SupplyApiCategoryApi.ApiCategory[]>([]);
const searchValue = ref('');
const sidebarOpen = ref(true);
const isExpanded = ref(true);
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

function handleSearch() {
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

async function loadCategories() {
  const request = ++categoryRequest;
  categoryLoading.value = true;
  try {
    const data = await getApiCategoryList({});
    if (active && request === categoryRequest) categories.value = data;
  } finally {
    if (active && request === categoryRequest) categoryLoading.value = false;
  }
}

function handleCategorySelect(keys: (number | string)[]) {
  selectedCategoryId.value = keys.length > 0 ? keys[0] : undefined;
  handleRefresh();
}

/** 类目增删改 */
const [CategoryFormModal, categoryFormModalApi] = useVbenModal({
  connectedComponent: CategoryForm,
  destroyOnClose: true,
});

function categoryNode(node: unknown) {
  return node as SupplyApiCategoryApi.ApiCategory;
}

function handleCreateCategory(parent?: SupplyApiCategoryApi.ApiCategory) {
  categoryFormModalApi.setData({ parentId: parent?.id ?? 0 }).open();
}

function handleEditCategory(category: SupplyApiCategoryApi.ApiCategory) {
  categoryFormModalApi.setData({ id: category.id }).open();
}

function handleDeleteCategory(category: SupplyApiCategoryApi.ApiCategory) {
  Modal.confirm({
    title: '系统提示',
    content: `确认要删除类目"${category.name}"吗？`,
    okButtonProps: { danger: true },
    onOk: async () => {
      await deleteApiCategory(category.id!);
      message.success(`类目"${category.name}"已删除`);
      if (selectedCategoryId.value === category.id) {
        selectedCategoryId.value = undefined;
        handleRefresh();
      }
      loadCategories();
    },
  });
}

function handleCategorySaved() {
  loadCategories();
}

function handleRefresh() {
  return gridApi.query();
}

function handleCreate() {
  wizardRef.value?.open(undefined, selectedCategoryId.value);
}

function handleEdit(row: SupplyDataApiApi.DataApi) {
  wizardRef.value?.open(row.id);
}

function handleDetail(row: SupplyDataApiApi.DataApi) {
  const id = parseResourceId(row.id);
  if (!id) {
    message.warning('API编号无效，请刷新列表后重试');
    return;
  }
  void router.push({ name: 'SupplyDataApiDetailPage', params: { id } });
}

async function handleDelete(row: SupplyDataApiApi.DataApi) {
  await deleteDataApi(row.id!);
  message.success('服务已删除');
  handleRefresh();
}

const [ReleaseDrawer, releaseDrawerApi] = useVbenDrawer({
  title: '发布历史',
  placement: 'right',
  showConfirmButton: false,
  cancelText: '关闭',
  class: 'w-[960px] max-w-full',
  onOpenChange(isOpen: boolean) {
    if (!isOpen) resetReleases();
  },
});
const access = useAccessStore();
const releaseSession = computed(
  () => `${access.accessToken}/${access.refreshToken}`,
);
const releaseLoading = ref(false);
const releaseRows = ref<SupplyDataApiApi.DataApiRelease[]>([]);
const releaseApi = ref<SupplyDataApiApi.DataApi>();
let releaseRequest = 0;
let releaseOpen = false;
let active = true;
const changingIds = reactive(new Set<string>());

function resetReleases() {
  ++releaseRequest;
  releaseOpen = false;
  releaseLoading.value = false;
  releaseRows.value = [];
  releaseApi.value = undefined;
}

function isCurrentRelease(request: number) {
  return releaseOpen && request === releaseRequest;
}

watch(releaseSession, () => {
  resetReleases();
  void releaseDrawerApi.close();
  categories.value = [];
  selectedCategoryId.value = undefined;
  searchValue.value = '';
  void loadCategories();
});
onBeforeUnmount(() => {
  active = false;
  ++categoryRequest;
  resetReleases();
});

function controlVersion(row: SupplyDataApiApi.DataApi): number {
  if (!Number.isInteger(row.rowVersion) || row.rowVersion! < 0) {
    throw new Error('缺少控制版本，请刷新列表后重试');
  }
  return row.rowVersion!;
}

async function handlePublish(row: SupplyDataApiApi.DataApi) {
  if (changingIds.has(String(row.id))) return;
  changingIds.add(String(row.id));
  try {
    try {
      await confirm(
        `确认发布“${row.name}”的当前草稿并上线吗？网关将改用新快照。`,
      );
    } catch {
      return;
    }
    await publishDataApi(row.id!, controlVersion(row));
    message.success('已发布快照');
    await handleRefresh();
  } finally {
    changingIds.delete(String(row.id));
  }
}

async function openReleases(row: SupplyDataApiApi.DataApi) {
  const request = ++releaseRequest;
  releaseOpen = true;
  releaseApi.value = row;
  releaseRows.value = [];
  releaseDrawerApi.open();
  releaseLoading.value = true;
  try {
    const rows = await getDataApiReleaseList(row.id!);
    if (isCurrentRelease(request)) releaseRows.value = rows;
  } finally {
    if (isCurrentRelease(request)) releaseLoading.value = false;
  }
}

async function handleRollback(release: SupplyDataApiApi.DataApiRelease) {
  const request = releaseRequest;
  const session = releaseSession.value;
  const api = releaseApi.value;
  if (!api?.id || release.id === undefined || release.id === null) {
    return;
  }
  try {
    await confirm(`确认回滚到修订 ${release.revision ?? release.id} 吗？`);
  } catch {
    return;
  }
  if (!isCurrentRelease(request)) return;
  await rollbackDataApi(api.id, release.id, controlVersion(api));
  if (!active || releaseSession.value !== session) return;
  message.success('已回滚到选定快照');
  handleRefresh();
  if (!isCurrentRelease(request)) return;
  const currentApi = await getDataApi(api.id);
  if (!isCurrentRelease(request)) return;
  await openReleases(currentApi);
}

async function handleOffline(row: SupplyDataApiApi.DataApi) {
  if (changingIds.has(String(row.id))) return;
  changingIds.add(String(row.id));
  try {
    try {
      await confirm(`确认下线“${row.name}”吗？下线后应用将无法调用。`);
    } catch {
      return;
    }
    await updateDataApiStatus(row.id!, 0, controlVersion(row));
    message.success('服务已下线');
    await handleRefresh();
  } finally {
    changingIds.delete(String(row.id));
  }
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: useGridFormSchema(),
  },
  gridOptions: {
    columns: useGridColumns(),
    height: 'auto',
    showHeaderOverflow: 'tooltip',
    showOverflow: false,
    stripe: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          return await getDataApiPage({
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
  } as VxeTableGridOptions<SupplyDataApiApi.DataApi>,
});

onMounted(() => {
  loadCategories();
});
</script>

<template>
  <Page auto-content-height>
    <div
      v-show="!wizardVisible"
      class="dataapi-layout flex h-full min-h-0 w-full gap-3 overflow-hidden"
      :class="{ 'dataapi-layout--collapsed': !sidebarOpen }"
    >
      <!-- 左侧服务分类树 -->
      <Card
        v-show="sidebarOpen"
        class="dataapi-tree h-full shrink-0"
        :styles="{
          body: {
            display: 'flex',
            flexDirection: 'column',
            height: '100%',
            overflow: 'hidden',
            padding: '12px',
          },
        }"
      >
        <div class="flex h-full min-h-0 flex-col overflow-hidden">
          <div class="mb-2 flex shrink-0 items-center gap-2">
            <Input
              v-model:value="searchValue"
              allow-clear
              class="min-w-0 flex-1"
              placeholder="搜索类目"
              title="请输入 API 服务类目"
              aria-label="搜索 API 服务类目"
              @change="handleSearch"
            >
              <template #prefix>
                <IconifyIcon class="size-4" icon="lucide:search" />
              </template>
            </Input>
            <TreeToolbar
              :expanded="allExpanded"
              :loading="categoryLoading"
              :show-refresh="false"
              @toggle-expand="toggleExpandAll"
            />
            <Tooltip title="新建根类目">
              <Button
                v-access:code="'supply:api-category:create'"
                class="shrink-0"
                aria-label="新建根类目"
                @click="handleCreateCategory()"
              >
                <template #icon>
                  <IconifyIcon icon="lucide:plus" />
                </template>
              </Button>
            </Tooltip>
          </div>
          <div
            class="subtle-tree-scrollbar min-h-0 flex-1 overflow-y-auto overflow-x-hidden"
          >
            <Tree
              v-if="categoryTree.length > 0"
              v-model:expanded-keys="expandedKeys"
              :field-names="{ children: 'children', title: 'name', key: 'id' }"
              :selected-keys="
                selectedCategoryId != null ? [selectedCategoryId] : []
              "
              :tree-data="categoryTree as any"
              block-node
              class="pt-2"
              @select="handleCategorySelect"
            >
              <template #titleRender="node">
                <span class="dataapi__node">
                  <IconifyIcon
                    class="size-4 shrink-0 text-blue-500"
                    icon="lucide:folder"
                  />
                  <span class="dataapi__node-name">
                    {{ categoryNode(node).name }}
                  </span>
                  <Dropdown :trigger="['hover']" placement="bottomRight">
                    <span class="dataapi__node-more" @click.stop>
                      <IconifyIcon icon="lucide:ellipsis" />
                    </span>
                    <template #popupRender>
                      <Menu>
                        <Menu.Item
                          key="add"
                          v-access:code="'supply:api-category:create'"
                          @click="handleCreateCategory(categoryNode(node))"
                        >
                          <template #icon>
                            <IconifyIcon icon="lucide:folder-plus" />
                          </template>
                          新增子类目
                        </Menu.Item>
                        <Menu.Item
                          key="edit"
                          v-access:code="'supply:api-category:update'"
                          @click="handleEditCategory(categoryNode(node))"
                        >
                          <template #icon>
                            <IconifyIcon icon="lucide:square-pen" />
                          </template>
                          编辑
                        </Menu.Item>
                        <Menu.Item
                          key="delete"
                          v-access:code="'supply:api-category:delete'"
                          danger
                          @click="handleDeleteCategory(categoryNode(node))"
                        >
                          <template #icon>
                            <IconifyIcon icon="lucide:trash-2" />
                          </template>
                          删除
                        </Menu.Item>
                      </Menu>
                    </template>
                  </Dropdown>
                </span>
              </template>
            </Tree>
            <div v-else class="py-4 text-center text-xs text-gray-500">
              暂无类目，点击右上角 + 新建
            </div>
          </div>
        </div>
      </Card>
      <button
        class="dataapi-layout__collapse"
        :aria-label="sidebarOpen ? '收起 API 服务类目' : '展开 API 服务类目'"
        @click="sidebarOpen = !sidebarOpen"
      >
        <IconifyIcon
          :icon="sidebarOpen ? 'lucide:chevron-left' : 'lucide:chevron-right'"
        />
      </button>

      <!-- 右侧列表 -->
      <div class="min-h-0 min-w-0 flex-1 overflow-hidden">
        <Grid class="dataapi-grid" table-title="API 服务列表">
          <template #toolbar-tools>
            <TableAction
              :actions="[
                {
                  label: $t('common.create'),
                  type: 'primary',
                  icon: ACTION_ICON.ADD,
                  auth: ['supply:data-api:create'],
                  onClick: handleCreate,
                },
              ]"
            />
          </template>
          <template #name="{ row }">
            <div class="dataapi-name-cell">
              <div class="dataapi-name-heading flex flex-wrap items-center gap-1">
                <IconifyIcon
                  aria-hidden="true"
                  class="size-4 shrink-0 rounded-sm bg-indigo-600 p-0.5 text-white"
                  icon="lucide:braces"
                />
                <span
                  class="max-w-full min-w-0 break-words whitespace-normal"
                >
                  {{ row.name }}
                </span>
                <Tag class="dataapi-meta-tag shrink-0" color="purple">
                  {{ row.version }}
                </Tag>
                <Tag
                  v-if="row.unpublishedDraft"
                  class="dataapi-meta-tag"
                  color="warning"
                >
                  未发布草稿
                </Tag>
              </div>
              <TextEllipsisCell
                class="text-muted-foreground whitespace-pre-wrap break-words text-xs"
                :content="row.description || '-'"
                :line="2"
                :line-height="18"
                :reserve-lines="false"
                center
              />
            </div>
          </template>
          <template #categoryId="{ row }">
            <Tag
              class="dataapi-meta-tag dataapi-category-tag max-w-full whitespace-normal break-words"
              color="processing"
            >
              {{ categoryName(row.categoryId) }}
            </Tag>
          </template>
          <template #path="{ row }">
            <div class="dataapi-path-cell">
              <Tooltip :title="row.path">
                <div class="truncate">{{ row.path }}</div>
              </Tooltip>
              <div class="flex flex-wrap gap-1">
                <Tag
                  class="dataapi-meta-tag"
                  :color="row.method === 'GET' ? 'processing' : 'geekblue'"
                >
                  {{ optionLabel(METHOD_OPTIONS, row.method) }}
                </Tag>
                <Tag
                  class="dataapi-meta-tag"
                  :color="
                    row.resultFormat === 'PAGE'
                      ? 'purple'
                      : optionColor(RESULT_FORMAT_OPTIONS, row.resultFormat)
                  "
                >
                  {{ optionLabel(RESULT_FORMAT_OPTIONS, row.resultFormat) }}
                </Tag>
              </div>
            </div>
          </template>
          <template #authType="{ row }">
            <Tag
              class="dataapi-meta-tag"
              :color="optionColor(AUTH_TYPE_OPTIONS, row.authType ?? 'NONE')"
            >
              {{ optionLabel(AUTH_TYPE_OPTIONS, row.authType ?? 'NONE') }}
            </Tag>
          </template>
          <template #status="{ row }">
            <Tooltip :title="row.status === 1 ? '已上线' : '已下线'">
              <Switch
                :aria-label="`${row.name}：${row.status === 1 ? '已上线' : '已下线'}`"
                :checked="row.status === 1"
                :disabled="!hasAccessByCodes(['supply:data-api:publish'])"
                :loading="changingIds.has(String(row.id))"
                @change="
                  row.status === 1 ? handleOffline(row) : handlePublish(row)
                "
              />
            </Tooltip>
          </template>
          <template #actions="{ row }">
            <TableAction
              :actions="[
                {
                  label: '详情',
                  type: 'link',
                  icon: ACTION_ICON.VIEW,
                  auth: ['supply:data-api:query'],
                  onClick: handleDetail.bind(null, row),
                },
                {
                  label: $t('common.edit'),
                  type: 'link',
                  icon: ACTION_ICON.EDIT,
                  auth: ['supply:data-api:update'],
                  onClick: handleEdit.bind(null, row),
                },
              ]"
              :drop-down-actions="[
                {
                  label: '文档',
                  type: 'link',
                  icon: ACTION_ICON.BOOK,
                  onClick: () => docRef?.open(row.id!),
                },
                {
                  label: '发布',
                  type: 'link',
                  icon: 'lucide:send',
                  auth: ['supply:data-api:publish'],
                  onClick: handlePublish.bind(null, row),
                },
                {
                  label: '下线',
                  type: 'link',
                  icon: 'lucide:square',
                  auth: ['supply:data-api:publish'],
                  ifShow: row.status === 1,
                  onClick: handleOffline.bind(null, row),
                },
                {
                  label: '发布历史',
                  type: 'link',
                  icon: 'lucide:history',
                  auth: ['supply:data-api:query'],
                  onClick: openReleases.bind(null, row),
                },
                {
                  label: $t('common.delete'),
                  type: 'link',
                  danger: true,
                  icon: ACTION_ICON.DELETE,
                  auth: ['supply:data-api:delete'],
                  popConfirm: {
                    title: $t('ui.actionMessage.deleteConfirm', [
                      row.name || row.id,
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

    <Wizard
      ref="wizardRef"
      v-model:visible="wizardVisible"
      @saved="handleRefresh"
    />
    <Doc ref="docRef" />
    <CategoryFormModal @success="handleCategorySaved" />
    <ReleaseDrawer>
      <Table
        class="mx-4 supply-font-table"
        :columns="[
          { title: '修订', dataIndex: 'revision', width: 80 },
          { title: '版本', dataIndex: 'apiVersion', width: 100 },
          { title: '发布时间', key: 'publishTime', width: 180 },
          { title: '合同摘要', dataIndex: 'contractHash', ellipsis: true },
          { title: '当前', key: 'active', width: 80 },
          { title: '操作', key: 'actions', width: 90, fixed: 'right' },
        ]"
        :data-source="releaseRows"
        :loading="releaseLoading"
        :pagination="false"
        :scroll="{ x: 820 }"
        row-key="id"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'publishTime'">
            {{ formatSupplyDateTime(record.publishTime) || '-' }}
          </template>
          <template v-else-if="column.key === 'active'">
            <Tag :color="record.active ? 'success' : 'default'">
              {{ record.active ? '生效' : '历史' }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'actions'">
            <a
              v-if="!record.active"
              v-access:code="'supply:data-api:rollback'"
              @click="handleRollback(record)"
            >
              <IconifyIcon
                class="mr-1 inline-block align-middle"
                icon="lucide:rotate-ccw"
              />
              回滚
            </a>
          </template>
        </template>
      </Table>
    </ReleaseDrawer>
  </Page>
</template>

<style scoped>
.supply-font-table :deep(.ant-table-thead > tr > th),
.supply-font-table :deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}

.dataapi-meta-tag {
  padding: 1px 5px;
  margin-inline-end: 0;
  font-size: 11px;
  line-height: 16px;
  border-color: transparent;
  border-radius: 2px;
}

.dataapi-category-tag {
  padding: 1px 6px;
  font-size: 12px;
  line-height: 18px;
}

.dataapi-name-cell,
.dataapi-path-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  justify-content: center;
  min-width: 0;
  min-height: 52px;
  line-height: 22px;
}

.dataapi-name-heading {
  font-size: 12px;
  line-height: 22px;
}

.dataapi-name-heading .dataapi-meta-tag {
  font-size: 11px;
  line-height: 16px;
}

.dataapi-layout {
  position: relative;
}

.dataapi-tree {
  width: 16.6667%;
  min-width: 0;
}

.dataapi-layout__collapse {
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

.dataapi-layout--collapsed .dataapi-layout__collapse {
  left: 0;
}

@media (width <= 900px) {
  .dataapi-tree {
    width: 210px;
    min-width: 210px;
  }

  .dataapi-layout__collapse {
    left: 208px;
  }

  .dataapi-layout--collapsed .dataapi-layout__collapse {
    left: 0;
  }
}

@media (width <= 640px) {
  .dataapi-layout {
    flex-direction: column;
    height: auto;
    min-height: 900px;
  }

  .dataapi-tree {
    width: 100%;
    height: 190px;
    min-height: 190px;
  }

  .dataapi-layout__collapse {
    top: 174px;
    right: 4px;
    left: auto;
    width: 24px;
    height: 24px;
  }

  .dataapi-layout--collapsed .dataapi-layout__collapse {
    top: 0;
    left: auto;
  }
}

.dataapi-tree :deep(.ant-tree) {
  font-size: 14px;
  background: transparent;
}

.dataapi-grid {
  --vxe-ui-table-row-striped-background-color: hsl(var(--muted) / 30%);
}

.dataapi-grid :deep(.vxe-body--column) {
  font-size: 12px;
}

.dataapi-tree :deep(.ant-tree-treenode) {
  width: 100%;
}

.dataapi-tree :deep(.ant-tree .ant-tree-node-content-wrapper) {
  flex: 1;
  min-width: 0;
}

.dataapi-tree :deep(.ant-tree-title) {
  display: block;
  width: 100%;
}

.dataapi__node {
  display: flex;
  gap: 6px;
  align-items: center;
  width: 100%;
}

.dataapi__node :deep(.ant-dropdown-trigger) {
  margin-left: auto;
}

.dataapi__node-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dataapi__node-more {
  display: inline-flex;
  visibility: hidden;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  margin-left: auto;
  color: hsl(var(--muted-foreground));
  border-radius: 4px;
}

.dataapi__node-more:hover {
  color: hsl(var(--primary));
  background: hsl(var(--accent));
}

.dataapi-tree :deep(.ant-tree-treenode:hover) .dataapi__node-more,
.dataapi-tree :deep(.ant-tree-node-selected) .dataapi__node-more {
  visibility: visible;
}
</style>
