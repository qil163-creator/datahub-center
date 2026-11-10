<script setup lang="ts">
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { JobApi as Api } from '#/api/dataforge/job';
import type { OpsInstanceApi } from '#/api/dataforge/ops';

import {
  computed,
  defineAsyncComponent,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  reactive,
  ref,
  watch,
} from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import {
  Alert,
  Button,
  ConfigProvider,
  Dropdown,
  Input,
  message,
  Modal,
  Select,
  Switch,
  Tree,
} from 'antdv-next';

import { useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  cloneJob,
  createJobCategory,
  deleteJob,
  deleteJobCategory,
  getJob,
  getJobCategories,
  getJobOwners,
  getJobPage,
  runJob,
  stopJob,
  updateJobCategory,
  updateJobSchedule,
  updateJobStatus,
} from '#/api/dataforge/job';
import { getOpsInstanceStatistics } from '#/api/dataforge/ops';
import { CronTab } from '#/components/cron-tab';
import ListPagination from '#/components/list-pagination/index.vue';
import SidebarCollapse from '#/components/sidebar-collapse/index.vue';
import TreeToolbar from '#/components/tree-toolbar/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';

import {
  collectExpandableKeys,
  isTreeExpanded,
} from '../common/tree-expansion';
import { integrationReferenceTheme } from '../integration/reference-theme';
import LogModal from '../ops/modules/log-modal.vue';
import ReferenceIcon from '../ops/modules/reference-icon.vue';
import StatusBadge from '../ops/modules/status-badge.vue';
import {
  displayDate,
  formatDuration,
  hasActiveJobRuns,
  jobCategoryTree,
  jobStrategyHelp,
  runStatusOptions,
  shouldObserveJob,
  strategyOptions,
} from './model';
import JobForm from './modules/job-form.vue';

import './style.css';

const NativeVxeButton = defineAsyncComponent(async () => {
  const { NativeVxeButton: button } = await import('#/adapter/vxe-table');
  if (!button.value) throw new Error('原生表格按钮尚未注册');
  return button.value;
});

const router = useRouter();
const { hasAccessByCodes } = useAccess();
const can = (action: string) => hasAccessByCodes([`dataforge:job:${action}`]);
const active = ref(true);
const access = useAccessStore();
const scope = computed(() => `${active.value}/${access.accessToken}/${access.refreshToken}/${access.accessCodes.join(',')}`);
onActivated(() => {
  active.value = true;
});
onDeactivated(() => {
  active.value = false;
});
let refreshTimer: ReturnType<typeof setTimeout> | undefined;
function stopRefreshTimer() {
  if (refreshTimer) clearTimeout(refreshTimer);
  refreshTimer = undefined;
}
onBeforeUnmount(stopRefreshTimer);
const request = useObjectRequest(() => scope.value, clear);
const contextRequest = useObjectRequest(() => scope.value);
const actionRequest = useObjectRequest(() => scope.value);
const rows = ref<Api.Job[]>([]);
const categories = ref<Api.Category[]>([]);
const owners = ref<Api.Owner[]>([]);
const stats = ref<OpsInstanceApi.Statistics>();
const loading = ref(false);
const failed = ref(false);
const contextFailed = ref(false);
const contextLoading = ref(false);
const categorySearch = ref('');
const categoryCollapsed = ref(false);
const selectedCategories = ref<string[]>([]);
const expandedCategories = ref<string[]>(['__root']);
const filter = reactive({
  name: '',
  status: undefined as number | undefined,
  runStatus: undefined as OpsInstanceApi.Status | undefined,
  ownerId: undefined as Api.Id | undefined,
});
const applied = reactive({
  ...filter,
  categoryId: undefined as Api.Id | undefined,
});
const page = ref(1);
  const pageSize = ref(10);
  const total = ref(0);
const expandedFilter = ref(false);
  const showBanner = ref(localStorage.getItem('dataforge-job-hide-help') !== '1');
  const showFilter = ref(true);
const moreOpenId = ref('');
const categoryMenuId = ref('');
const actionBusy = ref('');
const formJobId = ref<Api.Id>();
  const formOpen = ref(false);
const logRow = ref<OpsInstanceApi.Instance>();
const cron = ref('');
  const cronBusy = ref(false);
  const cronJob = ref<Api.Job>();
const categoryBusy = ref(false);
  const categoryOpen = ref(false);
const categoryForm = reactive({
  id: undefined as Api.Id | undefined,
  parentId: '0' as Api.Id,
  name: '',
  sort: 0,
});
const categoryError = ref('');
const tree = computed(() => [
  {
    key: '__root',
    title: '作业管理类目',
    isLeaf: false,
    children: jobCategoryTree(categories.value, categorySearch.value),
  },
]);
const allExpanded = computed(() =>
  isTreeExpanded(tree.value, expandedCategories.value),
);
const statusOptions = [
  { label: '未上线', value: 0 },
  { label: '已上线', value: 1 },
];
const runOptions = runStatusOptions;
function clear() {
  stopRefreshTimer();
  rows.value = [];
  categories.value = [];
  owners.value = [];
  stats.value = undefined;
  total.value = 0;
  formOpen.value = false;
  logRow.value = undefined;
  cronJob.value = undefined;
  categoryOpen.value = false;
  actionBusy.value = '';
  loading.value = false;
  contextLoading.value = false;
  formJobId.value = undefined;
  selectedCategories.value = [];
  expandedCategories.value = ['__root'];
  categorySearch.value = '';
  categoryMenuId.value = '';
  moreOpenId.value = '';
  page.value = 1;
  Object.assign(filter, { name: '', status: undefined, runStatus: undefined, ownerId: undefined });
  Object.assign(applied, filter, { categoryId: undefined });
}
async function load() {
  stopRefreshTimer();
  if (!active.value) return;
  const current = request.begin();
  loading.value = true;
  failed.value = false;
  try {
    const [result, statistics] = await Promise.all([
      getJobPage({ ...applied, pageNo: page.value, pageSize: pageSize.value }),
      getOpsInstanceStatistics({ kind: 'JOB' }),
    ]);
    if (!current()) return;
    rows.value = result.list;
    total.value = result.total;
    stats.value = statistics;
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) {
      loading.value = false;
      if (active.value && !failed.value && rows.value.some(shouldObserveJob))
        refreshTimer = setTimeout(() => { void load(); }, 3000);
    }
  }
}
async function context() {
  const current = contextRequest.begin();
  contextLoading.value = true;
  contextFailed.value = false;
  try {
    const [cats, people] = await Promise.all([
      getJobCategories(),
      getJobOwners(),
    ]);
    if (current()) {
      categories.value = cats;
      owners.value = people;
    }
  } catch {
    if (current()) contextFailed.value = true;
  } finally {
    if (current()) contextLoading.value = false;
  }
}
function search() {
  Object.assign(applied, filter);
  page.value = 1;
  void load();
}
function reset() {
  Object.assign(filter, {
    name: '',
    status: undefined,
    runStatus: undefined,
    ownerId: undefined,
  });
  search();
}
function selectCategory(keys: unknown[]) {
  selectedCategories.value = keys.map(String);
  applied.categoryId =
    keys[0] && keys[0] !== '__root' ? String(keys[0]) : undefined;
  page.value = 1;
  void load();
}
function edit(job?: Api.Job) {
  moreOpenId.value = '';
  formJobId.value = job?.id;
  formOpen.value = true;
}
function configure(id: Api.Id) {
  void router.push(`/dataforge/job/designer/${encodeURIComponent(String(id))}`);
}
function detail(job: Api.Job) {
  void router.push(
    `/dataforge/job/detail/${encodeURIComponent(String(job.id))}`,
  );
}
async function action(job: Api.Job, type: 'clone' | 'delete' | 'run' | 'stop') {
  if (actionBusy.value) return;
  const execute = async () => {
    const current = actionRequest.begin();
    actionBusy.value = `${job.id}:${type}`;
    try {
      const latest = await getJob(job.id);
      if (!current()) return;
      if (type === 'delete') await deleteJob(job.id, latest.revision);
      else if (type === 'clone') await cloneJob(job.id);
      else if (type === 'run')
        await runJob({
          id: job.id,
          revision: latest.revision,
          requestId: crypto.randomUUID(),
        });
      else await stopJob(job.id);
      if (current()) {
        const messages = { run: '执行请求已登记，请查看运行实例状态', stop: '停止请求已提交，排队实例将取消，运行实例等待停止确认', clone: '作业已复制', delete: '作业已删除' };
        if (type === 'run' || type === 'stop') message.info(messages[type]);
        else message.success(messages[type]);
        await load();
      }
    } finally {
      if (current()) actionBusy.value = '';
    }
  };
  if (type === 'delete' || type === 'run' || type === 'stop') {
    let content = job.name;
    if (type === 'run') content = `${job.name}。${jobStrategyHelp[job.strategy]}`;
    else if (type === 'stop') content = `${job.name}：取消未启动的排队实例，并请求停止正在执行的实例。`;
    Modal.confirm({
      title: {delete: '确认删除作业？', run: '确认执行一次？', stop: '确认停止作业？'}[type],
      content,
      okText: '确定',
      cancelText: '取消',
      onOk: execute,
    });
  } else await execute();
}
async function switchStatus(job: Api.Job) {
  if (actionBusy.value) return;
  const current = actionRequest.begin();
  actionBusy.value = `${job.id}:status`;
  try {
    const latest = await getJob(job.id);
    if (!current()) return;
    await updateJobStatus({
      id: job.id,
      revision: latest.revision,
      status: latest.status === 1 ? 0 : 1,
    });
    if (current()) await load();
  } finally {
    if (current()) actionBusy.value = '';
  }
}
async function switchSchedule(job: Api.Job) {
  if (actionBusy.value) return;
  const current = actionRequest.begin();
  actionBusy.value = `${job.id}:schedule`;
  try {
    const latest = await getJob(job.id);
    if (!current()) return;
    await updateJobSchedule({
      id: job.id,
      revision: latest.revision,
      cronExpression: latest.cronExpression ?? '',
      scheduleStatus: latest.scheduleStatus === 1 ? 0 : 1,
    });
    if (current()) await load();
  } finally {
    if (current()) actionBusy.value = '';
  }
}
async function openCron(job: Api.Job) {
  const current = actionRequest.begin();
  const latest = await getJob(job.id);
  if (current()) {
    cron.value = latest.cronExpression ?? '0 0 0 * * ?';
    cronJob.value = latest;
  }
}
async function saveCron() {
  if (!cronJob.value || cronBusy.value) return;
  const current = actionRequest.begin();
  cronBusy.value = true;
  try {
    await updateJobSchedule({
      id: cronJob.value.id,
      revision: cronJob.value.revision,
      cronExpression: cron.value,
      scheduleStatus: cronJob.value.scheduleStatus,
    });
    if (current()) {
      cronJob.value = undefined;
      message.success('调度周期已保存');
      await load();
    }
  } finally {
    if (current()) cronBusy.value = false;
  }
}
function openCategory(row?: Api.Category, parent?: Api.Id) {
  categoryMenuId.value = '';
  Object.assign(categoryForm, {
    id: row?.id,
    parentId: row?.parentId ?? parent ?? '0',
    name: row?.name ?? '',
    sort: row?.sort ?? 0,
  });
  categoryError.value = '';
  categoryOpen.value = true;
}
async function saveCategory() {
  if (categoryBusy.value) return;
  if (!categoryForm.name.trim()) {
    categoryError.value = '请输入类目名称';
    return;
  }
  const current = actionRequest.begin();
  categoryBusy.value = true;
  try {
    const data = { ...categoryForm, name: categoryForm.name.trim() };
    await (data.id === undefined ? createJobCategory(data) : updateJobCategory({ ...data, id: data.id }));
    if (current()) {
      categoryOpen.value = false;
      await context();
    }
  } finally {
    if (current()) categoryBusy.value = false;
  }
}
function removeCategory(row: Api.Category) {
  Modal.confirm({
    title: '确认删除类目？',
    content: row.name,
    okText: '删除',
    cancelText: '取消',
    onOk: async () => {
      await deleteJobCategory(row.id);
      await context();
      await load();
    },
  });
}
function categoryById(id: Api.Id) {
  return categories.value.find((row) => String(row.id) === String(id));
}
function closeBanner(permanent = false) {
  if (permanent) localStorage.setItem('dataforge-job-hide-help', '1');
  showBanner.value = false;
}
watch(categorySearch, (value) => {
  if (value.trim())
    expandedCategories.value = collectExpandableKeys(tree.value);
});
watch(
  scope,
  () => {
    if (active.value) {
      void context();
      void load();
    }
  },
  { immediate: true },
);

const [Grid, gridApi] = useVbenVxeGrid<Api.Job>({
  gridOptions: {
    columns: [
      { field: 'id', title: '编号', width: 82, sortable: true, sortType: 'string', sortBy: ({ row }: { row: Api.Job }) => String(row.id).padStart(20, '0') },
      { field: 'name', title: '任务信息', width: 380, align: 'left', slots: { default: 'info' } },
      { field: 'categoryName', title: '所属类目', width: 140, align: 'left', slots: { default: 'category' } },
      { field: 'lastRunStatus', title: '最近执行信息', width: 160, align: 'left', slots: { default: 'recent' } },
      { field: 'status', title: '运行控制', width: 165, align: 'left', slots: { default: 'control' } },
      { field: 'strategy', title: '调度与策略', width: 210, align: 'left', slots: { default: 'schedule' } },
      { field: 'ownerName', title: '责任人', width: 130, align: 'left', slots: { default: 'owner' } },
      { field: 'creatorName', title: '创建人', width: 130, align: 'left', slots: { default: 'creator' } },
      { field: 'createTime', title: '创建时间', width: 155, slots: { default: 'created' } },
      { field: 'actions', title: '操作', width: 240, fixed: 'right', slots: { default: 'actions' } },
    ],
    data: [],
    height: 'auto',
    pagerConfig: { enabled: false },
    proxyConfig: { enabled: false },
    rowConfig: { keyField: 'id', isHover: true },
    scrollbarConfig: { height: 6 },
    showOverflow: false,
    toolbarConfig: { refresh: true, refreshOptions: { queryMethod: load }, zoom: false, custom: true },
  } as VxeTableGridOptions<Api.Job>,
});
watch(loading, (value) => gridApi.setLoading(value), { immediate: true });
</script>

<template>
    <Page auto-content-height content-class="p-0">
      <div class="qj-workbench">
        <div v-if="showBanner" class="qj-banner">
          <img class="qj-banner-icon" src="/assets/qdata-job/help-info.png" alt="" />
          <div>
            <strong>作业管理</strong> —— 多任务流程的统一编排与调度
            <p>
              通过将数据集成、实时和离线开发任务串联成作业，实现跨任务的依赖控制与流程化调度。支持定时执行、状态监控与日志查看，保障数据处理链路的自动化与稳定运行。
            </p>
          </div>
          <Button @click="closeBanner(true)">不再提示</Button><Button type="primary" @click="closeBanner()">关闭</Button>
        </div>
        <div
          class="qj-layout"
          :class="{ 'is-category-collapsed': categoryCollapsed }"
        >
          <aside v-show="!categoryCollapsed" class="qj-category subtle-tree-scrollbar">
            <div class="qj-category-tools">
              <Input
                v-model:value="categorySearch"
                placeholder="请输入作业类目名称"
                allow-clear
                aria-label="搜索作业类目"
                >
<template #prefix>
<IconifyIcon class="size-4" icon="lucide:search" />
</template>
</Input><TreeToolbar
                compact
                :expanded="allExpanded"
                :loading="contextLoading"
                refresh-label="刷新作业类目"
                @toggle-expand="
                  expandedCategories = allExpanded
                    ? []
                    : collectExpandableKeys(tree)
                "
                @refresh="context"
              />
            </div>
            <Alert v-if="contextFailed" type="error" message="类目加载失败">
<template #action>
<Button type="link" @click="context">重试</Button>
</template>
</Alert>
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
<ReferenceIcon :name="node.isLeaf ? 'file' : 'folder'" />
</template>
              <template #titleRender="{ key, title }">
<Dropdown :trigger="['contextmenu']" :open="categoryMenuId === String(key)" @open-change="value=>categoryMenuId = value ? String(key) : ''">
<span :title="typeof title === 'string' ? title : undefined">{{ title }}</span><template #popupRender>
<div class="qj-popup">
                      <button
                        v-if="can('create')"
                        type="button"
                        @click="
                          openCategory(undefined, key === '__root' ? '0' : key)
                        "
                      >
                        新增子级
</button><button
                        v-if="can('update') && categoryById(key)"
                        type="button"
                        @click="openCategory(categoryById(key))"
                      >
                        修改
</button><button
                        v-if="can('delete') && categoryById(key)"
                        type="button"
                        @click="removeCategory(categoryById(key)!)"
                      >
                        删除
                      </button>
                    </div>
</template>
</Dropdown>
</template>
            </Tree>
          </aside>
          <SidebarCollapse
            :collapsed="categoryCollapsed"
            label="作业类目"
            @toggle="categoryCollapsed = !categoryCollapsed"
          />
          <main class="qj-main">
            <section class="qj-statistics">
              <button
                type="button"
                @click="
                  filter.runStatus = 'RUNNING';
                  search();
                "
              >
                <ReferenceIcon name="stat-running" /><span>运行中作业：</span><b>{{ stats?.running ?? '-' }}个</b>
</button><button
                type="button"
                @click="
                  filter.runStatus = 'FAILURE';
                  search();
                "
              >
                <ReferenceIcon name="stat-failed" /><span>今日异常：</span><b>{{ stats?.todayFailed ?? '-' }}次</b>
</button><button type="button" @click="reset">
                <ReferenceIcon name="stat-today" /><span>今日执行：</span><b>{{ stats?.todayExecuted ?? '-' }}次</b>
</button><button
                type="button"
                @click="
                  filter.runStatus = 'SUCCESS';
                  search();
                "
              >
                <ReferenceIcon name="stat-success" /><span>今日成功率：</span><b>{{ stats?.todaySuccessRate ?? '-' }}%</b>
              </button>
              <p>
                数据统计截止：{{ displayDate(stats?.statisticTime, true)
                }}<button type="button" aria-label="刷新统计" @click="load">
                  <ReferenceIcon name="refresh" />
                </button>
              </p>
            </section>
            <section v-if="showFilter" class="qj-filters">
              <form @submit.prevent="search">
                <label>作业名称<Input
                    v-model:value="filter.name"
                    placeholder="请输入作业名称"
                    allow-clear
/></label><label>作业状态<Select
                    v-model:value="filter.status"
                    :options="statusOptions"
                    placeholder="请选择作业状态"
                    allow-clear
/></label><label>执行状态<Select
                    v-model:value="filter.runStatus"
                    :options="runOptions"
                    placeholder="请选择执行状态"
                    allow-clear
/></label><label v-if="expandedFilter">责任人<Select
                    v-model:value="filter.ownerId"
                    :options="
                      owners.map((row) => ({ value: row.id, label: row.name }))
                    "
                    placeholder="请选择责任人"
                    allow-clear
                /></label>
                <div class="qj-query">
                  <Button html-type="submit" type="primary">查询</Button>
                  <Button @click="reset">重置</Button>
                  <Button
                    type="link"
                    :aria-expanded="expandedFilter"
                    @click="expandedFilter = !expandedFilter"
                  >
                    {{ expandedFilter ? '收起' : '展开' }}
                    <IconifyIcon :icon="expandedFilter ? 'lucide:chevron-up' : 'lucide:chevron-down'" />
                  </Button>
                </div>
              </form>
            </section>
            <section class="qj-table-panel">
              <Alert v-if="failed" type="error" message="作业列表加载失败">
                <template #action><Button type="link" @click="load">重试</Button></template>
              </Alert>
              <Grid class="qj-native-grid" table-title="作业列表" :table-data="rows">
                <template #toolbar-tools>
                  <Button v-if="can('create')" type="primary" @click="edit()">
                    <template #icon><IconifyIcon icon="lucide:plus" /></template>
                    新增
                  </Button>
                  <NativeVxeButton
                    circle
                    class="ml-2"
                    icon="vxe-icon-search"
                    :status="showFilter ? 'primary' : undefined"
                    :aria-pressed="showFilter"
                    aria-label="显示或隐藏搜索"
                    title="搜索"
                    @click="showFilter = !showFilter"
                  />
                </template>
                <template #info="{ row: record }">
                    <div class="qj-info">
                      <div class="qj-info-title">
                        <IconifyIcon
                          icon="lucide:workflow"
                          class="qj-info-icon"
                          aria-hidden="true"
                        />
                        <button
                          type="button"
                          :title="record.name"
                          @click="detail(record)"
                        >
                          {{ record.name }}
                        </button>
                      </div>
                      <p :title="record.description">
                        {{ record.description || '-' }}
                      </p>
                    </div>
                </template>
                <template #category="{ row: record }">{{ record.categoryName || '-' }}</template>
                <template #recent="{ row: record }">
                    <div>
                      <StatusBadge
                        :status="record.lastRunStatus || 'IDLE'"
                        :queue-state="record.latestRecord?.queueState"
                        :queue-reason="record.latestRecord?.queueReason"
                      /><small>{{
                          record.lastRunTime
                            ? displayDate(record.lastRunTime)
                            : '-'
                        }}
                        {{ formatDuration(record.lastRunDuration) }}</small><small v-if="record.activeRunCount !== undefined || record.queuedRunCount !== undefined" class="qj-run-counts">活动实例：{{ record.activeRunCount ?? 0 }}，排队：{{ record.queuedRunCount ?? 0 }}</small>
                    </div>
                </template>
                <template #control="{ row: record }">
                    <div class="qj-controls">
                      <label>任务状态:<Switch
                          :checked="record.status === 1"
                          size="small"
                          :disabled="!can('update') || !!actionBusy"
                          @change="switchStatus(record)"
/></label><label>调度状态:<Switch
                          :checked="record.scheduleStatus === 1"
                          size="small"
                          :disabled="!can('update') || !!actionBusy"
                          @change="switchSchedule(record)"
                      /></label>
                    </div>
                </template>
                <template #schedule="{ row: record }">
                    <div>
                      <span class="qj-strategy">{{
                        strategyOptions.find(
                          (option) => option.value === record.strategy,
                        )?.label || record.strategy
                      }}</span><small class="qj-cron">{{
                        record.cronExpression || '-'
                      }}</small><small v-if="record.scheduleStatus === 1 && record.nextRunTime">下次执行：{{ displayDate(record.nextRunTime) }}</small>
                    </div>
                </template>
                <template #owner="{ row: record }">
                    <div>
                      {{ record.ownerName || '-'
                      }}<small>{{ record.ownerPhone || '-' }}</small>
                    </div>
                </template>
                <template #creator="{ row: record }">
                    <div>
                      {{ record.creatorName || '-'
                      }}<small>{{ record.creatorPhone || '' }}</small>
                    </div>
                </template>
                <template #created="{ row: record }">{{ displayDate(record.createTime) }}</template>
                <template #actions="{ row: record }">
                    <div class="qj-actions">
                      <button
                        v-if="can('update')"
                        type="button"
                        @click="configure(record.id)"
                      >
                        <ReferenceIcon name="edit" />配置
</button><button type="button" @click="detail(record)">
                        <ReferenceIcon name="eye" />详情
</button><Dropdown :trigger="['click']" :open="moreOpenId === String(record.id)" @open-change="value=>moreOpenId = value ? String(record.id) : ''">
<button type="button">
                          <IconifyIcon icon="lucide:ellipsis-vertical" />更多
</button><template #popupRender>
<div class="qj-popup" @click="moreOpenId = ''">
                            <button
                              v-if="can('update')"
                              type="button"
                              @click="edit(record)"
                            >
                              修改
</button><button
                              v-if="can('create')"
                              type="button"
                              @click="action(record, 'clone')"
                            >
                              复制
</button><button
                              v-if="can('update')"
                              type="button"
                              @click="openCron(record)"
                            >
                              调度周期
</button><button
                              type="button"
                              @click="router.push('/develop/operations/job')"
                            >
                              运行实例
</button><button
                              v-if="record.latestRecord"
                              type="button"
                              @click="logRow = record.latestRecord"
                            >
                              查看日志
</button><button v-if="can('run')" type="button" @click="action(record, 'run')">执行一次</button><button
                              v-if="can('run') && hasActiveJobRuns(record)"
                              type="button"
                              @click="action(record, 'stop')"
                            >
                              停止运行
</button><button
                              v-if="can('delete')"
                              type="button"
                              @click="action(record, 'delete')"
                            >
                              删除
                            </button>
                          </div>
</template>
</Dropdown>
                    </div>
                </template>
                <template #pager>
              <ListPagination
                :current="page"
                :page-size="pageSize"
                :total="total"
                @change="
                  (next, size) => {
                    page = size === pageSize ? next : 1;
                    pageSize = size;
                    load();
                  }
                "
              />
                </template>
              </Grid>
            </section>
          </main>
        </div>
        <ConfigProvider :theme="integrationReferenceTheme">
          <JobForm
            v-model:open="formOpen"
            :job-id="formJobId"
            @success="load"
            @configure="configure"
          />
          <LogModal :open="!!logRow" :row="logRow" @close="logRow = undefined" />
          <Modal
            :open="!!cronJob"
            title="调度周期"
            :confirm-loading="cronBusy"
            ok-text="保存"
            cancel-text="关闭"
            :mask-closable="false"
            @cancel="cronJob = undefined"
            @ok="saveCron"
            >
  <div class="qj-cron-dialog">
              <label>调度周期</label><CronTab v-model="cron" week-mode="quartz" /><p class="qj-schedule-hint">周期调度与人工执行使用相同的作业策略，运行状态及排队原因可在运行实例中查看。</p>
  </div>
  </Modal>
          <Modal
            v-model:open="categoryOpen"
            :title="categoryForm.id ? '修改类目' : '新增类目'"
            :confirm-loading="categoryBusy"
            ok-text="保存"
            cancel-text="取消"
            @ok="saveCategory"
            >
  <div class="qj-category-dialog">
              <label>类目名称<Input
                  v-model:value="categoryForm.name"
                  :maxlength="64"
                  aria-label="类目名称"
  /></label><Alert
                v-if="categoryError"
                type="error"
                :message="categoryError"
              />
  </div>
  </Modal>
        </ConfigProvider>
      </div>
    </Page>
</template>
