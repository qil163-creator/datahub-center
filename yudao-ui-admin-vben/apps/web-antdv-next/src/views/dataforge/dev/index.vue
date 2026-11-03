<script lang="ts" setup>
import type { PageResult } from '@vben/request';

import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { DataforgeDevApi } from '#/api/dataforge/dev';

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
import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';
import { formatDate, formatDateTime, formatPast } from '@vben/utils';

import {
  Button,
  DateRangePicker,
  Dropdown,
  Input,
  Menu,
  message,
  Modal,
  Select,
  Switch,
  Tag,
  Tooltip,
  Tree,
} from 'antdv-next';
import dayjs from 'dayjs';

import { ACTION_ICON, useVbenVxeGrid } from '#/adapter/vxe-table';
import { getDatabaseTypeSimpleList } from '#/api/dataforge/databasetype';
import {
  deleteDevDir,
  deleteDevTask,
  getDevDirList,
  getDevProgramType,
  getDevTaskEngine,
  getDevTaskPage,
  getDevTaskStatistics,
  isNativeProgramNotReady,
  isNativeProgramTask,
  runDevTask,
  updateDevTaskSchedule,
  updateDevTaskStatus,
} from '#/api/dataforge/dev';
import { getSimpleUserList } from '#/api/system/user';
import TreeToolbar from '#/components/tree-toolbar/index.vue';
import { getRangePickerDefaultProps } from '#/utils';

import {
  collectExpandableKeys,
  isTreeExpanded,
} from '../common/tree-expansion';
import { useRunCommands } from '../execution/use-run-commands';
import {
  DEV_RECORD_STATUS_OPTIONS,
  DEV_SCHEDULE_SYNC_OPTIONS,
  formatDuration,
  optionColor,
  optionLabel,
} from './data';
import {
  buildDirectoryTree,
  cronSummary,
  resolveDirectoryName,
  resolveUserContact,
  resolveUserName,
  TASK_STATUS_OPTIONS,
} from './list-model';
import DirForm from './modules/dir-form.vue';
import InstanceModal from './modules/instance-modal.vue';
import ScheduleForm from './modules/schedule-form.vue';
import TaskInfo from './modules/task-info.vue';
import TaskTypeIcon from './modules/task-type-icon.vue';
import { DEV_REFERENCE_ASSETS } from './reference-assets';
import { normalizeDevDatabaseType } from './task-icon-model';
import Workbench from './workbench.vue';

defineOptions({ name: 'DataforgeDevWorkbench' });
const props = defineProps<{ workloadMode?: DataforgeDevApi.WorkloadMode }>();
const isRealtime = computed(() => props.workloadMode === 'STREAMING');
const showFilters = ref(true);
const router = useRouter();
const accessStore = useAccessStore();
const { hasAccessByCodes } = useAccess();
const canSave = computed(() => hasAccessByCodes(['dataforge:dev-task:save']));
const canDelete = computed(() =>
  hasAccessByCodes(['dataforge:dev-task:delete']),
);
const canChangeStatus = canSave;
const canRun = computed(() => hasAccessByCodes(['dataforge:dev-task:run']));
function hasNativeAccess(task: DataforgeDevApi.Task) {
  return (
    !isNativeProgramTask(task) ||
    hasAccessByCodes(['dataforge:dev-task:execute-native'])
  );
}
const canSaveDir = canSave;
const canDeleteDir = canDelete;
const dirForm = ref<InstanceType<typeof DirForm>>();
const taskInfo = ref<InstanceType<typeof TaskInfo>>();
const scheduleForm = ref<InstanceType<typeof ScheduleForm>>();
const instanceModal = ref<InstanceType<typeof InstanceModal>>();
const workspaceTaskId = ref<DataforgeDevApi.Id>();
const dirs = ref<DataforgeDevApi.Dir[]>([]);
const dirKeyword = ref('');
const directoryCollapsed = ref(false);
const expandedKeys = ref<string[]>(['root']);
const selectedKeys = ref<string[]>(['root']);
const dirId = ref<DataforgeDevApi.Id>();
const dirTree = computed(() => [
  {
    key: 'root',
    title: '数据开发类目',
    children: buildDirectoryTree(dirs.value, dirKeyword.value),
  },
]);
const treeLoading = ref(false);
const allExpanded = computed(() =>
  isTreeExpanded(dirTree.value, expandedKeys.value),
);
function toggleExpandAll() {
  expandedKeys.value = allExpanded.value
    ? []
    : collectExpandableKeys(dirTree.value);
}
const expandedSearch = ref(false);
const search = reactive<{
  dbType?: string;
  executionStartTime?: [string, string];
  executionStatus?: string;
  name?: string;
  status?: number;
}>({});
const databaseTypeOptions = ref<{ label: string; value: string }[]>([]);
const databaseIconPaths = ref<Record<string, string>>({});
const statistics = ref<DataforgeDevApi.Statistics>();
const statsLoading = ref(false);
const users = ref<{ id: string; mobile?: string; name: string }[]>([]);
const pendingRows = ref(new Set<string>());
let generation = 0;
let treeSeq = 0;
let statsSeq = 0;
let listSeq = 0;
let latestPageRequest: Promise<PageResult<DataforgeDevApi.Task>> | undefined;
let alive = true;
const commands = useRunCommands();
const userName = (id?: DataforgeDevApi.Id | null) =>
  resolveUserName(id, users.value);
const userContact = (id?: DataforgeDevApi.Id | null) =>
  resolveUserContact(id, users.value);
const dirName = (id?: DataforgeDevApi.Id) =>
  resolveDirectoryName(id, dirs.value);
function showDetail(id?: DataforgeDevApi.Id) {
  if (id === undefined) return;
  void router.push({
    name: 'DataforgeDevTaskDetail',
    params: { id: String(id) },
    query: props.workloadMode
      ? { workloadMode: props.workloadMode }
      : undefined,
  });
}
function taskDescription(row: DataforgeDevApi.Task) {
  if (row.taskType === 'PROCEDURE')
    return `${row.dbType || ''} 存储过程`.trim();
  if (row.taskType === 'SHELL') return 'Shell 脚本';
  const taskEngine = getDevTaskEngine(row.taskType) || row.engine;
  if (taskEngine) {
    const engine = taskEngine === 'FLINK' ? 'Flink' : 'Spark';
    return `${engine} ${row.programType || getDevProgramType(row.taskType)}`;
  }
  return `${row.dbType || ''} ${row.programType || row.taskType || 'SQL'}`.trim();
}
function recordIcon(status?: string) {
  switch (status) {
    case 'CANCEL_REQUESTED':
    case 'UNKNOWN': {
      return 'lucide:circle-alert';
    }
    case 'FAILED': {
      return 'lucide:circle-x';
    }
    case 'RUNNING':
    case 'SUBMITTING': {
      return 'lucide:loader-circle';
    }
    case 'SUCCESS': {
      return 'lucide:circle-check';
    }
    default: {
      return 'lucide:clock-3';
    }
  }
}
watch(dirKeyword, (keyword) => {
  if (keyword.trim())
    expandedKeys.value = ['root', ...dirs.value.map((dir) => String(dir.id))];
});

async function loadTree() {
  const seq = ++treeSeq;
  treeLoading.value = true;
  try {
    const result = await getDevDirList();
    if (alive && seq === treeSeq) {
      const expand = allExpanded.value;
      dirs.value = result;
      if (expand || dirKeyword.value.trim()) {
        expandedKeys.value = collectExpandableKeys(dirTree.value);
      }
    }
  } finally {
    if (alive && seq === treeSeq) treeLoading.value = false;
  }
}
async function loadStatistics() {
  const seq = ++statsSeq;
  statsLoading.value = true;
  try {
    const data = await getDevTaskStatistics(props.workloadMode);
    if (alive && seq === statsSeq) statistics.value = data;
  } finally {
    if (seq === statsSeq) statsLoading.value = false;
  }
}
function refresh() {
  void gridApi.query();
  void loadStatistics();
}
function selectDirectory(keys: (number | string)[]) {
  selectedKeys.value = keys.map(String);
  dirId.value = keys[0] === 'root' ? undefined : keys[0];
  void gridApi.reload();
}
function resetSearch() {
  Object.keys(search).forEach((key) => Reflect.deleteProperty(search, key));
  void gridApi.reload();
}
function changeExecutionTime(_value: unknown, formatted: string | string[]) {
  search.executionStartTime =
    Array.isArray(formatted) && formatted.some(Boolean)
      ? (formatted as [string, string])
      : undefined;
}
function saved(id: DataforgeDevApi.Id, configure: boolean) {
  refresh();
  if (configure) workspaceTaskId.value = id;
}
function closeWorkspace() {
  workspaceTaskId.value = undefined;
  refresh();
}
function confirmDeleteTask(row: DataforgeDevApi.Task) {
  const scope = generation;
  Modal.confirm({
    title: '删除任务',
    content: `确定删除“${row.name}”？已启用或存在活动实例的任务需先停用。`,
    onOk: () =>
      scope === generation && alive
        ? rowOperation(row, () => deleteDevTask(row.id!))
        : undefined,
  });
}
function confirmDeleteDir(dir: DataforgeDevApi.Dir) {
  const scope = generation;
  Modal.confirm({
    title: '删除类目',
    content: `确定删除“${dir.name}”？请先移除子类目及关联任务。`,
    async onOk() {
      if (scope !== generation || !alive) return;
      await deleteDevDir(dir.id!);
      await loadTree();
    },
  });
}
async function rowOperation(
  row: DataforgeDevApi.Task,
  operation: () => Promise<unknown>,
) {
  const key = String(row.id);
  if (pendingRows.value.has(key)) return;
  const scope = generation;
  pendingRows.value = new Set([...pendingRows.value, key]);
  try {
    await operation();
    if (scope !== generation || !alive) return;
    message.success('操作成功');
    refresh();
  } finally {
    if (scope === generation) {
      const next = new Set(pendingRows.value);
      next.delete(key);
      pendingRows.value = next;
    }
  }
}
function toggleStatus(
  row: DataforgeDevApi.Task,
  checked: boolean | number | string,
) {
  const scope = generation;
  if (!checked) {
    void rowOperation(row, () => updateDevTaskStatus(row.id!, 0));
    return;
  }
  if (!hasNativeAccess(row) || !row.currentVersionId) return;
  if (isNativeProgramNotReady(row)) {
    message.warning(
      row.executionCapabilityReason || '请完善受管部署与资源配置后重新保存',
    );
    return;
  }
  Modal.confirm({
    title: '上线任务',
    content: '启用当前保存的任务版本，后续运行使用该版本。',
    async onOk() {
      if (scope !== generation || !alive) return;
      await rowOperation(row, () => updateDevTaskStatus(row.id!, 1));
    },
  });
}
async function runOnce(row: DataforgeDevApi.Task) {
  if (
    !canRun.value ||
    !hasNativeAccess(row) ||
    !row.currentVersionId ||
    row.status !== 1 ||
    isNativeProgramNotReady(row)
  )
    return;
  const scope = generation;
  const id = await commands.execute(
    `dev-list-run:${row.id}`,
    { id: row.id!, businessDate: dayjs().format('YYYY-MM-DD') },
    runDevTask,
  );
  if (scope !== generation || !alive || id === undefined) return;
  message.success(`运行已受理，实例 #${id}`);
  refresh();
  void instanceModal.value?.open(row.id!, id);
}
const columns: VxeTableGridOptions<DataforgeDevApi.Task>['columns'] = [
  { field: 'id', title: '编号', width: 64, sortable: true },
  {
    field: 'name',
    title: '任务信息',
    minWidth: 425,
    align: 'left',
    slots: { default: 'taskInfo' },
  },
  {
    title: '所属类目',
    width: 150,
    align: 'left',
    slots: { default: 'category' },
  },
  {
    title: '最近执行信息',
    width: 178,
    align: 'left',
    slots: { default: 'latest' },
  },
  {
    title: '运行控制',
    width: 132,
    align: 'left',
    slots: { default: 'controls' },
  },
  ...(isRealtime.value
    ? []
    : [
        {
          title: '策略与调度',
          minWidth: 210,
          align: 'left' as const,
          slots: { default: 'schedule' },
        },
      ]),
  {
    field: 'ownerName',
    title: '责任人',
    width: 115,
    align: 'left',
    slots: { default: 'owner' },
  },
  {
    field: 'creator',
    title: '创建人',
    width: 115,
    align: 'left',
    slots: { default: 'creator' },
  },
  {
    field: 'createTime',
    title: '创建时间',
    width: 110,
    slots: { default: 'createdAt' },
  },
  {
    title: '操作',
    width: 250,
    fixed: 'right',
    showOverflow: false,
    slots: { default: 'actions' },
  },
];
const [Grid, gridApi] = useVbenVxeGrid({
  gridOptions: {
    showOverflow: false,
    showHeaderOverflow: 'tooltip',
    columns,
    height: 'auto',
    rowConfig: { keyField: 'id', isHover: true },
    pagerConfig: { pageSize: 10 },
    toolbarConfig: { refresh: true, custom: true, zoom: false },
    sortConfig: { defaultSort: { field: 'id', order: 'desc' } },
    proxyConfig: {
      ajax: {
        query: async ({ page }) => {
          const seq = ++listSeq;
          const request = getDevTaskPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            dirId: dirId.value,
            workloadMode: props.workloadMode,
            ...search,
          });
          latestPageRequest = request;
          const result = await request;
          if (!alive) return { list: [], total: 0 };
          return seq === listSeq
            ? result
            : await (latestPageRequest ??
                Promise.resolve({ list: [], total: 0 }));
        },
      },
    },
  } as VxeTableGridOptions<DataforgeDevApi.Task>,
});
watch(
  () => [accessStore.accessToken, accessStore.refreshToken, props.workloadMode],
  () => {
    ++generation;
    ++treeSeq;
    ++statsSeq;
    ++listSeq;
    latestPageRequest = undefined;
    workspaceTaskId.value = undefined;
    pendingRows.value = new Set();
    dirs.value = [];
    users.value = [];
    databaseTypeOptions.value = [];
    databaseIconPaths.value = {};
    statistics.value = undefined;
    dirId.value = undefined;
    selectedKeys.value = ['root'];
    expandedKeys.value = ['root'];
    taskInfo.value?.close();
    instanceModal.value?.close();
    scheduleForm.value?.close();
    dirForm.value?.close();
    void loadContext();
    refresh();
  },
  { flush: 'sync' },
);
async function loadContext() {
  const scope = generation;
  await Promise.all([
    loadTree(),
    getSimpleUserList().then((data) => {
      if (alive && scope === generation)
        users.value = data.map((user) => ({
          id: String(user.id),
          name: user.nickname,
          mobile: user.mobile,
        }));
    }),
    getDatabaseTypeSimpleList().then((data) => {
      if (alive && scope === generation)
        databaseTypeOptions.value = data.map((type) => ({
          label: type.typeName,
          value: (type.productId || type.typeCode).toUpperCase(),
        }));
      if (alive && scope === generation) {
        databaseIconPaths.value = Object.fromEntries(
          data.flatMap((type) =>
            [type.productId, type.typeCode]
              .filter(Boolean)
              .map((code) => [
                normalizeDevDatabaseType(code),
                type.iconPath || '',
              ]),
          ),
        );
      }
    }),
    loadStatistics(),
  ]);
}
onMounted(() => {
  void loadContext();
});
onBeforeUnmount(() => {
  alive = false;
  ++generation;
  ++treeSeq;
  ++statsSeq;
  ++listSeq;
});
</script>

<template>
  <Workbench
    v-if="workspaceTaskId !== undefined"
    :database-icon-paths="databaseIconPaths"
    :task-id="workspaceTaskId"
    :workload-mode="props.workloadMode"
    @back="closeWorkspace"
  />
  <Page v-else auto-content-height>
    <div class="development-page">
      <div
        class="development-layout"
        :class="{ 'directory-collapsed': directoryCollapsed }"
      >
        <aside
          v-show="!directoryCollapsed"
          id="development-directory"
          class="development-directory"
        >
          <div class="directory-header">
            <div class="directory-tools">
              <TreeToolbar
                :expanded="allExpanded"
                :loading="treeLoading"
                @toggle-expand="toggleExpandAll"
                @refresh="loadTree"
              />
            </div>
            <Input
              v-model:value="dirKeyword"
              class="directory-search"
              allow-clear
              placeholder="请输入数据开发类目名称"
            >
              <template #prefix><IconifyIcon icon="lucide:search" /></template>
            </Input>
          </div>
          <Tree
            v-model:expanded-keys="expandedKeys"
            class="subtle-tree-scrollbar"
            :selected-keys="selectedKeys"
            :tree-data="dirTree"
            block-node
            :auto-expand-parent="false"
            @select="selectDirectory"
          >
            <template #titleRender="node">
              <div class="directory-node">
                <img
                  :src="DEV_REFERENCE_ASSETS.folder"
                  class="directory-icon"
                  alt=""
                  aria-hidden="true"
                />
                <span class="directory-name">{{ node.title }}</span>
                <Dropdown
                  v-if="canSaveDir || canDeleteDir"
                  :trigger="['click']"
                  @click.stop
                >
                  <Button type="text" size="small" aria-label="类目操作">
                    <IconifyIcon icon="lucide:ellipsis-vertical" />
                  </Button>
                  <template #popupRender>
                    <Menu>
                      <Menu.Item
                        v-if="canSaveDir"
                        @click="dirForm?.open(undefined, node.dir?.id)"
                      >
                        <template #icon>
                          <IconifyIcon icon="lucide:folder-plus" />
                        </template>
                        新增子级
                      </Menu.Item>
                      <Menu.Item
                        v-if="node.dir && canSaveDir"
                        @click="dirForm?.open(undefined, node.dir.parentId)"
                      >
                        <template #icon>
                          <IconifyIcon icon="lucide:folder-plus" />
                        </template>
                        新增同级
                      </Menu.Item>
                      <Menu.Item
                        v-if="node.dir && canSaveDir"
                        @click="dirForm?.open(node.dir.id)"
                      >
                        <template #icon>
                          <IconifyIcon icon="lucide:edit" />
                        </template>
                        编辑
                      </Menu.Item>
                      <Menu.Item
                        v-if="node.dir && canDeleteDir"
                        danger
                        @click="confirmDeleteDir(node.dir)"
                      >
                        <template #icon>
                          <IconifyIcon icon="lucide:trash-2" />
                        </template>
                        删除
                      </Menu.Item>
                    </Menu>
                  </template>
                </Dropdown>
              </div>
            </template>
          </Tree>
        </aside>
        <Button
          class="directory-toggle"
          type="text"
          size="small"
          :aria-label="directoryCollapsed ? '展开类目树' : '收起类目树'"
          :aria-expanded="!directoryCollapsed"
          aria-controls="development-directory"
          @click="directoryCollapsed = !directoryCollapsed"
        >
          <IconifyIcon
            :icon="
              directoryCollapsed
                ? 'lucide:chevron-right'
                : 'lucide:chevron-left'
            "
          />
        </Button>
        <main class="development-main">
          <div class="development-statistics">
            <span>
              <img
                class="stat-icon"
                :src="DEV_REFERENCE_ASSETS.statRunning"
                alt=""
              />
              运行中任务：
              <b>{{ statistics?.runningTasks ?? '-' }}</b>
              次
            </span>
            <span>
              <img
                class="stat-icon"
                :src="DEV_REFERENCE_ASSETS.statFailed"
                alt=""
              />
              今日异常：
              <b>{{ statistics?.todayFailed ?? '-' }}</b>
              次
            </span>
            <span>
              <img
                class="stat-icon"
                :src="DEV_REFERENCE_ASSETS.statToday"
                alt=""
              />
              今日执行：
              <b>{{ statistics?.todayExecutions ?? '-' }}</b>
              次
            </span>
            <span>
              <img
                class="stat-icon"
                :src="DEV_REFERENCE_ASSETS.statSuccess"
                alt=""
              />
              今日成功率
              <b>{{ statistics?.todaySuccessRate ?? '-' }}%</b>
            </span>
            <div class="statistics-time">
              数据统计截止：{{
                statistics?.refreshedAt
                  ? formatDateTime(statistics.refreshedAt)
                  : '-'
              }}
              <Button
                size="small"
                type="text"
                :loading="statsLoading"
                aria-label="刷新统计"
                @click="loadStatistics"
              >
                <IconifyIcon icon="lucide:refresh-cw" />
              </Button>
            </div>
          </div>
          <div v-show="showFilters" class="development-search">
            <label>
              任务名称
              <Input
                v-model:value="search.name"
                allow-clear
                placeholder="请输入任务名称"
                @press-enter="gridApi.reload()"
              />
            </label>
            <label>
              任务状态
              <Select
                v-model:value="search.status"
                :options="TASK_STATUS_OPTIONS"
                allow-clear
                placeholder="请选择任务状态"
              />
            </label>
            <label>
              执行状态
              <Select
                v-model:value="search.executionStatus"
                :options="DEV_RECORD_STATUS_OPTIONS"
                allow-clear
                placeholder="请选择执行状态"
              />
            </label>
            <div class="development-search-actions">
              <Button class="reference-button" @click="gridApi.reload()">
                <img
                  class="reference-icon"
                  :src="DEV_REFERENCE_ASSETS.query"
                  alt=""
                />
                查询
              </Button>
              <Button @click="resetSearch">
                <img
                  class="reference-icon"
                  :src="DEV_REFERENCE_ASSETS.reset"
                  alt=""
                />
                重置
              </Button>
              <Button
                class="reference-button"
                @click="expandedSearch = !expandedSearch"
              >
                {{ expandedSearch ? '收起' : '展开' }}
                <IconifyIcon
                  :icon="
                    expandedSearch
                      ? 'lucide:chevrons-up'
                      : 'lucide:chevrons-down'
                  "
                />
              </Button>
            </div>
            <label v-if="expandedSearch">
              数据连接类型
              <Select
                v-model:value="search.dbType"
                :options="databaseTypeOptions"
                allow-clear
                show-search
                placeholder="请选择数据连接类型"
              />
            </label>
            <label v-if="expandedSearch">
              最近执行时间
              <DateRangePicker
                :value="search.executionStartTime"
                v-bind="getRangePickerDefaultProps()"
                @change="changeExecutionTime"
              />
            </label>
          </div>
          <div class="development-table">
            <Grid>
              <template #toolbar-actions>
                <Button
                  v-if="canSave"
                  class="reference-button"
                  @click="taskInfo?.open(undefined, dirId)"
                >
                  <IconifyIcon icon="lucide:plus" />
                  新增
                </Button>
              </template>
              <template #toolbar-tools>
                <Button
                  class="table-tool"
                  aria-label="显示或隐藏筛选"
                  :aria-pressed="showFilters"
                  @click="showFilters = !showFilters"
                >
                  <img
                    class="reference-icon"
                    :src="DEV_REFERENCE_ASSETS.search"
                    alt=""
                  />
                </Button>
              </template>
              <template #taskInfo="{ row }">
                <div class="task-information">
                  <div class="task-heading">
                    <TaskTypeIcon
                      :task-type="row.taskType"
                      :engine="row.engine"
                      :db-type="row.dbType"
                      :database-icon-path="
                        databaseIconPaths[normalizeDevDatabaseType(row.dbType)]
                      "
                      :size="20"
                    />
                    <button
                      type="button"
                      class="task-name"
                      :title="row.name"
                      @click="showDetail(row.id)"
                    >
                      {{ row.name }}
                    </button>
                  </div>
                  <p
                    :title="`[${taskDescription(row)}] ${row.description || '暂无描述'}`"
                  >
                    {{ row.description || taskDescription(row) }}
                  </p>
                </div>
              </template>
              <template #category="{ row }">
                <Tag
                  class="task-category"
                  color="blue"
                  :title="dirName(row.dirId)"
                >
                  {{ dirName(row.dirId) }}
                </Tag>
              </template>
              <template #controls="{ row }">
                <div class="task-controls">
                  <label>
                    任务状态:
                    <Switch
                      :checked="row.status === 1"
                      :disabled="
                        !canChangeStatus ||
                        (row.status !== 1 &&
                          (!row.currentVersionId ||
                            isNativeProgramNotReady(row) ||
                            !hasNativeAccess(row)))
                      "
                      :loading="pendingRows.has(String(row.id))"
                      size="small"
                      @change="(value) => toggleStatus(row, value)"
                    />
                  </label>
                  <label v-if="!isRealtime">
                    调度状态:
                    <Switch
                      :checked="row.scheduleStatus === 1"
                      :disabled="
                        !canRun || !hasNativeAccess(row) || row.status !== 1
                      "
                      :loading="pendingRows.has(String(row.id))"
                      size="small"
                      @change="
                        (value) =>
                          rowOperation(row, () =>
                            updateDevTaskSchedule({
                              id: row.id!,
                              scheduleStatus: value ? 1 : 0,
                            }),
                          )
                      "
                    />
                  </label>
                </div>
              </template>
              <template #schedule="{ row }">
                <div class="task-schedule">
                  <span>串行</span>
                  <span :title="row.cronExpression ?? undefined">
                    <IconifyIcon icon="lucide:clock-3" />
                    {{ cronSummary(row.cronExpression) }}
                  </span>

                  <Tag
                    v-if="
                      row.scheduleSyncStatus &&
                      row.scheduleSyncStatus !== 'NOT_CONFIGURED'
                    "
                    :color="
                      optionColor(
                        DEV_SCHEDULE_SYNC_OPTIONS,
                        row.scheduleSyncStatus,
                      )
                    "
                    :title="row.scheduleSyncReason ?? undefined"
                  >
                    {{
                      optionLabel(
                        DEV_SCHEDULE_SYNC_OPTIONS,
                        row.scheduleSyncStatus,
                      )
                    }}
                  </Tag>
                </div>
              </template>
              <template #latest="{ row }">
                <div v-if="row.latestRecord">
                  <button
                    type="button"
                    class="latest-record-link"
                    :aria-label="`查看${row.name}的最近执行实例`"
                    @click="
                      instanceModal?.open(
                        row.id!,
                        hasNativeAccess(row) ? row.latestRecord.id : undefined,
                      )
                    "
                  >
                    <Tag
                      :color="
                        optionColor(
                          DEV_RECORD_STATUS_OPTIONS,
                          row.latestRecord.status,
                        )
                      "
                      class="latest-status"
                    >
                      <template #icon>
                        <IconifyIcon
                          :icon="recordIcon(row.latestRecord.status)"
                        />
                      </template>
                      {{
                        optionLabel(
                          DEV_RECORD_STATUS_OPTIONS,
                          row.latestRecord.status,
                        )
                      }}
                    </Tag>
                  </button>
                  <Tooltip
                    :title="
                      row.latestRecord.startTime
                        ? formatDateTime(row.latestRecord.startTime)
                        : undefined
                    "
                  >
                    <p class="latest-time">
                      <span>{{
                        row.latestRecord.startTime
                          ? formatPast(
                              new Date(row.latestRecord.startTime),
                              'YYYY-MM-DD',
                            )
                          : '-'
                      }}</span>
                      <span>{{
                        formatDuration(row.latestRecord.durationMs)
                      }}</span>
                    </p>
                  </Tooltip>
                </div>
                <div v-else>
                  <Tag class="latest-status idle-status">
                    <template #icon>
                      <IconifyIcon icon="lucide:circle-minus" />
                    </template>
                    空闲
                  </Tag>
                  <p class="latest-time"><span>-</span><span>-</span></p>
                </div>
              </template>
              <template #owner="{ row }">
                <div class="task-person">
                  <span>{{ row.ownerName || userName(row.ownerId) }}</span>
                  <span>{{
                    row.contactNumber || userContact(row.ownerId)
                  }}</span>
                </div>
              </template>
              <template #creator="{ row }">
                <div class="task-person">
                  <span>{{ userName(row.creator) }}</span>
                  <span>{{ userContact(row.creator) }}</span>
                </div>
              </template>
              <template #createdAt="{ row }">
                <Tooltip
                  :title="
                    row.createTime ? formatDateTime(row.createTime) : undefined
                  "
                >
                  <span>{{
                    row.createTime ? formatDate(row.createTime) : '-'
                  }}</span>
                </Tooltip>
              </template>
              <template #actions="{ row }">
                <div class="task-actions">
                  <Button
                    v-if="canSave"
                    type="link"
                    size="small"
                    @click="workspaceTaskId = row.id"
                  >
                    <template #icon>
                      <img
                        class="reference-icon"
                        :src="DEV_REFERENCE_ASSETS.edit"
                        alt=""
                      />
                    </template>
                    配置任务
                  </Button>
                  <Button type="link" size="small" @click="showDetail(row.id)">
                    <template #icon>
                      <img
                        class="reference-icon"
                        :src="DEV_REFERENCE_ASSETS.eye"
                        alt=""
                      />
                    </template>
                    详情
                  </Button>
                  <Dropdown :trigger="['click']">
                    <Button type="link" size="small">
                      <template #icon>
                        <img
                          class="reference-icon"
                          :src="DEV_REFERENCE_ASSETS.down"
                          alt=""
                        />
                      </template>
                      更多
                    </Button>
                    <template #popupRender>
                      <Menu>
                        <Menu.Item
                          v-if="canSave"
                          @click="taskInfo?.open(row.id)"
                        >
                          <template #icon>
                            <IconifyIcon :icon="ACTION_ICON.EDIT" />
                          </template>
                          编辑任务
                        </Menu.Item>
                        <Menu.Item
                          v-if="
                            !isRealtime &&
                            canSave &&
                            canRun &&
                            hasNativeAccess(row)
                          "
                          @click="scheduleForm?.open(row.id!)"
                        >
                          <template #icon>
                            <IconifyIcon icon="lucide:clock-3" />
                          </template>
                          调度周期
                        </Menu.Item>
                        <Menu.Item @click="instanceModal?.open(row.id!)">
                          <template #icon>
                            <IconifyIcon icon="lucide:list" />
                          </template>
                          运行实例
                        </Menu.Item>
                        <Menu.Item
                          v-if="canRun && hasNativeAccess(row)"
                          :disabled="
                            !row.currentVersionId ||
                            row.status !== 1 ||
                            isNativeProgramNotReady(row) ||
                            commands.busy.value
                          "
                          @click="runOnce(row)"
                        >
                          <template #icon>
                            <IconifyIcon icon="lucide:play" />
                          </template>
                          执行一次
                        </Menu.Item>
                        <Menu.Item
                          :disabled="!row.latestRecord || !hasNativeAccess(row)"
                          @click="
                            instanceModal?.open(row.id!, row.latestRecord?.id)
                          "
                        >
                          <template #icon>
                            <IconifyIcon icon="lucide:activity" />
                          </template>
                          实时日志
                        </Menu.Item>
                        <Menu.Item
                          v-if="canDelete"
                          danger
                          :disabled="row.status === 1"
                          @click="confirmDeleteTask(row)"
                        >
                          <template #icon>
                            <IconifyIcon :icon="ACTION_ICON.DELETE" />
                          </template>
                          删除
                        </Menu.Item>
                      </Menu>
                    </template>
                  </Dropdown>
                </div>
              </template>
            </Grid>
          </div>
        </main>
      </div>
    </div>
  </Page>
  <DirForm ref="dirForm" @saved="loadTree" />
  <TaskInfo ref="taskInfo" :workload-mode="props.workloadMode" @saved="saved" />
  <ScheduleForm ref="scheduleForm" @saved="refresh" />
  <InstanceModal ref="instanceModal" />
</template>

<style scoped>
.development-page {
  --dev-primary: #2d63ff;

  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  color: hsl(var(--foreground));
}

.development-layout {
  position: relative;
  display: flex;
  flex: 1;
  gap: 15px;
  min-height: 0;
}

.development-directory {
  display: flex;
  flex: 0 0 280px;
  flex-direction: column;
  min-width: 0;
  padding: 10px;
  overflow: hidden;
  background: hsl(var(--background));
  border-radius: 2px;
}

.directory-header {
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  gap: 6px;
}

.directory-tools {
  display: flex;
  justify-content: flex-end;
}

.directory-tools :deep(.ant-btn) {
  width: 24px;
  min-width: 24px;
  height: 24px;
}

.directory-search {
  width: 100%;
  height: 32px;
}

.directory-toggle {
  position: absolute;
  top: 50%;
  left: 278px;
  z-index: 1;
  width: 16px;
  min-width: 16px;
  padding: 0;
  color: #a4a7ae;
  transform: translateY(-50%);
}

.directory-collapsed .directory-toggle {
  left: -14px;
}

.development-directory :deep(.ant-input-affix-wrapper) {
  flex-shrink: 0;
  border-radius: 1px;
}

.development-directory :deep(.ant-tree) {
  flex: 1;
  min-height: 0;
  margin-top: 14px;
  overflow: auto;
  font-size: 14px;
  background: transparent;
}

.development-directory :deep(.ant-tree-treenode) {
  width: 100%;
  padding-block: 1px;
}

.development-directory :deep(.ant-tree-node-content-wrapper) {
  flex: 1;
  min-width: 0;
}

.development-directory :deep(.ant-tree-title) {
  display: block;
  width: 100%;
}

.directory-node {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  min-height: 26px;
}

.directory-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.directory-icon {
  flex: none;
  width: 18px;
  height: 18px;
  object-fit: contain;
}

.directory-node :deep(.ant-btn) {
  opacity: 0;
}

.directory-node:hover :deep(.ant-btn),
.directory-node:focus-within :deep(.ant-btn) {
  opacity: 1;
}

.development-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 15px;
  min-width: 0;
  min-height: 0;
}

.development-statistics {
  display: flex;
  flex-shrink: 0;
  flex-wrap: wrap;
  gap: 10px 14px;
  align-items: center;
  min-height: 44px;
  padding: 10px 12px;
  font-size: 14px;
  color: #909399;
  background: hsl(var(--background));
  border-radius: 2px;
}

.development-statistics > span {
  display: flex;
  flex: 1;
  gap: 4px;
  align-items: center;
  white-space: nowrap;
  border-right: 1px solid hsl(var(--border));
}

.development-statistics > span:last-of-type {
  border-right: 0;
}

.development-statistics b {
  font-weight: 400;
  color: hsl(var(--foreground));
}

.stat-icon {
  flex-shrink: 0;
  width: 13px;
  height: 13px;
  margin-right: 4px;
  object-fit: contain;
}

.statistics-time {
  display: flex;
  gap: 4px;
  align-items: center;
  margin-left: auto;
  font-size: 13px;
  white-space: nowrap;
}

.statistics-time :deep(.ant-btn) {
  color: var(--dev-primary);
}

.development-search {
  display: grid;
  flex-shrink: 0;
  grid-template-columns: repeat(3, minmax(160px, 1fr)) auto;
  gap: 16px 20px;
  align-items: center;
  min-height: 62px;
  padding: 15px;
  background: hsl(var(--background));
  border-radius: 2px;
}

.development-search label {
  display: flex;
  gap: 12px;
  align-items: center;
  min-width: 0;
  font-size: 14px;
  white-space: nowrap;
}

.development-search label :deep(.ant-input-affix-wrapper),
.development-search label :deep(.ant-select),
.development-search label :deep(.ant-picker-range) {
  flex: 1;
  width: 100%;
  min-width: 0;
  border-radius: 1px;
}

.development-search-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  justify-content: flex-end;
}

.reference-button {
  color: var(--dev-primary);
  background: #f0f6ff;
  border-color: #a2b8ff;
  border-radius: 1px;
}

.reference-icon {
  flex-shrink: 0;
  width: 16px;
  height: 16px;
  object-fit: contain;
}

.development-table {
  flex: 1;
  min-height: 280px;
  overflow: hidden;
  background: hsl(var(--background));
  border-radius: 2px;
}

.development-table :deep(.vxe-grid) {
  padding: 0 15px 10px;
}

.development-table :deep(.vxe-toolbar) {
  height: 60px;
  padding: 0;
}

.development-table :deep(.vxe-toolbar--tools) {
  gap: 12px;
}

.table-tool,
.development-table :deep(.vxe-toolbar--tools .vxe-button) {
  width: 32px;
  height: 32px;
  margin-left: 0;
  border: 1px solid hsl(var(--border));
  border-radius: 1px;
}

.development-table :deep(.vxe-header--column) {
  height: 36px;
  font-size: 12px;
  font-weight: 400;
  color: #686b70;
  background: hsl(var(--muted) / 65%);
}

.development-table :deep(.vxe-body--column) {
  height: 79px;
  padding-block: 10px;
  font-size: 12px;
  border-bottom: 1px solid hsl(var(--border));
}

.development-table :deep(.vxe-body--row:nth-child(even)) {
  background: hsl(var(--muted) / 25%);
}

.development-table :deep(.vxe-body--row.row--hover > .vxe-body--column) {
  background: #f8f9fb;
}

.task-heading {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 0;
}

.task-name {
  min-width: 0;
  padding: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 12px;
  font-weight: 400;
  color: var(--dev-primary);
  text-align: left;
  white-space: nowrap;
  cursor: pointer;
  background: transparent;
  border: 0;
}

.task-name:focus-visible,
.latest-record-link:focus-visible {
  outline: 2px solid var(--dev-primary);
  outline-offset: 2px;
}

.task-category {
  max-width: 100%;
  padding: 2px 8px;
  margin-inline-end: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 12px;
  line-height: 20px;
  color: var(--dev-primary);
  white-space: nowrap;
  background: #edf6ff;
  border: 0;
  border-radius: 1px;
}

.task-information p {
  display: -webkit-box;
  padding-left: 28px;
  margin: 5px 0 0;
  overflow: hidden;
  -webkit-line-clamp: 2;
  font-size: 12px;
  line-height: 18px;
  color: #999;
  overflow-wrap: anywhere;
  white-space: normal;
  -webkit-box-orient: vertical;
}

.task-controls,
.task-schedule {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 12px;
}

.task-controls label,
.task-schedule > span {
  display: flex;
  gap: 6px;
  align-items: center;
}

.task-controls :deep(.ant-switch-small) {
  min-width: 40px;
  height: 20px;
  line-height: 20px;
}

.task-controls :deep(.ant-switch-handle) {
  width: 16px;
  height: 16px;
}

.task-controls :deep(.ant-switch-checked) {
  background: var(--dev-primary);
}

.task-schedule > span {
  overflow: hidden;
  text-overflow: ellipsis;
  color: #686b70;
  white-space: nowrap;
}

.task-schedule :deep(svg) {
  flex-shrink: 0;
  color: var(--dev-primary);
}

.latest-record-link {
  padding: 0;
  cursor: pointer;
  background: transparent;
  border: 0;
}

.latest-status {
  display: inline-flex;
  gap: 3px;
  align-items: center;
  max-width: 100%;
  padding-inline: 6px;
  margin-inline-end: 0;
  font-size: 12px;
  line-height: 20px;
  border: 0;
  border-radius: 1px;
}

.latest-status :deep(svg) {
  width: 12px;
  height: 12px;
}

.idle-status {
  color: #9b37da;
  background: #f4e9fc;
}

.latest-time {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 6px 0 0;
  font-size: 12px;
  color: #687e9d;
  white-space: nowrap;
}

.task-person {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.task-person span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-actions {
  display: flex;
  align-items: center;
  justify-content: center;
}

.task-actions :deep(.ant-btn) {
  gap: 5px;
  padding-inline: 5px;
  font-size: 14px;
  color: var(--dev-primary);
}

.task-actions :deep(svg) {
  width: 14px;
  height: 14px;
}

@media (max-width: 1450px) {
  .development-search {
    grid-template-columns: repeat(3, minmax(140px, 1fr));
  }

  .development-search-actions {
    grid-column: 1 / -1;
  }
}

@media (max-width: 1100px) {
  .development-directory {
    flex-basis: 230px;
  }

  .directory-toggle {
    left: 228px;
  }

  .development-statistics > span {
    flex-basis: 40%;
  }

  .development-search {
    grid-template-columns: repeat(2, minmax(140px, 1fr));
  }
}

@media (max-width: 900px) {
  .development-page {
    height: auto;
    min-height: 100%;
  }

  .development-layout {
    flex: none;
    flex-direction: column;
  }

  .directory-toggle {
    top: -10px;
    right: 0;
    left: auto;
    background: hsl(var(--background));
    transform: rotate(-90deg);
  }

  .directory-collapsed .directory-toggle {
    left: auto;
  }

  .development-directory {
    flex: 0 0 auto;
    max-height: 230px;
  }

  .development-main {
    flex: none;
  }

  .development-table {
    flex: none;
    height: 480px;
  }

  .development-search {
    grid-template-columns: 1fr;
  }

  .development-search label :deep(.ant-select),
  .development-search label :deep(.ant-input-affix-wrapper),
  .development-search label :deep(.ant-picker-range) {
    flex: 1;
    width: auto;
    min-width: 0;
  }
}
</style>
