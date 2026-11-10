<script setup lang="ts">
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { OpsInstanceApi as Api } from '#/api/dataforge/ops';

import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  reactive,
  ref,
  watch,
} from 'vue';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { downloadFileFromBlobPart, formatDate } from '@vben/utils';

import {
  Button,
  ConfigProvider,
  DateRangePicker,
  Input,
  Select,
  Tree,
} from 'antdv-next';

import { NativeVxeButton, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  downloadOpsInstanceLog,
  getOpsInstance,
  getOpsInstanceCategories,
  getOpsInstancePage,
  getOpsInstanceStatistics,
} from '#/api/dataforge/ops';
import ListPagination from '#/components/list-pagination/index.vue';
import SidebarCollapse from '#/components/sidebar-collapse/index.vue';
import TreeToolbar from '#/components/tree-toolbar/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';

import { collectExpandableKeys, isTreeExpanded } from '../common/tree-expansion';
import { integrationReferenceTheme } from '../integration/reference-theme';
import {
  buildCategoryTree,
  childLogRecord,
  displayCreated,
  displayDate,
  executionTypeLabel,
  formatDuration,
  isActiveOpsInstance,
  kindLabels,
  requestIdentity,
  rowIdentity,
  statusOptions,
  taskTypeOptions,
} from './model';
import InstanceDetail from './modules/instance-detail.vue';
import LogModal from './modules/log-modal.vue';
import ReferenceIcon from './modules/reference-icon.vue';
import StatusBadge from './modules/status-badge.vue';

import './style.css';

const props = defineProps<{ jobId?: string; kind: Api.Kind }>();
const emit = defineEmits<{ clearJobFilter: [] }>();
const labels = computed(() => kindLabels[props.kind]);
const compactColumns = computed(() => props.kind === 'JOB');
const expandedRows = ref<string[]>([]);

const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const canDownload = computed(() =>
  hasAccessByCodes(['dataforge:ops-instance:export']),
);
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
    props.kind,
    props.jobId,
    access.accessToken,
    access.refreshToken,
    access.accessCodes,
    user.userInfo?.userId,
  ]),
);
const rows = ref<Api.Instance[]>([]);
const categories = ref<Api.Category[]>([]);
const statistics = ref<Api.Statistics>();
const page = ref(1);
const pageSize = ref(10);
const total = ref(0);
const loading = ref(false);
const failed = ref(false);
const contextFailed = ref(false);
const contextLoading = ref(false);
let categoriesLoaded = false;
const detailLoading = ref(false);
const detail = ref<Api.Instance>();
const logRow = ref<Api.Instance>();
const downloading = ref('');
const categorySearch = ref('');
const categoryCollapsed = ref(false);
const selectedCategories = ref<string[]>([]);
const expandedCategories = ref<string[]>(['__root']);
let expandedBeforeSearch: string[] | undefined;
const showFilter = ref(true);
const expandedFilter = ref(false);
const executionDates = ref<[string, string]>();
const filter = reactive({
  name: '',
  taskType: undefined as string | undefined,
  status: undefined as Api.Status | undefined,
});
const applied = reactive({
  ...filter,
  categoryId: undefined as string | undefined,
  startDate: undefined as string | undefined,
  endDate: undefined as string | undefined,
});
const sortField = ref<'createTime' | 'endTime' | 'id' | 'startTime'>('startTime');
const sortOrder = ref<'asc' | 'desc'>('desc');
let listRefreshTimer: ReturnType<typeof setTimeout> | undefined;
function stopListRefresh() {
  if (listRefreshTimer) clearTimeout(listRefreshTimer);
  listRefreshTimer = undefined;
}
onBeforeUnmount(stopListRefresh);
const request = useObjectRequest(() => scope.value, clear);
const contextRequest = useObjectRequest(() => scope.value);
const detailRequest = useObjectRequest(() => scope.value);
const downloadRequest = useObjectRequest(() => scope.value);
const tree = computed(() =>
  buildCategoryTree(categories.value, props.kind, categorySearch.value),
);
const allExpanded = computed(() => isTreeExpanded(tree.value, expandedCategories.value));
function toggleExpandAll() {
  expandedCategories.value = allExpanded.value
    ? []
    : collectExpandableKeys(tree.value);
}
const statuses = computed(() =>
  statusOptions.filter((option) =>
    props.kind === 'INTEGRATION'
      ? !['STOPPED', 'WAITING'].includes(option.value)
      : option.value !== 'IDLE',
  ),
);
function toggleRow(row: Api.Instance) {
  const key = rowIdentity(row);
  expandedRows.value = expandedRows.value.includes(key)
    ? expandedRows.value.filter((item) => item !== key)
    : [...expandedRows.value, key];
}
const rowExpanded = (row: Api.Instance) => expandedRows.value.includes(rowIdentity(row));
interface DisplayRow {
  depth: number;
  key: string;
  parent?: Api.Instance;
  record: Api.Instance;
}
const displayedRows = computed(() => {
  const result: DisplayRow[] = [];
  function append(items: Api.Instance[], parent?: Api.Instance, depth = 0, prefix = '') {
    for (const row of items) {
      const key = `${prefix}/${rowIdentity(row)}`;
      result.push({ record: row, parent, depth, key });
      if (props.kind === 'JOB' && rowExpanded(row)) append(row.children ?? [], parent ?? row, depth + 1, key);
    }
  }
  append(rows.value);
  return result;
});
function clear() {
  stopListRefresh();
  rows.value = [];
  expandedRows.value = [];
  categories.value = [];
  statistics.value = undefined;
  detail.value = undefined;
  logRow.value = undefined;
  total.value = 0;
  loading.value = false;
  detailLoading.value = false;
  downloading.value = '';
  failed.value = false;
  contextFailed.value = false;
  contextLoading.value = false;
  categoriesLoaded = false;
  page.value = 1;
  selectedCategories.value = [];
  applied.categoryId = undefined;
  expandedCategories.value = ['__root'];
  expandedBeforeSearch = undefined;
}
async function load() {
  stopListRefresh();
  if (!active.value) return;
  const current = request.begin();
  loading.value = true;
  failed.value = false;
  try {
    const [result, stats] = await Promise.all([
      getOpsInstancePage({
        kind: props.kind,
        taskId: props.kind === 'JOB' ? props.jobId : undefined,
        pageNo: page.value,
        pageSize: pageSize.value,
        ...applied,
        sortField: sortField.value,
        sortOrder: sortOrder.value,
      }),
      props.kind === 'FULL_SYNC' ? Promise.resolve(undefined) : getOpsInstanceStatistics({
        kind: props.kind,
        categoryId: applied.categoryId,
        taskType: applied.taskType,
      }),
    ]);
    if (!current()) return;
    rows.value = result.list;
    total.value = result.total;
    statistics.value = stats;
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) {
      loading.value = false;
      if (active.value && !failed.value && rows.value.some((row) => row.origin !== 'SOURCE' && isActiveOpsInstance(row)))
        listRefreshTimer = setTimeout(() => { void load(); }, 3000);
    }
  }
}
async function loadContext() {
  if (!active.value || props.kind === 'FULL_SYNC') return;
  const current = contextRequest.begin();
  contextFailed.value = false;
  contextLoading.value = true;
  try {
    const result = await getOpsInstanceCategories(props.kind);
    if (!current()) return;
    const expand = allExpanded.value;
    categories.value = result;
    if (expand || categorySearch.value.trim()) {
      expandedCategories.value = collectExpandableKeys(tree.value);
    } else if (!categoriesLoaded) {
      const root = buildCategoryTree(result, props.kind)[0];
      expandedCategories.value = [
        ...(root ? [root.key] : []),
        ...(root?.children ?? [])
          .slice(0, props.kind === 'INTEGRATION' ? 3 : 2)
          .map((node) => node.key),
      ];
    }
    categoriesLoaded = true;
  } catch {
    if (current()) contextFailed.value = true;
  } finally {
    if (current()) contextLoading.value = false;
  }
}
function search() {
  Object.assign(applied, filter, {
    startDate: executionDates.value?.[0],
    endDate: executionDates.value?.[1],
  });
  page.value = 1;
  void load();
}
function reset() {
  Object.assign(filter, { name: '', taskType: undefined, status: undefined });
  executionDates.value = undefined;
  search();
}
function changeDates(value: unknown) {
  if (!Array.isArray(value)) {
    executionDates.value = undefined;
    return;
  }
  const [start, end] = value;
  executionDates.value =
    typeof start === 'string' && typeof end === 'string'
      ? [start, end]
      : undefined;
}
function selectCategory(keys: (number | string)[]) {
  selectedCategories.value = keys.map(String);
  const key = selectedCategories.value[0];
  const first = tree.value[0];
  applied.categoryId =
    key && key !== '__root' && key !== first?.key ? key : undefined;
  page.value = 1;
  void load();
}
function selectStatistic(status?: Api.Status, today = false) {
  filter.status = status;
  if (today) {
    const date =
      statistics.value?.statisticTime?.slice(0, 10) ||
      formatDate(Date.now(), 'YYYY-MM-DD');
    executionDates.value = [date, date];
  } else executionDates.value = undefined;
  search();
}
function sort(field: 'createTime' | 'endTime' | 'id' | 'startTime') {
  sortOrder.value =
    sortField.value === field && sortOrder.value === 'desc' ? 'asc' : 'desc';
  sortField.value = field;
  page.value = 1;
  void load();
}
async function inspect(row: Api.Instance) {
  if (!['FULL_SYNC', 'INTEGRATION'].includes(props.kind)) {
    logRow.value = row;
    return;
  }
  const current = detailRequest.begin();
  detailLoading.value = true;
  try {
    const result = await getOpsInstance(requestIdentity(row));
    if (current()) detail.value = result;
  } finally {
    if (current()) detailLoading.value = false;
  }
}
let refreshingDetail = false;
async function refreshDetail() {
  if (!detail.value || refreshingDetail) return;
  const identity = requestIdentity(detail.value);
  const current = detailRequest.begin();
  refreshingDetail = true;
  try {
    const result = await getOpsInstance(identity);
    if (
      current() &&
      detail.value &&
      rowIdentity(detail.value) === rowIdentity(identity)
    )
      detail.value = result;
  } finally {
    refreshingDetail = false;
  }
}
function closeDetail() {
  detailRequest.begin();
  detail.value = undefined;
}
async function download(row: Api.Instance) {
  if (!canDownload.value || downloading.value) return;
  const current = downloadRequest.begin();
  downloading.value = rowIdentity(row);
  try {
    const blob = await downloadOpsInstanceLog(requestIdentity(row));
    if (current())
      downloadFileFromBlobPart({
        fileName: `${row.instanceName || row.id}.log`,
        source: blob,
      });
  } finally {
    if (current()) downloading.value = '';
  }
}
function changePage(nextPage: number, nextSize: number) {
  page.value = nextSize === pageSize.value ? nextPage : 1;
  pageSize.value = nextSize;
  void load();
}
watch(categorySearch, (value, previous) => {
  if (value.trim()) {
    if (!previous.trim()) expandedBeforeSearch = [...expandedCategories.value];
    expandedCategories.value = collectExpandableKeys(tree.value);
  } else if (expandedBeforeSearch) {
    expandedCategories.value = expandedBeforeSearch;
    expandedBeforeSearch = undefined;
  }
});
watch(
  scope,
  () => {
    if (active.value) {
      void loadContext();
      void load();
    }
  },
  { immediate: true },
);
const date = displayDate;
const gridColumns = computed<VxeTableGridOptions<DisplayRow>['columns']>(() => [
  { field: 'id', title: '编号', width: 110, slots: { header: 'idHeader', default: 'id' } },
  { field: 'instanceName', title: `${labels.value.instance}名称`, minWidth: 300, align: 'left', slots: { default: 'instanceName' } },
  ...(props.kind === 'JOB' ? [{ field: 'taskName', title: '任务名称', minWidth: 210, slots: { default: 'taskName' } }] : []),
  { field: 'executionType', title: '执行类型', width: 105, slots: { default: 'executionType' } },
  { field: 'status', title: '执行状态', width: 110, slots: { default: 'status' } },
  ...(['FULL_SYNC', 'JOB'].includes(props.kind)
    ? [
        { field: 'startTime', title: '开始时间', width: 165, slots: { header: 'startHeader', default: 'startTime' } },
        { field: 'endTime', title: '结束时间', width: 165, slots: { header: 'endHeader', default: 'endTime' } },
      ]
    : [{ field: 'time', title: props.kind === 'INTEGRATION' ? '最近执行时间' : '执行时间', minWidth: 245, slots: { header: 'timeHeader', default: 'time' } }]),
  ...(props.kind !== 'FULL_SYNC' ? [{ field: 'duration', title: '运行时长', width: 120, slots: { default: 'duration' } }] : []),
  ...(props.kind === 'JOB' ? [{ field: 'runCount', title: '运行次数', width: 100, slots: { default: 'runCount' } }] : []),
  ...(props.kind !== 'JOB' ? [{ field: 'ownerName', title: '责任人', width: 110, slots: { default: 'owner' } }] : []),
  { field: 'creatorName', title: '创建人', width: 110, slots: { default: 'creator' } },
  { field: 'createTime', title: '创建时间', width: 165, slots: { header: 'createdHeader', default: 'created' } },
  { field: 'actions', title: '操作', width: 240, fixed: 'right', slots: { default: 'actions' } },
]);
const [Grid, gridApi] = useVbenVxeGrid<DisplayRow>({
  gridOptions: {
    columns: gridColumns.value,
    data: [],
    height: 'auto',
    loading: false,
    pagerConfig: { enabled: false },
    proxyConfig: { enabled: false },
    rowConfig: { keyField: 'key', isHover: true },
    rowClassName: ({ row }: { row: DisplayRow }) => row.depth ? 'qo-child-row' : '',
    scrollbarConfig: { height: 6 },
    showOverflow: false,
    toolbarConfig: { refresh: true, refreshOptions: { queryMethod: () => load() }, custom: true, zoom: false },
  } as VxeTableGridOptions<DisplayRow>,
});
watch(gridColumns, (value) => gridApi.setGridOptions({ columns: value }), { immediate: true });
watch([loading, detailLoading], ([listLoading, itemLoading]) => gridApi.setLoading(listLoading || itemLoading), { immediate: true });
</script>

<template>
    <Page :auto-content-height="!detail" content-class="p-0">
    <div class="qo-workbench" :class="{ 'is-list': !detail, 'is-compact-instance': compactColumns, 'is-full-sync': kind === 'FULL_SYNC' }">
      <ConfigProvider v-if="detail" :theme="integrationReferenceTheme">
        <InstanceDetail
          :record="detail"
          @back="closeDetail"
          @refresh="refreshDetail"
        />
      </ConfigProvider>
      <div v-else class="qo-layout" :class="{ 'is-category-collapsed': categoryCollapsed }">
        <aside v-if="kind !== 'FULL_SYNC'" v-show="!categoryCollapsed" class="qo-category-panel">
          <div class="qo-category-tools flex shrink-0 items-center gap-2">
          <Input
            v-model:value="categorySearch"
            class="min-w-0 flex-1"
            :placeholder="`请输入${labels.category}类目名称`"
            allow-clear
            aria-label="搜索实例类目"
            >
<template #prefix><IconifyIcon class="size-4" icon="lucide:search" /></template>
</Input>
            <TreeToolbar
              compact
              :expanded="allExpanded"
              :loading="contextLoading"
              refresh-label="刷新类目"
              @toggle-expand="toggleExpandAll"
              @refresh="loadContext"
            />
          </div>

          <div class="qo-category-scroll subtle-tree-scrollbar">
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
              <template #titleRender="{ title }">
                <span
                  class="qo-category-title"
                  :title="typeof title === 'string' ? title : undefined"
                >{{ title }}</span>
              </template>
            </Tree>
          </div>
        </aside>
        <SidebarCollapse v-if="kind !== 'FULL_SYNC'" :collapsed="categoryCollapsed" label="实例类目" @toggle="categoryCollapsed = !categoryCollapsed" />
        <main class="qo-list-panel">
          <section v-if="kind !== 'FULL_SYNC'" class="qo-statistics" aria-label="实例统计">
            <span v-if="kind === 'JOB' && jobId">统计范围：当前类目的全部作业</span>
            <button type="button" @click="selectStatistic('RUNNING')">
              <ReferenceIcon name="stat-running" /><span>运行中任务：</span><b>{{ statistics?.running ?? '-' }}次</b>
            </button>
            <button type="button" @click="selectStatistic('FAILURE', true)">
              <ReferenceIcon name="stat-failed" /><span>今日异常：</span><b>{{ statistics?.todayFailed ?? '-' }}次</b>
            </button>
            <button type="button" @click="selectStatistic(undefined, true)">
              <ReferenceIcon name="stat-today" /><span>今日执行：</span><b>{{ statistics?.todayExecuted ?? '-' }}次</b>
            </button>
            <button type="button" @click="selectStatistic('SUCCESS', true)">
              <ReferenceIcon name="stat-success" /><span>今日成功率：</span><b>{{ statistics?.todaySuccessRate ?? '-' }}%</b>
            </button>
            <p>
              数据统计截止：{{ date(statistics?.statisticTime, true)
              }}<button type="button" aria-label="刷新统计" @click="load">
                <ReferenceIcon name="refresh" />
              </button>
            </p>
          </section>
          <section v-if="showFilter" class="qo-filters">
            <form @submit.prevent="search">
              <div class="qo-filter-item">
                <label for="qo-instance-name">{{ labels.instance }}名称</label><Input
                  id="qo-instance-name"
                  v-model:value="filter.name"
                  :placeholder="`请输入${labels.instance}名称`"
                  allow-clear
                />
              </div>
              <div v-if="kind === 'DEVELOPMENT'" class="qo-filter-item">
                <label>任务类型</label><Select
                  v-model:value="filter.taskType"
                  :options="taskTypeOptions"
                  placeholder="请选择任务类型"
                  allow-clear
                  aria-label="任务类型"
                />
              </div>
              <div class="qo-filter-item">
                <label>执行状态</label><Select
                  v-model:value="filter.status"
                  :options="statuses"
                  placeholder="请选择执行状态"
                  allow-clear
                  aria-label="执行状态"
                />
              </div>
              <div
                v-if="kind !== 'DEVELOPMENT'"
                class="qo-filter-item qo-filter-time"
              >
                <label>执行时间</label><DateRangePicker
                  :value="executionDates"
                  value-format="YYYY-MM-DD"
                  :placeholder="['开始日期', '结束日期']"
                  aria-label="执行时间"
                  @change="changeDates"
                />
              </div>
              <div class="qo-query-buttons">
                <Button html-type="submit" type="primary">查询</Button>
                <Button @click="reset">重置</Button>
                <Button
                  v-if="kind === 'DEVELOPMENT'"
                  type="link"
                  :aria-expanded="expandedFilter"
                  @click="expandedFilter = !expandedFilter"
                >
                  {{ expandedFilter ? '收起' : '展开' }}
                  <IconifyIcon :icon="expandedFilter ? 'lucide:chevron-up' : 'lucide:chevron-down'" />
                </Button>
              </div>
              <div
                v-if="kind === 'DEVELOPMENT' && expandedFilter"
                class="qo-filter-item qo-expanded-time"
              >
                <label>执行时间</label><DateRangePicker
                  :value="executionDates"
                  value-format="YYYY-MM-DD"
                  :placeholder="['开始日期', '结束日期']"
                  aria-label="开发实例执行时间"
                  @change="changeDates"
                />
              </div>
            </form>
          </section>
          <section class="qo-table-panel">

            <Grid class="qo-native-grid" :table-title="`${labels.title}列表`" :table-data="displayedRows">
              <template #toolbar-tools>
                <button v-if="kind === 'JOB' && jobId" type="button" class="ml-2" @click="emit('clearJobFilter')">
                  作业 #{{ jobId }} ×
                </button>
                <component
                  :is="NativeVxeButton"
                  class="ml-2"
                  circle
                  icon="vxe-icon-search"
                  :status="showFilter ? 'primary' : undefined"
                  title="搜索"
                  aria-label="显示或隐藏搜索"
                  :aria-pressed="showFilter"
                  @click="showFilter = !showFilter"
                />
              </template>
              <template #idHeader><button type="button" class="qo-sort" aria-label="按编号排序" @click="sort('id')">编号<ReferenceIcon name="sort" /></button></template>
              <template #timeHeader><button type="button" class="qo-sort" aria-label="按执行时间排序" @click="sort('startTime')">{{ kind === 'INTEGRATION' ? '最近执行时间' : '执行时间' }}<ReferenceIcon name="sort" /></button></template>
              <template #startHeader><button type="button" class="qo-sort" aria-label="按开始时间排序" @click="sort('startTime')">开始时间<ReferenceIcon name="sort" /></button></template>
              <template #endHeader><button type="button" class="qo-sort" aria-label="按结束时间排序" @click="sort('endTime')">结束时间<ReferenceIcon name="sort" /></button></template>
              <template #createdHeader><button type="button" class="qo-sort" aria-label="按创建时间排序" @click="sort('createTime')">创建时间<ReferenceIcon name="sort" /></button></template>
              <template #id="{ row: item }">
                <span class="qo-instance-id" :title="String(item.record.id)" :style="item.depth ? { paddingLeft: `${item.depth * 16}px` } : undefined">
                  <button v-if="kind === 'JOB' && item.record.children?.length" type="button" class="qo-row-toggle" :aria-expanded="rowExpanded(item.record)" :aria-label="`${rowExpanded(item.record) ? '收起' : '展开'}${item.record.instanceName}`" @click="toggleRow(item.record)"><ReferenceIcon name="row-expand" :class="{ expanded: rowExpanded(item.record) }" /></button>{{ item.record.id }}
                </span>
              </template>
              <template #instanceName="{ row: item }"><span class="qo-instance-name" :title="item.record.instanceName">{{ item.record.instanceName }}</span></template>
              <template #taskName="{ row: item }"><span class="qo-job-task-name">{{ item.record.taskName }}</span></template>
              <template #executionType="{ row: item }"><span class="qo-execution-type">{{ executionTypeLabel(item.record.executionType) }}</span></template>
              <template #status="{ row: item }">
                <button v-if="kind === 'JOB'" type="button" class="qo-status-action" title="点击查看日志" @click="inspect(item.parent ? childLogRecord(item.parent, item.record) : item.record)"><StatusBadge :status="item.record.status" :queue-state="item.record.queueState" :queue-reason="item.record.queueReason" /></button>
                <StatusBadge v-else :status="item.record.status" :queue-state="item.record.queueState" :queue-reason="item.record.queueReason" />
              </template>
              <template #time="{ row: item }"><span class="qo-time-cell">{{ date(item.record.startTime, true) }} - {{ item.record.endTime ? date(item.record.endTime, true) : ' - ' }}</span></template>
              <template #startTime="{ row: item }">{{ date(item.record.startTime) }}</template>
              <template #endTime="{ row: item }">{{ date(item.record.endTime) }}</template>
              <template #duration="{ row: item }">{{ formatDuration(item.record.duration) }}</template>
              <template #runCount="{ row: item }">{{ item.record.runCount ?? '-' }}</template>
              <template #owner="{ row: item }">{{ item.record.ownerName || '-' }}</template>
              <template #creator="{ row: item }">{{ item.record.creatorName || '-' }}</template>
              <template #created="{ row: item }"><span class="qo-created-cell">{{ displayCreated(item.record.createTime, item.record.origin) }}</span></template>
              <template #actions="{ row: item }">
                <div class="qo-actions">
                  <Button type="link" @click="inspect(item.parent ? childLogRecord(item.parent, item.record) : item.record)"><ReferenceIcon name="eye" />{{ ['FULL_SYNC', 'INTEGRATION'].includes(kind) ? '详情' : '查看日志' }}</Button>
                  <Button v-if="canDownload" type="link" class="qo-download-link" :disabled="!!downloading" @click="download(item.parent ? childLogRecord(item.parent, item.record) : item.record)"><ReferenceIcon name="download" />{{ downloading === rowIdentity(item.record) ? '下载中' : '下载日志' }}</Button>
                </div>
              </template>
              <template #pager><ListPagination :current="page" :page-size="pageSize" :total="total" :page-size-options="[10, 20, 50, 100]" @change="changePage" /></template>
            </Grid>
          </section>
        </main>
      </div>
      <ConfigProvider :theme="integrationReferenceTheme">
        <LogModal :row="logRow" :open="!!logRow" @close="logRow = undefined" />
      </ConfigProvider>
    </div>
    </Page>
</template>
