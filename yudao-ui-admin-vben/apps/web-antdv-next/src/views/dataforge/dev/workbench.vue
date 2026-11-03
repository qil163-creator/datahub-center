<script lang="ts" setup>
import type { EditorContent } from './workbench-state';

import type { DataforgeDevApi } from '#/api/dataforge/dev';
import type { SqlEditorStatus } from '#/components/monaco/sql-editor-status';

import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  ref,
  watch,
} from 'vue';
import { onBeforeRouteLeave } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import {
  Button,
  Card,
  Dropdown,
  Empty,
  Input,
  Menu,
  message,
  Modal,
  Splitter,
  SplitterPanel,
  TabPane,
  Tabs,
  Tag,
  Tooltip,
  Tree,
  TreeSelect,
} from 'antdv-next';
import dayjs from 'dayjs';

import {
  getDatabaseSimpleList,
  getDatabaseTableList,
} from '#/api/dataforge/database';
import {
  deleteDevDir,
  deleteDevTask,
  getDevDirList,
  getDevProgramType,
  getDevTableColumns,
  getDevTask,
  getDevTaskListByDir,
  isNativeProgramNotReady,
  isNativeProgramTask,
  runDevTask,
  stopDevRecord,
  updateDevDir,
  updateDevTask,
  updateDevTaskStatus,
} from '#/api/dataforge/dev';
import {
  resolveSqlLanguage,
  setColumnLoader,
  setCompletionTables,
} from '#/components/monaco/setup';
import SqlEditor from '#/components/monaco/sql-editor.vue';
import TreeToolbar from '#/components/tree-toolbar/index.vue';
import { createLatestWins } from '#/utils/resource-id';

import {
  collectExpandableKeys,
  isTreeExpanded,
} from '../common/tree-expansion';
import { useRunCommands } from '../execution/use-run-commands';
import { defaultExecutionConfig } from './list-model';
import DirForm from './modules/dir-form.vue';
import InstanceModal from './modules/instance-modal.vue';
import ScheduleForm from './modules/schedule-form.vue';
import TaskDetailView from './modules/task-detail-view.vue';
import TaskForm from './modules/task-form.vue';
import TaskInfo from './modules/task-info.vue';
import TaskTypeIcon from './modules/task-type-icon.vue';
import VersionModal from './modules/version-modal.vue';
import WorkbenchExecutionPanel from './modules/workbench-execution-panel.vue';
import WorkbenchLineagePanel from './modules/WorkbenchLineagePanel.vue';
import WorkbenchPropertiesPanel from './modules/WorkbenchPropertiesPanel.vue';
import { DEV_REFERENCE_ASSETS } from './reference-assets';
import { normalizeDevDatabaseType } from './task-icon-model';
import {
  hasUnsavedContent,
  mergeEditorSnapshot,
  normalizeEditorDatabaseId,
  saveEditorSnapshot,
  submitTaskRun,
} from './workbench-state';
import {
  resolveWorkbenchDirectoryPath,
  resolveWorkbenchRuntimeLabel,
} from './workbench-status';
import { newDevelopmentTask } from './workload-mode';

defineOptions({ name: 'DataforgeDevEditor' });
const props = defineProps<{
  databaseIconPaths?: Record<string, string>;
  readOnly?: boolean;
  taskId?: DataforgeDevApi.Id;
  workloadMode?: DataforgeDevApi.WorkloadMode;
}>();
const emit = defineEmits<{ back: [] }>();
async function returnToTasks() {
  if (await confirmDiscard()) emit('back');
}
const accessStore = useAccessStore();
const { hasAccessByCodes } = useAccess();
/** 平台只有一个研发工作空间，操作能力直接来自菜单按钮权限；接口仍是最终授权边界。 */
const canManage = computed(
  () => !props.readOnly && hasAccessByCodes(['dataforge:dev-task:save']),
);
const canOperate = computed(
  () => !props.readOnly && hasAccessByCodes(['dataforge:dev-task:run']),
);
const editorAsset = (name: string) =>
  `/assets/qdata-development/editor-${name}.png`;
const statusAsset = (name: 'document' | 'locked' | 'unlocked' | 'valid') =>
  `/assets/qdata-development/status-${name}.png`;
const directoryVisible = ref(true);
const taskDetails = ref<Record<string, DataforgeDevApi.Task>>({});
const detailVisible = ref(false);
const activeDetail = computed(() =>
  activeTab.value?.taskId === undefined
    ? undefined
    : taskDetails.value[String(activeTab.value.taskId)],
);
const currentDirectoryName = computed(
  () =>
    dirs.value.find(
      (dir) => String(dir.id) === String(activeDetail.value?.dirId),
    )?.name || '数据开发',
);
const currentTaskPath = computed(() =>
  [
    ...resolveWorkbenchDirectoryPath(dirs.value, activeDetail.value?.dirId),
    activeTab.value?.title || '数据开发',
  ].join(' > '),
);
function openTaskConfiguration() {
  const id = activeTab.value?.taskId;
  if (id === undefined) return;
  if (props.readOnly || activeTab.value?.status === 1)
    detailVisible.value = true;
  else taskInfoRef.value?.open(id);
}
const contextLoading = ref(false);
const openingTask = ref(false);
const taskLoadError = ref('');
let contextSeq = 0;
let openSeq = 0;
const businessDate = ref(dayjs().format('YYYY-MM-DD'));
async function loadWorkspace() {
  const seq = ++contextSeq;
  contextLoading.value = true;
  try {
    await Promise.all([loadResources(), loadDatabases()]);
  } finally {
    if (seq === contextSeq) contextLoading.value = false;
  }
}

function confirmDiscard() {
  if (!hasUnsavedContent(openTabs.value)) return Promise.resolve(true);
  return new Promise<boolean>((resolve) =>
    Modal.confirm({
      title: '有尚未保存的修改',
      content: '离开后将丢弃当前任务和临时脚本的未保存内容。',
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    }),
  );
}
onBeforeRouteLeave(confirmDiscard);
watch(
  () => [accessStore.accessToken, accessStore.refreshToken],
  () => {
    stopStatusPolling();
    ++openSeq;
    openingTask.value = false;
    taskLoadError.value = '';
    ++completionSeq;
    setCompletionTables([]);
    setColumnLoader(null);
    openTabs.value = [];
    taskDetails.value = {};
    detailVisible.value = false;
    activeKey.value = '';
    instanceModalRef.value?.close();
    dirs.value = [];
    tasks.value = [];
    databases.value = [];
    connectionTables.value = {};
    void loadWorkspace();
  },
);

const dirFormRef = ref<InstanceType<typeof DirForm>>();
const taskFormRef = ref<InstanceType<typeof TaskForm>>();
const taskInfoRef = ref<InstanceType<typeof TaskInfo>>();
const scheduleFormRef = ref<InstanceType<typeof ScheduleForm>>();
const versionModalRef = ref<InstanceType<typeof VersionModal>>();
const editorRef = ref<InstanceType<typeof SqlEditor>>();
const instanceModalRef = ref<InstanceType<typeof InstanceModal>>();

// ==================== 左侧资源树 ====================

const dirs = ref<DataforgeDevApi.Dir[]>([]);
const tasks = ref<DataforgeDevApi.Task[]>([]);
const treeFilter = ref('');
const selectedKeys = ref<string[]>([]);
const resourcesLoading = ref(false);
let resourceSeq = 0;

interface ResourceNode {
  key: string;
  title: string;
  isLeaf?: boolean;
  taskId?: DataforgeDevApi.Id;
  dirId?: DataforgeDevApi.Id;
  status?: number;
  taskType?: string;
  dbType?: string;
  databaseId?: DataforgeDevApi.Id;
  tableName?: string;
  engine?: DataforgeDevApi.Task['engine'];
  children?: ResourceNode[];
}

const databases = ref<
  {
    label: string;
    productId?: string;
    typeName?: string;
    value: DataforgeDevApi.Id;
  }[]
>([]);
const connectionTables = ref<Record<string, ResourceNode[]>>({});
const openTabs = ref<EditorTab[]>([]);
const activeKey = ref<string>('');

const resourceTree = computed<ResourceNode[]>(() => {
  const keyword = treeFilter.value.trim().toLowerCase();
  const taskNodes = (dirId: DataforgeDevApi.Id) =>
    tasks.value
      .filter((task) => String(task.dirId ?? 0) === String(dirId))
      .filter((task) => !keyword || task.name?.toLowerCase().includes(keyword))
      .map((task) => ({
        key: `task-${task.id}`,
        title: task.name ?? '',
        isLeaf: true,
        taskId: task.id,
        status: task.status,
        revision: task.revision,
        currentVersionId: task.currentVersionId,
        taskType: task.taskType,
        dbType: task.dbType,
        engine: task.engine,
      }));
  const buildDir = (parentId: DataforgeDevApi.Id): ResourceNode[] =>
    dirs.value
      .filter((dir) => String(dir.parentId ?? 0) === String(parentId))
      .map((dir) => {
        const children = [...buildDir(dir.id!), ...taskNodes(dir.id!)];
        return {
          key: `dir-${dir.id}`,
          title: dir.name ?? '',
          dirId: dir.id,
          children,
        };
      })
      .filter(
        (node) =>
          !keyword ||
          node.children!.length > 0 ||
          node.title.toLowerCase().includes(keyword),
      );
  return [
    {
      key: 'development-root',
      title: '数据开发',
      children: [...buildDir(0), ...taskNodes(0)],
    },
    {
      key: 'connection-root',
      title: '数据连接目录',
      children: databases.value
        .filter(
          (database) =>
            !keyword || database.label.toLowerCase().includes(keyword),
        )
        .map((database) => ({
          key: `database-${database.value}`,
          title: database.label,
          databaseId: database.value,
          dbType: database.productId,
          children: connectionTables.value[String(database.value)],
          isLeaf: false,
        })),
    },
    {
      key: 'recent-root',
      title: '最近查看',
      children: openTabs.value
        .filter((tab) => tab.taskId !== undefined)
        .slice(-5)
        .map((tab) => ({
          key: `recent-${tab.taskId}`,
          title: tab.title,
          taskId: tab.taskId,
          taskType: tab.taskType,
          dbType: tab.dbType,
          status: tab.status,
          isLeaf: true,
        })),
    },
  ];
});

async function loadResources() {
  const scope = contextSeq;
  const seq = ++resourceSeq;
  resourcesLoading.value = true;
  try {
    const [dirList, taskList] = await Promise.all([
      getDevDirList(),
      getDevTaskListByDir(undefined, props.workloadMode),
    ]);
    if (scope !== contextSeq || seq !== resourceSeq) return;
    dirs.value = dirList;
    tasks.value = taskList;
  } finally {
    if (scope === contextSeq && seq === resourceSeq) {
      resourcesLoading.value = false;
    }
  }
}

/** 展开状态（默认全部展开，搜索时自动展开） */
const expandedKeys = ref<string[]>([
  'development-root',
  'connection-root',
  'recent-root',
]);
const expandAllResources = ref(false);
const isExpanded = computed(() =>
  isTreeExpanded(resourceTree.value, expandedKeys.value),
);

function toggleExpandAll() {
  expandAllResources.value = !isExpanded.value;
  expandedKeys.value = isExpanded.value
    ? []
    : collectExpandableKeys(resourceTree.value);
}

function handleTreeFilter() {
  if (treeFilter.value.trim()) {
    expandedKeys.value = collectExpandableKeys(resourceTree.value);
  }
}

watch(resourceTree, (nodes) => {
  if (expandAllResources.value || treeFilter.value.trim()) {
    expandedKeys.value = collectExpandableKeys(nodes);
  } else {
    expandTaskPath(activeDetail.value?.dirId);
  }
});

function expandTaskPath(directoryId?: DataforgeDevApi.Id) {
  const keys = new Set(expandedKeys.value);
  const visited = new Set<string>();
  let current = directoryId;
  while (
    current !== undefined &&
    String(current) !== '0' &&
    !visited.has(String(current))
  ) {
    visited.add(String(current));
    keys.add(`dir-${current}`);
    current = dirs.value.find(
      (dir) => String(dir.id) === String(current),
    )?.parentId;
  }
  expandedKeys.value = [...keys];
}

function selectedDirId(): DataforgeDevApi.Id | undefined {
  const key = selectedKeys.value[0];
  if (key?.startsWith('dir-')) {
    return key.slice(4);
  }
  return undefined;
}

/** Tree 的 dblclick 事件签名为 (e, node)，节点数据可能在 node 本身或 dataRef 上 */
function handleTreeDoubleClick(_event: MouseEvent, node: any) {
  const data = node?.dataRef ?? node?.data ?? node;
  if (
    data?.databaseId !== undefined &&
    canManage.value &&
    props.workloadMode !== 'STREAMING'
  ) {
    openScratch();
    handleDatabaseChange(data.databaseId);
    return;
  }
  const taskId = (data?.taskId ?? node?.taskId) as
    | DataforgeDevApi.Id
    | undefined;
  if (taskId) {
    openTask(taskId);
    return;
  }
  // 兜底：从节点 key 解析任务 ID（key 形如 task-123）
  const key = String(node?.key ?? node?.eventKey ?? '');
  if (key.startsWith('task-')) {
    openTask(key.slice(5));
  }
}

/** 树节点「…」菜单：编辑 */
function handleNodeEdit(rawNode: any) {
  const data = rawNode?.dataRef ?? rawNode;
  if (data?.taskId) {
    const task = tasks.value.find(
      (item) => String(item.id) === String(data.taskId),
    );
    if (task?.status === 1) {
      void taskInfoRef.value?.open(data.taskId, undefined, true);
      return;
    }
    taskFormRef.value?.open(data.taskId);
  } else if (data?.dirId) {
    dirFormRef.value?.open(data.dirId);
  }
}

// ==================== 移动到目录 ====================

const moveVisible = ref(false);
const moveSaving = ref(false);
const moveTarget = ref<{
  dirId?: DataforgeDevApi.Id;
  taskId?: DataforgeDevApi.Id;
  title?: string;
}>();
const moveDirId = ref<DataforgeDevApi.Id>(0);

/** 移动弹窗的目录树（移动目录时排除自身及子孙目录，避免形成环） */
const moveDirTree = computed(() => {
  const excluded = new Set<DataforgeDevApi.Id>();
  if (moveTarget.value?.dirId) {
    const collect = (id: DataforgeDevApi.Id) => {
      excluded.add(id);
      dirs.value
        .filter((dir) => (dir.parentId ?? 0) === id)
        .forEach((dir) => collect(dir.id!));
    };
    collect(moveTarget.value.dirId);
  }
  const build = (parentId: DataforgeDevApi.Id): any[] =>
    dirs.value
      .filter(
        (dir) => (dir.parentId ?? 0) === parentId && !excluded.has(dir.id!),
      )
      .map((dir) => ({ id: dir.id, name: dir.name, children: build(dir.id!) }));
  return [{ id: 0, name: '顶级目录', children: build(0) }];
});

/** 树节点「…」菜单：移动 */
function handleNodeMove(rawNode: any) {
  const data = rawNode?.dataRef ?? rawNode;
  if (!data?.taskId && !data?.dirId) {
    return;
  }
  moveTarget.value = {
    taskId: data.taskId,
    dirId: data.dirId,
    title: data.title,
  };
  moveDirId.value = data.taskId
    ? (tasks.value.find((task) => task.id === data.taskId)?.dirId ?? 0)
    : (dirs.value.find((dir) => dir.id === data.dirId)?.parentId ?? 0);
  moveVisible.value = true;
}

async function handleMoveOk() {
  const target = moveTarget.value;
  if (!target) {
    return;
  }
  moveSaving.value = true;
  try {
    if (target.taskId) {
      const task = await getDevTask(target.taskId);
      await updateDevTask({
        ...task,
        expectedRevision: task.revision,
        scheduler: 'DOLPHIN_SCHEDULER',
        dirId: moveDirId.value,
      });
    } else if (target.dirId) {
      const dir = dirs.value.find((item) => item.id === target.dirId);
      await updateDevDir({ ...dir, parentId: moveDirId.value });
    }
    message.success('移动成功');
    moveVisible.value = false;
    loadResources();
  } finally {
    moveSaving.value = false;
  }
}

/** 树节点「…」菜单：删除 */
function handleNodeDelete(rawNode: any) {
  const data = rawNode?.dataRef ?? rawNode;
  if (data?.taskId) {
    const taskId = data.taskId as DataforgeDevApi.Id;
    Modal.confirm({
      title: '系统提示',
      content: `确认删除任务"${data.title}"吗？`,
      okButtonProps: { danger: true },
      onOk: async () => {
        await deleteDevTask(taskId);
        message.success('任务已删除');
        closeTab(`task-${taskId}`, true);
        loadResources();
      },
    });
  } else if (data?.dirId) {
    const dirId = data.dirId as DataforgeDevApi.Id;
    Modal.confirm({
      title: '系统提示',
      content: `确认删除目录"${data.title}"吗？`,
      okButtonProps: { danger: true },
      onOk: async () => {
        await deleteDevDir(dirId);
        message.success('目录已删除');
        selectedKeys.value = selectedKeys.value.filter(
          (key) => key !== `dir-${dirId}`,
        );
        loadResources();
      },
    });
  }
}

// ==================== 数据源 ====================

async function loadConnectionTables(node: unknown) {
  if (!node || typeof node !== 'object') return;
  const candidate = 'dataRef' in node ? node.dataRef : node;
  if (!candidate || typeof candidate !== 'object') return;
  const data = candidate as Partial<ResourceNode>;
  const id = data.databaseId;
  if (id === undefined || data.tableName || connectionTables.value[String(id)])
    return;
  const scope = contextSeq;
  const tables = await getDatabaseTableList(id);
  if (scope !== contextSeq) return;
  connectionTables.value = {
    ...connectionTables.value,
    [String(id)]: tables.map((table) => ({
      key: `database-${id}-table-${table.tableName}`,
      title: table.tableName,
      tableName: table.tableName,
      databaseId: id,
      dbType: data.dbType,
      isLeaf: true,
    })),
  };
}

async function loadDatabases() {
  const seq = contextSeq;
  const list = await getDatabaseSimpleList();
  if (seq !== contextSeq) return;
  databases.value = list.map((item) => ({
    label: item.name ?? String(item.id),
    value: String(item.id),
    typeName: item.databaseTypeName,
    productId: item.productId,
  }));
}

/** 切换补全元数据到指定数据源（带竞态防护：快速切换标签时以最后一次为准） */
let completionSeq = 0;
async function refreshCompletion(databaseId?: DataforgeDevApi.Id) {
  const seq = ++completionSeq;
  if (!databaseId) {
    setCompletionTables([]);
    setColumnLoader(null);
    return;
  }
  setColumnLoader(async (tableName) => {
    const columns = await getDevTableColumns(databaseId, tableName);
    if (seq !== completionSeq) return [];
    return columns.map((column) => ({
      name: column.name,
      comment: column.comment,
      dataType: column.dataType,
    }));
  });
  try {
    const tableList = await getDatabaseTableList(databaseId);
    if (seq !== completionSeq) {
      return;
    }
    setCompletionTables(
      tableList.map((table) => ({
        name: table.tableName,
        comment: table.remarks,
      })),
    );
  } catch {
    if (seq === completionSeq) {
      setCompletionTables([]);
    }
  }
}

// ==================== 编辑器多标签 ====================

interface EditorTab extends EditorContent {
  revision?: number;
  currentVersionId?: DataforgeDevApi.Id | null;
  key: string;
  title: string;
  taskId?: DataforgeDevApi.Id;
  taskType?: DataforgeDevApi.TaskType;
  programConfig?: DataforgeDevApi.ProgramConfig | null;
  executionCapability?: DataforgeDevApi.Task['executionCapability'];
  executionCapabilityReason?: string;
  dbType?: string;
  status?: number;
  scheduleStatus?: number;
  scheduleSyncStatus?: DataforgeDevApi.Task['scheduleSyncStatus'];
  scheduleSyncReason?: DataforgeDevApi.Task['scheduleSyncReason'];
  legacyCronExpression?: DataforgeDevApi.Task['legacyCronExpression'];
  instanceUnconfirmed?: boolean;
  instanceBusinessDate?: string;
  latestVersion?: number;
  latestRecord?: DataforgeDevApi.Record;
  acceptedRecordId?: DataforgeDevApi.Id;
}

let scratchSeq = 0;

const activeTab = computed(() =>
  openTabs.value.find((tab) => tab.key === activeKey.value),
);
const canEditDefinition = computed(
  () => canManage.value && activeTab.value?.status !== 1,
);
const activeDirty = computed(() =>
  activeTab.value ? hasUnsavedContent([activeTab.value]) : false,
);
const editorLanguage = computed(() => {
  const programType = getDevProgramType(activeTab.value?.taskType);
  if (programType === 'PYTHON') return 'python';
  if (programType === 'JAR') return 'plaintext';
  return resolveSqlLanguage(activeTab.value?.dbType, activeTab.value?.taskType);
});
const activeIsShell = computed(() => activeTab.value?.taskType === 'SHELL');
const activeIsNativeNotReady = computed(() =>
  activeTab.value ? isNativeProgramNotReady(activeTab.value) : false,
);
const activeIsNative = computed(() =>
  activeTab.value ? isNativeProgramTask(activeTab.value) : false,
);
const canUseNative = computed(
  () =>
    !activeIsNative.value ||
    hasAccessByCodes(['dataforge:dev-task:execute-native']),
);
const canInspectRunLog = computed(
  () =>
    (!activeIsNative.value && !activeTab.value?.latestRecord?.nativeProgram) ||
    hasAccessByCodes(['dataforge:dev-task:execute-native']),
);
const activeProgramType = computed(() =>
  getDevProgramType(activeTab.value?.taskType),
);
const activeProgramLabel = computed(() => {
  if (!activeTab.value) return '-';
  if (activeIsShell.value) return 'Shell';
  return activeProgramType.value === 'PYTHON'
    ? 'Python'
    : activeProgramType.value;
});
const activeRuntimeLabel = computed(() => {
  const task = activeTab.value;
  if (!task) return '-';
  return resolveWorkbenchRuntimeLabel(
    task.taskType,
    task.dbType,
    task.programConfig?.workloadMode,
  );
});

async function openTask(taskId: DataforgeDevApi.Id, forceReload = false) {
  const key = `task-${taskId}`;
  const exists = openTabs.value.find((tab) => tab.key === key);
  if (exists && !forceReload) {
    activeKey.value = key;
    return;
  }
  const seq = ++openSeq;
  openingTask.value = true;
  taskLoadError.value = '';
  let task: DataforgeDevApi.Task;
  try {
    task = await getDevTask(taskId);
  } catch {
    if (seq === openSeq) taskLoadError.value = '任务配置加载失败，请重试。';
    return;
  } finally {
    if (seq === openSeq) openingTask.value = false;
  }
  if (seq !== openSeq) return;
  taskDetails.value = { ...taskDetails.value, [String(taskId)]: task };
  expandTaskPath(task.dirId);
  const tab: EditorTab = {
    key,
    title: task.name ?? `任务${taskId}`,
    taskId,
    revision: task.revision,
    currentVersionId: task.currentVersionId,
    taskType: task.taskType ?? 'SQL',
    programConfig: task.programConfig,
    executionCapability: task.executionCapability,
    executionCapabilityReason: task.executionCapabilityReason,
    content: task.content ?? '',
    savedContent: task.content ?? '',
    databaseId: normalizeEditorDatabaseId(task.databaseId),
    dbType: task.dbType,
    status: task.status,
    scheduleStatus: task.scheduleStatus,
    scheduleSyncStatus: task.scheduleSyncStatus,
    scheduleSyncReason: task.scheduleSyncReason,
    legacyCronExpression: task.legacyCronExpression,
    latestVersion: task.latestVersion,
    latestRecord: task.latestRecord,
    maxRows: task.maxRows,
    timeoutSeconds: task.timeoutSeconds,
    executionConfig: task.executionConfig ?? defaultExecutionConfig(),
    savedConfig: {
      databaseId: normalizeEditorDatabaseId(task.databaseId),
      maxRows: task.maxRows,
      timeoutSeconds: task.timeoutSeconds,
      executionConfig: JSON.parse(
        JSON.stringify(task.executionConfig ?? defaultExecutionConfig()),
      ),
      programConfig:
        task.programConfig === null || task.programConfig === undefined
          ? task.programConfig
          : JSON.parse(JSON.stringify(task.programConfig)),
    },
  };
  if (exists) {
    Object.assign(exists, tab, mergeEditorSnapshot(exists, tab));
  } else {
    openTabs.value.push(tab);
  }
  activeKey.value = key;
  refreshCompletion(activeTab.value?.databaseId);
}

function openScratch() {
  if (!canManage.value) return;
  scratchSeq += 1;
  const key = `scratch-${scratchSeq}`;
  openTabs.value.push({
    key,
    title: `临时脚本${scratchSeq}`,
    ...newDevelopmentTask(props.workloadMode),
    content: '',
    savedContent: '',
    databaseId:
      props.workloadMode === 'STREAMING'
        ? undefined
        : databases.value[0]?.value,
    savedConfig: {
      databaseId:
        props.workloadMode === 'STREAMING'
          ? undefined
          : databases.value[0]?.value,
    },
  });
  activeKey.value = key;
  refreshCompletion(databases.value[0]?.value);
}

function closeTab(key: string, force = false) {
  const tab = openTabs.value.find((item) => item.key === key);
  if (!tab) {
    return;
  }
  const doClose = () => {
    const index = openTabs.value.findIndex((item) => item.key === key);
    openTabs.value.splice(index, 1);
    if (activeKey.value === key) {
      activeKey.value = openTabs.value[Math.max(0, index - 1)]?.key ?? '';
    }
  };
  if (!force && hasUnsavedContent([tab])) {
    Modal.confirm({
      title: '系统提示',
      content: `任务"${tab.title}"有未保存的修改，确认关闭吗？`,
      onOk: doClose,
    });
    return;
  }
  doClose();
}

function handleTabChange(key: number | string) {
  activeKey.value = String(key);
  refreshCompletion(activeTab.value?.databaseId);
}

function handleDatabaseChange(databaseId: DataforgeDevApi.Id) {
  if (!activeTab.value || !canEditDefinition.value) return;
  activeTab.value.databaseId = databaseId;
  const database = databases.value.find(
    (item) => String(item.value) === String(databaseId),
  );
  activeTab.value.dbType = database?.productId ?? activeTab.value.dbType;
  void refreshCompletion(databaseId);
}

// ==================== 属性配置面板 ====================

/** 右侧「版本信息」：打开当前任务的版本抽屉 */
function handleOpenVersions() {
  const taskId = activeTab.value?.taskId;
  if (!taskId) {
    message.warning('请先打开一个任务再查看版本信息');
    return;
  }
  versionModalRef.value?.open(taskId);
}

const showProps = ref(false);
const showLineage = ref(false);
const showMinimap = ref(false);
const editorStatus = ref<SqlEditorStatus>();
const syntaxStatusLabel = computed(() => {
  const count = editorStatus.value?.syntaxErrors;
  if (count === undefined) return '语法未检测';
  return count > 0 ? `语法错误 ${count}` : '语法检测通过';
});
const panelTask = computed<DataforgeDevApi.Task>(() => ({
  ...activeDetail.value,
  taskType: activeTab.value?.taskType,
  content: activeTab.value?.content,
  databaseId: activeTab.value?.databaseId,
  dbType: activeTab.value?.dbType,
  executionConfig: activeTab.value?.executionConfig,
  programConfig: activeTab.value?.programConfig,
  timeoutSeconds: activeTab.value?.timeoutSeconds,
  maxRows: activeTab.value?.maxRows,
}));
function toggleProperties() {
  showProps.value = !showProps.value;
  showLineage.value = false;
}
function toggleLineage() {
  showLineage.value = !showLineage.value;
  showProps.value = false;
}
async function savePanel(patch: Partial<DataforgeDevApi.Task>) {
  const tab = activeTab.value;
  if (!tab?.taskId || !canEditDefinition.value || saving.value) return;
  const scope = contextSeq;
  saving.value = true;
  try {
    const task = await getDevTask(tab.taskId);
    if (
      scope !== contextSeq ||
      activeTab.value !== tab ||
      !canEditDefinition.value
    )
      return;
    await updateDevTask({ ...task, ...patch, expectedRevision: tab.revision });
    if (scope !== contextSeq) return;
    await openTask(tab.taskId, true);
    void loadResources();
    message.success('配置已保存');
  } finally {
    if (scope === contextSeq) saving.value = false;
  }
}

// ==================== 保存 / 上下线 ====================

const saving = ref(false);
const changingStatus = ref(false);
/** 临时脚本转正式任务时待关闭的标签 */
let pendingScratchKey: string | undefined;

async function handleSave() {
  const tab = activeTab.value;
  if (
    !tab ||
    saving.value ||
    changingStatus.value ||
    !canEditDefinition.value ||
    !hasAccessByCodes(['dataforge:dev-task:save'])
  )
    return;
  if (!tab.taskId) {
    // 临时脚本转正式任务：保存成功后关闭临时标签
    pendingScratchKey = tab.key;
    taskFormRef.value?.open(undefined, selectedDirId(), {
      content: tab.content,
      databaseId: tab.databaseId,
      taskType: tab.taskType,
      programConfig: tab.programConfig,
    });
    return;
  }
  saving.value = true;
  const scope = contextSeq;
  const expectedRevision = tab.revision;
  try {
    await saveEditorSnapshot(tab, async (snapshot) => {
      const task = await getDevTask(tab.taskId!);
      if (scope !== contextSeq) return;
      await updateDevTask({
        ...task,
        ...snapshot,
        scheduler: 'DOLPHIN_SCHEDULER',
        expectedRevision,
      });
    });
    if (scope !== contextSeq) return;
    // 刷新版本号等元数据（脚本变化时后端已生成新版本）
    const latest = await getDevTask(tab.taskId);
    if (scope !== contextSeq) return;
    taskDetails.value = { ...taskDetails.value, [String(tab.taskId)]: latest };
    tab.latestVersion = latest.latestVersion;
    tab.status = latest.status;
    tab.scheduleStatus = latest.scheduleStatus;
    tab.scheduleSyncStatus = latest.scheduleSyncStatus;
    tab.scheduleSyncReason = latest.scheduleSyncReason;
    tab.legacyCronExpression = latest.legacyCronExpression;
    tab.latestRecord = latest.latestRecord;
    tab.revision = latest.revision;
    tab.currentVersionId = latest.currentVersionId;
    tab.executionCapability = latest.executionCapability;
    tab.executionCapabilityReason = latest.executionCapabilityReason;
    tab.dbType = latest.dbType;
    Object.assign(
      tab,
      mergeEditorSnapshot(tab, {
        content: latest.content ?? '',
        databaseId: normalizeEditorDatabaseId(latest.databaseId),
        maxRows: latest.maxRows,
        timeoutSeconds: latest.timeoutSeconds,
        executionConfig: latest.executionConfig ?? defaultExecutionConfig(),
        programConfig: latest.programConfig,
      }),
    );
    message.success('保存成功');
    loadResources();
  } finally {
    saving.value = false;
  }
}

/** 任务信息保存回调：刷新资源树，重载/打开任务标签，清理临时脚本标签 */
async function handleTaskSaved(taskId: DataforgeDevApi.Id, configure = true) {
  loadResources();
  if (!configure) return;
  if (pendingScratchKey) {
    closeTab(pendingScratchKey, true);
    pendingScratchKey = undefined;
  }
  await openTask(taskId, true);
}

async function handleToggleStatus() {
  const tab = activeTab.value;
  if (
    !tab?.taskId ||
    saving.value ||
    changingStatus.value ||
    !canManage.value
  ) {
    return;
  }
  const scope = contextSeq;
  const newStatus = tab.status === 1 ? 0 : 1;
  if (
    newStatus === 1 &&
    (!tab.currentVersionId || isNativeProgramNotReady(tab))
  )
    return;
  changingStatus.value = true;
  try {
    await updateDevTaskStatus(tab.taskId, newStatus);
    if (scope !== contextSeq) return;
    const latest = await getDevTask(tab.taskId);
    if (scope !== contextSeq || !openTabs.value.includes(tab)) return;
    taskDetails.value = { ...taskDetails.value, [String(tab.taskId)]: latest };
    tab.status = latest.status;
    tab.scheduleStatus = latest.scheduleStatus;
    tab.scheduleSyncStatus = latest.scheduleSyncStatus;
    tab.scheduleSyncReason = latest.scheduleSyncReason;
    tab.revision = latest.revision;
    message.success(newStatus === 1 ? '任务已启用' : '任务已停用');
    void loadResources();
  } finally {
    changingStatus.value = false;
  }
}

async function handleScheduleSaved() {
  const tab = activeTab.value;
  if (!tab?.taskId) return;
  const scope = contextSeq;
  const latest = await getDevTask(tab.taskId);
  if (scope !== contextSeq || !openTabs.value.includes(tab)) return;
  taskDetails.value = { ...taskDetails.value, [String(tab.taskId)]: latest };
  tab.latestVersion = latest.latestVersion;
  tab.currentVersionId = latest.currentVersionId;
  tab.revision = latest.revision;
  tab.status = latest.status;
  tab.scheduleStatus = latest.scheduleStatus;
  tab.scheduleSyncStatus = latest.scheduleSyncStatus;
  tab.scheduleSyncReason = latest.scheduleSyncReason;
  tab.legacyCronExpression = latest.legacyCronExpression;
  Object.assign(
    tab,
    mergeEditorSnapshot(tab, {
      content: latest.content ?? '',
      databaseId: normalizeEditorDatabaseId(latest.databaseId),
      maxRows: latest.maxRows,
      timeoutSeconds: latest.timeoutSeconds,
      executionConfig: latest.executionConfig ?? defaultExecutionConfig(),
      programConfig: latest.programConfig,
    }),
  );
  void loadResources();
}

const instanceCommands = useRunCommands();
const submittingInstance = instanceCommands.busy;
const stoppingInstance = ref(false);
const canStopInstance = computed(
  () =>
    canOperate.value &&
    canUseNative.value &&
    !!activeTab.value?.latestRecord?.id &&
    activeRunStatuses.has(activeTab.value.latestRecord.status || '') &&
    activeTab.value.latestRecord.status !== 'CANCEL_REQUESTED',
);
function stopInstance() {
  const record = activeTab.value?.latestRecord;
  if (!canStopInstance.value || !record?.id || stoppingInstance.value) return;
  const scope = contextSeq;
  Modal.confirm({
    title: '停止任务实例',
    content: '确认向当前运行实例发送停止请求？执行状态将在运行日志中更新。',
    async onOk() {
      if (scope !== contextSeq || !canOperate.value) return;
      stoppingInstance.value = true;
      try {
        await stopDevRecord(record.id!);
        if (scope !== contextSeq) return;
        message.success('停止请求已受理');
        await refreshRunLog();
      } finally {
        if (scope === contextSeq) stoppingInstance.value = false;
      }
    },
  });
}

async function handleRunAsInstance() {
  const tab = activeTab.value;
  if (
    !tab?.taskId ||
    tab.status !== 1 ||
    submittingInstance.value ||
    !canOperate.value ||
    !canUseNative.value ||
    tab.acceptedRecordId !== undefined ||
    activeRunStatuses.has(tab.latestRecord?.status ?? '')
  ) {
    return;
  }
  if (activeDirty.value)
    message.info('本次执行当前已保存版本，未保存的脚本与配置不参与运行');
  try {
    if (!tab.currentVersionId) {
      message.warning(
        isNativeProgramNotReady(tab)
          ? tab.executionCapabilityReason ||
              '此任务尚无可运行版本，请完善受管部署并保存'
          : '请先保存完整任务',
      );
      return;
    }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(businessDate.value)) {
      message.warning('业务日期格式应为 YYYY-MM-DD');
      return;
    }
    const recordId = await submitTaskRun(tab, businessDate.value, (payload) =>
      instanceCommands.execute(`dev:run:${tab.taskId}`, payload, runDevTask),
    );
    if (recordId === undefined || !openTabs.value.includes(tab)) return;
    tab.instanceUnconfirmed = false;
    tab.acceptedRecordId = recordId;
    if (!editorActive.value) return;
    selectedExecutionId.value = recordId;
    bottomMode.value = 'log';
    bottomCollapsed.value = false;
    scheduleStatusPolling();
    await Promise.allSettled([refreshRunLog()]);
  } catch {
    if (openTabs.value.includes(tab) && tab.acceptedRecordId === undefined)
      tab.instanceUnconfirmed = true;
  }
}

// ==================== 运行日志面板（高度拖拽 / 收起） ====================

const bottomHeight = ref(340);
const bottomMode = ref<'history' | 'log' | 'results'>('log');
const selectedExecutionId = ref<DataforgeDevApi.Id>();
function toggleBottom(mode: 'history' | 'log' | 'results') {
  bottomCollapsed.value =
    bottomMode.value === mode ? !bottomCollapsed.value : false;
  bottomMode.value = mode;
}
function showExecutionLog(id: DataforgeDevApi.Id) {
  selectedExecutionId.value = id;
  bottomMode.value = 'log';
  bottomCollapsed.value = false;
}
watch(activeKey, () => {
  selectedExecutionId.value = undefined;
  editorStatus.value = undefined;
});
const bottomCollapsed = ref(true);
const propertiesWidth = ref(340);

/** 面板尺寸由现有 Splitter 管理，拖动时只保存当前可见尺寸。 */
function resizeConsole(sizes: number[]) {
  if (!bottomCollapsed.value && sizes[1] !== undefined)
    bottomHeight.value = sizes[1];
}
function resizeProperties(sizes: number[]) {
  if ((showProps.value || showLineage.value) && sizes[1] !== undefined)
    propertiesWidth.value = sizes[1];
}

const logRefreshGuard = createLatestWins();
const refreshingLog = ref(false);
const editorActive = ref(true);
const activeRunStatuses = new Set([
  'CANCEL_REQUESTED',
  'RUNNING',
  'SUBMITTING',
  'UNKNOWN',
  'WAITING',
]);
let statusTimer: ReturnType<typeof setTimeout> | undefined;
function stopStatusPolling() {
  if (statusTimer) clearTimeout(statusTimer);
  statusTimer = undefined;
  logRefreshGuard.invalidate();
  refreshingLog.value = false;
}
function scheduleStatusPolling() {
  if (statusTimer) clearTimeout(statusTimer);
  statusTimer = undefined;
  const tab = activeTab.value;
  if (
    !editorActive.value ||
    !tab?.taskId ||
    (!tab.acceptedRecordId &&
      !activeRunStatuses.has(tab.latestRecord?.status ?? ''))
  )
    return;
  statusTimer = setTimeout(
    async () => {
      statusTimer = undefined;
      try {
        await refreshRunLog();
      } catch {
        /* 统一请求层已展示读取错误，后续按真实状态重试。 */
      }
      scheduleStatusPolling();
    },
    tab.latestRecord?.status === 'UNKNOWN' ? 5000 : 2000,
  );
}
watch(
  () => [
    activeKey.value,
    activeTab.value?.acceptedRecordId,
    activeTab.value?.latestRecord?.status,
  ],
  scheduleStatusPolling,
);
onActivated(() => {
  editorActive.value = true;
  if (activeTab.value?.taskId)
    void refreshRunLog()
      .catch(() => undefined)
      .finally(scheduleStatusPolling);
});
onDeactivated(() => {
  editorActive.value = false;
  stopStatusPolling();
  instanceModalRef.value?.close();
});
watch(
  activeKey,
  () => {
    logRefreshGuard.invalidate();
    refreshingLog.value = false;
  },
  { flush: 'sync' },
);

/** 只刷新运行信息，保留当前编辑的脚本与配置。 */
async function refreshRunLog() {
  const tab = activeTab.value;
  if (!tab?.taskId || refreshingLog.value) return;
  const request = logRefreshGuard.next();
  const scope = contextSeq;
  refreshingLog.value = true;
  try {
    const latest = await getDevTask(tab.taskId);
    if (
      request.isCurrent() &&
      scope === contextSeq &&
      activeTab.value === tab
    ) {
      tab.latestRecord = latest.latestRecord;
      if (String(latest.latestRecord?.id) === String(tab.acceptedRecordId))
        tab.acceptedRecordId = undefined;
      taskDetails.value = {
        ...taskDetails.value,
        [String(tab.taskId)]: latest,
      };
      tab.scheduleSyncStatus = latest.scheduleSyncStatus;
      tab.scheduleSyncReason = latest.scheduleSyncReason;
    }
  } finally {
    if (request.isCurrent()) refreshingLog.value = false;
  }
}

function openRunRecords() {
  if (
    activeTab.value?.taskId &&
    canInspectRunLog.value &&
    hasAccessByCodes(['dataforge:dev-task:query'])
  )
    toggleBottom('history');
}

/** 浏览器刷新/关闭时提示未保存的修改 */
function handleBeforeUnload(event: BeforeUnloadEvent) {
  const hasDirty = hasUnsavedContent(openTabs.value);
  if (hasDirty) {
    event.preventDefault();
  }
}

onMounted(() => {
  void loadWorkspace();
  if (props.taskId !== undefined) void openTask(props.taskId);
  window.addEventListener('beforeunload', handleBeforeUnload);
});

onBeforeUnmount(() => {
  editorActive.value = false;
  stopStatusPolling();
  logRefreshGuard.invalidate();
  instanceModalRef.value?.close();
  ++contextSeq;
  ++openSeq;
  ++completionSeq;
  setCompletionTables([]);
  setColumnLoader(null);
  openTabs.value = [];
  setCompletionTables([]);
  setColumnLoader(null);
  window.removeEventListener('beforeunload', handleBeforeUnload);
});
</script>

<template>
  <Page class="dev-workbench-page" auto-content-height>
    <div class="dev-workbench-shell">
      <div class="dev-workbench">
        <nav class="dev-workbench__rail" aria-label="开发工具">
          <button
            type="button"
            aria-label="切换资源目录"
            :aria-expanded="directoryVisible"
            @click="directoryVisible = !directoryVisible"
          >
            <img :src="editorAsset('rail-grid')" alt="" />
          </button>
          <button
            type="button"
            aria-label="新建任务"
            :disabled="!canManage"
            @click="taskInfoRef?.open()"
          >
            <img :src="editorAsset('rail-code')" alt="" />
          </button>
          <button
            type="button"
            aria-label="属性配置"
            :aria-expanded="showProps"
            @click="toggleProperties"
          >
            <img :src="editorAsset('rail-link')" alt="" />
          </button>
          <button
            type="button"
            aria-label="任务详情"
            @click="detailVisible = true"
          >
            <img :src="editorAsset('rail-eye')" alt="" />
          </button>
          <span class="dev-workbench__rail-spacer"></span>
          <button
            type="button"
            aria-label="切换日志控制台"
            @click="toggleBottom('log')"
          >
            <img :src="editorAsset('rail-console')" alt="" />
          </button>
          <button
            type="button"
            aria-label="查询结果"
            :aria-expanded="!bottomCollapsed && bottomMode === 'results'"
            @click="toggleBottom('results')"
          >
            <img :src="editorAsset('rail-results')" alt="" />
          </button>
          <button type="button" aria-label="执行历史" @click="openRunRecords">
            <img :src="editorAsset('rail-history')" alt="" />
          </button>
        </nav>
        <Splitter
          class="dev-workbench__workspace"
          :style="{
            flex: '1 1 0px',
            width: 0,
            minWidth: 0,
            maxWidth: '100%',
            minHeight: 0,
          }"
          :styles="{
            panel: {
              minWidth: 0,
              maxWidth: '100%',
              minHeight: 0,
              overflow: 'hidden',
            },
          }"
          orientation="vertical"
          @resize="resizeConsole"
        >
          <SplitterPanel :min="180">
            <div class="dev-workbench__upper">
              <!-- 左侧资源树 -->
              <Card
                v-show="directoryVisible"
                class="dev-workbench__aside h-full w-1/6 shrink-0"
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
                  <div
                    class="dev-workbench__resource-search mb-2 flex shrink-0 items-center gap-2"
                  >
                    <Input
                      v-model:value="treeFilter"
                      allow-clear
                      class="min-w-0 flex-1"
                      placeholder="请输入数据开发或数据查询信息"
                      @change="handleTreeFilter"
                    >
                      <template #prefix>
                        <IconifyIcon class="size-4" icon="lucide:search" />
                      </template>
                    </Input>
                    <TreeToolbar
                      class="dev-workbench__tree-tools"
                      :expanded="isExpanded"
                      :loading="resourcesLoading"
                      @toggle-expand="toggleExpandAll"
                      @refresh="loadResources"
                    />
                    <Tooltip title="新建">
                      <Dropdown :trigger="['click']">
                        <Button class="dev-workbench__new-resource">
                          <template #icon>
                            <IconifyIcon icon="lucide:plus" />
                          </template>
                        </Button>
                        <template #popupRender>
                          <Menu>
                            <Menu.Item
                              v-if="canManage"
                              key="task"
                              @click="
                                taskFormRef?.open(undefined, selectedDirId())
                              "
                            >
                              <template #icon>
                                <IconifyIcon icon="lucide:plus" />
                              </template>
                              新建任务
                            </Menu.Item>
                            <Menu.Item
                              v-if="canManage"
                              key="dir"
                              @click="
                                dirFormRef?.open(undefined, selectedDirId())
                              "
                            >
                              <template #icon>
                                <IconifyIcon icon="lucide:folder-plus" />
                              </template>
                              新建目录
                            </Menu.Item>
                            <Menu.Item
                              v-if="canManage"
                              key="scratch"
                              @click="openScratch"
                            >
                              <template #icon>
                                <IconifyIcon icon="lucide:file-plus" />
                              </template>
                              临时脚本
                            </Menu.Item>
                          </Menu>
                        </template>
                      </Dropdown>
                    </Tooltip>
                  </div>
                  <div
                    class="subtle-tree-scrollbar min-h-0 flex-1 overflow-y-auto overflow-x-hidden"
                  >
                    <Tree
                      v-if="resourceTree.length > 0"
                      v-model:expanded-keys="expandedKeys as any"
                      v-model:selected-keys="selectedKeys as any"
                      :tree-data="resourceTree as any"
                      :auto-expand-parent="false"
                      :load-data="loadConnectionTables"
                      block-node
                      class="pt-2"
                      @dblclick="handleTreeDoubleClick"
                    >
                      <template #titleRender="node">
                        <span class="dev-workbench__node">
                          <TaskTypeIcon
                            v-if="
                              (node as ResourceNode).isLeaf ||
                              (node as ResourceNode).databaseId !== undefined
                            "
                            :task-type="(node as ResourceNode).taskType"
                            :db-type="(node as ResourceNode).dbType"
                            :database-icon-path="
                              props.databaseIconPaths?.[
                                normalizeDevDatabaseType(
                                  (node as ResourceNode).dbType,
                                )
                              ]
                            "
                            :engine="(node as ResourceNode).engine"
                            class="dev-workbench__task-icon shrink-0"
                          />
                          <img
                            v-else
                            class="dev-workbench__dir-icon shrink-0"
                            :src="
                              '/assets/qdata-integration/' + 'icon-folder.png'
                            "
                            alt=""
                          />
                          <span class="dev-workbench__node-title">
                            {{ (node as any).title }}
                          </span>
                          <Tag
                            v-if="
                              (node as any).isLeaf && (node as any).status === 1
                            "
                            color="success"
                          >
                            上线
                          </Tag>
                          <Dropdown
                            v-if="
                              (node as ResourceNode).taskId !== undefined ||
                              (node as ResourceNode).dirId !== undefined
                            "
                            :trigger="['hover']"
                            placement="bottomRight"
                          >
                            <span
                              class="dev-workbench__node-more"
                              @click.stop
                              @dblclick.stop
                            >
                              <IconifyIcon icon="lucide:ellipsis" />
                            </span>
                            <template #popupRender>
                              <Menu>
                                <Menu.Item
                                  v-if="canManage"
                                  v-access:code="'dataforge:dev-task:save'"
                                  key="edit"
                                  @click="handleNodeEdit(node)"
                                  ><template #icon
                                    ><IconifyIcon icon="lucide:edit"
                                  /></template>
                                  编辑
                                </Menu.Item>
                                <Menu.Item
                                  v-if="canManage"
                                  v-access:code="'dataforge:dev-task:save'"
                                  key="move"
                                  @click="handleNodeMove(node)"
                                  ><template #icon
                                    ><IconifyIcon icon="lucide:folder-input"
                                  /></template>
                                  移动
                                </Menu.Item>
                                <Menu.Item
                                  key="delete"
                                  v-if="canManage"
                                  v-access:code="'dataforge:dev-task:delete'"
                                  danger
                                  @click="handleNodeDelete(node)"
                                  ><template #icon
                                    ><IconifyIcon icon="lucide:trash-2"
                                  /></template>
                                  删除
                                </Menu.Item>
                              </Menu>
                            </template>
                          </Dropdown>
                        </span>
                      </template>
                    </Tree>
                    <div v-else class="py-4 text-center text-xs text-gray-500">
                      暂无任务，点击右上角 + 新建
                    </div>
                  </div>
                  <div class="text-muted-foreground mt-2 shrink-0 text-xs">
                    双击任务打开编辑器
                  </div>
                </div>
              </Card>

              <!-- 右侧编辑区 -->
              <div class="dev-workbench__main">
                <template v-if="openTabs.length > 0">
                  <!-- 编辑器标签页 -->
                  <Tabs
                    :active-key="activeKey"
                    :hide-add="!canManage"
                    class="dev-workbench__tabs"
                    size="small"
                    type="editable-card"
                    @change="handleTabChange"
                    @edit="
                      (key: any, action: string) =>
                        action === 'remove'
                          ? closeTab(String(key))
                          : canManage && taskInfoRef?.open()
                    "
                  >
                    <TabPane
                      v-for="tab in openTabs"
                      :key="tab.key"
                      :closable="true"
                    >
                      <template #tab>
                        <span
                          class="dev-workbench__tab-title"
                          title="右键查看任务版本"
                          @contextmenu.prevent="handleOpenVersions"
                        >
                          <TaskTypeIcon
                            :task-type="tab.taskType"
                            :db-type="tab.dbType"
                            :database-icon-path="
                              props.databaseIconPaths?.[
                                normalizeDevDatabaseType(tab.dbType)
                              ]
                            "
                            :size="16"
                          />
                          {{ tab.title }}
                          <span
                            v-if="hasUnsavedContent([tab])"
                            class="text-orange-500"
                          >
                            •
                          </span>
                        </span>
                      </template>
                    </TabPane>
                  </Tabs>

                  <header
                    class="dev-workbench__toolbar"
                    aria-label="任务编辑工具栏"
                  >
                    <Button
                      type="text"
                      :disabled="
                        !canEditDefinition || !activeTab || changingStatus
                      "
                      :loading="saving"
                      @click="handleSave"
                    >
                      <img :src="editorAsset('save')" alt="" />保存
                    </Button>
                    <Button
                      type="text"
                      :disabled="
                        !canOperate ||
                        !canUseNative ||
                        !activeTab?.currentVersionId ||
                        activeTab.status !== 1 ||
                        activeIsNativeNotReady ||
                        activeTab.acceptedRecordId !== undefined ||
                        activeRunStatuses.has(
                          activeTab.latestRecord?.status ?? '',
                        )
                      "
                      :loading="submittingInstance"
                      @click="handleRunAsInstance"
                    >
                      <img :src="editorAsset('run')" alt="" />运行
                    </Button>
                    <Tooltip title="当前引擎未提供在线调试">
                      <Button type="text" disabled>
                        <img :src="editorAsset('debug')" alt="" />调试
                      </Button>
                    </Tooltip>
                    <Button
                      type="text"
                      :disabled="!canStopInstance"
                      :loading="stoppingInstance"
                      @click="stopInstance"
                    >
                      <img :src="editorAsset('stop')" alt="" />停止
                    </Button>
                    <Button
                      type="text"
                      :disabled="
                        !canEditDefinition ||
                        !activeTab ||
                        activeProgramType !== 'SQL'
                      "
                      @click="editorRef?.format()"
                    >
                      <img :src="editorAsset('format')" alt="" />格式化
                    </Button>
                    <Button
                      type="text"
                      :disabled="!activeTab?.taskId"
                      @click="openTaskConfiguration"
                    >
                      <img :src="editorAsset('config')" alt="" />{{
                        readOnly ? '任务详情' : '配置'
                      }}
                    </Button>
                    <Button
                      type="text"
                      :disabled="
                        !canManage ||
                        !activeTab?.taskId ||
                        workloadMode === 'STREAMING'
                      "
                      @click="scheduleFormRef?.open(activeTab?.taskId!)"
                    >
                      <img :src="editorAsset('schedule')" alt="" />调度周期
                    </Button>
                    <Tooltip title="当前工作空间尚未配置 AI 助手">
                      <Button type="text" disabled>
                        <img :src="editorAsset('ai')" alt="" />AI助手
                      </Button>
                    </Tooltip>
                    <Button
                      v-if="taskId !== undefined"
                      class="dev-workbench__return"
                      type="text"
                      @click="returnToTasks"
                    >
                      返回列表
                    </Button>
                  </header>
                  <div class="dev-workbench__canvas dev-workbench__editing">
                    <Splitter
                      class="dev-workbench__editing-splitter"
                      :style="{
                        flex: '1 1 0px',
                        width: 0,
                        minWidth: 0,
                        maxWidth: 'calc(100% - 54px)',
                      }"
                      :styles="{
                        panel: {
                          minWidth: 0,
                          maxWidth: '100%',
                          minHeight: 0,
                          overflow: 'hidden',
                        },
                      }"
                      @resize="resizeProperties"
                    >
                      <SplitterPanel :min="200">
                        <div class="dev-workbench__editor">
                          <div
                            v-if="
                              activeProgramType === 'JAR' ||
                              activeProgramType === 'PYTHON'
                            "
                            class="flex h-full flex-col items-center justify-center gap-3 text-muted-foreground"
                          >
                            <span>{{
                              activeProgramType === 'JAR'
                                ? 'JAR 程序执行所选受管制品与入口类。'
                                : '执行所选受管 Python 制品，编辑器文本不参与运行。'
                            }}</span>
                            <Button
                              v-if="canEditDefinition && activeTab?.taskId"
                              @click="taskFormRef?.open(activeTab.taskId)"
                            >
                              <template #icon>
                                <IconifyIcon icon="lucide:settings" />
                              </template>
                              编辑制品与运行配置
                            </Button>
                          </div>
                          <SqlEditor
                            v-else-if="activeTab"
                            :key="activeTab.key"
                            ref="editorRef"
                            v-model="activeTab.content"
                            :language="editorLanguage"
                            :readonly="!canEditDefinition"
                            :minimap="showMinimap"
                            :tab-size="4"
                            @status-change="editorStatus = $event"
                            height="100%"
                            @save="handleSave"
                          />
                        </div>
                      </SplitterPanel>
                      <SplitterPanel
                        v-if="showProps || showLineage"
                        :size="propertiesWidth"
                        :min="300"
                        max="65%"
                      >
                        <WorkbenchPropertiesPanel
                          v-if="showProps"
                          :key="activeKey"
                          :task="panelTask"
                          :databases="databases"
                          :readonly="!canEditDefinition"
                          :saving="saving"
                          :workload-mode="workloadMode"
                          @save="savePanel"
                          @cancel="showProps = false"
                          @collapse="showProps = false"
                        />
                        <WorkbenchLineagePanel
                          v-else
                          :key="activeKey"
                          :task="panelTask"
                          :databases="databases"
                          :tasks="tasks"
                          :readonly="!canEditDefinition"
                          :saving="saving"
                          @save="savePanel"
                          @cancel="showLineage = false"
                          @collapse="showLineage = false"
                        />
                      </SplitterPanel>
                    </Splitter>
                    <nav
                      class="dev-workbench__strip"
                      aria-label="编辑器辅助面板"
                    >
                      <button
                        type="button"
                        class="dev-workbench__strip-btn"
                        aria-label="切换属性配置"
                        :aria-expanded="showProps"
                        @click="toggleProperties"
                      >
                        <img
                          :src="
                            editorAsset(
                              showProps ? 'right-props-active' : 'right-props',
                            )
                          "
                          alt=""
                        />
                      </button>
                      <button
                        type="button"
                        class="dev-workbench__strip-btn"
                        aria-label="血缘维护"
                        :aria-expanded="showLineage"
                        @click="toggleLineage"
                      >
                        <img
                          :src="
                            editorAsset(
                              showLineage
                                ? 'right-lineage-active'
                                : 'right-lineage',
                            )
                          "
                          alt=""
                        />
                      </button>
                      <button
                        type="button"
                        class="dev-workbench__strip-btn"
                        aria-label="代码缩略图"
                        :aria-pressed="showMinimap"
                        @click="showMinimap = !showMinimap"
                      >
                        <img
                          :src="
                            editorAsset(
                              showMinimap
                                ? 'right-minimap-active'
                                : 'right-minimap',
                            )
                          "
                          alt=""
                        />
                      </button>
                    </nav>
                  </div>
                </template>

                <!-- 空状态 -->
                <div
                  v-else
                  class="flex h-full flex-col items-center justify-center gap-3"
                >
                  <Empty
                    :description="
                      taskLoadError ||
                      (openingTask
                        ? '正在加载任务配置…'
                        : '打开任务或新建临时脚本开始开发')
                    "
                  />
                  <Button
                    v-if="taskLoadError && taskId !== undefined"
                    @click="openTask(taskId)"
                  >
                    <template #icon>
                      <IconifyIcon icon="lucide:refresh-cw" />
                    </template>
                    重新加载
                  </Button>
                  <Button v-if="taskId !== undefined" @click="returnToTasks">
                    返回列表
                  </Button>
                  <div v-if="canManage" class="flex gap-2">
                    <Button
                      type="primary"
                      @click="taskFormRef?.open(undefined, selectedDirId())"
                    >
                      <template #icon>
                        <IconifyIcon icon="lucide:plus" />
                      </template>
                      新建任务
                    </Button>
                    <Button @click="openScratch">
                      <template #icon>
                        <IconifyIcon icon="lucide:file-plus" /> </template
                      >临时脚本
                    </Button>
                  </div>
                </div>
              </div>
            </div>
          </SplitterPanel>
          <SplitterPanel
            v-if="!bottomCollapsed"
            :size="bottomHeight"
            :min="180"
            max="70%"
          >
            <WorkbenchExecutionPanel
              v-if="bottomMode !== 'results'"
              :mode="bottomMode"
              :task-id="activeTab?.taskId"
              :task-name="activeTab?.title"
              :record-id="selectedExecutionId"
              @collapse="bottomCollapsed = true"
              @open-log="showExecutionLog"
            />
            <section
              v-else
              class="dev-workbench__results"
              aria-label="查询结果"
            >
              <header>
                <h3>查询结果</h3>
                <Button
                  type="text"
                  aria-label="收起查询结果"
                  @click="bottomCollapsed = true"
                >
                  −
                </Button>
              </header>
              <div class="dev-workbench__results-tools">
                <Button disabled>↓ 导出</Button>
              </div>
              <div class="dev-workbench__result-empty">
                <img :src="DEV_REFERENCE_ASSETS.emptyLog" alt="" /><span
                  >暂无数据</span
                >
              </div>
            </section>
          </SplitterPanel>
        </Splitter>
      </div>

      <footer class="dev-workbench__statusbar" aria-label="任务编辑状态栏">
        <span class="dev-workbench__breadcrumb" :title="currentTaskPath">
          <img
            class="dev-workbench__status-icon"
            :src="statusAsset('document')"
            alt=""
          />
          <span class="dev-workbench__breadcrumb-text">{{
            currentTaskPath
          }}</span>
        </span>
        <div class="dev-workbench__status-items">
          <span
            class="dev-workbench__status-item dev-workbench__status-control"
          >
            <Button
              type="text"
              :disabled="!canManage || !activeTab?.taskId"
              :loading="changingStatus"
              :title="
                activeTab?.status === 1 ? '点击下线后编辑' : '点击上线任务'
              "
              @click="handleToggleStatus"
            >
              <span>任务状态:</span>
              <i :class="{ online: activeTab?.status === 1 }"></i>
              <span>{{ activeTab?.status === 1 ? '已上线' : '未上线' }}</span>
            </Button>
          </span>
          <span
            class="dev-workbench__status-item dev-workbench__status-editable"
          >
            <img
              class="dev-workbench__status-icon"
              :src="statusAsset(canEditDefinition ? 'unlocked' : 'locked')"
              alt=""
            />
            <span>{{ canEditDefinition ? '可编辑' : '不可编辑' }}</span>
          </span>
          <span class="dev-workbench__status-item"
            >行 {{ editorStatus?.lineNumber ?? 1 }}，列
            {{ editorStatus?.column ?? 1 }}</span
          >
          <span class="dev-workbench__status-item"
            >{{ editorStatus?.insertSpaces === false ? '制表符' : '空格' }}:
            {{ editorStatus?.tabSize ?? 4 }}</span
          >
          <span class="dev-workbench__status-item">UTF-8</span>
          <span class="dev-workbench__status-item">{{
            editorStatus?.eol ??
            (activeTab?.content.includes('\r\n') ? 'CRLF' : 'LF')
          }}</span>
          <span class="dev-workbench__status-item">{{
            activeProgramLabel
          }}</span>
          <span class="dev-workbench__status-item dev-workbench__status-engine">
            <TaskTypeIcon
              v-if="activeTab"
              :task-type="activeTab.taskType"
              :db-type="activeTab.dbType"
              :size="12"
            />
            <span>{{ activeRuntimeLabel }}</span>
          </span>
          <span
            class="dev-workbench__status-item dev-workbench__status-syntax"
            :class="{
              'dev-workbench__syntax-error':
                (editorStatus?.syntaxErrors ?? 0) > 0,
            }"
          >
            <span
              class="dev-workbench__status-diagnostic-icon"
              aria-hidden="true"
            >
              <img
                v-if="editorStatus?.syntaxErrors === 0"
                class="dev-workbench__status-valid"
                :src="statusAsset('valid')"
                alt=""
              />
            </span>
            <span>{{ syntaxStatusLabel }}</span>
          </span>
        </div>
      </footer>

      <Modal
        v-model:open="detailVisible"
        title="任务详情"
        width="60vw"
        :style="{
          minWidth: 'min(760px, calc(100vw - 32px))',
          maxWidth: 'calc(100vw - 32px)',
        }"
        :styles="{
          container: { borderRadius: '1px' },
          body: { padding: '28px 24px', maxHeight: '74vh', overflowY: 'auto' },
        }"
      >
        <TaskDetailView
          v-if="activeDetail"
          :task="activeDetail"
          :directory-name="currentDirectoryName"
          :owner-name="activeDetail.ownerName"
          presentation="config"
        />
        <template #footer>
          <Button @click="detailVisible = false">关闭</Button>
        </template>
      </Modal>

      <!-- 移动到目录弹窗 -->
      <Modal
        v-model:open="moveVisible"
        :confirm-loading="moveSaving"
        :width="800"
        title="移动到目录"
        @ok="handleMoveOk"
      >
        <div class="mb-2 text-sm">将「{{ moveTarget?.title }}」移动到：</div>
        <TreeSelect
          v-model:value="moveDirId"
          :field-names="{ children: 'children', label: 'name', value: 'id' }"
          :tree-data="moveDirTree as any"
          class="w-full"
          tree-default-expand-all
        />
      </Modal>

      <DirForm ref="dirFormRef" @saved="loadResources" />
      <TaskForm
        ref="taskFormRef"
        :workload-mode="props.workloadMode"
        @saved="handleTaskSaved"
        @cancelled="pendingScratchKey = undefined"
      />
      <TaskInfo
        ref="taskInfoRef"
        :workload-mode="props.workloadMode"
        @saved="handleTaskSaved"
      />
      <InstanceModal ref="instanceModalRef" :read-only="props.readOnly" />
      <ScheduleForm ref="scheduleFormRef" @saved="handleScheduleSaved" />
      <VersionModal
        :can-edit="canManage"
        ref="versionModalRef"
        @changed="
          () => {
            loadResources();
            if (activeTab?.taskId) {
              const taskId = activeTab.taskId;
              closeTab(activeTab.key, true);
              openTask(taskId);
            }
          }
        "
      />
    </div>
  </Page>
</template>

<style scoped>
/* 表格宽度与长日志的固有宽度只在业务区内滚动，不反向撑开页面和工具栏。 */
.dev-workbench-page {
  width: 100%;
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
}

.dev-workbench-shell {
  display: flex;
  flex-direction: column;
  gap: 0;
  width: 100%;
  min-width: 0;
  max-width: 100%;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.dev-workbench__heading {
  display: flex;
  flex-shrink: 0;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  min-height: 62px;
  padding: 12px 16px;
  background: hsl(var(--card));
}

.dev-workbench__identity {
  display: flex;
  gap: 10px;
  align-items: center;
  min-width: 0;
}

.dev-workbench__heading-icon {
  width: 24px;
  height: 24px;
  color: hsl(var(--primary));
}

.dev-workbench__identity-copy {
  min-width: 0;
}

.dev-workbench__identity-copy h2 {
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
}

.dev-workbench__loading {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.dev-workbench__heading-actions {
  display: flex;
  flex-shrink: 0;
  gap: 8px;
  align-items: center;
}

.dev-workbench-shell :deep(.ant-btn) {
  height: 30px;
  font-size: 12px;
  border-radius: 2px;
}

.dev-workbench-shell :deep(.ant-tag) {
  font-size: 11px;
  border-radius: 2px;
}

.dev-workbench {
  display: flex;
  flex: 1;
  gap: 0;
  width: 100%;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}

.dev-workbench__aside {
  width: 226px;
  border: 0;
  border-right: 1px solid #e6e7ed;
  border-radius: 0;
}

.dev-workbench__aside :deep(.ant-tree) {
  font-size: 14px;
  background: transparent;
}

.dev-workbench__aside :deep(.ant-tree-treenode) {
  width: 100%;
}

.dev-workbench__aside :deep(.ant-tree-title) {
  display: block;
  width: 100%;
}

.dev-workbench__main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: hsl(var(--card));
  border-radius: 2px;
}

.dev-workbench__tabs {
  min-height: 33px;
  padding: 0;
}

.dev-workbench__tab-title {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dev-workbench__canvas {
  flex: 1;
  min-height: 0;
}

.dev-workbench__editing {
  display: flex;
  height: 100%;
  min-height: 0;
}

.dev-workbench__editing-splitter {
  flex: 1 1 0;
  width: 0;
  min-width: 0;
}

.dev-workbench__canvas :deep(.ant-splitter-bar-dragger) {
  background: hsl(var(--border));
}

.dev-workbench__tabs :deep(.ant-tabs-nav) {
  margin-bottom: 0;
}

.dev-workbench__editor {
  height: 100%;
  min-height: 0;
  padding: 0;
  overflow: hidden;
  background: hsl(var(--background));
}

.dev-workbench__bottom {
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.dev-workbench__console-summary {
  display: flex;
  flex-shrink: 0;
  gap: 12px;
  align-items: center;
  min-height: 32px;
  padding: 6px 14px;
  font-size: 12px;
  background: hsl(var(--muted) / 45%);
  border-bottom: 1px solid hsl(var(--border));
}

.dev-workbench__console-summary > span:first-child {
  display: inline-flex;
  flex-shrink: 0;
  gap: 6px;
  align-items: center;
  font-weight: 500;
  color: hsl(var(--foreground));
}

.dev-workbench__console-summary > span:first-child :deep(svg) {
  color: hsl(var(--primary));
}

.dev-workbench__console-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  padding: 14px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.dev-workbench__console-tools {
  display: flex;
  gap: 4px;
  align-items: center;
  margin-left: auto;
}

.dev-workbench__run-log {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.dev-workbench__release-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-block: 12px;
}

.dev-workbench__pending-run {
  font-size: 12px;
  color: #b7791f;
}

.dev-workbench__dir-icon {
  width: 16px;
  height: 16px;
  color: #3b82f6;
  object-fit: contain;
}

.dev-workbench__task-icon {
  font-size: 14px;
}

.dev-workbench__aside :deep(.ant-tree .ant-tree-switcher) {
  width: 18px;
  margin-right: 0;
}

.dev-workbench__aside :deep(.ant-tree-indent-unit) {
  width: 14px;
}

.dev-workbench__aside :deep(.ant-tree .ant-tree-node-content-wrapper) {
  flex: 1;
  min-width: 0;
  padding-left: 2px;
}

.dev-workbench__node {
  display: flex;
  gap: 6px;
  align-items: center;
  width: 100%;
  min-width: 0;
}

.dev-workbench__node :deep(.ant-dropdown-trigger) {
  margin-left: auto;
}

.dev-workbench__node-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dev-workbench__node-more {
  display: inline-flex;
  visibility: hidden;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  margin-left: auto;
  color: hsl(var(--muted-foreground));
  border-radius: 4px;
}

.dev-workbench__node-more:hover {
  color: hsl(var(--primary));
  background: hsl(var(--accent));
}

.dev-workbench__aside :deep(.ant-tree-treenode:hover) .dev-workbench__node-more,
.dev-workbench__aside :deep(.ant-tree-node-selected) .dev-workbench__node-more {
  visibility: visible;
}

/* 右侧属性配置面板 */
.dev-workbench__props {
  flex-shrink: 0;
  width: 100%;
  height: 100%;
  padding: 12px;
  overflow-y: auto;
  background: hsl(var(--card));
  border-left: 1px solid hsl(var(--border));
}

.dev-workbench__props-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
}

.dev-workbench__props-section-title {
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 4px 8px;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 500;
  color: hsl(var(--primary));
  background: hsl(var(--primary) / 8%);
  border-radius: 2px;
}

.dev-workbench__props-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  font-size: 13px;
}

.dev-workbench__props-field {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 10px;
}

.dev-workbench__datasource {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 10px 0;
  font-size: 12px;
  white-space: nowrap;
}

.dev-workbench__datasource :deep(.ant-select) {
  flex: 1;
  min-width: 0;
}

.dev-workbench__props-label {
  font-size: 14px;
  color: hsl(var(--muted-foreground));
}

.dev-workbench__props-field .dev-workbench__props-label {
  display: block;
  flex: 0 0 106px;
  text-align: right;
}

.dev-workbench__props-field :deep(.ant-input-number) {
  flex: 1;
  min-width: 0;
}

/* 右侧边缘竖排标签条 */
.dev-workbench__strip {
  display: flex;
  flex: 0 0 54px;
  flex-direction: column;
  gap: 15px;
  align-items: center;
  width: 54px;
  padding-top: 10px;
  background: hsl(var(--card));
  border-left: 1px solid hsl(var(--border));
}

.dev-workbench__strip-btn {
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: center;
  width: 30px;
  height: 30px;
  padding: 0;
  font-size: 12px;
  line-height: 1;
  color: #2f63ff;
  cursor: pointer;
  user-select: none;
  background: transparent;
  border: 0;
  border-radius: 2px;
  transition:
    color 0.2s,
    background-color 0.2s;
}

.dev-workbench__strip-btn:hover {
  color: hsl(var(--primary));
}

.dev-workbench__strip-btn--active {
  color: hsl(var(--primary));
  background: hsl(var(--primary) / 10%);
}

.dev-workbench__strip-text {
  font-size: 10px;
  letter-spacing: 0;
  writing-mode: horizontal-tb;
}

@media (max-width: 1000px) {
  .dev-workbench__heading {
    flex-wrap: wrap;
  }

  .dev-workbench__heading-actions {
    margin-left: auto;
  }
}

@media (max-width: 760px) {
  .dev-workbench-shell {
    min-height: 780px;
  }

  .dev-workbench__aside {
    width: 190px;
  }

  .dev-workbench__identity-copy h2 {
    max-width: 280px;
  }
}

.dev-workbench__rail {
  display: flex;
  flex: 0 0 54px;
  flex-direction: column;
  gap: 12px;
  align-items: center;
  padding: 10px 0 14px;
  background: hsl(var(--card));
  border-right: 1px solid #e6e7ed;
}

.dev-workbench__rail button {
  display: grid;
  place-items: center;
  padding: 0;
  cursor: pointer;
  background: transparent;
  border: 0;
}

.dev-workbench__rail button:disabled {
  cursor: default;
  opacity: 0.45;
}

.dev-workbench__rail button:focus-visible {
  outline: 2px solid #2f63ff;
  outline-offset: 3px;
}

.dev-workbench__rail img {
  width: 28px;
  height: 28px;
  object-fit: contain;
}

.dev-workbench__rail-spacer {
  flex: 1;
}

.dev-workbench__toolbar {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  gap: 5px;
  align-items: center;
  min-height: 33px;
  padding: 2px 10px;
  border-bottom: 1px solid #e6e7ed;
}

.dev-workbench__toolbar :deep(.ant-btn) {
  display: inline-flex;
  gap: 8px;
  align-items: center;
  height: 28px;
  padding: 0 4px;
  margin-right: 12px;
  font-size: 14px;
  color: inherit;
  border: 0;
  box-shadow: none;
}

.dev-workbench__toolbar :deep(.ant-btn:disabled) {
  color: #999;
}

.dev-workbench__toolbar img {
  width: 16px;
  height: 16px;
  object-fit: contain;
}

.dev-workbench__toolbar :deep(.ant-btn:disabled) img {
  opacity: 0.4;
}

.dev-workbench__toolbar .dev-workbench__return {
  margin-left: auto;
  font-size: 12px;
}

.dev-workbench__tabs :deep(.ant-tabs-tab) {
  min-width: 150px;
  max-width: 240px;
  height: 33px;
  margin: 0 !important;
  background: transparent !important;
  border-top: 2px solid transparent !important;
  border-radius: 0 !important;
}

.dev-workbench__tabs :deep(.ant-tabs-tab-active) {
  border-top-color: #2f63ff !important;
}

.dev-workbench__statusbar {
  display: flex;
  flex: 0 0 28px;
  gap: 0;
  align-items: center;
  min-width: 0;
  padding: 0 0 0 10px;
  overflow: auto;
  font-family:
    'Helvetica Neue', Helvetica, 'PingFang SC', 'Hiragino Sans GB',
    'Microsoft YaHei', Arial, sans-serif;
  font-size: 12px;
  line-height: 16px;
  color: #606266;
  white-space: nowrap;
  background: #f9f9f9;
  border-top: 1px solid #e6e7ed;
}

.dev-workbench__breadcrumb {
  display: inline-flex;
  flex: 1;
  gap: 8px;
  align-items: center;
  min-width: 100px;
  padding-right: 10px;
}

.dev-workbench__breadcrumb-text {
  overflow: hidden;
  text-overflow: ellipsis;
}

.dev-workbench__status-items {
  display: inline-flex;
  flex: none;
  align-items: center;
}

.dev-workbench__status-item {
  display: inline-flex;
  flex: none;
  gap: 6px;
  align-items: center;
  height: 16px;
  padding: 0 14px;
  border-left: 1px solid #e0e5ee;
}

.dev-workbench__status-icon {
  flex: none;
  width: 12px;
  height: 12px;
  object-fit: contain;
}

.dev-workbench__status-valid {
  flex: none;
  width: 14px;
  height: 12px;
  object-fit: contain;
}

.dev-workbench__status-syntax {
  min-width: 122px;
}

.dev-workbench__status-editable {
  padding-right: 10px;
  padding-left: 10px;
}

.dev-workbench__status-engine {
  padding-right: 15px;
  padding-left: 15px;
}

.dev-workbench__status-diagnostic-icon {
  display: inline-flex;
  flex: none;
  align-items: center;
  width: 14px;
  height: 12px;
}

.dev-workbench__statusbar i {
  display: inline-block;
  width: 7px;
  height: 7px;
  margin: 0 6px;
  background: #b5bac4;
  border-radius: 50%;
}

.dev-workbench__statusbar i.online {
  background: #67c23a;
}

@media (max-width: 760px) {
  .dev-workbench {
    min-width: 640px;
  }

  .dev-workbench-shell {
    overflow-x: auto;
  }

  .dev-workbench__toolbar :deep(.ant-btn) {
    margin-right: 4px;
  }
}

.dev-workbench__resource-search {
  position: relative;
  padding-top: 33px;
}

.dev-workbench__tree-tools {
  position: absolute;
  top: 0;
  right: 34px;
}

.dev-workbench__new-resource {
  position: absolute;
  top: 0;
  right: 0;
}

.dev-workbench__strip-btn img {
  width: 30px;
  height: 30px;
  object-fit: contain;
}

.dev-workbench__workspace {
  flex: 1;
  width: 0;
  min-width: 0;
  max-width: 100%;
  min-height: 0;
  overflow: hidden;
}

.dev-workbench__workspace :deep(.ant-splitter-panel) {
  min-width: 0;
  max-width: 100%;
  min-height: 0;
  overflow: hidden;
}

.dev-workbench__upper {
  display: flex;
  width: 100%;
  min-width: 0;
  height: 100%;
  min-height: 0;
}

.dev-workbench__syntax-error {
  color: #f56c6c;
}

.dev-workbench__results {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: hsl(var(--background));
  border-top: 1px solid #e6e7ed;
}

.dev-workbench__results > header {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  height: 40px;
  padding: 0 20px;
  background: #f8f8fa;
  border-bottom: 1px solid #eee;
}

.dev-workbench__results h3 {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 0;
  font-size: 16px;
  font-weight: 500;
}

.dev-workbench__results h3::before {
  width: 6px;
  height: 16px;
  content: '';
  background: #2d63ff;
  border-radius: 4px;
}

.dev-workbench__results-tools {
  padding: 15px 16px;
}

.dev-workbench__results-tools :deep(.ant-btn) {
  color: #e6a23c;
  background: #fdf6ec;
  border-color: #faecd8;
}

.dev-workbench__result-empty {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  align-items: center;
  justify-content: center;
  min-height: 100px;
  padding-bottom: 25px;
  color: #909399;
}

.dev-workbench__result-empty img {
  width: 195px;
  height: 140px;
  object-fit: contain;
}

.dev-workbench__status-control :deep(.ant-btn) {
  display: inline-flex;
  gap: 0;
  height: 16px;
  padding: 0;
  font: inherit;
  line-height: 16px;
  color: inherit;
  border: 0;
}

:global(.dark) .dev-workbench__statusbar {
  color: hsl(var(--foreground));
  background: hsl(var(--muted));
}

:global(.dark) .dev-workbench__status-icon {
  filter: invert(1);
}
</style>
