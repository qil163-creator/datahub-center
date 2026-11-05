<script lang="ts" setup>
import type { DomainOperation, FillFieldSchema, FillRowData } from './types';

import type { DataforgeFillApi } from '#/api/dataforge/fill';

import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { downloadFileFromBlobPart, formatDateTime } from '@vben/utils';

import {
  Button,
  Drawer,
  Dropdown,
  Input,
  Menu,
  MenuItem,
  message,
  Modal,
  Radio,
  RadioGroup,
  Select,
  Space,
  Tag,
} from 'antdv-next';

import {
  applyDraftOperations,
  confirmWorkItemExcelImport,
  downloadWorkItemExcelTemplate,
  exportWorkItemExcel,
  getDraftOperations,
  getDraftSnapshot,
  getSubmissionsByWorkItem,
  previewWorkItemExcelImport,
  submitWorkItem,
  transferWorkItem,
  withdrawSubmission,
} from '#/api/dataforge/fill';
import { getSimpleUserList } from '#/api/system/user';

import { APPROVAL_STATUS_TEXT, WORK_ITEM_STATUS_TEXT } from '../shared';
import { FillCollabClient } from './collab-client';
import { mapDraftRows, mapFieldSchema } from './schema-mapper';
import SheetEditor from './SheetEditor.vue';

defineOptions({ name: 'DataforgeFillWorkbench' });

/** Univer 依赖 Intl.Segmenter，低版本内核（信创终端）需先加载 polyfill */
const polyfillReady = ref(false);
(async () => {
  if (!('Segmenter' in Intl)) {
    await import('@formatjs/intl-segmenter/polyfill.js');
  }
  polyfillReady.value = true;
})();

const route = useRoute();
const router = useRouter();
const accessStore = useAccessStore();
const userStore = useUserStore();
const myUserId = computed(() =>
  String(
    (userStore.userInfo as any)?.id ??
      (userStore.userInfo as any)?.userId ??
      '',
  ),
);

/** 仅接受十进制工作项 ID；无合法 ID 时回「我的填报」 */
const workItemId = computed(() => {
  const raw = route.query.workItemId;
  const value = Array.isArray(raw) ? raw[0] : raw;
  return value && /^[1-9]\d*$/.test(String(value)) ? String(value) : undefined;
});

// ---------------------------------------------------------------- 编辑器数据集
const fields = shallowRef<FillFieldSchema[]>([]);
const rows = shallowRef<FillRowData[]>([]);
const readonly = ref(false);
const reloadSeq = ref(0);
const editorRef = ref<InstanceType<typeof SheetEditor>>();

const datasetKey = computed(
  () => `real-${workItemId.value ?? 'none'}-${reloadSeq.value}`,
);

// ---------------------------------------------------------------- 真实模式：快照 + 操作同步
const snapshotMeta = ref<{
  deadlineTime?: string;
  orgName?: string;
  overdue?: number;
  periodKey?: string;
  status?: number;
  taskName?: string;
}>({});
const serverVersion = ref(0);
const wsOpen = ref(false);
const memberUserIds = ref<number[]>([]);
// 用户编号到昵称的缓存，锁冲突和在线成员提示显示姓名而不是数字编号。
const userNames = ref<Record<string, string>>({});
let userNamesLoading: null | Promise<void> = null;
function loadUserNames() {
  userNamesLoading ??= getSimpleUserList()
    .then((users) => {
      userNames.value = Object.fromEntries(
        (users as any[]).map((user) => [String(user.id), String(user.nickname ?? user.id)]),
      );
    })
    .catch(() => {
      userNamesLoading = null;
    });
  return userNamesLoading;
}
function userLabel(userId?: null | number | string) {
  if (userId === undefined || userId === null || userId === '') return '其他用户';
  return userNames.value[String(userId)] ?? `用户${userId}`;
}
const pendingCount = ref(0);
const clientSessionId = `sess-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;

let collabClient: FillCollabClient | null = null;
let opSeq = 0;
const myClientOpIds = new Set<string>();
const pendingQueue: DataforgeFillApi.OperationApplyReq['operations'] = [];
let flushTimer: null | ReturnType<typeof setTimeout> = null;
let flushing = false;
let needRestore = false;
let pulling = false;

async function loadSnapshot() {
  if (!workItemId.value) return;
  const snapshot = await getDraftSnapshot(workItemId.value);
  snapshotMeta.value = {
    deadlineTime: snapshot.deadlineTime,
    orgName: snapshot.orgName,
    overdue: snapshot.overdue,
    periodKey: snapshot.periodKey,
    status: snapshot.status,
    taskName: snapshot.taskName,
  };
  serverVersion.value = snapshot.serverVersion;
  fields.value = mapFieldSchema(snapshot.fieldSchema);
  rows.value = mapDraftRows(snapshot.rows);
  readonly.value = !snapshot.editable;
  // 丢弃未提交队列（快照即最新事实），重挂编辑器
  pendingQueue.length = 0;
  pendingCount.value = 0;
  reloadSeq.value += 1;
}

/** 刷新前先同步本端未保存的修改，避免重新加载快照时静默丢弃。 */
async function refreshSnapshot() {
  await flushNow();
  await loadSnapshot();
}

/** 增量拉取远端操作并应用（广播只是提示，一致性靠这里） */
async function pullRemoteOperations() {
  if (!workItemId.value || pulling) return;
  pulling = true;
  try {
    for (let round = 0; round < 20; round++) {
      const ops = await getDraftOperations(
        workItemId.value,
        serverVersion.value,
        200,
      );
      for (const op of ops) {
        serverVersion.value = Math.max(serverVersion.value, op.serverVersion);
        if (myClientOpIds.has(op.clientOpId)) {
          continue;
        }
        const adapter = editorRef.value?.getAdapter();
        if (!adapter) continue;
        if (op.opType === 'SET_CELL' && op.rowId && op.fieldCode) {
          adapter.applyRemoteOperation({
            fieldCode: op.fieldCode,
            rowId: op.rowId,
            type: 'setCell',
            value: op.newValue,
          });
        } else if (op.opType === 'ADD_ROW' && op.rowId) {
          adapter.applyRemoteOperation({
            data: (op.newValue ?? {}) as Record<string, unknown>,
            rowId: op.rowId,
            type: 'addRow',
          });
        } else if (op.opType === 'REMOVE_ROW' && op.rowId) {
          adapter.applyRemoteOperation({ rowId: op.rowId, type: 'removeRow' });
        }
      }
      if (ops.length < 200) break;
    }
  } finally {
    pulling = false;
  }
}

function enqueueOperation(op: DomainOperation) {
  opSeq += 1;
  const clientOpId = `${clientSessionId}-${opSeq.toString(36)}`;
  myClientOpIds.add(clientOpId);
  if (op.type === 'setCell') {
    pendingQueue.push({
      clientOpId,
      fieldCode: op.fieldCode,
      opType: 'SET_CELL',
      rowId: op.rowId,
      value: op.value,
    });
  } else if (op.type === 'addRow') {
    pendingQueue.push({
      afterRowId: op.afterRowId,
      clientOpId,
      opType: 'ADD_ROW',
      rowId: op.rowId,
    });
  } else {
    pendingQueue.push({ clientOpId, opType: 'REMOVE_ROW', rowId: op.rowId });
  }
  pendingCount.value = pendingQueue.length;
  scheduleFlush();
}

function scheduleFlush() {
  if (flushTimer) return;
  flushTimer = setTimeout(() => {
    flushTimer = null;
    void flushOperations();
  }, 400);
}

async function flushOperations(retrying = false) {
  if (!workItemId.value || flushing || pendingQueue.length === 0) return;
  flushing = true;
  const batch = pendingQueue.splice(0);
  pendingCount.value = 0;
  const allowed: typeof batch = [];
  let denied = false;
  await loadUserNames();
  for (const op of batch) {
    if (op.opType !== 'SET_CELL' || !op.rowId) {
      allowed.push(op);
      continue;
    }
    const result =
      wsOpen.value && collabClient
        ? await collabClient.acquireLockAndWait(op.rowId, op.fieldCode)
        : undefined;
    if (result && !result.granted) {
      denied = true;
      message.warning(
        result.holderUserId
          ? `单元格正被 ${userLabel(result.holderUserId)} 编辑，未提交该格修改`
          : '未能获得单元格编辑锁，未提交该格修改',
      );
      continue;
    }
    allowed.push(op);
  }
  if (denied) {
    await pullRemoteOperations();
    // 被拒绝的修改仍显示在本地表格里，需要在其余操作同步完成后以服务端数据为准恢复。
    needRestore = true;
  }
  if (allowed.length === 0) {
    flushing = false;
    if (pendingQueue.length > 0) scheduleFlush();
    else void restoreAfterDenied();
    return;
  }
  try {
    const resp = await applyDraftOperations(workItemId.value, {
      clientSessionId,
      expectedVersion: serverVersion.value,
      operations: allowed,
    });
    serverVersion.value = resp.serverVersion;
  } catch (error: any) {
    if (!retrying) {
      await pullRemoteOperations();
      pendingQueue.unshift(...allowed);
      pendingCount.value = pendingQueue.length;
      flushing = false;
      await flushOperations(true);
      return;
    }
    message.error(error?.message ?? '保存失败，正在重新加载最新数据');
    await loadSnapshot();
  } finally {
    flushing = false;
    if (pendingQueue.length > 0) scheduleFlush();
    else if (needRestore) void restoreAfterDenied();
  }
}

/** 编辑锁被拒绝后，用服务端快照覆盖本地未保存的单元格值，避免界面显示“已修改”但实际未保存。 */
async function restoreAfterDenied() {
  if (!needRestore || flushing || pendingQueue.length > 0) return;
  needRestore = false;
  await loadSnapshot();
}

// ---------------------------------------------------------------- 提交与审批
const submitting = ref(false);
const issues = ref<DataforgeFillApi.ValidationIssue[]>([]);
// 严重级别 1 为错误（阻断提交），2 为警告（可提交）
const isWarning = (issue: DataforgeFillApi.ValidationIssue) => issue.severity === 2;
const errorCount = computed(() => issues.value.filter((issue) => !isWarning(issue)).length);
const warningCount = computed(() => issues.value.filter((issue) => isWarning(issue)).length);
const issuesOpen = ref(false);
const submissionsOpen = ref(false);
const submissions = ref<DataforgeFillApi.Submission[]>([]);

/** 立即冲刷全部未同步操作（提交前必须等所有操作获得 ACK） */
async function flushNow() {
  if (flushTimer) {
    clearTimeout(flushTimer);
    flushTimer = null;
  }
  for (
    let round = 0;
    round < 100 && (pendingQueue.length > 0 || flushing);
    round++
  ) {
    await (flushing
      ? new Promise((resolve) => setTimeout(resolve, 100))
      : flushOperations());
  }
}

async function handleSubmit() {
  if (!workItemId.value || submitting.value) return;
  submitting.value = true;
  try {
    await flushNow();
    const rowCount = editorRef.value?.getAdapter()?.getRows().length ?? 0;
    const isEmpty = rowCount === 0;
    const confirmed = await new Promise<boolean>((resolve) => {
      Modal.confirm({
        content: isEmpty
          ? '当前没有数据行，将以"零上报"方式提交（表示本期无数据）。'
          : `共 ${rowCount} 行数据。提交后数据将冻结并进入审批，审批期间不可编辑。`,
        onCancel: () => resolve(false),
        onOk: () => resolve(true),
        title: isEmpty ? '零上报确认' : '提交审批确认',
      });
    });
    if (!confirmed) return;
    const resp = await submitWorkItem(workItemId.value, {
      expectedVersion: serverVersion.value,
      submissionType: isEmpty ? 2 : 1,
    });
    if (resp.success) {
      // 仅有警告时提交成功，警告留在列表中并随提交一并交给审批人
      issues.value = resp.issues ?? [];
      message.success(
        warningCount.value > 0
          ? `提交成功（第 ${resp.attemptNo} 次），已进入审批；另有 ${warningCount.value} 条警告提示`
          : `提交成功（第 ${resp.attemptNo} 次），已进入审批`,
      );
      await loadSnapshot();
    } else {
      issues.value = resp.issues ?? [];
      issuesOpen.value = true;
      message.error(
        warningCount.value > 0
          ? `校验未通过：${errorCount.value} 个错误，另有 ${warningCount.value} 条警告`
          : `校验未通过，共 ${errorCount.value} 个问题`,
      );
    }
  } catch (error: any) {
    message.error(error?.message ?? '提交失败');
  } finally {
    submitting.value = false;
  }
}

function focusIssue(issue: DataforgeFillApi.ValidationIssue) {
  if (issue.rowId) {
    editorRef.value?.getAdapter()?.focusCell(issue.rowId, issue.fieldCode);
  }
}

// ---------------------------------------------------------------- 零上报
const emptyReportOpen = ref(false);
const emptyReportSaving = ref(false);
const emptyReportReason = ref('');
const emptyReportRowCount = computed(
  () => editorRef.value?.getAdapter()?.getRows().length ?? 0,
);

function openEmptyReport() {
  emptyReportReason.value = '';
  emptyReportOpen.value = true;
}

/**
 * 零上报：本期确无数据的显式申报。若草稿仍有行，先经操作通道删除全部行（留痕、
 * 协同可见），再以零上报类型提交进入审批，避免"有数据但逾期未填"的误会
 */
async function handleEmptyReport() {
  if (!workItemId.value) return;
  if (!emptyReportReason.value.trim()) {
    message.warning('请填写零上报说明（如：本期无新增项目）');
    return;
  }
  emptyReportSaving.value = true;
  try {
    await flushNow();
    const adapterRows = editorRef.value?.getAdapter()?.getRows() ?? [];
    if (adapterRows.length > 0) {
      const operations = adapterRows.map((row) => {
        opSeq += 1;
        const clientOpId = `${clientSessionId}-${opSeq.toString(36)}`;
        myClientOpIds.add(clientOpId);
        return { clientOpId, opType: 'REMOVE_ROW', rowId: row.rowId };
      });
      const applyResp = await applyDraftOperations(workItemId.value, {
        clientSessionId,
        expectedVersion: serverVersion.value,
        operations,
      });
      serverVersion.value = applyResp.serverVersion;
    }
    const resp = await submitWorkItem(workItemId.value, {
      emptyReason: emptyReportReason.value.trim(),
      expectedVersion: serverVersion.value,
      submissionType: 2,
    });
    if (resp.success) {
      message.success('零上报已提交，进入审批');
      emptyReportOpen.value = false;
      issues.value = [];
      await loadSnapshot();
    } else {
      issues.value = resp.issues ?? [];
      issuesOpen.value = true;
    }
  } catch (error: any) {
    message.error(error?.message ?? '零上报提交失败');
    await loadSnapshot();
  } finally {
    emptyReportSaving.value = false;
  }
}

async function openSubmissions() {
  submissionsOpen.value = true;
  if (workItemId.value) {
    submissions.value = await getSubmissionsByWorkItem(workItemId.value);
  }
}

function handleWithdraw(submission: DataforgeFillApi.Submission) {
  Modal.confirm({
    content: '将通过流程中心终止当前审批流程，实例转入整改中重新开放编辑。',
    onOk: async () => {
      await withdrawSubmission(submission.id, '提交人撤回');
      message.success('已撤回，实例进入整改中');
      await Promise.all([openSubmissions(), loadSnapshot()]);
    },
    title: '撤回提交确认',
  });
}

function canWithdraw(submission: DataforgeFillApi.Submission) {
  return (
    submission.approvalStatus === 1 &&
    String(submission.submitterUserId) === myUserId.value
  );
}

// ---------------------------------------------------------------- Excel 导入导出
const fileInputRef = ref<HTMLInputElement>();
const importOpen = ref(false);
const importing = ref(false);
const importConfirming = ref(false);
const importMode = ref(1);
const importPreview = ref<DataforgeFillApi.ExcelImportPreview>();

async function handleExcelMenu(event: { key: string }) {
  if (!workItemId.value) return;
  if (event.key === 'template') {
    const blob = await downloadWorkItemExcelTemplate(workItemId.value);
    downloadFileFromBlobPart({
      fileName: `${snapshotMeta.value.taskName || '填报'}-模板.xlsx`,
      source: blob,
    });
  } else if (event.key === 'export') {
    const blob = await exportWorkItemExcel(workItemId.value);
    downloadFileFromBlobPart({
      fileName: `${snapshotMeta.value.taskName || '填报'}-数据.xlsx`,
      source: blob,
    });
  } else if (event.key === 'import') {
    fileInputRef.value?.click();
  }
}

async function onImportFileChange(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file || !workItemId.value) return;
  importing.value = true;
  try {
    importPreview.value = await previewWorkItemExcelImport(
      workItemId.value,
      file,
    );
    importMode.value = 1;
    importOpen.value = true;
  } finally {
    importing.value = false;
  }
}

async function handleImportConfirm() {
  if (!workItemId.value || !importPreview.value) return;
  importConfirming.value = true;
  try {
    // 先冲刷本端未同步操作，避免导入与本地编辑的版本冲突
    await flushNow();
    const resp = await confirmWorkItemExcelImport(workItemId.value, {
      expectedVersion: serverVersion.value,
      mode: importMode.value,
      rows: importPreview.value.rows,
    });
    message.success(
      `导入成功：${importMode.value === 2 ? '已替换为' : '已追加'} ${importPreview.value.totalCount} 行（当前共 ${resp.draftRowCount} 行）`,
    );
    importOpen.value = false;
    importPreview.value = undefined;
    await loadSnapshot();
  } finally {
    importConfirming.value = false;
  }
}

// ---------------------------------------------------------------- 填报职责转派
const transferOpen = ref(false);
const transferSaving = ref(false);
const transferTargetId = ref<number>();
const transferReason = ref('');
const transferUserOptions = ref<{ label: string; value: number }[]>([]);

async function openTransfer() {
  transferTargetId.value = undefined;
  transferReason.value = '';
  transferOpen.value = true;
  if (transferUserOptions.value.length === 0) {
    const users = await getSimpleUserList();
    transferUserOptions.value = (users as any[])
      .filter((user) => String(user.id) !== myUserId.value)
      .map((user) => ({ label: user.nickname, value: user.id }));
  }
}

async function handleTransfer() {
  if (!workItemId.value) return;
  if (!transferTargetId.value) {
    message.warning('请选择转派目标人员');
    return;
  }
  transferSaving.value = true;
  try {
    // 转派后本人失去编辑权限，先把未同步的修改提交到服务端。
    await flushNow();
    await transferWorkItem({
      reason: transferReason.value || undefined,
      targetUserId: transferTargetId.value,
      workItemId: workItemId.value,
    });
    message.success('转派成功，您已失去本实例的填报权限');
    transferOpen.value = false;
    await loadSnapshot();
  } finally {
    transferSaving.value = false;
  }
}

const LOCK_RENEW_MS = 15_000;
const heldLock = ref<{ fieldCode?: string; rowId: string }>();
let lockRenewTimer: null | ReturnType<typeof setInterval> = null;

function stopLockRenew() {
  if (lockRenewTimer) {
    clearInterval(lockRenewTimer);
    lockRenewTimer = null;
  }
}

function startLockRenew() {
  stopLockRenew();
  lockRenewTimer = setInterval(() => {
    if (heldLock.value && !readonly.value) {
      collabClient?.renewLock(heldLock.value.rowId, heldLock.value.fieldCode);
    }
  }, LOCK_RENEW_MS);
}

function releaseHeldLock() {
  if (heldLock.value) {
    collabClient?.releaseLock(heldLock.value.rowId, heldLock.value.fieldCode);
    heldLock.value = undefined;
  }
  stopLockRenew();
}

function handleSelection(cell?: { fieldCode: string; rowId: string }) {
  if (readonly.value || !workItemId.value) {
    return;
  }
  const same =
    heldLock.value?.rowId === cell?.rowId &&
    heldLock.value?.fieldCode === cell?.fieldCode;
  if (same) {
    return;
  }
  releaseHeldLock();
  if (!cell) {
    return;
  }
  heldLock.value = cell;
  collabClient?.acquireLock(cell.rowId, cell.fieldCode);
  startLockRenew();
}

function setupCollabClient() {
  if (!workItemId.value) return;
  collabClient = new FillCollabClient({
    callbacks: {
      onError: (msg) => message.warning(msg),
      onLockChanged: (payload) => {
        if (String(payload.holderUserId ?? '') === myUserId.value) {
          return;
        }
        editorRef.value
          ?.getAdapter()
          ?.setForeignLock(
            payload.rowId ?? '',
            payload.fieldCode,
            payload.holderUserId,
          );
      },
      onLockResult: (payload) => {
        if (payload.granted) {
          return;
        }
        editorRef.value
          ?.getAdapter()
          ?.setForeignLock(
            payload.rowId ?? '',
            payload.fieldCode,
            payload.holderUserId,
          );
        if (
          heldLock.value &&
          heldLock.value.rowId === payload.rowId &&
          heldLock.value.fieldCode === payload.fieldCode
        ) {
          heldLock.value = undefined;
          stopLockRenew();
        }
        void loadUserNames().then(() =>
          message.warning(
            payload.holderUserId
              ? `单元格正被 ${userLabel(payload.holderUserId)} 编辑`
              : '未能获得单元格编辑锁',
          ),
        );
      },
      onOperationApplied: (payload) => {
        if (payload.serverVersion > serverVersion.value) {
          void pullRemoteOperations();
        }
      },
      onPresenceChanged: ({ joined, userId }) => {
        const set = new Set(memberUserIds.value);
        if (joined) {
          set.add(userId);
        } else {
          set.delete(userId);
        }
        memberUserIds.value = [...set];
      },
      onPresenceSnapshot: (payload) => {
        memberUserIds.value = payload.memberUserIds ?? [];
        void loadUserNames();
        if (payload.serverVersion > serverVersion.value) {
          void pullRemoteOperations();
        }
      },
      onStatusChange: (open) => {
        wsOpen.value = open;
        if (open) {
          void pullRemoteOperations();
          if (heldLock.value && !readonly.value) {
            collabClient?.acquireLock(
              heldLock.value.rowId,
              heldLock.value.fieldCode,
            );
          }
        }
      },
    },
    refreshToken: accessStore.refreshToken as string,
    workItemId: workItemId.value,
  });
  collabClient.connect();
}

watch(
  () => workItemId.value,
  async (id) => {
    releaseHeldLock();
    collabClient?.close();
    collabClient = null;
    if (!id) {
      message.warning('请从「我的填报」进入工作台');
      const hasMyRoute = router
        .getRoutes()
        .some((item) => item.name === 'DataforgeFillMy');
      void router.replace(
        hasMyRoute
          ? { name: 'DataforgeFillMy' }
          : { path: '/dataforge/fill/my' },
      );
      return;
    }
    await loadSnapshot();
    setupCollabClient();
  },
  { immediate: true },
);

onBeforeUnmount(() => {
  releaseHeldLock();
  collabClient?.close();
  collabClient = null;
});

const LOG_LIMIT = 200;
const opsLog = shallowRef<string[]>([]);
const logOpen = ref(false);

function describeOperation(op: DomainOperation): string {
  switch (op.type) {
    case 'addRow': {
      return `addRow    rowId=${op.rowId}${op.afterRowId ? ` after=${op.afterRowId}` : ''}`;
    }
    case 'removeRow': {
      return `removeRow rowId=${op.rowId}`;
    }
    case 'setCell': {
      return `setCell   rowId=${op.rowId} field=${op.fieldCode} value=${JSON.stringify(op.value)}`;
    }
  }
}

function handleOperation(op: DomainOperation) {
  const time = new Date().toLocaleTimeString('zh-CN', { hour12: false });
  opsLog.value = [
    `[${time}] ${describeOperation(op)}`,
    ...opsLog.value.slice(0, LOG_LIMIT - 1),
  ];
  enqueueOperation(op);
}
</script>

<template>
  <Page auto-content-height>
    <div class="flex h-full flex-col gap-2">
      <div class="bg-card flex items-center justify-between rounded-md px-3 py-2">
        <Space wrap>
          <span class="font-medium">{{ snapshotMeta.taskName ?? '在线填报工作台' }}</span>
          <Tag color="blue">{{ snapshotMeta.orgName }}</Tag>
          <Tag>{{ snapshotMeta.periodKey }}</Tag>
          <Tag
            :color="
              snapshotMeta.overdue === 1
                ? 'error'
                : WORK_ITEM_STATUS_TEXT[snapshotMeta.status ?? 0]?.color
            "
          >
            {{ WORK_ITEM_STATUS_TEXT[snapshotMeta.status ?? 0]?.text ?? snapshotMeta.status
            }}{{ snapshotMeta.overdue === 1 ? '（已逾期）' : '' }}
          </Tag>
          <Tag v-if="readonly" color="warning">只读</Tag>
        </Space>

        <Space wrap>
            <Tag :color="wsOpen ? 'success' : 'error'">
              {{ wsOpen ? '协同已连接' : '协同未连接' }}
            </Tag>
            <Tag :title="memberUserIds.map((id) => userLabel(id)).join('、')">在线 {{ memberUserIds.length }} 人</Tag>
            <Tag>版本 {{ serverVersion }}</Tag>
            <Tag :color="pendingCount > 0 ? 'warning' : 'default'">
              未同步 {{ pendingCount }}
            </Tag>
            <Button @click="refreshSnapshot"><template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>刷新</Button>
            <Dropdown>
              <Button :loading="importing"><template #icon><IconifyIcon icon="lucide:file-spreadsheet" /></template>Excel</Button>
              <template #popupRender>
                <Menu @click="handleExcelMenu">
                  <MenuItem key="template"><template #icon><IconifyIcon icon="lucide:download" /></template>下载模板</MenuItem>
                  <MenuItem v-if="!readonly" key="import"><template #icon><IconifyIcon icon="lucide:upload" /></template>导入数据</MenuItem>
                  <MenuItem key="export"><template #icon><IconifyIcon icon="lucide:download" /></template>导出数据</MenuItem>
                </Menu>
              </template>
            </Dropdown>
            <Button v-if="!readonly" @click="openTransfer"><template #icon><IconifyIcon icon="lucide:user-round-pen" /></template>转派</Button>
            <Button v-if="issues.length > 0" :danger="errorCount > 0" @click="issuesOpen = true">
<template #icon><IconifyIcon icon="lucide:list-checks" /></template>
              {{ errorCount > 0 ? '校验问题' : '校验提示' }} {{ issues.length }}
            </Button>
            <Button @click="openSubmissions"><template #icon><IconifyIcon icon="lucide:history" /></template>提交历史</Button>
            <Button v-if="!readonly" @click="openEmptyReport"><template #icon><IconifyIcon icon="lucide:file-check" /></template>零上报</Button>
            <Button
              v-if="!readonly"
              :loading="submitting"
              type="primary"
              @click="handleSubmit"
            >
<template #icon><IconifyIcon icon="lucide:send" /></template>
              提交审批
            </Button>
            <input
              ref="fileInputRef"
              accept=".xlsx"
              class="hidden"
              type="file"
              @change="onImportFileChange"
            />
          <Button @click="logOpen = true"><template #icon><IconifyIcon icon="lucide:scroll-text" /></template>操作日志</Button>
        </Space>
      </div>

      <div class="bg-card min-h-0 flex-1 overflow-hidden rounded-md">
        <SheetEditor
          v-if="polyfillReady && fields.length > 0"
          ref="editorRef"
          :key="datasetKey"
          :fields="fields"
          :readonly="readonly"
          :rows="rows"
          @operation="handleOperation"
          @selection="handleSelection"
        />
      </div>
    </div>

    <Drawer v-model:open="logOpen" :width="560" title="领域操作日志（最近 200 条）">
      <p class="text-muted-foreground mb-2 text-xs">
        所有编辑路径（输入/粘贴/删除/插行/删行）都转为 rowId + fieldCode 领域操作；
        真实模式下按批（400ms 合并）提交到服务端并推进 serverVersion。
      </p>
      <div class="flex flex-col gap-1 font-mono text-xs">
        <div v-for="(line, index) in opsLog" :key="index" class="border-b pb-1">
          {{ line }}
        </div>
        <div v-if="opsLog.length === 0" class="text-muted-foreground">暂无操作</div>
      </div>
    </Drawer>

    <Drawer v-model:open="issuesOpen" :width="520" title="提交校验问题">
      <p class="text-muted-foreground mb-2 text-xs">
        点击问题条目可定位到对应单元格；错误必须全部修正后才能提交，警告不阻断提交。
      </p>
      <div class="flex flex-col gap-2">
        <div
          v-for="(issue, index) in issues"
          :key="index"
          class="cursor-pointer rounded-md border p-2 text-sm"
          :class="isWarning(issue) ? 'hover:border-yellow-400' : 'hover:border-red-400'"
          @click="focusIssue(issue)"
        >
          <Space>
            <Tag :color="isWarning(issue) ? 'warning' : 'error'">
              {{ isWarning(issue) ? '警告' : '错误' }} · {{ issue.ruleCode }}
            </Tag>
            <span v-if="issue.displayRowNo">第 {{ issue.displayRowNo }} 行</span>
            <span v-if="issue.fieldCode" class="font-mono text-xs">{{ issue.fieldCode }}</span>
          </Space>
          <div class="mt-1">{{ issue.message }}</div>
        </div>
        <div v-if="issues.length === 0" class="text-muted-foreground">暂无问题</div>
      </div>
    </Drawer>

    <Drawer v-model:open="submissionsOpen" :width="560" title="提交历史">
      <div class="flex flex-col gap-3">
        <div v-for="submission in submissions" :key="submission.id" class="rounded-md border p-3">
          <div class="flex items-center justify-between">
            <Space>
              <span class="font-medium">第 {{ submission.attemptNo }} 次提交</span>
              <Tag :color="APPROVAL_STATUS_TEXT[submission.approvalStatus]?.color">
                {{ APPROVAL_STATUS_TEXT[submission.approvalStatus]?.text }}
              </Tag>
              <Tag v-if="submission.submissionType === 2" color="warning">零上报</Tag>
            </Space>
            <Button
              v-if="canWithdraw(submission)"
              danger
              size="small"
              @click="handleWithdraw(submission)"
            >
<template #icon><IconifyIcon icon="lucide:undo-2" /></template>
              撤回
            </Button>
          </div>
          <div class="text-muted-foreground mt-1 text-xs">
            {{ submission.rowCount }} 行 · 提交人 {{ submission.submitterUserId }} ·
            {{ formatDateTime(submission.submitTime) }}
          </div>
          <div v-if="submission.emptyReason" class="mt-1 text-sm">
            零上报说明：{{ submission.emptyReason }}
          </div>
          <div v-if="submission.approvalReason" class="mt-1 text-sm">
            审批意见：{{ submission.approvalReason }}
          </div>
        </div>
        <div v-if="submissions.length === 0" class="text-muted-foreground">暂无提交记录</div>
      </div>
    </Drawer>

    <Modal
      v-model:open="importOpen"
      :confirm-loading="importConfirming"
      :ok-button-props="{ disabled: (importPreview?.errorCount ?? 0) > 0 }"
      :width="640"
      ok-text="确认导入"
      title="Excel 导入预检"
      @ok="handleImportConfirm"
    >
      <div v-if="importPreview" class="flex flex-col gap-3 py-2">
        <Space>
          <Tag color="processing">共 {{ importPreview.totalCount }} 行</Tag>
          <Tag :color="importPreview.errorCount > 0 ? 'error' : 'success'">
            {{ importPreview.errorCount > 0 ? `${importPreview.errorCount} 个错误` : '校验通过' }}
          </Tag>
          <Tag>匹配 {{ importPreview.matchedColumns.length }} 列</Tag>
        </Space>
        <div v-if="importPreview.ignoredHeaders.length > 0" class="text-muted-foreground text-xs">
          以下表头未匹配模板输入列，已忽略：{{ importPreview.ignoredHeaders.join('、') }}
        </div>
        <div>
          <RadioGroup v-model:value="importMode">
            <Radio :value="1">追加到现有草稿</Radio>
            <Radio :value="2">替换全部草稿行</Radio>
          </RadioGroup>
          <p v-if="importMode === 2" class="mt-1 text-xs text-red-500">
            替换将删除当前全部草稿行（操作日志保留，可追溯）；其他在线填报人会实时收到变更。
          </p>
        </div>
        <div
          v-if="importPreview.issues.length > 0"
          class="max-h-64 overflow-auto rounded-md border p-2"
        >
          <div
            v-for="(issue, index) in importPreview.issues.slice(0, 100)"
            :key="index"
            class="py-0.5 text-sm"
          >
            <Tag color="error">{{ issue.ruleCode }}</Tag>
            {{ issue.message }}
          </div>
          <div
            v-if="importPreview.issues.length > 100"
            class="text-muted-foreground pt-1 text-xs"
          >
            仅显示前 100 条，共 {{ importPreview.issues.length }} 条
          </div>
        </div>
        <p v-if="importPreview.errorCount > 0" class="text-xs text-red-500">
          存在校验错误时不允许导入，请修正 Excel 后重新上传。
        </p>
      </div>
    </Modal>

    <Modal
      v-model:open="emptyReportOpen"
      :confirm-loading="emptyReportSaving"
      ok-text="确认零上报"
      title="零上报（本期无数据）"
      @ok="handleEmptyReport"
    >
      <div class="flex flex-col gap-3 py-2">
        <p class="text-muted-foreground text-xs">
          零上报表示本组织本期确实没有需要报送的数据。提交后同样进入审批，
          审批通过即视为按期完成报送，不会被标记为逾期未报。
        </p>
        <p v-if="emptyReportRowCount > 0" class="text-sm text-red-500">
          当前草稿还有 {{ emptyReportRowCount }} 行数据，确认零上报将先删除全部草稿行
          （删除操作留痕、其他在线填报人实时可见），再提交零上报。
        </p>
        <div>
          <div class="mb-1 text-sm font-medium">零上报说明（必填）</div>
          <Input
            v-model:value="emptyReportReason"
            :maxlength="200"
            placeholder="如：本期无新增项目 / 本季度无涉改事项"
          />
        </div>
      </div>
    </Modal>

    <Modal
      v-model:open="transferOpen"
      :confirm-loading="transferSaving"
      title="填报职责转派"
      @ok="handleTransfer"
    >
      <div class="flex flex-col gap-3 py-2">
        <div>
          <div class="mb-1 text-sm font-medium">转派给</div>
          <Select
            v-model:value="transferTargetId"
            :options="transferUserOptions"
            class="w-full"
            option-filter-prop="label"
            placeholder="选择接收人（须为同组织内人员，服务端校验）"
            show-search
          />
        </div>
        <div>
          <div class="mb-1 text-sm font-medium">转派原因</div>
          <Input
            v-model:value="transferReason"
            placeholder="如：工作调整、休假交接"
          />
        </div>
        <p class="text-muted-foreground text-xs">
          转派后您将失去本实例的编辑与提交权限；数据仍属于同一组织实例，历史记录完整保留。
        </p>
      </div>
    </Modal>
  </Page>
</template>
