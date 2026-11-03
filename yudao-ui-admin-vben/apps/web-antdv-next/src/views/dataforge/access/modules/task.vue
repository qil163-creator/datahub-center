<script lang="ts" setup>
import type { DataforgeAccessApi } from '#/api/dataforge/access';
import type { ResolveCommand, RunId } from '#/api/dataforge/execution/command';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenDrawer } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  Button,
  Checkbox,
  Input,
  message,
  Modal,
  Select,
  Table,
  Tag,
  TextArea,
} from 'antdv-next';

import { ACTION_ICON, TableAction } from '#/adapter/vxe-table';
import {
  deleteAccessTaskList,
  getAccessGroupRecovery,
  getAccessTask,
  getAccessTaskDetailPage,
  getAccessTaskPage,
  recoverAccessGroups,
  resolveAccess,
  stopAccess,
} from '#/api/dataforge/access';
import { getRunState } from '#/views/dataforge/execution/run-status';
import { useRunCommands } from '#/views/dataforge/execution/use-run-commands';

import { optionColor, optionLabel, RUN_TYPE_OPTIONS } from '../data';
import { isTypeMappingPlan } from './type-mapping';
import TypeMappingDiagnostic from './type-mapping-diagnostic.vue';

defineOptions({ name: 'DataforgeAccessTask' });

const dataAccess = ref<DataforgeAccessApi.DataAccess>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const stopCommands = useRunCommands();
const resolveCommands = useRunCommands();
const recoveryCommands = useRunCommands();
const state = (record: DataforgeAccessApi.AccessTask) =>
  getRunState('ACCESS', record);
const canReview = computed(() =>
  hasAccessByCodes(['dataforge:access:resolve']),
);
let generation = 0;
let taskRequest = 0;
let logRequest = 0;
let detailRequest = 0;
let reviewRequest = 0;
let recoveryRequest = 0;
let opened = false;
const deleting = ref(false);

/** ==================== 执行记录列表 ==================== */
const taskList = ref<DataforgeAccessApi.AccessTask[]>([]);
const taskLoading = ref(false);
const taskLoadFailed = ref(false);
const taskPagination = reactive({ pageNo: 1, pageSize: 10, total: 0 });

let refreshTimer: null | ReturnType<typeof setInterval> = null;

const taskColumns = [
  { title: '编号', dataIndex: 'id', key: 'id', width: 70 },
  { title: '触发方式', dataIndex: 'runType', key: 'runType', width: 90 },
  { title: '运行状态', dataIndex: 'runStatus', key: 'runStatus', width: 170 },
  {
    title: '执行阶段',
    dataIndex: 'executionStage',
    key: 'executionStage',
    width: 150,
  },
  {
    title: '输出证据',
    dataIndex: 'effectState',
    key: 'effectState',
    width: 100,
  },
  {
    title: '计划时间',
    dataIndex: 'scheduledTime',
    key: 'scheduledTime',
    width: 150,
    customRender: ({ text }: { text?: number }) => formatDateTime(text) || '-',
  },
  {
    title: '开始时间',
    dataIndex: 'startTime',
    key: 'startTime',
    width: 150,
    customRender: ({ text }: { text: any }) => formatDateTime(text) || '-',
  },
  {
    title: '结束时间',
    dataIndex: 'endTime',
    key: 'endTime',
    width: 150,
    customRender: ({ text }: { text: any }) => formatDateTime(text) || '-',
  },
  { title: '同步行数', dataIndex: 'dataCount', key: 'dataCount', width: 90 },
  {
    title: '成功/失败表',
    key: 'tableCount',
    width: 100,
  },
  { title: '数据量', dataIndex: 'byteCount', key: 'byteCount', width: 90 },
  { title: '操作', key: 'actions', width: 310, fixed: 'right' as const },
];

async function loadTasks() {
  if (!dataAccess.value?.id) {
    return;
  }
  const request = ++taskRequest;
  taskLoading.value = true;
  try {
    const result = await getAccessTaskPage({
      pageNo: taskPagination.pageNo,
      pageSize: taskPagination.pageSize,
      dataAccessId: dataAccess.value.id,
    });
    if (request !== taskRequest || !opened) return;
    taskLoadFailed.value = false;
    taskList.value = result.list ?? [];
    taskPagination.total = Number(result.total ?? 0);
  } catch (error) {
    if (request === taskRequest && opened) taskLoadFailed.value = true;
    throw error;
  } finally {
    if (request === taskRequest) taskLoading.value = false;
  }
}

function handleTaskPageChange(page: number, pageSize: number) {
  taskPagination.pageNo = page;
  taskPagination.pageSize = pageSize;
  loadTasks();
}

function startAutoRefresh() {
  stopAutoRefresh();
  refreshTimer = setInterval(() => {
    if (
      !taskLoading.value &&
      (taskLoadFailed.value ||
        taskList.value.some((item) => state(item).shouldPoll))
    ) {
      void loadTasks().catch(() => {}); // 请求层统一报告错误，下一轮仍可查询。
    }
    if (
      logVisible.value &&
      currentTask.value &&
      state(currentTask.value).shouldPoll &&
      !logLoading.value
    ) {
      void loadTaskLog(currentTask.value.id).catch(() => {});
    }
    if (
      reviewVisible.value &&
      reviewRecord.value &&
      !recoveryLoading.value &&
      !recoveryCommands.busy.value &&
      !resolveCommands.busy.value &&
      (recoveryState.value?.applicable || recoveryFailed.value)
    ) {
      void loadRecoveryStatus(reviewRecord.value.id).catch(() => {});
    }
  }, 5000);
}

function stopAutoRefresh() {
  if (refreshTimer) {
    clearInterval(refreshTimer);
    refreshTimer = null;
  }
}

/** 删除执行记录 */
async function handleDeleteTask(row: DataforgeAccessApi.AccessTask) {
  if (
    !opened ||
    deleting.value ||
    !state(row).canDelete ||
    !hasAccessByCodes(['dataforge:access:delete'])
  )
    return;
  const started = generation;
  deleting.value = true;
  try {
    await deleteAccessTaskList([row.id]);
    if (started !== generation) return;
    message.success('删除成功');
    await loadTasks();
  } finally {
    if (started === generation) deleting.value = false;
  }
}

async function handleStop(row: DataforgeAccessApi.AccessTask) {
  if (
    !opened ||
    !state(row).canStop ||
    !hasAccessByCodes(['dataforge:access:run'])
  )
    return;
  const receipt = await stopCommands.execute(
    `stop:${row.id}`,
    { recordId: row.id },
    stopAccess,
  );
  if (!receipt) return;
  message.success(
    receipt.stopRequested
      ? '取消已受理 / 待确认，请查看执行记录'
      : `记录 #${receipt.recordId}：${getRunState('ACCESS', receipt).label}`,
  );
  await loadTasks();
}

/** ==================== 实时日志 ==================== */
const logVisible = ref(false);
const logLoading = ref(false);
const currentTask = ref<DataforgeAccessApi.AccessTask>();

const logTitle = computed(() =>
  currentTask.value ? `实时日志（记录 #${currentTask.value.id}）` : '实时日志',
);

async function loadTaskLog(taskId: RunId) {
  const request = ++logRequest;
  logLoading.value = true;
  try {
    const record = await getAccessTask(taskId);
    if (request === logRequest && logVisible.value) currentTask.value = record;
  } finally {
    if (request === logRequest) logLoading.value = false;
  }
}

function handleShowLog(row: DataforgeAccessApi.AccessTask) {
  currentTask.value = row;
  logVisible.value = true;
  loadTaskLog(row.id);
}

/** ==================== 表级同步结果 ==================== */
const detailVisible = ref(false);
const detailLoading = ref(false);
const detailList = ref<DataforgeAccessApi.AccessTaskDetail[]>([]);
const detailPagination = reactive({ pageNo: 1, pageSize: 10, total: 0 });
const detailTaskId = ref<RunId>();
const mappingDiagnosticVisible = ref(false);
const mappingDiagnostic = ref<DataforgeAccessApi.TypeMappingPlan>();

function showMappingDiagnostic(record: DataforgeAccessApi.AccessTaskDetail) {
  if (!isTypeMappingPlan(record.typeMappingPlan)) return;
  mappingDiagnostic.value = record.typeMappingPlan;
  mappingDiagnosticVisible.value = true;
}

const detailColumns = [
  { title: '源表', dataIndex: 'sourceTableName', key: 'sourceTableName' },
  { title: '目标表', dataIndex: 'targetTableName', key: 'targetTableName' },
  { title: '同步行数', dataIndex: 'syncCount', key: 'syncCount', width: 90 },
  { title: '数据量', dataIndex: 'syncBytes', key: 'syncBytes', width: 90 },
  { title: '结果', key: 'ifSuccess', width: 80 },
  { title: '信息', key: 'msg' },
  { title: '类型映射', key: 'typeMapping', width: 130 },
];

async function loadDetails() {
  if (!detailTaskId.value) {
    return;
  }
  const request = ++detailRequest;
  mappingDiagnosticVisible.value = false;
  detailLoading.value = true;
  try {
    const result = await getAccessTaskDetailPage({
      pageNo: detailPagination.pageNo,
      pageSize: detailPagination.pageSize,
      taskId: detailTaskId.value,
    });
    if (request !== detailRequest || !detailVisible.value) return;
    detailList.value = result.list ?? [];
    detailPagination.total = Number(result.total ?? 0);
  } finally {
    if (request === detailRequest) detailLoading.value = false;
  }
}

function handleShowDetail(row: DataforgeAccessApi.AccessTask) {
  detailList.value = [];
  detailPagination.total = 0;
  detailTaskId.value = row.id;
  detailPagination.pageNo = 1;
  detailVisible.value = true;
  loadDetails();
}

function handleDetailPageChange(page: number, pageSize: number) {
  detailPagination.pageNo = page;
  detailPagination.pageSize = pageSize;
  loadDetails();
}

/** 核查只使用打开表单后读取的记录版本；失败重试保留原命令身份与版本。 */
const reviewVisible = ref(false);
const reviewLoading = ref(false);
const reviewRecord = ref<DataforgeAccessApi.AccessTask>();
const recoveryState = ref<DataforgeAccessApi.GroupRecoveryState>();
const recoveryLoading = ref(false);
const recoveryFailed = ref(false);
const recoveryUnconfirmed = ref(false);
const recoveryPayload =
  ref<Omit<DataforgeAccessApi.GroupRecoverCommand, 'requestId'>>();
const review = reactive({
  confirmedStopped: false,
  outputReviewed: false,
  outcome: 'FAILED' as ResolveCommand['outcome'],
  reason: '',
  evidenceRef: '',
});
const reviewValid = computed(
  () =>
    canReview.value &&
    !!reviewRecord.value &&
    state(reviewRecord.value).canResolve &&
    !recoveryFailed.value &&
    !recoveryLoading.value &&
    !recoveryCommands.busy.value &&
    (!recoveryState.value?.applicable || recoveryState.value.released) &&
    review.confirmedStopped &&
    review.outputReviewed &&
    ['FAILED', 'STOPPED', 'SUCCESS'].includes(review.outcome) &&
    !!review.reason.trim() &&
    review.reason.length <= 500 &&
    !!review.evidenceRef.trim() &&
    review.evidenceRef.length <= 1000,
);
const recoveryValid = computed(
  () =>
    canReview.value &&
    !!reviewRecord.value &&
    (recoveryState.value?.ready || recoveryUnconfirmed.value) &&
    !reviewLoading.value &&
    !recoveryLoading.value &&
    !resolveCommands.busy.value &&
    !recoveryCommands.busy.value &&
    !!review.reason.trim() &&
    review.reason.length <= 500 &&
    !!review.evidenceRef.trim() &&
    review.evidenceRef.length <= 1000,
);

function resetReview() {
  ++reviewRequest;
  ++recoveryRequest;
  resolveCommands.invalidate();
  recoveryCommands.invalidate();
  recoveryState.value = undefined;
  recoveryLoading.value = false;
  recoveryFailed.value = false;
  recoveryUnconfirmed.value = false;
  recoveryPayload.value = undefined;
  reviewRecord.value = undefined;
  reviewLoading.value = false;
  Object.assign(review, {
    confirmedStopped: false,
    outputReviewed: false,
    outcome: 'FAILED',
    reason: '',
    evidenceRef: '',
  });
}

async function handleReview(row: DataforgeAccessApi.AccessTask) {
  if (!opened || !canReview.value || !state(row).canResolve) return;
  resetReview();
  reviewVisible.value = true;
  const request = ++reviewRequest;
  reviewLoading.value = true;
  try {
    const fresh = await getAccessTask(row.id);
    if (request !== reviewRequest || !reviewVisible.value) return;
    if (String(fresh.id) !== String(row.id) || !state(fresh).canResolve) {
      message.warning('记录状态已变化，请刷新执行记录后重新核查');
      return;
    }
    reviewRecord.value = fresh;
    await loadRecoveryStatus(fresh.id);
  } finally {
    if (request === reviewRequest) reviewLoading.value = false;
  }
}

async function loadRecoveryStatus(recordId: RunId) {
  const request = ++recoveryRequest;
  recoveryLoading.value = true;
  try {
    const status = await getAccessGroupRecovery(recordId);
    if (
      request !== recoveryRequest ||
      !reviewVisible.value ||
      String(reviewRecord.value?.id) !== String(recordId)
    )
      return;
    if (String(status.recordId) !== String(recordId))
      throw new Error('Recovery response record mismatch');
    const newlyReleased = status.released && !recoveryState.value?.released;
    recoveryState.value = status;
    recoveryFailed.value = false;
    if (
      newlyReleased &&
      !recoveryUnconfirmed.value &&
      !recoveryCommands.busy.value &&
      !resolveCommands.busy.value
    ) {
      const fresh = await getAccessTask(recordId);
      if (
        request === recoveryRequest &&
        reviewVisible.value &&
        String(fresh.id) === String(recordId)
      )
        reviewRecord.value = fresh;
    }
  } catch (error) {
    if (request === recoveryRequest && reviewVisible.value) {
      recoveryFailed.value = true;
      recoveryState.value = undefined;
    }
    throw error;
  } finally {
    if (request === recoveryRequest) recoveryLoading.value = false;
  }
}

async function submitRecovery() {
  if (
    !recoveryValid.value ||
    !reviewRecord.value ||
    (!recoveryState.value && !recoveryPayload.value)
  )
    return;
  const started = reviewRequest;
  const payload = recoveryPayload.value ?? {
    recordId: reviewRecord.value.id,
    expectedVersion: recoveryState.value!.rowVersion,
    reason: review.reason.trim(),
    evidenceRef: review.evidenceRef.trim(),
  };
  recoveryPayload.value = payload;
  let receipt;
  try {
    receipt = await recoveryCommands.execute(
      `recover:${payload.recordId}`,
      payload,
      recoverAccessGroups,
    );
  } catch (error) {
    if (started === reviewRequest) recoveryUnconfirmed.value = true;
    throw error;
  }
  if (!receipt || started !== reviewRequest) return;
  recoveryUnconfirmed.value = false;
  recoveryPayload.value = undefined;
  message.success(
    '数据组恢复已受理，不代表恢复完成；请等待状态更新，随后核查原同步输出',
  );
  await loadRecoveryStatus(payload.recordId);
  await loadTasks();
}

async function submitReview() {
  if (!reviewValid.value || reviewLoading.value || !reviewRecord.value) return;
  const payload: Omit<ResolveCommand, 'requestId'> = {
    recordId: reviewRecord.value.id,
    expectedVersion: reviewRecord.value.rowVersion!,
    confirmedStopped: true,
    outputReviewed: true,
    outcome: review.outcome,
    reason: review.reason.trim(),
    evidenceRef: review.evidenceRef.trim(),
  };
  const receipt = await resolveCommands.execute(
    `resolve:${payload.recordId}`,
    payload,
    resolveAccess,
  );
  if (!receipt) return;
  message.success(
    `核查已受理，记录 #${receipt.recordId}：${getRunState('ACCESS', receipt).label}`,
  );
  reviewVisible.value = false;
  await loadTasks();
}

watch(
  logVisible,
  (visible) => {
    if (!visible) {
      ++logRequest;
      currentTask.value = undefined;
      logLoading.value = false;
    }
  },
  { flush: 'sync' },
);
watch(
  detailVisible,
  (visible) => {
    if (!visible) {
      ++detailRequest;
      detailTaskId.value = undefined;
      detailList.value = [];
      detailLoading.value = false;
      detailPagination.total = 0;
      mappingDiagnosticVisible.value = false;
    }
  },
  { flush: 'sync' },
);
watch(
  mappingDiagnosticVisible,
  (visible) => {
    if (!visible) mappingDiagnostic.value = undefined;
  },
  { flush: 'sync' },
);
watch(
  reviewVisible,
  (visible) => {
    if (!visible) resetReview();
  },
  { flush: 'sync' },
);

function reset() {
  ++generation;
  ++taskRequest;
  ++logRequest;
  ++detailRequest;
  opened = false;
  stopAutoRefresh();
  stopCommands.invalidate();
  resetReview();
  dataAccess.value = undefined;
  taskList.value = [];
  taskPagination.total = 0;
  taskLoading.value = false;
  taskLoadFailed.value = false;
  deleting.value = false;
  logVisible.value = false;
  currentTask.value = undefined;
  logLoading.value = false;
  detailVisible.value = false;
  detailTaskId.value = undefined;
  detailList.value = [];
  detailLoading.value = false;
  reviewVisible.value = false;
}
watch(
  () => [access.accessToken, access.refreshToken, user.userInfo?.userId],
  () => {
    reset();
    void drawerApi.close();
  },
  { flush: 'sync' },
);
onBeforeUnmount(reset);

/** ==================== 执行记录抽屉 ==================== */
const getTitle = computed(() =>
  dataAccess.value
    ? `执行记录 - ${dataAccess.value.taskName ?? dataAccess.value.id}`
    : '执行记录',
);

const [DrawerComponent, drawerApi] = useVbenDrawer({
  class: 'w-[1500px] max-w-full',
  footer: false,
  placement: 'right',
  async onOpenChange(isOpen: boolean) {
    reset();
    if (!isOpen) {
      return;
    }
    opened = true;
    const started = generation;
    dataAccess.value = drawerApi.getData() as DataforgeAccessApi.DataAccess;
    taskPagination.pageNo = 1;
    try {
      await loadTasks();
    } finally {
      // 首次查询失败也启动轮询，后续按失败标记重试。
      if (started === generation && opened) startAutoRefresh();
    }
  },
});
</script>

<template>
  <DrawerComponent :title="getTitle">
    <div class="px-2 pb-2">
      <div class="mb-3 flex justify-end">
        <Button size="small" @click="loadTasks"
          ><template #icon><IconifyIcon icon="lucide:refresh-cw" /></template
          >刷新</Button
        >
      </div>
      <Table
        :columns="taskColumns"
        :data-source="taskList"
        :loading="taskLoading"
        :pagination="{
          current: taskPagination.pageNo,
          pageSize: taskPagination.pageSize,
          total: taskPagination.total,
          showSizeChanger: true,
          onChange: handleTaskPageChange,
        }"
        :scroll="{ x: 1500 }"
        row-key="id"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'runType'">
            <Tag :color="optionColor(RUN_TYPE_OPTIONS, record.runType)">
              {{ optionLabel(RUN_TYPE_OPTIONS, record.runType) }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'runStatus'">
            <Tag :color="state(record).color">
              {{ state(record).label }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'tableCount'">
            <span class="text-green-600">{{
              record.tableSuccessCount ?? 0
            }}</span>
            /
            <span class="text-red-500">{{ record.tableFailCount ?? 0 }}</span>
          </template>
          <template v-else-if="column.key === 'actions'">
            <TableAction
              :actions="[
                {
                  icon: 'lucide:square',
                  label: '停止',
                  type: 'link',
                  danger: true,
                  auth: ['dataforge:access:run'],
                  ifShow: state(record).canStop,
                  disabled: stopCommands.busy.value,
                  popConfirm: {
                    title: `确认请求停止执行记录 #${record.id}？受理后仍须确认资源退出。`,
                    confirm: handleStop.bind(null, record as any),
                  },
                },
                {
                  icon: 'lucide:clipboard-check',
                  label: '核查',
                  type: 'link',
                  auth: ['dataforge:access:resolve'],
                  ifShow: state(record).canResolve,
                  onClick: handleReview.bind(null, record as any),
                },
                {
                  label: '表级结果',
                  auth: ['dataforge:access:query'],
                  type: 'link',
                  icon: ACTION_ICON.VIEW,
                  onClick: handleShowDetail.bind(null, record as any),
                },
              ]"
              :drop-down-actions="[
                {
                  label: '日志',
                  auth: ['dataforge:access:query'],
                  type: 'link',
                  icon: 'lucide:history',
                  onClick: handleShowLog.bind(null, record as any),
                },
                {
                  label: '删除',
                  auth: ['dataforge:access:delete'],
                  ifShow: state(record).canDelete,
                  disabled: deleting,
                  type: 'link',
                  danger: true,
                  icon: ACTION_ICON.DELETE,
                  popConfirm: {
                    title: '确认删除该执行记录吗？',
                    confirm: handleDeleteTask.bind(null, record as any),
                  },
                },
              ]"
            />
          </template>
        </template>
      </Table>
    </div>

    <!-- 实时日志 -->
    <Modal
      v-model:open="logVisible"
      :title="logTitle"
      :footer="null"
      width="860px"
    >
      <div class="mb-2 flex items-center justify-between">
        <div class="flex items-center gap-2">
          <Tag v-if="currentTask" :color="state(currentTask).color">
            {{ state(currentTask).label }}
          </Tag>
          <Tag v-if="currentTask?.seatunnelJobId" color="cyan">
            Seatunnel JobId: {{ currentTask.seatunnelJobId }}
          </Tag>
          <span
            v-if="currentTask && state(currentTask).shouldPoll"
            class="text-xs text-gray-400"
          >
            未收敛记录每 5 秒自动刷新日志
          </span>
        </div>
        <Button
          size="small"
          :loading="logLoading"
          @click="currentTask && loadTaskLog(currentTask.id)"
        >
          <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
          刷新日志
        </Button>
      </div>
      <pre class="task-log">{{ currentTask?.realTimeLog || '暂无日志' }}</pre>
      <div v-if="currentTask?.errorInfo" class="mt-2">
        <div class="mb-1 text-xs font-semibold text-red-500">错误信息</div>
        <pre class="task-log task-log--error">{{ currentTask.errorInfo }}</pre>
      </div>
    </Modal>

    <!-- 表级同步结果 -->
    <Modal
      v-model:open="detailVisible"
      :footer="null"
      title="表级同步结果"
      width="960px"
    >
      <Table
        :columns="detailColumns"
        :data-source="detailList"
        :loading="detailLoading"
        :pagination="{
          current: detailPagination.pageNo,
          pageSize: detailPagination.pageSize,
          total: detailPagination.total,
          showSizeChanger: true,
          onChange: handleDetailPageChange,
        }"
        row-key="id"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'ifSuccess'">
            <Tag :color="record.ifSuccess === 1 ? 'success' : 'error'">
              {{ record.ifSuccess === 1 ? '成功' : '失败' }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'msg'">
            <span
              :class="record.ifSuccess === 1 ? '' : 'text-red-500'"
              class="text-xs"
            >
              {{ record.ifSuccess === 1 ? record.successMsg : record.errorMsg }}
            </span>
          </template>
          <template v-else-if="column.key === 'typeMapping'">
            <Button
              v-if="isTypeMappingPlan(record.typeMappingPlan)"
              type="link"
              size="small"
              @click="showMappingDiagnostic(record)"
              ><template #icon
                ><IconifyIcon icon="lucide:scan-search" /></template
              >类型映射诊断</Button
            >
            <span v-else class="text-xs text-muted-foreground">{{
              record.typeMappingPlan ? '诊断不可读取' : '未生成'
            }}</span>
          </template>
        </template>
      </Table>
    </Modal>

    <Modal
      v-model:open="mappingDiagnosticVisible"
      title="类型映射诊断"
      :footer="null"
      width="1080px"
    >
      <TypeMappingDiagnostic
        v-if="mappingDiagnostic"
        :plan="mappingDiagnostic"
      />
    </Modal>

    <Modal
      v-model:open="reviewVisible"
      title="人工核查执行结果"
      :footer="null"
      width="660px"
    >
      <p>先核实旧执行已退出，再核查输出与水位。停止不会回滚已写入的数据。</p>
      <p v-if="reviewLoading">正在读取最新记录和版本…</p>
      <template v-else-if="reviewRecord">
        <p>
          记录 #{{ reviewRecord.id }} · 版本 {{ reviewRecord.rowVersion }} ·
          {{ state(reviewRecord).label }}
        </p>
        <p>
          执行阶段：{{ reviewRecord.executionStage || '-' }}；输出：{{
            reviewRecord.effectState || '-'
          }}
        </p>
        <p v-if="recoveryLoading">正在核对服务端数据组恢复证据…</p>
        <p v-else-if="recoveryFailed" role="alert">
          恢复状态暂不可读取，请稍后重试；不能据此确认执行资源已退出。
        </p>
        <p v-else-if="recoveryState?.applicable" role="status">
          {{ recoveryState.message }}
        </p>
        <p v-if="recoveryUnconfirmed" role="alert">
          上次恢复请求的受理结果尚未确认，请保持原原因与证据重试确认。
        </p>
        <fieldset
          :disabled="resolveCommands.busy.value || recoveryCommands.busy.value"
          class="flex flex-col gap-3"
        >
          <Checkbox
            v-model:checked="review.confirmedStopped"
            :disabled="resolveCommands.busy.value"
            >已确认执行资源退出（线程、远端作业及物理连接）</Checkbox
          >
          <Checkbox
            v-model:checked="review.outputReviewed"
            :disabled="resolveCommands.busy.value"
            >已核查输出、水位及部分写入，确认后续运行安全</Checkbox
          >
          <label>最终结论</label>
          <Select
            v-model:value="review.outcome"
            :disabled="resolveCommands.busy.value"
            :options="[
              { label: '失败', value: 'FAILED' },
              { label: '已停止', value: 'STOPPED' },
              { label: '成功', value: 'SUCCESS' },
            ]"
          />
          <label>核查原因（必填，最多 500 字）</label>
          <TextArea
            v-model:value="review.reason"
            aria-label="核查原因"
            :maxlength="500"
            :disabled="resolveCommands.busy.value || !!recoveryPayload"
          />
          <label>证据引用（必填，最多 1000 字，不填写凭据或原始数据）</label>
          <Input
            v-model:value="review.evidenceRef"
            aria-label="证据引用"
            :maxlength="1000"
            :disabled="resolveCommands.busy.value || !!recoveryPayload"
          />
          <Button
            v-if="
              canReview && (recoveryState?.applicable || recoveryUnconfirmed)
            "
            :disabled="!recoveryValid"
            :loading="recoveryCommands.busy.value"
            @click="submitRecovery"
            ><template #icon
              ><IconifyIcon
                :icon="
                  recoveryUnconfirmed
                    ? 'lucide:refresh-cw'
                    : 'lucide:rotate-ccw'
                " /></template
            >{{
              recoveryUnconfirmed ? '重试确认恢复受理' : '恢复数据组'
            }}</Button
          >
          <Button
            type="primary"
            :disabled="!reviewValid || resolveCommands.busy.value"
            :loading="resolveCommands.busy.value"
            @click="submitReview"
            ><template #icon
              ><IconifyIcon icon="lucide:shield-check" /></template
            >提交核查</Button
          >
        </fieldset>
      </template>
      <p v-else>未获得可核查的当前版本，请关闭后重新打开。</p>
      <p class="mt-3 text-xs text-gray-500">
        提交超时请在本窗口重试，保持原命令。关闭窗口会放弃本地重试身份；再次处置前请核对记录。
      </p>
    </Modal>
  </DrawerComponent>
</template>

<style scoped>
.task-log {
  max-height: 420px;
  padding: 12px;
  margin: 0;
  overflow: auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.7;
  color: hsl(var(--foreground));
  white-space: pre-wrap;
  background: hsl(var(--muted) / 50%);
  border-radius: 8px;
}

.task-log--error {
  max-height: 160px;
  color: hsl(var(--destructive));
}
</style>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
