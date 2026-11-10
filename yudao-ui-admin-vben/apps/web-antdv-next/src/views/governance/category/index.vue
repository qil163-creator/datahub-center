<script lang="ts" setup>
import type { DialogRequest } from './modules/workbench-dialog.vue';

import type { SafetyCategoryWorkbenchApi as Api } from '#/api/governance/category/workbench';

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

import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import {
  Alert,
  Button,
  CheckboxGroup,
  Dropdown,
  Menu,
  MenuItem,
  message,
  Switch,
  Tag,
} from 'antdv-next';

import {
  changeCategoryWorkbenchStatus,
  deleteCategoryWorkbench,
  deleteCategoryWorkbenchDirectory,
  deleteCategoryWorkbenchList,
  getCategoryWorkbenchDirectory,
  getCategoryWorkbenchOptions,
  getCategoryWorkbenchPage,
} from '#/api/governance/category/workbench';
import SidebarCollapse from '#/components/sidebar-collapse/index.vue';
import TableViewport from '#/components/table-viewport/index.vue';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import {
  createLatestWins,
  parseResourceId,
  resourceIdKey,
} from '#/utils/resource-id';
import { useBatchConfirm as useOwnedConfirm } from '#/views/dataforge/batchfile/use-confirm';
import NativeSearch from '#/views/governance/common/native-search.vue';
import ReferencePagination from '#/views/governance/standardcommon/standard-pagination.vue';

import ReferenceLevel from './components/reference-level.vue';
import ReferenceTree from './components/reference-tree.vue';
import WorkbenchDialog from './modules/workbench-dialog.vue';
import {
  CATEGORY_COLUMNS,
  CATEGORY_STATUS_OPTIONS,
  categoryConfigured,
  categoryDescendants,
  categoryDirectoryName,
  categoryDisplayId,
  categoryIcon,
  categoryLevel,
  categoryTime,
  validCategoryRevision,
} from './reference-data';
import { useCategorySession } from './use-session';

import './reference.css';

defineOptions({ name: 'GovernanceSafetyCategory' });
const { identity, can } = useCategorySession();
const active = ref(true);
let alive = true;
let epoch = 0;
const confirm = useOwnedConfirm(
  computed(() => JSON.stringify([identity.value, active.value])),
);
const options = ref<Api.Options>({
  directories: [],
  levels: [],
  priorities: [],
});
const rows = ref<Api.Category[]>([]);
const selectedId = ref<string>();
const selectedRowKeys = ref<string[]>([]);
const pending = reactive(new Map<string, symbol>());
const loading = ref(false);
const optionsLoading = ref(false);
const errorText = ref('');
const optionsError = ref('');
const sidebarOpen = ref(true);
const showFilters = ref(true);
const visibleColumns = ref(CATEGORY_COLUMNS.map((column) => column.key));
const columns = computed(() =>
  CATEGORY_COLUMNS.filter((column) =>
    visibleColumns.value.includes(column.key),
  ),
);
const filter = reactive<{
  dataLevelId?: Api.Id;
  name?: string;
  status?: number;
}>({});
const query = reactive<Api.PageReq & { pageNo: number; pageSize: number }>({
  pageNo: 1,
  pageSize: 10,
  sortField: 'createTime',
  sortOrder: 'desc',
});
const total = ref(0);
const listGuard = createLatestWins();
const optionsGuard = createLatestWins();
const dialog = ref<DialogRequest | null>(null);
const dialogKey = ref(0);
function open(request: DialogRequest) {
  let action = 'update';
  if (request.mode === 'create') action = 'create';
  else if (['detail', 'mask-view'].includes(request.mode)) action = 'query';
  if (
    request.mode === 'directory'
      ? !can(request.id ? 'update' : 'create', 'category-data')
      : !can(action)
  )
    return;
  dialogKey.value++;
  dialog.value = request;
}
function clear() {
  epoch++;
  listGuard.invalidate();
  optionsGuard.invalidate();
  dialog.value = null;
  selectedRowKeys.value = [];
  pending.clear();
  rows.value = [];
  total.value = 0;
  options.value = { directories: [], levels: [], priorities: [] };
  loading.value = false;
  optionsLoading.value = false;
  errorText.value = '';
  optionsError.value = '';
  selectedId.value = undefined;
}
async function loadRows() {
  if (!active.value || !alive || !can('query')) return;
  const ticket = listGuard.next();
  loading.value = true;
  errorText.value = '';
  try {
    const result = await getCategoryWorkbenchPage({
      ...query,
      categoryIds: selectedId.value
        ? categoryDescendants(selectedId.value, options.value.directories)
        : undefined,
    });
    if (!ticket.isCurrent()) return;
    if (query.pageNo > 1 && result.list.length === 0 && result.total > 0) {
      query.pageNo = Math.max(1, Math.ceil(result.total / query.pageSize));
      void loadRows();
      return;
    }
    rows.value = result.list;
    total.value = result.total;
    selectedRowKeys.value = [];
  } catch {
    if (ticket.isCurrent()) {
      rows.value = [];
      total.value = 0;
      errorText.value = '数据分类读取失败，请重试。';
    }
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}
async function loadOptions() {
  if (!active.value || !alive || !can('query')) return;
  const ticket = optionsGuard.next();
  optionsLoading.value = true;
  optionsError.value = '';
  try {
    const context = await getCategoryWorkbenchOptions();
    if (!ticket.isCurrent()) return;
    options.value = context;
    if (
      selectedId.value &&
      !context.directories.some(
        (item) => resourceIdKey(item.id) === selectedId.value,
      )
    )
      selectedId.value = undefined;
  } catch {
    if (ticket.isCurrent()) optionsError.value = '类目和分级读取失败，请重试。';
  } finally {
    if (ticket.isCurrent()) optionsLoading.value = false;
  }
}
async function refresh() {
  await loadOptions();
  await loadRows();
}
function search() {
  Object.assign(query, filter, { pageNo: 1 });
  void loadRows();
}
function reset() {
  filter.name = undefined;
  filter.dataLevelId = undefined;
  filter.status = undefined;
  search();
}
function selectDirectory(id?: string) {
  selectedId.value = id;
  query.pageNo = 1;
  void loadRows();
}
function tableChange(
  _page: unknown,
  _filters: unknown,
  sorter:
    | { columnKey?: number | string; field?: unknown; order?: null | string }
    | { columnKey?: number | string; field?: unknown; order?: null | string }[],
) {
  const sort = Array.isArray(sorter) ? sorter[0] : sorter;
  const key = sort?.columnKey || sort?.field;
  query.sortField = key === 'id' ? 'id' : 'createTime';
  query.sortOrder = sort?.order === 'ascend' ? 'asc' : 'desc';
  query.pageNo = 1;
  void loadRows();
}
function mutationItem(row: Api.Category): Api.MutationItem | undefined {
  return parseResourceId(row.id) && validCategoryRevision(row.revision)
    ? { id: row.id, revision: row.revision }
    : undefined;
}
function selectedItems() {
  const selected = rows.value.filter((row) =>
    selectedRowKeys.value.includes(resourceIdKey(row.id)),
  );
  const items = selected.map((item) => mutationItem(item));
  return items.length > 0 && items.every((item) => !!item)
    ? (items as Api.MutationItem[])
    : undefined;
}
function batchLevel() {
  const items = selectedItems();
  if (items) open({ mode: 'batch', items });
}
function remove(row?: Api.Category) {
  if (!can('delete')) return;
  const items = row
    ? [mutationItem(row)].filter((item): item is Api.MutationItem => !!item)
    : selectedItems();
  if (!items?.length) return;
  const captured = epoch;
  confirm({
    title: '提示',
    content: `是否确认删除数据分类编号为“${(row ? [row] : rows.value.filter((item) => selectedRowKeys.value.includes(resourceIdKey(item.id)))).map((item) => categoryDisplayId(item)).join(',')}”的数据项？`,
    okText: '确定',
    cancelText: '取消',
    async onOk() {
      if (captured !== epoch || !active.value || !can('delete')) return;
      try {
        await (row
          ? deleteCategoryWorkbench(items[0]!)
          : deleteCategoryWorkbenchList(items));
        if (captured !== epoch || !active.value) return;
        message.success('删除成功');
        await loadRows();
      } catch {
        if (captured === epoch && active.value)
          errorText.value = '删除失败，请检查启用状态、引用或刷新后重试。';
      }
    },
  });
}
async function statusChange(row: Api.Category, checked: boolean) {
  const item = mutationItem(row);
  const key = resourceIdKey(row.id);
  if (!item || !can('update') || pending.has(key)) return;
  const captured = epoch;
  confirm({
    title: '提示',
    content: `确认要${checked ? '启用' : '禁用'}“${row.name || ''}”分类吗？`,
    okText: '确定',
    cancelText: '取消',
    async onOk() {
      if (
        captured !== epoch ||
        !active.value ||
        !can('update') ||
        pending.has(key)
      )
        return;
      const token = Symbol(key);
      pending.set(key, token);
      try {
        await changeCategoryWorkbenchStatus({
          ...item,
          status: checked ? 0 : 1,
        });
        if (captured !== epoch || !active.value) return;
        message.success('操作成功');
        await loadRows();
      } catch {
        if (captured === epoch && active.value)
          errorText.value = '状态修改失败，请刷新后重试。';
      } finally {
        if (pending.get(key) === token) pending.delete(key);
      }
    },
  });
}
function removeDirectory(id: string) {
  if (!can('delete', 'category-data')) return;
  const captured = epoch;
  confirm({
    title: '提示',
    content: '是否确认删除该数据分类类目？',
    okText: '确定',
    cancelText: '取消',
    async onOk() {
      if (
        captured !== epoch ||
        !active.value ||
        !can('delete', 'category-data')
      )
        return;
      try {
        const node = await getCategoryWorkbenchDirectory(id);
        if (
          captured !== epoch ||
          resourceIdKey(node.id) !== id ||
          !validCategoryRevision(node.revision)
        )
          return;
        await deleteCategoryWorkbenchDirectory(node.id, node.revision);
        if (captured !== epoch || !active.value) return;
        message.success('删除成功');
        await refresh();
      } catch {
        if (captured === epoch && active.value)
          optionsError.value = '删除失败，请检查子类目、分类引用或刷新后重试。';
      }
    },
  });
}
function saved() {
  void refresh();
}
watch(
  identity,
  () => {
    clear();
    if (active.value) void refresh();
  },
  { flush: 'sync' },
);
onMounted(() => {
  void refresh();
});
onActivated(() => {
  if (!active.value) {
    active.value = true;
    void refresh();
  }
});
onDeactivated(() => {
  active.value = false;
  clear();
});
onBeforeUnmount(() => {
  alive = false;
  active.value = false;
  clear();
});
</script>

<template>
  <Page auto-content-height>
    <div class="qdata-category">
      <Alert
        v-if="!can('query')"
        message="暂无数据分类查看权限"
        type="warning"
      />
      <template v-else>
        <ReferenceTree
          v-if="sidebarOpen"
          :directories="options.directories"
          :selected-id="selectedId"
          :loading="optionsLoading"
          :error="optionsError"
          :can-create="can('create', 'category-data')"
          :can-edit="can('update', 'category-data')"
          :can-delete="can('delete', 'category-data')"
          @select="selectDirectory"
          @retry="loadOptions"
          @create="(id) => open({ mode: 'directory', categoryId: id })"
          @edit="(id) => open({ mode: 'directory', id })"
          @remove="removeDirectory"
        />
        <SidebarCollapse :collapsed="!sidebarOpen" label="数据分类类目" @toggle="sidebarOpen = !sidebarOpen" />
        <main class="category-main">
          <section v-show="showFilters" class="category-filters">
            <NativeSearch
              :values="filter"
              icons
              :schema="[
                {
                  fieldName: 'name',
                  label: '分类名称',
                  component: 'Input',
                  componentProps: {
                    allowClear: true,
                    placeholder: '请输入分类名称',
                  },
                },
                {
                  fieldName: 'dataLevelId',
                  label: '数据分级',
                  component: 'Select',
                  componentProps: {
                    allowClear: true,
                    placeholder: '请选择数据分级',
                    options: options.levels.map((item) => ({
                      value: resourceIdKey(item.id),
                      label: item.safetyLevel || item.safetyName,
                    })),
                  },
                },
                {
                  fieldName: 'status',
                  label: '状态',
                  component: 'Select',
                  componentProps: {
                    allowClear: true,
                    placeholder: '请选择状态',
                    options: CATEGORY_STATUS_OPTIONS,
                  },
                },
              ]"
              @values="Object.assign(filter, $event)"
              @submit="search"
              @reset="reset"
            />
          </section>
          <section class="category-results">
            <div class="category-toolbar">
              <strong>数据分类列表</strong>
              <div>
                <Button
                  v-if="can('create')"
                  type="primary"
                  @click="open({ mode: 'create', categoryId: selectedId })"
                >
                  <i
                    class="category-icon"
                    :style="{ maskImage: `url(${categoryIcon('plus')})` }"
                  ></i>新增
</Button><Button
                  v-if="can('delete')"
                  danger
                  :disabled="!selectedRowKeys.length"
                  @click="remove()"
                >
                  <i
                    class="category-icon"
                    :style="{ maskImage: `url(${categoryIcon('delete')})` }"
                  ></i>删除
</Button><Button
                  v-if="can('update')"
                  type="primary"
                  :disabled="!selectedRowKeys.length"
                  @click="batchLevel"
                >
                  <i
                    class="category-icon"
                    :style="{
                      maskImage:
                        'url(/assets/qdata-safety-category/assign-level.png)',
                    }"
                  ></i>指定分级
                </Button>
              </div>
              <div>
                <Button
                  aria-label="显示或隐藏查询"
                  @click="showFilters = !showFilters"
                >
                  <i
                    class="category-icon"
                    :style="{ maskImage: `url(${categoryIcon('search')})` }"
                  ></i>
</Button><Button aria-label="刷新数据分类" @click="refresh">
                  <i
                    class="category-icon"
                    :style="{ maskImage: `url(${categoryIcon('refresh')})` }"
                  ></i>
</Button><Dropdown :trigger="['click']">
                  <Button aria-label="选择显示列">
                    <i
                      class="category-icon"
                      :style="{
                        maskImage: `url(${categoryIcon('columns')})`,
                      }"
                    ></i>
</Button><template #popupRender>
                    <div class="category-column-picker">
                      <CheckboxGroup
                        v-model:value="visibleColumns"
                        :options="
                          CATEGORY_COLUMNS.map((item) => ({
                            value: item.key,
                            label: item.title,
                          }))
                        "
                      />
                    </div>
                  </template>
                </Dropdown>
              </div>
            </div>
            <Alert v-if="errorText" :message="errorText" type="error">
              <template #action>
                <Button size="small" @click="loadRows"> 重试 </Button>
              </template>
            </Alert>
            <TableViewport
              class="category-table"
              :columns="columns"
              :data-source="rows"
              :row-key="(row: Api.Category) => resourceIdKey(row.id)"
              :row-selection="{
                columnWidth: 45,
                selectedRowKeys,
                onChange: (keys: (number | string)[]) =>
                  (selectedRowKeys = keys.map(String)),
              }"
              :loading="loading"
              :pagination="false"
              :scroll="{ x: 1785 }"
              :custom-row="
                (row: Api.Category) => ({
                  onDblclick: () => open({ mode: 'detail', id: row.id }),
                })
              "
              @change="tableChange"
            >
              <template #bodyCell="{ column, record }">
                <span v-if="column.key === 'id'">{{
                  categoryDisplayId(record)
                }}</span>
                <div
                  v-else-if="column.key === 'name'"
                  class="category-name-cell"
                >
                  <span>{{ record.name || '-' }}</span>
                  <TextEllipsisCell
                    class="category-description"
                    :content="record.description || '-'"
                    :line="2"
                    :line-height="18"
                    center
                  />
                </div>
                <span
                  v-else-if="column.key === 'shortName'"
                  class="category-code"
                  >{{ record.shortName || '-' }}</span><span v-else-if="column.key === 'categoryName'">{{
                  categoryDirectoryName(record, options.directories)
                }}</span><ReferenceLevel
                  v-else-if="column.key === 'dataLevelId'"
                  v-bind="categoryLevel(record, options.levels)"
                /><Tag
                  v-else-if="column.key === 'priority'"
                  class="category-soft-tag"
                >
                  {{
                    options.priorities.find(
                      (item) => item.value === record.priority,
                    )?.label ||
                    record.priority ||
                    '-'
                  }}
</Tag><Button
                  type="link"
                  v-else-if="
                    column.key === 'masking' && categoryConfigured(record)
                  "
                  class="category-tag-link"
                  @click="open({ mode: 'mask-view', id: record.id })"
                >
                  已配置
</Button><span v-else-if="column.key === 'masking'">未配置</span><Switch
                  v-else-if="column.key === 'status'"
                  :checked="record.status === 0"
                  :loading="pending.has(resourceIdKey(record.id))"
                  :disabled="
                    !can('update') || !validCategoryRevision(record.revision)
                  "
                  @change="(checked) => statusChange(record, !!checked)"
                /><span v-else-if="column.key === 'creatorName'">{{
                  record.creatorName || '-'
                }}</span><span v-else-if="column.key === 'createTime'">{{
                  categoryTime(record.createTime)
                }}</span>
                <div
                  v-else-if="column.key === 'actions'"
                  class="category-row-actions"
                >
                  <Button
                    type="link"
                    @click="open({ mode: 'detail', id: record.id })"
                  >
                    <i
                      class="category-icon"
                      :style="{ maskImage: `url(${categoryIcon('detail')})` }"
                    ></i>详情
</Button><Button
                    v-if="can('update')"
                    type="link"
                    @click="open({ mode: 'edit', id: record.id })"
                  >
                    <i
                      class="category-icon"
                      :style="{ maskImage: `url(${categoryIcon('edit')})` }"
                    ></i>修改
</Button><Dropdown
                    v-if="can('update') || can('delete')"
                    :trigger="['hover']"
                  >
                    <Button type="link">
                      <IconifyIcon icon="lucide:ellipsis-vertical" />更多
</Button><template #popupRender>
                      <Menu>
                        <MenuItem
                          v-if="can('update')"
                          key="mask"
                          @click="open({ mode: 'mask', id: record.id })"
                        >
                          设置脱敏
</MenuItem><MenuItem
                          v-if="can('delete')"
                          key="delete"
                          danger
                          @click="remove(record)"
                        >
                          删除
                        </MenuItem>
                      </Menu>
                    </template>
                  </Dropdown>
                </div>
              </template>
            </TableViewport>
            <footer class="category-pagination">
              <ReferencePagination
                v-model:page="query.pageNo"
                v-model:page-size="query.pageSize"
                :total="total"
                @change="loadRows"
              />
            </footer>
          </section>
        </main>
      </template>
      <WorkbenchDialog
        v-if="dialog"
        :key="dialogKey"
        :request="dialog"
        @close="dialog = null"
        @success="saved"
        @mask="(id) => open({ mode: 'mask-view', id })"
      />
    </div>
  </Page>
</template>
