<script setup lang="ts">
import type { IntegrationApi as Api } from '#/api/dataforge/integration';

import {
  computed,
  onActivated,
  onDeactivated,
  reactive,
  ref,
  watch,
} from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDate } from '@vben/utils';

import { useStorage } from '@vueuse/core';
import {
  Alert,
  Button,
  ConfigProvider,
  DateRangePicker,
  Dropdown,
  Empty,
  Input,
  Menu,
  MenuItem,
  message,
  Modal,
  Select,
  Spin,
  Switch,
  Tooltip,
  Tree,
} from 'antdv-next';

import {
  cloneIntegration,
  deleteIntegration,
  getIntegration,
  getIntegrationCategories,
  getIntegrationOwners,
  getIntegrationPage,
  getIntegrationStatistics,
  runIntegration,
  setIntegrationSchedule,
  setIntegrationStatus,
  stopIntegration,
} from '#/api/dataforge/integration';
import ListPagination from '#/components/list-pagination/index.vue';
import SidebarCollapse from '#/components/sidebar-collapse/index.vue';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { useObjectRequest } from '#/hooks/use-object-request';

import {
  collectExpandableKeys,
  isTreeExpanded,
} from '../common/tree-expansion';
import {
  categoryTree,
  cronSummary,
  duration,
  labelOf,
  recentRunTime,
  runOptions,
  statusOptions,
  strategyOptions,
} from './model';
import CategoryModal from './modules/category-modal.vue';
import InstancesModal from './modules/instances-modal.vue';
import ReferenceIcon from './modules/reference-icon.vue';
import ScheduleModal from './modules/schedule-modal.vue';
import TaskDetail from './modules/task-detail.vue';
import TaskForm from './modules/task-form.vue';
import { integrationReferenceTheme } from './reference-theme';

import './style.css';

const props = defineProps<{ mode: Api.Mode }>();

const router = useRouter();
const guideHidden = useStorage('dataforge.integration.guide.hidden', false);
const guideClosed = ref(false);
const showAdvancedFilters = ref(false);
const runDateRange = ref<[string, string]>();

const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const isActive = (task: Api.Task) =>
  task.activeRunning ??
  (!task.importedSnapshot && task.lastRunStatus === 'RUNNING');
const can = (action: string) =>
  hasAccessByCodes([`dataforge:integration:${action}`]);
const active = ref(true);
onActivated(() => {
  active.value = true;
});
onDeactivated(() => {
  active.value = false;
});
const scope = computed(() =>
  JSON.stringify([
    active.value,
    access.accessToken,
    access.refreshToken,
    access.accessCodes,
    user.userInfo?.userId,
    props.mode,
  ]),
);
const rows = ref<Api.Task[]>([]);
const categories = ref<Api.Category[]>([]);
const owners = ref<Api.Owner[]>([]);
const statistics = ref<Api.Statistics>();
const statisticsUnavailable = computed(
  () => statistics.value?.runtimeAvailable === false,
);
const page = ref(1);
const pageSize = ref(10);
const total = ref(0);
const loading = ref(false);
const failed = ref(false);
const contextFailed = ref(false);
const contextLoading = ref(false);
let categoriesLoaded = false;
const busy = ref(false);
const detail = ref<Api.Task>();
const currentTask = ref<Api.Task>();
const categoryOpen = ref(false);
const categoryAction = ref<'child' | 'delete' | 'edit' | 'sibling'>('child');
const currentCategory = ref<Api.Category>();
const formOpen = ref(false);
const scheduleOpen = ref(false);
const instancesOpen = ref(false);
const showFilter = ref(true);
const showColumns = ref(false);
const categoryCollapsed = ref(false);
const categorySearch = ref('');
const selectedCategories = ref<string[]>([]);
const expandedCategories = ref<string[]>(['__root']);
const sortOrder = ref<'asc' | 'desc'>('desc');
const filter = reactive({
  name: '',
  status: undefined as number | undefined,
  runStatus: undefined as Api.RunStatus | undefined,
  ownerId: undefined as Api.Id | undefined,
});
const executedToday = ref(false);
const applied = reactive({
  ...filter,
  executedToday: false,
  runDateStart: undefined as string | undefined,
  runDateEnd: undefined as string | undefined,
});
const hiddenColumns = ref<string[]>([]);
type Action = 'clone' | 'delete' | 'run' | 'schedule' | 'status' | 'stop';
const confirmation = ref<{ action: Action; task: Api.Task; value?: boolean }>();
const request = useObjectRequest(() => scope.value, clear);
const contextRequest = useObjectRequest(() => scope.value);
const detailRequest = useObjectRequest(() => scope.value);
const actionRequest = useObjectRequest(() => scope.value);
const visible = (column: string) => !hiddenColumns.value.includes(column);
const columns = computed(() => [
  { key: 'category', title: '所属类目' },
  { key: 'last', title: '最近执行信息' },
  { key: 'control', title: '运行控制' },
  ...(props.mode === 'OFFLINE'
    ? [{ key: 'strategy', title: '策略与调度' }]
    : []),
  { key: 'owner', title: '责任人' },
]);
const tree = computed(() => {
  const result = categoryTree(categories.value);
  const roots =
    result.length === 1 && result[0]?.title === '数据集成类目'
      ? result
      : [
          {
            key: '__root',
            value: '__root',
            title: '数据集成类目',
            selectable: true,
            isLeaf: false,
            children: result,
          },
        ];
  const keyword = categorySearch.value.trim().toLowerCase();
  if (!keyword) return roots;
  function filterNodes(nodes: typeof roots): typeof roots {
    return nodes.flatMap((node) => {
      if (node.title.toLowerCase().includes(keyword)) return [node];
      const children = node.children ? filterNodes(node.children) : [];
      return children.length > 0 ? [{ ...node, children }] : [];
    });
  }
  return filterNodes(roots);
});
const allExpanded = computed(() =>
  isTreeExpanded(tree.value, expandedCategories.value),
);
function toggleExpandAll() {
  expandedCategories.value = allExpanded.value
    ? []
    : collectExpandableKeys(tree.value);
}
watch(categorySearch, (value) => {
  if (value.trim())
    expandedCategories.value = collectExpandableKeys(tree.value);
});
function clear() {
  rows.value = [];
  categories.value = [];
  owners.value = [];
  statistics.value = undefined;
  total.value = 0;
  detail.value = undefined;
  currentTask.value = undefined;
  categoryOpen.value = false;
  formOpen.value = false;
  scheduleOpen.value = false;
  instancesOpen.value = false;
  confirmation.value = undefined;
  loading.value = false;
  busy.value = false;
  contextLoading.value = false;
  categoriesLoaded = false;
}
async function loadContext() {
  if (!active.value || !can('query')) return;
  const current = contextRequest.begin();
  contextFailed.value = false;
  contextLoading.value = true;
  try {
    const [cats, users] = await Promise.all([
      getIntegrationCategories(),
      getIntegrationOwners(),
    ]);
    if (!current()) return;
    const expand = allExpanded.value;
    categories.value = cats;
    owners.value = users;
    if (!categoriesLoaded || expand || categorySearch.value.trim()) {
      expandedCategories.value = collectExpandableKeys(tree.value);
    }
    categoriesLoaded = true;
  } catch {
    if (current()) contextFailed.value = true;
  } finally {
    if (current()) contextLoading.value = false;
  }
}
async function load() {
  if (!active.value || !can('query')) return;
  const current = request.begin();
  loading.value = true;
  failed.value = false;
  try {
    const selected = selectedCategories.value[0];
    const result = await getIntegrationPage({
      mode: props.mode,
      pageNo: page.value,
      pageSize: pageSize.value,
      ...applied,
      name: applied.name.trim() || undefined,
      categoryId: selected && selected !== '__root' ? selected : undefined,
      sortField: 'id',
      sortOrder: sortOrder.value,
    });
    if (!current()) return;
    rows.value = result.list;
    total.value = result.total;
    if (page.value > 1 && result.list.length === 0 && result.total > 0) {
      page.value = Math.ceil(result.total / pageSize.value);
      void load();
      return;
    }
    const resultStatistics = await getIntegrationStatistics(props.mode);
    if (current()) statistics.value = resultStatistics;
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
function selectStatistic(status?: Api.RunStatus, today = false) {
  filter.runStatus = status;
  executedToday.value = today;
  search();
}
function statisticValue(value?: number, unit = '次') {
  return statisticsUnavailable.value ? '不可用' : `${value ?? '-'}${unit}`;
}
function search() {
  Object.assign(applied, filter, {
    executedToday: executedToday.value,
    runDateStart: runDateRange.value?.[0],
    runDateEnd: runDateRange.value?.[1],
  });
  page.value = 1;
  void load();
}
function reset() {
  selectedCategories.value = [];
  categorySearch.value = '';
  executedToday.value = false;
  runDateRange.value = undefined;
  Object.assign(filter, {
    name: '',
    status: undefined,
    runStatus: undefined,
    ownerId: undefined,
  });
  search();
}
watch(
  scope,
  () => {
    clear();
    if (active.value && can('query')) {
      void loadContext();
      void load();
    }
  },
  { immediate: true },
);
function selectCategory(keys: (number | string)[]) {
  selectedCategories.value = keys.map(String);
  page.value = 1;
  void load();
}
async function openTask(
  task: Api.Task,
  target: 'detail' | 'form' | 'instances' | 'schedule',
) {
  const current = detailRequest.begin();
  busy.value = true;
  try {
    const result = await getIntegration(task.id);
    if (!current()) return;
    currentTask.value = result;
    if (target === 'detail') detail.value = result;
    if (target === 'form') formOpen.value = true;
    if (target === 'instances') instancesOpen.value = true;
    if (target === 'schedule') scheduleOpen.value = true;
  } finally {
    if (current()) busy.value = false;
  }
}
function openCategory(
  action: 'child' | 'delete' | 'edit' | 'sibling',
  key: unknown,
) {
  currentCategory.value = categories.value.find(
    (category) => String(category.id) === String(key),
  );
  categoryAction.value = action;
  categoryOpen.value = true;
}
function categorySaved() {
  void loadContext();
  void load();
}
function add() {
  currentTask.value = undefined;
  formOpen.value = true;
}
function configure(task: Api.Task) {
  configureId(task.id);
}
function configureId(id: Api.Id) {
  void router.push({
    name: props.mode === 'REALTIME' ? 'DataforgeRealtimeIntegrationDesigner' : 'DataforgeIntegrationDesigner',
    params: { id: String(id) },
  });
}
function confirmAction(action: Action, task: Api.Task, value?: boolean) {
  if (action === 'run' && !task.executable) {
    message.info(
      task.runtimeAvailable === false
        ? task.runtimeMessage || '运行环境不可用，暂不能执行'
        : '任务流程尚未配置，暂不能执行',
    );
    return;
  }
  confirmation.value = { action, task, value };
}
const actionText: Record<Action, string> = {
  clone: '克隆任务',
  delete: '删除任务',
  run: '执行任务',
  stop: '停止任务',
  schedule: '调整调度状态',
  status: '调整任务状态',
};
async function perform() {
  const command = confirmation.value;
  if (!command || busy.value) return;
  let permission = 'update';
  if (command.action === 'delete') permission = 'delete';
  if (command.action === 'clone') permission = 'create';
  if (['run', 'stop'].includes(command.action)) permission = 'run';
  if (!can(permission)) {
    confirmation.value = undefined;
    return;
  }
  const current = actionRequest.begin();
  busy.value = true;
  try {
    if (command.action === 'delete') await deleteIntegration(command.task.id);
    if (command.action === 'clone') await cloneIntegration(command.task.id);
    if (command.action === 'run') await runIntegration(command.task.id);
    if (command.action === 'stop') await stopIntegration(command.task.id);
    if (command.action === 'status')
      await setIntegrationStatus({
        id: command.task.id,
        revision: command.task.revision,
        status: command.value ? 1 : 0,
      });
    if (command.action === 'schedule')
      await setIntegrationSchedule({
        id: command.task.id,
        revision: command.task.revision,
        scheduleStatus: command.value ? 1 : 0,
      });
    if (!current()) return;
    confirmation.value = undefined;
    message.success('操作成功');
    await load();
  } finally {
    if (current()) busy.value = false;
  }
}
function changeColumns(key: string) {
  hiddenColumns.value = visible(key)
    ? [...hiddenColumns.value, key]
    : hiddenColumns.value.filter((item) => item !== key);
}
function changePage(nextPage: number, nextSize: number) {
  page.value = nextSize === pageSize.value ? nextPage : 1;
  pageSize.value = nextSize;
  void load();
}
function saved() {
  void load();
}
const date = (value?: null | number) =>
  value ? formatDate(value, 'YYYY-MM-DD HH:mm:ss') : '-';
function past(value?: null | number) {
  return recentRunTime(value);
}
</script>

<template>
  <div class="qi-workbench-shell">
  <ConfigProvider :theme="integrationReferenceTheme">
    <Page
      :auto-content-height="!detail"
      :content-class="detail ? 'p-0' : 'flex min-h-0 flex-col overflow-hidden p-0'"
    >
      <div
        class="qi-workbench"
        :class="{ 'is-list': !detail }"
        :data-mode="mode"
      >
        <Alert
          v-if="!can('query')"
          type="warning"
          message="暂无数据集成任务查看权限"
          show-icon
        />
        <template v-else>
          <TaskDetail v-if="detail" :task="detail" @back="detail = undefined" />
          <template v-else>
            <section
              v-if="!guideHidden && !guideClosed"
              class="qi-guide"
              aria-label="数据集成任务说明"
            >
              <img
                class="qi-guide-icon"
                src="/assets/qdata-integration/help-info.png"
                alt=""
              />
              <div class="qi-guide-text">
                <h2>数据集成任务 —— 多源数据同步与转换的统一通道</h2>
                <p>
                  通过图形化方式配置ETL流程，支持关系型数据库、大数据平台、Kafka、HDFS等多源数据的接入与写出。提供丰富的转换组件，支持离线批处理与Flink实时流处理，实现数据清洗、转换与入湖入仓的一体化调度。
                </p>
              </div>
              <div class="qi-guide-actions">
                <Button @click="guideHidden = true">不再展示</Button>
                <Button type="primary" @click="guideClosed = true">关闭</Button>
              </div>
            </section>
            <div
              class="qi-main-layout"
              :class="{ 'is-category-collapsed': categoryCollapsed }"
            >
              <aside v-show="!categoryCollapsed" class="qi-category-panel">
                <div class="qi-category-tools flex shrink-0 items-center gap-2">
                  <Input
                    v-model:value="categorySearch"
                    class="min-w-0 flex-1"
                    placeholder="请输入数据集成类目名称"
                    aria-label="搜索数据集成类目"
                  >
                    <template #prefix>
                      <ReferenceIcon name="search" />
                    </template>
                  </Input>
                  <div class="qi-tree-tools">
                    <button
                      type="button"
                      :aria-label="
                        allExpanded ? '折叠全部类目' : '展开全部类目'
                      "
                      :title="allExpanded ? '折叠全部类目' : '展开全部类目'"
                      @click="toggleExpandAll"
                    >
                      <ReferenceIcon name="expand" />
                    </button>
                    <button
                      type="button"
                      aria-label="刷新类目"
                      :disabled="contextLoading"
                      @click="loadContext"
                    >
                      <ReferenceIcon name="refresh" />
                    </button>
                  </div>
                </div>
                <Alert
                  v-if="contextFailed"
                  type="error"
                  message="类目与负责人加载失败"
                >
                  <template #action>
                    <button type="button" @click="loadContext">重试</button>
                  </template>
                </Alert>
                <div class="qi-category-tree subtle-tree-scrollbar">
                  <Tree
                    v-model:expanded-keys="expandedCategories"
                    :selected-keys="selectedCategories"
                    :tree-data="tree"
                    :auto-expand-parent="false"
                    show-icon
                    block-node
                    @select="selectCategory"
                  >
                    <template #icon="node">
                      <ReferenceIcon
                        :name="node.isLeaf === false ? 'folder' : 'file'"
                      />
                    </template>
                    <template #titleRender="node">
                      <span class="qi-tree-title"><span
                          :title="
                            typeof node.title === 'string'
                              ? node.title
                              : undefined
                          "
                          >{{ node.title }}</span><Dropdown :trigger="['click']"><button
                            type="button"
                            class="qi-category-more"
                            :aria-label="`${node.title}类目操作`"
                            @click.stop
                          >
                            ⋮</button><template #popupRender><Menu class="qi-more-menu"><MenuItem
                                v-if="can('create')"
                                key="child"
                                @click="openCategory('child', node.key)"
                                >新增子级</MenuItem><MenuItem
                                v-if="can('create') && node.key !== '__root'"
                                key="sibling"
                                @click="openCategory('sibling', node.key)"
                                >新增同级</MenuItem><MenuItem
                                v-if="can('update') && node.key !== '__root'"
                                key="edit"
                                @click="openCategory('edit', node.key)"
                                >修改</MenuItem><MenuItem
                                v-if="can('delete') && node.key !== '__root'"
                                key="delete"
                                danger
                                @click="openCategory('delete', node.key)"
                                >删除</MenuItem></Menu></template></Dropdown></span>
                    </template>
                  </Tree>
                </div>
              </aside>
              <SidebarCollapse
                :collapsed="categoryCollapsed"
                label="数据集成类目"
                @toggle="categoryCollapsed = !categoryCollapsed"
              />
              <main class="qi-list-panel">
                <section class="qi-statistics" aria-label="任务统计">
                  <button
                    type="button"
                    title="按任务去重，点击查看运行中的任务"
                    :disabled="statisticsUnavailable"
                    @click="selectStatistic('RUNNING')"
                  >
                    <span class="qi-stat-dot running"></span><span>运行中任务：</span><b>{{ statisticValue(statistics?.running) }}</b>
                  </button>
                  <button
                    type="button"
                    title="点击查看今日最近执行失败的任务"
                    :disabled="statisticsUnavailable"
                    @click="selectStatistic('FAILURE', true)"
                  >
                    <span class="qi-stat-dot warning"></span><span>今日异常任务：</span><b>{{ statisticValue(statistics?.todayFailed) }}</b>
                  </button>
                  <button
                    type="button"
                    title="今日执行的全部实例数，点击筛选今日任务"
                    :disabled="statisticsUnavailable"
                    @click="selectStatistic(undefined, true)"
                  >
                    <span class="qi-stat-dot executed"></span><span>今日执行：</span><b>{{ statisticValue(statistics?.todayExecuted) }}</b>
                  </button>
                  <button
                    type="button"
                    title="成功实例 /（成功实例 + 失败实例），点击查看今日成功任务"
                    :disabled="statisticsUnavailable"
                    @click="selectStatistic('SUCCESS', true)"
                  >
                    <span class="qi-stat-dot success"></span><span>今日成功率：</span><b>{{ statisticValue(statistics?.todaySuccessRate, '%') }}</b>
                  </button>
                  <p>
                    数据统计截止：{{ date(statistics?.statisticTime)
                    }}<button type="button" aria-label="刷新统计" @click="load">
                      <ReferenceIcon name="refresh" />
                    </button>
                  </p>
                </section>
                <section v-if="showFilter" class="qi-filters">
                  <form @submit.prevent="search">
                    <div class="qi-filter-item">
                      <label>任务名称</label><Input
                        v-model:value="filter.name"
                        placeholder="请输入任务名称"
                        allow-clear
                        aria-label="查询任务名称"
                      />
                    </div>
                    <div class="qi-filter-item">
                      <label>任务状态</label><Select
                        v-model:value="filter.status"
                        :options="statusOptions"
                        placeholder="请选择任务状态"
                        allow-clear
                        aria-label="任务状态"
                      />
                    </div>
                    <div class="qi-filter-item">
                      <label>执行状态</label><Select
                        v-model:value="filter.runStatus"
                        :options="runOptions"
                        placeholder="请选择执行状态"
                        allow-clear
                        aria-label="执行状态"
                      />
                    </div>
                    <div class="qi-query-buttons">
                      <Button html-type="submit" class="qi-reference-button">
                        <ReferenceIcon name="query" />查询
                      </Button>
                      <Button @click="reset">
                        <ReferenceIcon name="reset" />重置
                      </Button>
                      <Button
                        class="qi-reference-button"
                        :aria-expanded="showAdvancedFilters"
                        @click="showAdvancedFilters = !showAdvancedFilters"
                      >
                        <ReferenceIcon name="expand" />{{
                          showAdvancedFilters ? '收起' : '展开'
                        }}
                      </Button>
                    </div>
                    <template v-if="showAdvancedFilters">
                      <div class="qi-filter-item">
                        <label>责任人</label><Select
                          v-model:value="filter.ownerId"
                          :options="
                            owners.map((owner) => ({
                              value: String(owner.id),
                              label: owner.name,
                            }))
                          "
                          placeholder="请选择责任人"
                          allow-clear
                          aria-label="筛选责任人"
                        />
                      </div>
                      <div class="qi-filter-item qi-filter-date">
                        <label>执行日期</label><DateRangePicker
                          v-model:value="runDateRange"
                          value-format="YYYY-MM-DD"
                          aria-label="执行日期范围"
                        />
                      </div>
                    </template>
                  </form>
                </section>
                <section class="qi-table-panel">
                  <div class="qi-table-toolbar">
                    <Button
                      v-if="can('create')"
                      class="qi-reference-button"
                      @click="add"
                    >
                      <ReferenceIcon name="plus" />新增
                    </Button>
                    <div class="qi-table-tools">
                      <Tooltip title="搜索">
                        <Button
                          aria-label="显示或隐藏搜索"
                          :aria-pressed="showFilter"
                          @click="showFilter = !showFilter"
                        >
                          <ReferenceIcon name="search" />
                        </Button>
</Tooltip><Tooltip title="刷新">
                        <Button
                          aria-label="刷新列表"
                          :loading="loading"
                          @click="load"
                        >
                          <ReferenceIcon name="refresh" />
                        </Button>
</Tooltip><Dropdown
                        :open="showColumns"
                        :trigger="['click']"
                        @open-change="(value) => (showColumns = value)"
                      >
                        <Tooltip title="列设置">
                          <Button aria-label="列设置">
                            <ReferenceIcon name="grid" />
                          </Button>
</Tooltip><template #popupRender>
                          <div class="qi-column-menu">
                            <label v-for="column in columns" :key="column.key"><input
                                type="checkbox"
                                :checked="visible(column.key)"
                                @change="changeColumns(column.key)"
                              />{{ column.title }}</label>
                          </div>
                        </template>
                      </Dropdown>
                    </div>
                  </div>
                  <Alert
                    v-if="failed"
                    type="error"
                    message="任务加载失败，请重试"
                    show-icon
                  >
                    <template #action>
                      <Button @click="load">重试</Button>
                    </template>
                  </Alert>
                  <Spin :spinning="loading" class="qi-table-loading">
                    <div class="qi-table-scroll">
                      <table
                        class="qi-table"
                        :class="{ 'qi-realtime-table': mode === 'REALTIME' }"
                      >
                        <colgroup>
                          <col class="qi-col-id" />
                          <col class="qi-col-info" />
                          <col
                            v-if="visible('category')"
                            class="qi-col-category"
                          />
                          <col v-if="visible('last')" class="qi-col-last" />
                          <col
                            v-if="visible('control')"
                            class="qi-col-control"
                          />
                          <col
                            v-if="mode === 'OFFLINE' && visible('strategy')"
                            class="qi-col-strategy"
                          />
                          <col v-if="visible('owner')" class="qi-col-owner" />
                          <col class="qi-col-actions" />
                        </colgroup>
                        <thead>
                          <tr>
                            <th>
                              <button
                                type="button"
                                class="qi-sort-button"
                                @click="
                                  sortOrder =
                                    sortOrder === 'desc' ? 'asc' : 'desc';
                                  load();
                                "
                              >
                                编号<span
                                  class="qi-sort-arrows"
                                  :class="sortOrder"
                                  aria-hidden="true"
                                ></span>
                              </button>
                            </th>
                            <th>任务信息</th>
                            <th v-if="visible('category')">所属类目</th>
                            <th v-if="visible('last')">
                              最近执行信息
                              <Tooltip
                                title="展示最近一次执行的状态、时间与耗时"
                              >
                                <ReferenceIcon name="info" />
                              </Tooltip>
                            </th>
                            <th v-if="visible('control')">运行控制</th>
                            <th
                              v-if="mode === 'OFFLINE' && visible('strategy')"
                            >
                              策略与调度
                            </th>
                            <th v-if="visible('owner')">责任人</th>
                            <th class="qi-action-cell">操作</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr v-for="task in rows" :key="String(task.id)">
                            <td class="qi-id-cell" :title="String(task.id)">
                              {{ task.id }}
                            </td>
                            <td>
                              <button
                                type="button"
                                class="qi-task-name"
                                :title="task.name"
                                @click="openTask(task, 'detail')"
                              >
                                <img
                                  :src="`/assets/qdata-integration/${task.engine === 'FLINK' ? 'flink.svg' : task.engine === 'SPARK' ? 'spark.svg' : 'datax.png'}`"
                                  alt=""
                                />{{ task.name }}
                              </button>
                              <TextEllipsisCell
                                class="qi-description"
                                :content="task.description || '-'"
                                :line="2"
                              />
                            </td>
                            <td v-if="visible('category')">
                              <button
                                class="qi-category-tag"
                                type="button"
                                @click="selectCategory([task.categoryId])"
                              >
                                {{ task.categoryName || '-' }}
                              </button>
                            </td>
                            <td v-if="visible('last')">
                              <span
                                class="qi-run-badge"
                                :class="task.lastRunStatus.toLowerCase()"
                                :title="
                                  task.importedSnapshot
                                    ? '源系统执行快照，不代表本平台正在执行'
                                    : ''
                                "
                                ><img
                                  class="qi-status-icon"
                                  :src="`/assets/qdata-integration/status-${task.lastRunStatus.toLowerCase()}.svg`"
                                  alt=""
                                />{{
                                  labelOf(runOptions, task.lastRunStatus)
                                }}</span>
                              <p class="qi-last-time">
                                <span :title="date(task.lastRunTime)">{{
                                  past(task.lastRunTime)
                                }}</span><span>{{
                                  duration(task.lastRunDuration)
                                }}</span>
                              </p>
                            </td>
                            <td v-if="visible('control')">
                              <div class="qi-switch-row">
                                <span>任务状态:</span><Switch
                                  :checked="task.status === 1"
                                  :disabled="
                                    !can('update') ||
                                    busy ||
                                    task.configurationStatus !== 'COMPLETE'
                                  "
                                  :aria-label="`${task.name}任务状态`"
                                  @change="
                                    (value) =>
                                      confirmAction('status', task, !!value)
                                  "
                                />
                              </div>
                              <div
                                v-if="mode === 'OFFLINE'"
                                class="qi-switch-row"
                              >
                                <span>调度状态:</span><Switch
                                  :checked="task.scheduleStatus === 1"
                                  :disabled="
                                    !can('update') || task.status !== 1 || busy
                                  "
                                  :aria-label="`${task.name}调度状态`"
                                  @change="
                                    (value) =>
                                      confirmAction('schedule', task, !!value)
                                  "
                                />
                              </div>
                            </td>
                            <td
                              v-if="mode === 'OFFLINE' && visible('strategy')"
                            >
                              <p class="qi-strategy">
                                {{
                                  labelOf(
                                    strategyOptions,
                                    task.executionStrategy,
                                  )
                                }}
                              </p>
                              <button
                                type="button"
                                class="qi-cron-summary"
                                :title="cronSummary(task.cronExpression)"
                                @click="openTask(task, 'schedule')"
                              >
                                <ReferenceIcon name="clock" />{{
                                  cronSummary(task.cronExpression)
                                }}
                              </button>
                            </td>
                            <td v-if="visible('owner')">
                              <p>{{ task.ownerName || '-' }}</p>
                              <p class="qi-phone">
                                {{ task.contactNumber || '-' }}
                              </p>
                            </td>
                            <td class="qi-action-cell">
                              <div class="qi-actions">
                                <Button
                                  type="link"
                                  v-if="can('update')"
                                  :disabled="isActive(task) || busy"
                                  @click="configure(task)"
                                >
                                  <ReferenceIcon name="edit" />配置
</Button><Button
                                  type="link"
                                  :disabled="busy"
                                  @click="openTask(task, 'detail')"
                                >
                                  <ReferenceIcon name="eye" />详情
</Button><Dropdown :trigger="['click']">
                                  <Button type="link">
                                    <ReferenceIcon name="down" />更多
</Button><template #popupRender>
                                    <Menu class="qi-more-menu">
                                      <MenuItem
                                        v-if="mode === 'OFFLINE'"
                                        key="schedule"
                                        @click="openTask(task, 'schedule')"
                                      >
                                        <ReferenceIcon
                                          name="settings"
                                        />调度周期
</MenuItem><MenuItem
                                        key="instances"
                                        @click="openTask(task, 'instances')"
                                      >
                                        <ReferenceIcon
                                          name="clock"
                                        />运行实例
</MenuItem><MenuItem
                                        v-if="can('run')"
                                        key="run"
                                        :disabled="!task.executable"
                                        @click="
                                          confirmAction(
                                            isActive(task) ? 'stop' : 'run',
                                            task,
                                          )
                                        "
                                      >
                                        <ReferenceIcon
                                          :name="
                                            isActive(task) ? 'stop' : 'play'
                                          "
                                        />{{
                                          isActive(task)
                                            ? '停止任务'
                                            : mode === 'OFFLINE'
                                              ? '执行一次'
                                              : '执行任务'
                                        }}
</MenuItem><MenuItem
                                        v-if="can('delete')"
                                        key="delete"
                                        danger
                                        :disabled="
                                          task.status === 1 || isActive(task)
                                        "
                                        @click="confirmAction('delete', task)"
                                      >
                                        <ReferenceIcon
                                          name="delete"
                                        />删除任务
</MenuItem><MenuItem
                                        v-if="can('create')"
                                        key="clone"
                                        :disabled="
                                          task.status === 1 || isActive(task)
                                        "
                                        @click="confirmAction('clone', task)"
                                      >
                                        <ReferenceIcon name="clone" />克隆任务
                                      </MenuItem>
                                    </Menu>
                                  </template>
                                </Dropdown>
                              </div>
                            </td>
                          </tr>
                        </tbody>
                      </table>
                      <Empty
                        v-if="!rows.length && !loading && !failed"
                        description="暂无数据"
                      />
                    </div>
                  </Spin>
                  <ListPagination
                    :current="page"
                    :page-size="pageSize"
                    :total="total"
                    :page-size-options="[10, 20, 50, 100]"
                    @change="changePage"
                  />
                </section>
              </main>
            </div>
          </template>
          <CategoryModal
            v-model:open="categoryOpen"
            :action="categoryAction"
            :category="currentCategory"
            :categories="categories"
            @saved="categorySaved"
          />
          <TaskForm
            v-model:open="formOpen"
            :mode="mode"
            :task="currentTask"
            :categories="categories"
            :owners="owners"
            @saved="saved"
            @configure="configureId"
          />
          <ScheduleModal
            v-model:open="scheduleOpen"
            :task="currentTask"
            :editable="can('update') && currentTask?.scheduleStatus !== 1"
            @saved="saved"
          />
          <InstancesModal v-model:open="instancesOpen" :task="currentTask" />
          <Modal
            :open="!!confirmation"
            :title="confirmation ? actionText[confirmation.action] : ''"
            wrap-class-name="qi-modal"
            :confirm-loading="busy"
            :mask-closable="!busy"
            :closable="!busy"
            @ok="perform"
            @cancel="!busy && (confirmation = undefined)"
          >
            <p v-if="confirmation">
              确定{{ actionText[confirmation.action] }}“{{
                confirmation.task.name
              }}”吗？
            </p>
            <p v-if="confirmation?.action === 'delete'" class="qi-confirm-hint">
              删除后无法恢复。
            </p>
          </Modal>
        </template>
      </div>
    </Page>
  </ConfigProvider>
  </div>
</template>
