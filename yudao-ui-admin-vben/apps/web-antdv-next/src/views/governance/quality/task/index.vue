<script setup lang="ts">
import type { QualityTaskApi } from '#/api/governance/quality/task';

import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { formatDateTime } from '@vben/utils';

import { Alert, Button, Input, message, Switch, Tree } from 'antdv-next';

import { ACTION_ICON, TableAction } from '#/adapter/vxe-table';
import { getCategoryDataQualityList } from '#/api/governance/categorydataquality';
import {
  deleteQualityTask,
  executeQualityTask,
  getQualityTaskPage,
  updateQualityTaskStatus,
} from '#/api/governance/quality/task';
import DetailBackButton from '#/components/detail-back-button/index.vue';
import SidebarCollapse from '#/components/sidebar-collapse/index.vue';
import TreeToolbar from '#/components/tree-toolbar/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';

import { useTreeExpansion } from '../../common/use-tree-expansion';
import { useMetadataGrid } from '../../metadata-common/use-metadata-grid';
import { statusOptions, strategyOptions } from './data';
import TaskForm from './modules/form.vue';

import './qdata-quality.css';
defineOptions({ name: 'GovernanceQualityTask' });
const route = useRoute();
const router = useRouter();
const { hasAccessByCodes } = useAccess();
const view = computed(() =>
  ['create', 'detail', 'edit'].includes(String(route.query.view))
    ? String(route.query.view)
    : 'list',
);
const taskId = computed(() =>
  typeof route.query.id === 'string' && /^\d+$/u.test(route.query.id)
    ? route.query.id
    : undefined,
);
const search = ref('');
const categories = ref<{ label: string; parent?: string; value: string }[]>([]);
const categoryFailed = ref(false);
const categoryLoading = ref(false);
const filters = reactive({
  taskName: '',
  taskStatus: undefined as number | undefined,
  taskCategory: undefined as string | undefined,
});
const applied = reactive({ ...filters });
const busy = reactive(new Set<string>());
const categoryRequests = useObjectRequest(
  () => '',
  () => {
    categories.value = [];
    void loadCategories();
  },
);
const treeData = computed(() => [
  {
    title: '数据质量类目',
    key: 'all',
    children: categories.value
      .filter((row) => row.label.includes(search.value.trim()))
      .map((row) => ({ title: row.label, key: row.value })),
  },
]);
async function loadCategories() {
  const current = categoryRequests.begin();
  categoryFailed.value = false;
  categoryLoading.value = true;
  try {
    const rows = await getCategoryDataQualityList({});
    if (current())
      categories.value = rows.map((row) => ({
        label: row.categoryName ?? row.categoryCode ?? String(row.id),
        value: row.categoryCode ?? String(row.id),
      }));
  } catch {
    if (current()) categoryFailed.value = true;
  } finally {
    if (current()) categoryLoading.value = false;
  }
}
function enter(next: string, id?: QualityTaskApi.Id) {
  void router.push({
    path: route.path,
    query: { view: next, ...(id === undefined ? {} : { id: String(id) }) },
  });
}
async function back() {
  await router.push({ path: route.path });
  void load();
}
function query() {
  Object.assign(applied, filters);
  void load(true);
}
function reset() {
  filters.taskName = '';
  filters.taskStatus = undefined;
  query();
}
async function status(row: QualityTaskApi.Task) {
  if (row.id === undefined || busy.has(String(row.id))) return;
  const key = String(row.id);
  busy.add(key);
  try {
    await updateQualityTaskStatus(row.id, row.taskStatus === 0 ? 1 : 0);
    message.success('状态已更新');
    await load();
  } finally {
    busy.delete(key);
  }
}
async function execute(row: QualityTaskApi.Task) {
  if (row.id === undefined || busy.has(String(row.id))) return;
  const key = String(row.id);
  busy.add(key);
  try {
    await executeQualityTask(row.id);
    message.success('已提交执行，请在质量报告中查看结果');
    await load();
  } finally {
    busy.delete(key);
  }
}
async function remove(row: QualityTaskApi.Task) {
  if (row.id === undefined) return;
  await deleteQualityTask(row.id);
  await load();
}
const { Grid, gridApi, load } = useMetadataGrid<QualityTaskApi.Task>(
  [
    { field: 'id', title: '编号', width: 80, sortable: true },
    {
      field: 'taskName',
      title: '任务名称',
      minWidth: 230,
      slots: { default: 'name' },
    },
    {
      field: 'description',
      title: '描述',
      minWidth: 150,
    },
    {
      field: 'taskCategory',
      title: '所属类目',
      width: 120,
      formatter: ({ cellValue }) =>
        categories.value.find((row) => row.value === cellValue)?.label ??
        cellValue ??
        '-',
    },
    { field: 'objectCount', title: '稽查对象数', width: 100, sortable: true },
    { field: 'ruleCount', title: '稽查规则数', width: 100, sortable: true },
    {
      field: 'executeStrategy',
      title: '执行策略',
      width: 110,
      formatter: ({ cellValue }) =>
        strategyOptions.find((row) => row.value === cellValue)?.label ??
        cellValue,
    },
    {
      field: 'taskStatus',
      title: '任务状态',
      width: 105,
      slots: { default: 'status' },
    },
    {
      field: 'cronExpression',
      title: '调度周期',
      width: 140,
      formatter: ({ cellValue }) => cellValue || '-',
    },
    {
      field: 'lastRunTime',
      title: '上次执行时间',
      width: 170,
      formatter: ({ cellValue }) =>
        cellValue ? formatDateTime(cellValue) : '-',
    },
    { field: 'creator', title: '创建人', width: 100 },
    {
      field: 'createTime',
      title: '创建时间',
      width: 170,
      formatter: ({ cellValue }) =>
        cellValue ? formatDateTime(cellValue) : '-',
    },
    {
      field: 'actions',
      title: '操作',
      width: 240,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ],
  (page) => getQualityTaskPage({ ...page, ...applied }),
  {
    schema: [
      {
        fieldName: 'taskName',
        label: '任务名称',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入任务名称' },
      },
      {
        fieldName: 'taskStatus',
        label: '任务状态',
        component: 'Select',
        componentProps: {
          allowClear: true,
          placeholder: '请选择任务状态',
          options: statusOptions,
        },
      },
    ],
    handleValuesChange: (values) => Object.assign(filters, values),
    handleSubmit: (values) => {
      Object.assign(filters, values);
      query();
    },
    handleReset: async () => {
      await gridApi.formApi.reset();
      reset();
    },
  },
  () => {
    Object.assign(filters, {
      taskName: '',
      taskStatus: undefined,
      taskCategory: undefined,
    });
    Object.assign(applied, filters);
  },
  'fill',
  true,
);
gridApi.setGridOptions({
  rowConfig: { keyField: 'id', isHover: true },
  scrollbarConfig: { height: 6 },
});
onMounted(loadCategories);
const expandedKeys = ref<string[]>([]);
const sidebarCollapsed = ref(false);
const { allExpanded, toggleExpand } = useTreeExpansion(
  treeData,
  expandedKeys,
  search,
);
</script>
<template>
  <Page auto-content-height content-class="q-quality-page">
    <TaskForm
      v-if="view !== 'list' && (view === 'create' || taskId)"
      :key="`${view}/${taskId}`"
      :id="view === 'create' ? undefined : taskId"
      :readonly="view === 'detail'"
      @back="back"
      @saved="back"
    />
    <Alert v-else-if="view !== 'list'" type="error" message="任务编号无效">
      <template #action>
        <DetailBackButton v-if="view === 'detail'" @click="back" /><Button v-else @click="back">返回列表</Button>
      </template>
    </Alert>
    <template v-else>
      <div class="q-task-layout">
        <aside v-show="!sidebarCollapsed" class="q-task-tree">
          <div class="flex items-center gap-2">
            <Input
              class="min-w-0 flex-1"
              v-model:value="search"
              placeholder="请输入类目名称"
              allow-clear
            />
            <TreeToolbar
              :expanded="allExpanded"
              :loading="categoryLoading"
              @toggle-expand="toggleExpand"
              @refresh="loadCategories"
            />
          </div>
          <div class="q-task-tree-scroll subtle-tree-scrollbar">
            <Tree
              v-model:expanded-keys="expandedKeys"
              :tree-data="treeData"
              block-node
                  :selected-keys="[filters.taskCategory ?? 'all']"
              @select="
                (keys) => {
                  filters.taskCategory =
                    String(keys[0]) === 'all' ? undefined : String(keys[0]);
                  query();
                }
              "
            >
              <template #titleRender="{ title }">
                <span
                  class="q-task-tree-label"
                  :title="typeof title === 'string' ? title : undefined"
                >{{ title }}</span>
              </template>
            </Tree>
          </div>
        </aside>
        <SidebarCollapse :collapsed="sidebarCollapsed" label="质量类目" @toggle="sidebarCollapsed = !sidebarCollapsed" />
        <main class="q-task-main">
          <section class="q-grid-shell">
<Grid class="q-grid subtle-grid-scrollbar" table-title="数据质量任务">
              <template #toolbar-tools>
                <TableAction
                  :actions="[
                    {
                      label: '新增',
                      type: 'primary',
                      icon: ACTION_ICON.ADD,
                      auth: ['governance:quality-task:create'],
                      onClick: () => enter('create'),
                    },
                  ]"
                />
</template><template #name="{ row }">
                <button
                  type="button"
                  class="q-link"
                  @click="enter('detail', row.id)"
                >
                  {{ row.taskName }}
                </button>
</template><template #status="{ row }">
                <Switch
                  :checked="row.taskStatus === 0"
                  checked-children="上线"
                  un-checked-children="下线"
                  :loading="busy.has(String(row.id))"
                  :disabled="
                    !hasAccessByCodes(['governance:quality-task:update'])
                  "
                  @change="status(row)"
                />
</template><template #actions="{ row }">
                <TableAction
                  :actions="[
                    {
                      label: '修改',
                      icon: ACTION_ICON.EDIT,
                      type: 'link',
                      auth: ['governance:quality-task:update'],
                      onClick: () => enter('edit', row.id),
                    },
                    {
                      label: '详情',
                      icon: ACTION_ICON.VIEW,
                      type: 'link',
                      auth: ['governance:quality-task:query'],
                      onClick: () => enter('detail', row.id),
                    },
                  ]"
                  :drop-down-actions="[
                    {
                      label: '执行一次',
                      icon: 'lucide:play',
                      auth: ['governance:quality-task:execute'],
                      disabled: busy.has(String(row.id)),
                      popConfirm: {
                        title: `确认执行「${row.taskName}」？`,
                        confirm: () => execute(row),
                      },
                    },
                    {
                      label: '质量报告',
                      icon: 'lucide:chart-no-axes-combined',
                      auth: ['governance:quality-task:query'],
                      onClick: () =>
                        router.push({
                          path: '/governance/quality/report',
                          query: { taskId: String(row.id) },
                        }),
                    },
                    {
                      label: '删除',
                      icon: ACTION_ICON.DELETE,
                      danger: true,
                      auth: ['governance:quality-task:delete'],
                      popConfirm: {
                        title: `确认删除「${row.taskName}」？`,
                        confirm: () => remove(row),
                      },
                    },
                  ]"
                />
              </template>
            </Grid>
          </section>
        </main>
      </div>
    </template>
  </Page>
</template>
