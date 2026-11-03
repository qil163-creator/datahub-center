<script lang="ts" setup>
import type { AssetCategoryTagApi } from '#/api/asset/categorytag';
import type { AssetTagApi } from '#/api/asset/tag';

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
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page, useVbenModal } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';
import { handleTree } from '@vben/utils';

import {
  Alert,
  Button,
  CheckboxGroup,
  Dropdown,
  Input,
  message,
  Spin,
  Switch,
  Tag,
  Tree,
} from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import { getCategoryTagList } from '#/api/asset/categorytag';
import {
  deleteAssetTag,
  deleteAssetTagList,
  getAssetTagPage,
  updateAssetTagStatus,
} from '#/api/asset/tag';
import ListPagination from '#/components/list-pagination/index.vue';
import TableViewport from '#/components/table-viewport/index.vue';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import TreeToolbar from '#/components/tree-toolbar/index.vue';
import {
  createLatestWins,
  parseResourceId,
  resourceIdKey,
} from '#/utils/resource-id';
import { useBatchConfirm as useOwnedConfirm } from '#/views/dataforge/batchfile/use-confirm';

import {
  tagAsset,
  tagColumns,
  tagDate,
  tagNumber,
  tagStatusOptions,
} from './data';
import TagForm from './modules/form.vue';

import './reference.css';

defineOptions({ name: 'AssetTag' });
const folderIcon = '/assets/qdata-market/icon-folder.png';
const router = useRouter();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const identity = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    access.loginExpired,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]),
);
const signedIn = computed(
  () =>
    !!access.accessToken &&
    !access.loginExpired &&
    !!parseResourceId(user.userInfo?.userId),
);
const dialogActive = ref(true);
const confirm = useOwnedConfirm(
  computed(() => JSON.stringify([identity.value, dialogActive.value])),
);
const canQuery = computed(
  () => signedIn.value && hasAccessByCodes(['asset:tag:query']),
);
function can(code: string) {
  return signedIn.value && hasAccessByCodes([`asset:tag:${code}`]);
}
let active = true;
let alive = true;
const rows = ref<AssetTagApi.Tag[]>([]);
const categories = ref<AssetCategoryTagApi.CategoryTag[]>([]);
const loading = ref(false);
const categoryLoading = ref(false);
const listError = ref('');
const categoryError = ref('');
const selectedCategoryId = ref<string>();
const treeKeyword = ref('');
const selectedRowKeys = ref<string[]>([]);
const pendingIds = ref(new Set<string>());
const query = reactive<{
  creatorName?: string;
  name?: string;
  sortField?: 'assetCount' | 'createTime' | 'id' | 'name';
  sortOrder?: 'asc' | 'desc';
  status?: number;
}>({ sortField: 'createTime', sortOrder: 'desc' });
const pagination = reactive({ pageNo: 1, pageSize: 10, total: 0 });
const listGuard = createLatestWins();
const categoryGuard = createLatestWins();
let writeEpoch = 0;
interface CategoryNode {
  key: string;
  title: string;
  children?: CategoryNode[];
}
const categoryTree = computed<CategoryNode[]>(() => {
  const flat = categories.value.map((item) => ({
    id: resourceIdKey(item.id),
    parentId: resourceIdKey(item.parentId) || '0',
    key: resourceIdKey(item.id),
    title: item.categoryName || '-',
  }));
  const nodes = handleTree(flat) as CategoryNode[];
  const text = treeKeyword.value.trim();
  function filter(items: CategoryNode[]): CategoryNode[] {
    return items.flatMap((item) => {
      const children = filter(item.children || []);
      if (item.title.includes(text)) return [item];
      return children.length > 0 ? [{ ...item, children }] : [];
    });
  }
  return [
    { key: 'root', title: '标签类目', children: text ? filter(nodes) : nodes },
  ];
});
const categoryExpandedKeys = ref<string[]>(['root']);
function categoryBranchKeys(nodes: CategoryNode[]): string[] {
  return nodes.flatMap((node) =>
    node.children?.length
      ? [node.key, ...categoryBranchKeys(node.children)]
      : [],
  );
}
const isCategoryExpanded = computed(() => {
  const keys = categoryBranchKeys(categoryTree.value);
  return (
    keys.length > 0 &&
    keys.every((key) => categoryExpandedKeys.value.includes(key))
  );
});
function toggleCategoryExpanded() {
  categoryExpandedKeys.value = isCategoryExpanded.value
    ? []
    : categoryBranchKeys(categoryTree.value);
}
watch(treeKeyword, () => {
  if (treeKeyword.value.trim())
    categoryExpandedKeys.value = categoryBranchKeys(categoryTree.value);
});
const visibleColumnKeys = ref(tagColumns.map((column) => column.key));
const columns = computed(() =>
  tagColumns.filter((column) => visibleColumnKeys.value.includes(column.key)),
);
const rowSelection = computed(() => ({
  selectedRowKeys: selectedRowKeys.value,
  columnWidth: 46,
  onChange: (keys: (number | string)[]) => {
    selectedRowKeys.value = keys.map(String);
  },
}));
const sidebarOpen = ref(true);
const sidebarWidth = ref(280);
const showFilters = ref(true);
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
      fieldName: 'name',
      label: '标签名称',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入标签名称' },
    },
    {
      fieldName: 'creatorName',
      label: '创建人',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入创建人' },
    },
    {
      fieldName: 'status',
      label: '状态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: tagStatusOptions,
        placeholder: '请选择状态',
      },
    },
  ],
  handleValuesChange(values) {
    query.name = values.name;
    query.creatorName = values.creatorName;
    query.status = values.status;
  },
  handleSubmit: search,
  handleReset: reset,
});
const [FormModal, formApi] = useVbenModal({
  connectedComponent: TagForm,
  destroyOnClose: true,
});
async function loadRows() {
  if (!alive || !active || !canQuery.value) return;
  const ticket = listGuard.next();
  loading.value = true;
  listError.value = '';
  selectedRowKeys.value = [];
  try {
    const data = await getAssetTagPage({
      pageNo: pagination.pageNo,
      pageSize: pagination.pageSize,
      categoryId: selectedCategoryId.value,
      ...query,
      name: query.name?.trim() || undefined,
      creatorName: query.creatorName?.trim() || undefined,
    });
    if (ticket.isCurrent()) {
      rows.value = data.list || [];
      pagination.total = data.total || 0;
    }
  } catch {
    if (ticket.isCurrent()) {
      rows.value = [];
      pagination.total = 0;
      listError.value = '标签读取失败，请重试。';
    }
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}
async function loadCategories() {
  if (!alive || !active || !canQuery.value) return;
  const ticket = categoryGuard.next();
  categoryLoading.value = true;
  categoryError.value = '';
  try {
    const items = await getCategoryTagList();
    if (ticket.isCurrent()) categories.value = items;
  } catch {
    if (ticket.isCurrent()) {
      categories.value = [];
      categoryError.value = '标签类目读取失败，请重试。';
    }
  } finally {
    if (ticket.isCurrent()) categoryLoading.value = false;
  }
}
function search() {
  pagination.pageNo = 1;
  void loadRows();
}
function reset() {
  if (filterFormApi.isMounted) void filterFormApi.reset();
  Object.assign(query, {
    name: undefined,
    creatorName: undefined,
    status: undefined,
  });
  selectedCategoryId.value = undefined;
  treeKeyword.value = '';
  search();
}
function selectCategory(keys: (number | string)[]) {
  selectedCategoryId.value =
    keys[0] && keys[0] !== 'root' ? String(keys[0]) : undefined;
  search();
}
function openCreate() {
  if (active && can('create'))
    formApi.setData({ categoryId: selectedCategoryId.value }).open();
}
function openEdit(row: AssetTagApi.Tag) {
  if (active && can('update') && row.status === 1 && parseResourceId(row.id))
    formApi.setData({ id: row.id }).open();
}
function openDetail(row: AssetTagApi.Tag) {
  if (active && canQuery.value && parseResourceId(row.id))
    void router.push({ name: 'AssetTagDetail', params: { id: row.id } });
}
function refresh() {
  void loadRows();
  void loadCategories();
}
function validRevision(row: AssetTagApi.Tag) {
  return Number.isInteger(row.revision) && row.revision! >= 0;
}
function statusChange(row: AssetTagApi.Tag, checked: boolean) {
  if (
    !active ||
    !can('update') ||
    pendingIds.value.has(resourceIdKey(row.id)) ||
    !parseResourceId(row.id) ||
    !validRevision(row)
  )
    return;
  const subject = identity.value;
  const epoch = writeEpoch;
  confirm({
    wrapClassName: 'qdata-tag-confirm',
    centered: true,
    width: 420,
    okText: '确定',
    cancelText: '取消',
    title: '系统提示',
    content: `确认要"${checked ? '启用' : '禁用'}","${row.name}"标签吗`,
    onOk: async () => {
      if (
        !active ||
        epoch !== writeEpoch ||
        identity.value !== subject ||
        !can('update')
      )
        return;
      const key = resourceIdKey(row.id);
      pendingIds.value = new Set(pendingIds.value).add(key);
      try {
        await updateAssetTagStatus(row.id!, checked ? 0 : 1, row.revision!);
        if (
          alive &&
          active &&
          epoch === writeEpoch &&
          identity.value === subject
        ) {
          message.success('操作成功');
          refresh();
        }
      } finally {
        if (epoch === writeEpoch && identity.value === subject)
          pendingIds.value = new Set(
            [...pendingIds.value].filter((id) => id !== key),
          );
      }
    },
  });
}
function remove(row?: AssetTagApi.Tag) {
  if (!active || !can('delete')) return;
  const targets = row
    ? [row]
    : rows.value.filter((item) =>
        selectedRowKeys.value.includes(resourceIdKey(item.id)),
      );
  if (
    targets.length === 0 ||
    targets.some((item) => !parseResourceId(item.id) || !validRevision(item)) ||
    (row && row.status === 0)
  )
    return;
  const subject = identity.value;
  const epoch = writeEpoch;
  const label = targets.map((item) => tagNumber(item)).join(',');
  confirm({
    wrapClassName: 'qdata-tag-confirm',
    centered: true,
    width: 420,
    okText: '确定',
    cancelText: '取消',
    title: '系统提示',
    content: `是否确认删除标签编号为"${label}"的数据项？`,
    onOk: async () => {
      if (
        !active ||
        epoch !== writeEpoch ||
        identity.value !== subject ||
        !can('delete')
      )
        return;
      try {
        await (row
          ? deleteAssetTag(row.id!, row.revision!)
          : deleteAssetTagList(
              targets.map((item) => ({
                id: item.id!,
                revision: item.revision!,
              })),
            ));
        if (
          alive &&
          active &&
          epoch === writeEpoch &&
          identity.value === subject
        ) {
          message.success('删除成功');
          refresh();
        }
      } catch {
        if (
          alive &&
          active &&
          epoch === writeEpoch &&
          identity.value === subject
        )
          message.error('删除失败，请检查标签状态及关联资产后重试。');
      }
    },
  });
}
function tableChange(
  _page: unknown,
  _filter: unknown,
  sorter:
    | { columnKey?: number | string; order?: null | string }
    | { columnKey?: number | string; order?: null | string }[],
) {
  const value = Array.isArray(sorter) ? sorter[0] : sorter;
  const key = value?.columnKey;
  if (
    typeof key === 'string' &&
    ['assetCount', 'createTime', 'id', 'name'].includes(key)
  ) {
    query.sortField = key as typeof query.sortField;
    query.sortOrder = value?.order === 'descend' ? 'desc' : 'asc';
    search();
  }
}
function clear() {
  listGuard.invalidate();
  categoryGuard.invalidate();
  writeEpoch++;
  rows.value = [];
  categories.value = [];
  selectedRowKeys.value = [];
  pendingIds.value = new Set();
  pagination.total = 0;
  loading.value = false;
  categoryLoading.value = false;
  formApi.setState({ isOpen: false });
}
watch(
  identity,
  () => {
    clear();
    refresh();
  },
  { flush: 'sync' },
);
onMounted(refresh);
onActivated(() => {
  if (!active) {
    active = true;
    dialogActive.value = true;
    refresh();
  }
});
onDeactivated(() => {
  active = false;
  dialogActive.value = false;
  clear();
});
onBeforeUnmount(() => {
  alive = false;
  active = false;
  dialogActive.value = false;
  clear();
});

function syncFilterValues() {
  if (filterFormApi.isMounted)
    void filterFormApi.setValues({
      name: query.name,
      creatorName: query.creatorName,
      status: query.status,
    });
}
</script>
<template>
  <Page
    auto-content-height
    content-class="flex min-h-0 flex-col overflow-hidden"
  >
    <FormModal @success="refresh" />
    <div class="qdata-tag qdata-tag-list">
      <Alert
        v-if="!canQuery"
        message="当前登录身份没有标签查询权限。"
        type="warning"
        show-icon
      />
      <div v-else class="tag-layout">
        <aside
          v-if="sidebarOpen"
          class="tag-sidebar"
          :style="{ width: `${sidebarWidth}px` }"
        >
          <div class="mb-2 flex items-center gap-2">
            <Input
              v-model:value="treeKeyword"
              placeholder="请输入标签类目"
              class="min-w-0 flex-1"
              allow-clear
            >
              <template #prefix>
                <span
                  class="ref-icon muted"
                  :style="{ maskImage: `url(${tagAsset('search.svg')})` }"
                ></span>
              </template>
            </Input>
            <TreeToolbar
              :expanded="isCategoryExpanded"
              :loading="categoryLoading"
              @toggle-expand="toggleCategoryExpanded"
              @refresh="loadCategories"
            />
          </div>
          <Alert v-if="categoryError" :message="categoryError" type="error">
            <template #action>
              <Button size="small" @click="loadCategories"> 重试 </Button>
            </template> </Alert
          ><Spin :spinning="categoryLoading">
            <Tree
              class="subtle-tree-scrollbar"
              :tree-data="categoryTree"
              :selected-keys="selectedCategoryId ? [selectedCategoryId] : []"
              v-model:expanded-keys="categoryExpandedKeys"
              block-node
              @select="selectCategory"
            >
              <template #titleRender="node">
                <span class="tag-tree-label"
                  ><img :src="folderIcon" alt="" />{{ node.title }}</span
                >
              </template>
            </Tree> </Spin
          ><button
            class="tag-sidebar__collapse"
            aria-label="收起标签类目"
            @click="sidebarOpen = false"
          >
            ‹
          </button>
        </aside>
        <button
          v-else
          class="tag-sidebar__expand"
          aria-label="展开标签类目"
          @click="sidebarOpen = true"
        >
          ›
        </button>
        <main class="tag-main">
          <FilterForm
            @vue:mounted="syncFilterValues"
            v-show="showFilters"
            class="rounded-lg bg-background p-4"
          >
            <template #reset-before>
              <Button @click="reset">
                <i class="tag-reset-icon" aria-hidden="true"></i>重置
              </Button>
              <Button type="primary" @click="filterFormApi.validateAndSubmit()">
                <i class="tag-query-icon" aria-hidden="true"></i>查询
              </Button>
            </template>
          </FilterForm>
          <section class="tag-results">
            <div class="tag-toolbar">
              <h2 class="text-base font-medium">资产标签列表</h2>
              <div>
                <Button v-if="can('create')" type="primary" @click="openCreate">
                  <span
                    class="ref-icon"
                    :style="{ maskImage: `url(${tagAsset('plus.svg')})` }"
                  ></span
                  >新增 </Button
                ><Button
                  v-if="can('delete')"
                  danger
                  :disabled="!selectedRowKeys.length"
                  @click="remove()"
                >
                  <span
                    class="ref-icon"
                    :style="{ maskImage: `url(${tagAsset('delete.svg')})` }"
                  ></span
                  >删除
                </Button>
              </div>
              <div>
                <Button
                  aria-label="显示或隐藏查询"
                  @click="showFilters = !showFilters"
                >
                  <span
                    class="ref-icon"
                    :style="{ maskImage: `url(${tagAsset('search.svg')})` }"
                  ></span> </Button
                ><Button aria-label="刷新标签" @click="refresh">
                  <span
                    class="ref-icon"
                    :style="{ maskImage: `url(${tagAsset('refresh.svg')})` }"
                  ></span> </Button
                ><Dropdown :trigger="['click']">
                  <Button aria-label="选择显示列">
                    <span
                      class="ref-icon"
                      :style="{
                        maskImage: `url(${tagAsset('columns.svg')})`,
                      }"
                    ></span> </Button
                  ><template #popupRender>
                    <div class="tag-column-picker">
                      <CheckboxGroup
                        v-model:value="visibleColumnKeys"
                        :options="
                          tagColumns.map((column) => ({
                            label: column.title,
                            value: column.key,
                          }))
                        "
                      />
                    </div>
                  </template>
                </Dropdown>
              </div>
            </div>
            <Alert v-if="listError" :message="listError" type="error">
              <template #action>
                <Button size="small" @click="loadRows"> 重试 </Button>
              </template>
            </Alert>
            <TableViewport
              class="tag-table"
              :columns="columns"
              :data-source="rows"
              :row-key="(row: AssetTagApi.Tag) => resourceIdKey(row.id)"
              :row-selection="rowSelection"
              :loading="loading"
              :pagination="false"
              :scroll="{ x: 1500 }"
              :custom-row="
                (record: AssetTagApi.Tag) => ({
                  onDblclick: () => openDetail(record),
                })
              "
              @change="tableChange"
            >
              <template #bodyCell="{ column, record }">
                <span v-if="column.key === 'id'">{{ tagNumber(record) }}</span
                ><button
                  v-else-if="column.key === 'name'"
                  class="tag-name"
                  @click="openDetail(record)"
                >
                  {{ record.name }}</button
                ><Tag
                  v-else-if="column.key === 'categoryName'"
                  class="tag-category"
                >
                  {{ record.categoryName || '-' }} </Tag
                ><TextEllipsisCell
                  v-else-if="column.key === 'description'"
                  class="tag-description"
                  :content="record.description || '-'"
                  :line="2"
                  center
                /><span v-else-if="column.key === 'assetCount'">{{
                  record.assetCount ?? 0
                }}</span
                ><Switch
                  v-else-if="column.key === 'status'"
                  :checked="record.status === 0"
                  :disabled="
                    !can('update') ||
                    !validRevision(record) ||
                    pendingIds.has(resourceIdKey(record.id))
                  "
                  @change="(checked) => statusChange(record, !!checked)"
                /><span v-else-if="column.key === 'creatorName'">{{
                  record.creatorName ||
                  record.sourceCreatorName ||
                  record.creator ||
                  '-'
                }}</span
                ><span v-else-if="column.key === 'createTime'">{{
                  tagDate(record.createTime)
                }}</span>
                <div
                  v-else-if="column.key === 'actions'"
                  class="tag-row-actions"
                >
                  <Button
                    v-if="can('update')"
                    type="link"
                    :disabled="record.status === 0"
                    @click="openEdit(record)"
                  >
                    <span
                      class="ref-icon"
                      :style="{ maskImage: `url(${tagAsset('edit.svg')})` }"
                    ></span
                    >修改 </Button
                  ><Button
                    v-if="can('delete')"
                    type="link"
                    danger
                    :disabled="record.status === 0"
                    @click="remove(record)"
                  >
                    <span
                      class="ref-icon"
                      :style="{ maskImage: `url(${tagAsset('delete.svg')})` }"
                    ></span
                    >删除 </Button
                  ><Button type="link" @click="openDetail(record)">
                    <span
                      class="ref-icon"
                      :style="{ maskImage: `url(${tagAsset('detail.svg')})` }"
                    ></span
                    >详情
                  </Button>
                </div>
              </template>
            </TableViewport>
            <footer class="tag-pagination">
              <ListPagination
                :current="pagination.pageNo"
                :page-size="pagination.pageSize"
                :total="pagination.total"
                @change="
                  (pageNo: number, pageSize: number) => {
                    pagination.pageNo = pageNo;
                    pagination.pageSize = pageSize;
                    void loadRows();
                  }
                "
              />
            </footer>
          </section>
        </main>
      </div>
    </div>
  </Page>
</template>

<style scoped src="../table-font.css"></style>
