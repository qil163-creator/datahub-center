<script setup lang="ts">
import type { Dayjs } from 'dayjs';

import type { QualityWorkOrderApi } from '#/api/governance/quality/workorder';

import {
  computed,
  onBeforeUnmount,
  onDeactivated,
  reactive,
  ref,
  watch,
} from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  DatePicker,
  Input,
  Select,
  Spin,
  Tag,
  TextArea,
} from 'antdv-next';

import { replayEtlTargetBatch } from '#/api/dataforge/etl';
import { createCommandTracker } from '#/api/dataforge/execution/command';
import {
  confirmWorkOrder,
  extendWorkOrderDeadline,
  getWorkOrder,
  recheckWorkOrder,
  reopenWorkOrder,
  submitWorkOrderResolution,
} from '#/api/governance/quality/workorder';

import { createWorkOrderRequestGuard } from '../use-work-order-request';
import WorkOrderNotifications from './notifications.vue';
import {
  acceptance,
  ACTION_LABELS,
  CLOSE_LABELS,
  evidenceReasons,
  RECHECK_COLORS,
  RECHECK_LABELS,
  STATUS_LABELS,
  SUBMISSION_LABELS,
} from './resolution-state';

const emit = defineEmits<{ saved: [] }>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const router = useRouter();
const identity = () =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
  ]);
const permitted = (action: string) =>
  hasAccessByCodes([`governance:quality-work-order:${action}`]);
const canRead = computed(() => permitted('query'));
const shown = ref(false);
const loading = ref(false);
const busy = ref(false);
const error = ref('');
const note = ref('');
const order = ref<QualityWorkOrderApi.WorkOrder>();
const orderId = ref<QualityWorkOrderApi.Id>();
const expiry = ref<Dayjs>();
const newDeadline = ref<Dayjs>();
const deadlineBasis = ref('');
const form = reactive({
  type: 'SOURCE_FIX' as QualityWorkOrderApi.SubmissionType,
  content: '',
  evidenceRef: '',
  repairOrderId: '',
  exceptionScope: '',
  reason: '',
});
const replayForm = reactive({
  snapshotMode: 'ORIGINAL' as 'LATEST_PUBLISHED' | 'ORIGINAL',
  reason: '',
  confirmed: false,
});
const replayPending = ref(false);
const replayPayload = ref<
  | undefined
  | {
      confirmedTargetReplacement: true;
      reason: string;
      snapshotMode: 'LATEST_PUBLISHED' | 'ORIGINAL';
      submissionId: QualityWorkOrderApi.Id;
      workOrderId: QualityWorkOrderApi.Id;
    }
>();
const commandApi = {
  confirm: confirmWorkOrder,
  extend: extendWorkOrderDeadline,
  recheck: recheckWorkOrder,
  reopen: reopenWorkOrder,
  submit: submitWorkOrderResolution,
};
const commandPermission = {
  confirm: 'confirm',
  extend: 'process',
  recheck: 'process',
  reopen: 'reopen',
  submit: 'process',
};
type Operation = keyof typeof commandApi;
type Pending = Omit<QualityWorkOrderApi.Command, 'requestId'> & {
  operation: Operation;
};
const pending = ref<Pending>();
const receipt = ref<QualityWorkOrderApi.Recheck>();
const requests = createWorkOrderRequestGuard();
let generation = 0;
const commands = createCommandTracker(() => `${identity()}:${generation}`);
const replayCommands = createCommandTracker(identity);
const writable = computed(
  () => !!order.value && Number.isInteger(order.value.lockVersion),
);
const hasBaseline = computed(
  () => order.value?.taskId !== undefined && order.value.taskId !== null,
);
const isEtlInline = computed(() => order.value?.sourceType === 'ETL_INLINE');
const allowed = (action: string, permission: string) =>
  writable.value &&
  permitted(permission) &&
  !!order.value?.allowedActions?.includes(action);
const canSubmit = computed(() => allowed('SUBMIT', 'process'));
const canRecheck = computed(
  () => !isEtlInline.value && allowed('RECHECK', 'process'),
);
const canConfirm = computed(
  () =>
    allowed('CONFIRM', 'confirm') &&
    (!isEtlInline.value ||
      (order.value?.currentSubmission?.submissionType === 'SOURCE_FIX' &&
        order.value?.confirmationPendingType === 'RECHECK_PASS')) &&
    order.value?.currentSubmission?.submittedBy !== undefined &&
    order.value.currentSubmission.submittedBy !== null &&
    String(order.value.currentSubmission.submittedBy) !==
      String(user.userInfo?.userId) &&
    (order.value.confirmerUserId === undefined ||
      order.value.confirmerUserId === null ||
      String(order.value.confirmerUserId) === String(user.userInfo?.userId)),
);
const canEtlReplay = computed(
  () =>
    isEtlInline.value &&
    hasAccessByCodes(['dataforge:etl:run']) &&
    order.value?.currentSubmission?.submissionType === 'SOURCE_FIX' &&
    order.value?.orderStatus === 'PENDING_RECHECK' &&
    !busy.value,
);
const conclusion = computed(() =>
  order.value ? acceptance(order.value) : undefined,
);
const typeOptions = computed(() =>
  Object.entries(SUBMISSION_LABELS).map(([value, label]) => ({
    value,
    label,
    disabled: isEtlInline.value
      ? value !== 'SOURCE_FIX'
      : !hasBaseline.value && ['PLATFORM_REPAIR', 'SOURCE_FIX'].includes(value),
  })),
);

function reset() {
  ++generation;
  requests.begin();
  commands.invalidate();
  order.value = undefined;
  orderId.value = undefined;
  pending.value = undefined;
  receipt.value = undefined;
  error.value = '';
  note.value = '';
  loading.value = false;
  busy.value = false;
  expiry.value = undefined;
  newDeadline.value = undefined;
  deadlineBasis.value = '';
  if (!replayPending.value) {
    replayPayload.value = undefined;
    Object.assign(replayForm, {
      snapshotMode: 'ORIGINAL',
      reason: '',
      confirmed: false,
    });
  }
  Object.assign(form, {
    type: 'SOURCE_FIX',
    content: '',
    evidenceRef: '',
    repairOrderId: '',
    exceptionScope: '',
    reason: '',
  });
}
const [Modal, modalApi] = useVbenModal({
  title: '工单处理与复检记录',
  class: 'w-[880px]',
  contentClass: 'bg-muted/40 p-4 sm:p-5',
  footerClass: 'px-4 sm:px-5',
  cancelText: '关闭',
  showConfirmButton: false,
  onOpenChange(isOpen: boolean) {
    shown.value = isOpen;
    if (!isOpen) reset();
  },
});
function open(id: QualityWorkOrderApi.Id) {
  reset();
  orderId.value = id;
  shown.value = true;
  modalApi.open();
  void load();
}
defineExpose({ open });
async function load() {
  if (
    !shown.value ||
    orderId.value === undefined ||
    orderId.value === null ||
    busy.value
  )
    return;
  const request = requests.begin();
  const scope = identity();
  const current = () =>
    shown.value &&
    requests.isLatest(request) &&
    scope === identity() &&
    canRead.value;
  order.value = undefined;
  if (!canRead.value) {
    error.value = '无权查看工单';
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    const result = await getWorkOrder(orderId.value);
    if (!current()) return;
    if (
      String(result.id) !== String(orderId.value) ||
      !Number.isInteger(result.lockVersion)
    )
      throw new Error('不完整的工单详情');
    order.value = result;
    if (pending.value && result.lockVersion !== pending.value.expectedVersion) {
      pending.value = undefined;
      commands.invalidate();
      note.value = '工单版本已更新，请核对下方最新状态和操作记录后再处理。';
    }
    if (isEtlInline.value) form.type = 'SOURCE_FIX';
    else if (
      !hasBaseline.value &&
      ['PLATFORM_REPAIR', 'SOURCE_FIX'].includes(form.type)
    )
      form.type = 'EXPLAIN';
  } catch {
    if (current())
      error.value = '未取得最新工单，请刷新核对后再操作。';
  } finally {
    if (current()) loading.value = false;
  }
}
async function startEtlReplay() {
  const current = order.value;
  if (!canEtlReplay.value || !current?.id || !current.currentSubmissionId)
    return;
  if (
    replayPending.value &&
    replayPayload.value &&
    (String(replayPayload.value.workOrderId) !== String(current.id) ||
      String(replayPayload.value.submissionId) !==
        String(current.currentSubmissionId))
  ) {
    error.value = `工单 #${replayPayload.value.workOrderId} 的回放请求结果仍未确认，请先打开该工单原样重试。`;
    return;
  }
  if (!replayForm.confirmed) {
    error.value = '请先确认回放会受控替换原目标批次并保留原批次审计证据';
    return;
  }
  if (!replayForm.reason.trim()) {
    error.value = '请填写本次 ETL 回放原因';
    return;
  }
  replayPayload.value ??= {
    workOrderId: current.id,
    submissionId: current.currentSubmissionId,
    snapshotMode: replayForm.snapshotMode,
    reason: replayForm.reason.trim(),
    confirmedTargetReplacement: true,
  };
  const request = requests.begin();
  const scope = identity();
  busy.value = true;
  const response = await replayCommands
    .execute(
      `etl-target-replay:${current.id}`,
      { ...replayPayload.value },
      replayEtlTargetBatch,
    )
    .catch(() => {
      if (requests.isLatest(request) && scope === identity()) {
        replayPending.value = true;
        error.value =
          '回放请求结果未确认。请使用同一请求重试，或稍后到执行记录核对。';
      }
      return undefined;
    })
    .finally(() => {
      if (requests.isLatest(request) && scope === identity())
        busy.value = false;
    });
  if (!response || scope !== identity()) return;
  replayPending.value = false;
  replayPayload.value = undefined;
  shown.value = false;
  modalApi.close();
  await router.push({
    name: 'DataforgeEtlRecord',
    query: { recordId: String(response.recordId) },
  });
}
function payload(
  operation: Operation,
  confirmAction?: QualityWorkOrderApi.ConfirmAction,
): Pending | undefined {
  const current = order.value;
  if (
    !current ||
    !writable.value ||
    current.id === undefined ||
    current.id === null ||
    current.lockVersion === undefined ||
    current.lockVersion === null
  )
    return;
  const base: Pending = {
    operation,
    id: current.id,
    expectedVersion: current.lockVersion,
  };
  if (operation === 'reopen') {
    if (!allowed('REOPEN', 'reopen')) return;
    base.reason = form.reason.trim() || undefined;
  } else if (operation === 'extend') {
    if (!allowed('EXTEND', 'process')) return;
    const timestamp = newDeadline.value?.valueOf();
    if (
      !timestamp ||
      timestamp <= Math.max(Date.now(), Number(current.deadline) || 0)
    ) {
      error.value = '请选择晚于当前时间和原截止时间的新截止时间';
      return;
    }
    base.newDeadline = timestamp;
    base.deadlineBasis = deadlineBasis.value.trim() || undefined;
  } else if (operation === 'submit') {
    if (!canSubmit.value) return;
    if (isEtlInline.value && form.type !== 'SOURCE_FIX') {
      error.value = 'ETL 内联工单仅支持提交源端整改说明';
      return;
    }
    if (!Object.hasOwn(SUBMISSION_LABELS, form.type)) {
      error.value = '请选择有效整改类型';
      return;
    }
    if (!form.content.trim()) {
      error.value =
        form.type === 'EXCEPTION' ? '请填写例外依据' : '请填写整改或说明内容';
      return;
    }
    if (
      !isEtlInline.value &&
      !hasBaseline.value &&
      ['PLATFORM_REPAIR', 'SOURCE_FIX'].includes(form.type)
    ) {
      error.value = '无检测基线，请选择说明确认或例外申请';
      return;
    }
    Object.assign(base, {
      submissionType: form.type,
      content: form.content.trim(),
      evidenceRef: form.evidenceRef.trim() || undefined,
    });
    if (form.type === 'PLATFORM_REPAIR') {
      const repairId = form.repairOrderId.trim();
      if (
        !/^[1-9]\d{0,18}$/.test(repairId) ||
        (repairId.length === 19 && repairId > '9223372036854775807')
      ) {
        error.value = '请输入有效修复单编号';
        return;
      }
      base.repairOrderId = repairId;
    }
    if (form.type === 'EXCEPTION') {
      const timestamp = expiry.value?.valueOf();
      if (
        !form.exceptionScope.trim() ||
        !timestamp ||
        timestamp <= Date.now()
      ) {
        error.value = '请填写例外范围并选择未来的到期时间';
        return;
      }
      base.exceptionScope = form.exceptionScope.trim();
      base.exceptionExpireAt = timestamp;
    }
  } else {
    if (
      current.currentSubmissionId === undefined ||
      current.currentSubmissionId === null
    ) {
      error.value = '缺少当前提交，请刷新核对';
      return;
    }
    base.submissionId = current.currentSubmissionId;
    if (operation === 'recheck') {
      if (!canRecheck.value) return;
    } else {
      if (
        !canConfirm.value ||
        !confirmAction ||
        (confirmAction !== 'RETURN' &&
          conclusion.value?.action !== confirmAction)
      )
        return;
      if (confirmAction === 'RETURN' && !form.reason.trim()) {
        error.value = '退回整改必须填写原因';
        return;
      }
      base.confirmAction = confirmAction;
      base.reason = form.reason.trim();
    }
  }
  return base;
}
async function send(
  operation: Operation,
  action?: QualityWorkOrderApi.ConfirmAction,
) {
  if (busy.value || loading.value || !canRead.value) return;
  error.value = '';
  const command = pending.value ?? payload(operation, action);
  if (!command) return;
  if (!permitted(commandPermission[command.operation])) return;
  // 未确认请求只允许原样重试；新命令须先刷新核对，不能更换幂等身份。
  pending.value = command;
  const scope = identity();
  const started = generation;
  const current = () =>
    shown.value &&
    scope === identity() &&
    started === generation &&
    canRead.value;
  busy.value = true;
  try {
    const result = await commands.execute(
      'work-order-command',
      command,
      ({ operation: kind, ...body }) => commandApi[kind](body),
    );
    if (!current() || !result) return;
    pending.value = undefined;
    order.value = result;
    receipt.value = result.recheckAttempt;
    if (command.operation === 'reopen') {
      note.value = '工单已重新打开，责任人可继续办理。';
    } else if (command.operation === 'extend') {
      newDeadline.value = undefined;
      deadlineBasis.value = '';
      note.value = '整改截止时间已更新。';
    } else if (command.operation === 'recheck') {
      note.value = '复检已提交，运行完成和可比性判定前不视为通过。';
    } else if (command.confirmAction === 'RETURN') {
      note.value = '已退回整改，原因已记录。';
    } else if (result.closeType) {
      note.value = CLOSE_LABELS[result.closeType] ?? result.closeType;
    } else {
      note.value = '提交已记录，请按最新状态继续复检或等待验收。';
    }
    form.reason = '';
    emit('saved');
  } catch {
    if (current())
      error.value =
        '请求未确认成功。可原样重试；若服务拒绝或状态已变更，请刷新核对，必要时关闭后重新办理。';
  } finally {
    if (current()) busy.value = false;
  }
}
watch(
  () => [
    identity(),
    canRead.value,
    permitted('process'),
    permitted('confirm'),
    permitted('reopen'),
  ],
  () => {
    shown.value = false;
    reset();
    void modalApi.close();
  },
  { flush: 'sync' },
);
onDeactivated(() => {
  shown.value = false;
  reset();
  void modalApi.close();
});
onBeforeUnmount(() => {
  shown.value = false;
  reset();
  requests.dispose();
});
</script>

<template>
  <Modal>
    <div class="space-y-4" aria-label="工单处理">
      <div v-if="loading" class="loading-state" role="status">
        <Spin size="small" />
        正在读取最新工单
      </div>
      <Alert
        v-if="error"
        role="alert"
        type="warning"
        :title="error"
        show-icon
      />
      <Alert v-if="note" type="info" :title="note" show-icon />
      <Button
        v-if="pending"
        data-action="retry"
        :loading="busy"
        :disabled="busy || loading"
        @click="send(pending.operation)"
      >
        <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
        原样重试未确认请求
      </Button>

      <template v-if="order">
        <section class="resolution-panel" aria-label="工单概况">
          <div class="summary-header">
            <span class="summary-icon">
              <IconifyIcon icon="lucide:clipboard-list" aria-hidden="true" />
            </span>
            <div class="min-w-0 flex-1">
              <p class="muted text-xs">
                工单 {{ order.orderCode || `#${order.id}` }}
              </p>
              <h3 class="summary-title">
                {{ order.orderName || '未命名工单' }}
              </h3>
            </div>
            <Tag
              class="m-0!"
              :color="STATUS_LABELS[order.orderStatus ?? '']?.[1]"
            >
              {{
                STATUS_LABELS[order.orderStatus ?? '']?.[0] ??
                order.orderStatus ??
                '状态未知'
              }}
            </Tag>
          </div>
          <dl class="summary-meta">
            <div>
              <dt>工单版本</dt>
              <dd>{{ order.lockVersion }}</dd>
            </div>
            <div v-if="order.deadline">
              <dt>整改截止时间</dt>
              <dd class="tabular-nums">{{ formatDateTime(order.deadline) }}</dd>
            </div>
            <div v-if="order.closeType">
              <dt>关闭结论</dt>
              <dd>{{ CLOSE_LABELS[order.closeType] ?? order.closeType }}</dd>
            </div>
          </dl>
        </section>

        <Alert
          v-if="order.lastError"
          type="warning"
          :title="order.lastError"
          show-icon
        />
        <Alert
          v-if="isEtlInline"
          type="info"
          title="ETL 工单通过受控回放完成复检：回放会覆盖原目标批次并保留历史证据，治理服务核验原始全量身份、每个目标批次和真实质检实例后才允许验收。"
          show-icon
        />
        <section
          v-if="isEtlInline && order.etlReport"
          class="resolution-panel space-y-2"
        >
          <h4 class="section-title">
            ETL 治理报告 #{{ order.etlReport.reportId }}
          </h4>
          <p class="muted">
            执行 {{ order.etlReport.runId }} · 节点
            {{ order.etlReport.nodeId }} · 批次 {{ order.etlReport.batchId }}
          </p>
          <p>
            总行数 {{ order.etlReport.totalCount }} · 合格
            {{ order.etlReport.passCount }} · 警告
            {{ order.etlReport.warnCount }} · 不合格
            {{ order.etlReport.rejectCount }}
          </p>
          <p class="muted">执行计划 {{ order.etlReport.planHash }}</p>
          <p v-if="order.etlReport.identityHash" class="muted">
            原始全量身份摘要 {{ order.etlReport.identityHash }}
          </p>
        </section>
        <Alert
          v-if="!hasBaseline && !isEtlInline"
          type="info"
          title="本工单无检测基线，可提交说明或申请例外；这两类结论不代表质量通过。"
          show-icon
        />

        <section
          v-if="canSubmit"
          class="resolution-panel space-y-4"
          aria-label="提交整改"
        >
          <h4 class="section-title">提交整改</h4>
          <div class="form-grid">
            <label class="field">整改类型<Select
                v-model:value="form.type"
                aria-label="整改类型"
                class="w-full"
                :options="typeOptions"
                :disabled="busy || !!pending"
            /></label>
            <label class="field">证据引用（选填）<Input
                v-model:value="form.evidenceRef"
                aria-label="证据引用"
                :maxlength="512"
                :disabled="busy || !!pending"
            /></label>
            <label class="field form-full">{{
                form.type === 'EXCEPTION'
                  ? '例外依据（必填）'
                  : '整改或说明内容（必填）'
              }}<TextArea
                v-model:value="form.content"
                aria-label="整改内容"
                :rows="3"
                :maxlength="2000"
                :disabled="busy || !!pending"
            /></label>
            <template v-if="form.type === 'PLATFORM_REPAIR'">
              <label class="field">成功修复单编号<Input
                  v-model:value="form.repairOrderId"
                  aria-label="修复单编号"
                  :maxlength="19"
                  :disabled="busy || !!pending"
              /></label>
              <p class="muted form-full text-xs">
                从修复工单页面复制编号。服务端核对本工单问题对象、审批通过且执行成功；绑定后仍须复检。
              </p>
            </template>
            <template v-if="form.type === 'EXCEPTION'">
              <label class="field">例外范围（必填）<TextArea
                  v-model:value="form.exceptionScope"
                  aria-label="例外范围"
                  :maxlength="512"
                  :disabled="busy || !!pending"
              /></label>
              <label class="field">例外到期时间（必填）<DatePicker
                  v-model:value="expiry"
                  aria-label="例外到期时间"
                  show-time
                  class="w-full"
                  :disabled="busy || !!pending"
              /></label>
            </template>
          </div>
          <div class="flex justify-end">
            <Button
              data-action="submit"
              type="primary"
              :disabled="busy || !!pending"
              @click="send('submit')"
            >
              <template #icon><IconifyIcon icon="lucide:send" /></template>
              提交整改
            </Button>
          </div>
        </section>

        <section
          v-if="order.currentSubmission"
          class="resolution-panel space-y-4"
          aria-label="当前整改提交"
        >
          <div class="section-header">
            <h4 class="section-title">当前整改提交</h4>
            <Tag
              class="m-0!"
              :color="
                RECHECK_COLORS[order.currentSubmission.recheckStatus ?? '']
              "
            >
              {{
                RECHECK_LABELS[order.currentSubmission.recheckStatus ?? ''] ??
                order.currentSubmission.recheckStatus ??
                '复检不适用'
              }}
            </Tag>
          </div>
          <div class="space-y-2">
            <p class="font-medium">
              {{
                SUBMISSION_LABELS[
                  order.currentSubmission
                    .submissionType as QualityWorkOrderApi.SubmissionType
                ] ??
                order.currentSubmission.submissionType ??
                '整改提交'
              }}
              <span
                v-if="order.currentSubmission.revision !== undefined"
                class="muted ml-2 text-xs"
              >
                第 {{ order.currentSubmission.revision }} 版
              </span>
            </p>
            <p
              v-if="order.currentSubmission.content"
              class="whitespace-pre-wrap"
            >
              {{ order.currentSubmission.content }}
            </p>
            <p v-if="order.currentSubmission.evidenceRef" class="muted">
              证据引用 {{ order.currentSubmission.evidenceRef }}
            </p>
            <p v-if="order.currentSubmission.repairOrderId" class="muted">
              修复单 #{{ order.currentSubmission.repairOrderId }}
            </p>
            <p v-if="order.currentSubmission.exceptionScope" class="muted">
              例外范围 {{ order.currentSubmission.exceptionScope }} · 到期
              {{ formatDateTime(order.currentSubmission.exceptionExpireAt) }}
            </p>
            <Alert
              v-if="order.currentSubmission.lastError"
              type="warning"
              :title="order.currentSubmission.lastError"
              show-icon
            />
          </div>

          <section
            class="space-y-3 border-t pt-4"
            aria-label="当前提交的复检轮次"
          >
            <div class="section-header">
              <h5 class="font-medium">当前提交的复检轮次</h5>
              <Button
                v-if="canRecheck"
                data-action="recheck"
                :disabled="busy || !!pending"
                @click="send('recheck')"
              >
                <template #icon><IconifyIcon icon="lucide:play" /></template>
                启动新一轮复检
              </Button>
            </div>
            <p v-if="receipt" class="muted text-xs">
              本次命令回执：第 {{ receipt.attemptNo }} 轮 · 实例
              {{ receipt.instanceId ?? '—' }}
            </p>
            <p
              v-if="!order.currentSubmission.rechecks?.length"
              class="empty-state"
            >
              暂无复检轮次记录
            </p>
            <template v-else>
              <p class="muted text-xs">复检结论以本轮结果为准。</p>
              <article
                v-for="attempt in order.currentSubmission.rechecks"
                :key="String(attempt.id)"
                class="detail-card space-y-2"
              >
                <div class="section-header">
                  <p class="font-medium">
                    第 {{ attempt.attemptNo }} 轮
                    <span class="muted ml-2 text-xs">实例 {{ attempt.instanceId ?? '—' }}</span>
                  </p>
                  <Tag class="m-0!" :color="RECHECK_COLORS[attempt.status]">
                    {{ RECHECK_LABELS[attempt.status] ?? attempt.status }}
                  </Tag>
                </div>
                <p class="muted text-xs">
                  {{
                    attempt.comparableFlag === 1 ? '可比' : '尚不可比 / 未证明'
                  }}
                  · {{ evidenceReasons(attempt.evidenceJson) }}
                </p>
                <dl class="attempt-times">
                  <div>
                    <dt>发起时间</dt>
                    <dd>{{ formatDateTime(attempt.requestedAt) || '—' }}</dd>
                  </div>
                  <div>
                    <dt>结束时间</dt>
                    <dd>
                      {{
                        attempt.finishedAt
                          ? formatDateTime(attempt.finishedAt)
                          : '未结束'
                      }}
                    </dd>
                  </div>
                </dl>
                <p
                  v-if="attempt.lastError"
                  class="text-xs text-destructive whitespace-pre-wrap"
                >
                  {{ attempt.lastError }}
                </p>
              </article>
            </template>
          </section>

          <section
            v-if="canEtlReplay"
            class="detail-card space-y-3"
            aria-label="ETL 受控回放复检"
          >
            <h5 class="font-medium">ETL 受控回放复检</h5>
            <p class="muted text-xs">
              仅在本次源端整改后使用。回放会对原目标批次执行受控替换，保持原物理批次范围，逐个目标绑定新的治理质检实例；原始报告与旧实例仍保留供审计。
            </p>
            <label class="field">回放配置<Select
                v-model:value="replayForm.snapshotMode"
                aria-label="回放配置"
                class="w-full"
                :options="[
                  { value: 'ORIGINAL', label: '复用原运行冻结配置' },
                  {
                    value: 'LATEST_PUBLISHED',
                    label: '使用当前最新已发布配置',
                  },
                ]"
                :disabled="busy || replayPending"
            /></label>
            <label class="field">回放原因<TextArea
                v-model:value="replayForm.reason"
                aria-label="回放原因"
                :maxlength="1000"
                :rows="2"
                :disabled="busy || replayPending"
            /></label>
            <label class="flex items-start gap-2">
              <input
                v-model="replayForm.confirmed"
                aria-label="确认替换原目标批次"
                class="mt-1"
                type="checkbox"
                :disabled="busy || replayPending"
              />
              <span>我确认本次回放将受控替换原目标批次，并保留原批次审计证据。</span>
            </label>
            <Button
              data-action="etl-replay"
              type="primary"
              :loading="busy"
              :disabled="busy || (replayPending && !replayPayload)"
              @click="startEtlReplay"
            >
              <template #icon><IconifyIcon icon="lucide:play" /></template>
              {{
                replayPending ? '原样重试未确认的回放请求' : '启动 ETL 回放复检'
              }}
            </Button>
          </section>

          <div v-if="canConfirm" class="detail-card space-y-3">
            <h5 class="font-medium">验收整改</h5>
            <label class="field">验收意见 / 退回原因<TextArea
                v-model:value="form.reason"
                aria-label="验收意见"
                :rows="2"
                :maxlength="1024"
                :disabled="busy || !!pending"
            /></label>
            <p v-if="!conclusion" class="muted text-xs">
              当前凭证不满足接受条件，可退回补充。
            </p>
            <div class="flex flex-wrap justify-end gap-2">
              <Button
                data-action="return"
                danger
                :disabled="busy || !!pending"
                @click="send('confirm', 'RETURN')"
              >
                <template #icon><IconifyIcon icon="lucide:undo-2" /></template>
                退回整改
              </Button>
              <Button
                v-if="conclusion"
                data-action="accept"
                type="primary"
                :disabled="busy || !!pending"
                @click="send('confirm', conclusion.action)"
              >
                <template #icon>
<IconifyIcon icon="lucide:clipboard-check" />
</template>
                {{ conclusion.label }}
              </Button>
            </div>
          </div>
          <p
            v-else-if="order.orderStatus === 'PENDING_CONFIRMATION'"
            class="muted text-xs"
          >
            等待有验收权限的非提交人确认；指定验收人时仅该人员可办理。
          </p>
        </section>

        <section
          v-if="allowed('REOPEN', 'reopen')"
          class="resolution-panel space-y-3"
          aria-label="重开工单"
        >
          <h4 class="section-title">重开工单</h4>
          <div class="flex flex-wrap items-end gap-3">
            <label class="field grow basis-64">重开原因（选填）<TextArea
                v-model:value="form.reason"
                aria-label="重开原因"
                :auto-size="{ minRows: 1, maxRows: 4 }"
                :maxlength="1024"
                :disabled="busy || !!pending"
            /></label>
            <Button
              data-action="reopen"
              class="ml-auto"
              :disabled="busy || !!pending"
              @click="send('reopen')"
            >
              <template #icon>
<IconifyIcon icon="lucide:rotate-ccw" />
</template>
              重新打开工单
            </Button>
          </div>
        </section>
        <section
          v-if="allowed('EXTEND', 'process')"
          class="resolution-panel space-y-3"
          aria-label="延长截止时间"
        >
          <h4 class="section-title">整改延期</h4>
          <div class="form-grid">
            <label class="field">新的整改截止时间<DatePicker
                v-model:value="newDeadline"
                aria-label="新的整改截止时间"
                show-time
                class="w-full"
                :disabled="busy || !!pending"
            /></label>
            <label class="field">延期依据（选填）<Input
                v-model:value="deadlineBasis"
                aria-label="延期依据"
                :maxlength="256"
                :disabled="busy || !!pending"
            /></label>
          </div>
          <div class="flex justify-end">
            <Button
              data-action="extend"
              :disabled="busy || !!pending"
              @click="send('extend')"
              >
延长截止时间
</Button>
          </div>
        </section>

        <section class="resolution-panel space-y-3" aria-label="操作记录">
          <div class="section-header">
            <h4 class="section-title">操作记录</h4>
            <span v-if="order.history?.length" class="muted text-xs">{{ order.history.length }} 条</span>
          </div>
          <ol v-if="order.history?.length" class="history-list">
            <li
              v-for="action in order.history"
              :key="String(action.id)"
              class="history-item"
            >
              <div class="section-header">
                <p class="font-medium">
                  {{
                    ACTION_LABELS[action.actionType ?? ''] ??
                    action.actionType ??
                    '工单操作'
                  }}
                </p>
                <span class="muted text-xs tabular-nums">{{
                  formatDateTime(action.createTime) || '—'
                }}</span>
              </div>
              <div
                v-if="action.fromStatus || action.toStatus"
                class="history-status"
              >
                <span>{{
                  STATUS_LABELS[action.fromStatus ?? '']?.[0] ??
                  action.fromStatus ??
                  '—'
                }}</span>
                <IconifyIcon
                  class="size-3.5"
                  icon="lucide:arrow-right"
                  aria-label="变为"
                />
                <span>{{
                  STATUS_LABELS[action.toStatus ?? '']?.[0] ??
                  action.toStatus ??
                  '—'
                }}</span>
              </div>
              <p v-if="action.reason" class="history-reason">
                {{ action.reason }}
              </p>
            </li>
          </ol>
          <p v-else class="empty-state">暂无操作记录</p>
        </section>
        <div v-if="shown && order.id" class="resolution-panel">
          <WorkOrderNotifications :work-order-id="order.id" />
        </div>
      </template>
    </div>

    <template #prepend-footer>
      <Button
        class="mr-auto"
        :loading="loading"
        :disabled="busy || loading || !canRead"
        @click="load"
      >
        <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
        刷新核对
      </Button>
    </template>
  </Modal>
</template>

<style scoped>
.resolution-panel {
  padding: 16px;
  font-size: 14px;
  line-height: 1.5;
  overflow-wrap: anywhere;
  background: hsl(var(--card));
  border: 1px solid hsl(var(--border));
  border-radius: 10px;
}

.summary-header {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-start;
}

.summary-icon {
  display: grid;
  flex: none;
  place-items: center;
  width: 40px;
  height: 40px;
  font-size: 20px;
  color: hsl(var(--primary));
  background: hsl(var(--primary) / 9%);
  border-radius: 10px;
}

.summary-title {
  margin-top: 3px;
  font-size: 16px;
  font-weight: 600;
}

.summary-meta,
.attempt-times {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 28px;
}

.summary-meta {
  padding-top: 12px;
  margin-top: 14px;
  border-top: 1px solid hsl(var(--border));
}

.summary-meta dt,
.attempt-times dt,
.muted {
  color: hsl(var(--muted-foreground));
}

.summary-meta dt {
  margin-bottom: 3px;
  font-size: 12px;
}

.summary-meta dd {
  font-weight: 500;
}

.section-title {
  font-size: 15px;
  font-weight: 600;
}

.section-header {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 12px;
  align-items: center;
  justify-content: space-between;
}

.detail-card {
  padding: 12px;
  background: hsl(var(--muted) / 50%);
  border: 1px solid hsl(var(--border));
  border-radius: 8px;
}

.attempt-times {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.attempt-times dd {
  margin-top: 2px;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 250px), 1fr));
  gap: 12px;
}

.form-full {
  grid-column: 1 / -1;
}

.field {
  display: grid;
  gap: 5px;
  align-content: start;
  min-width: 0;
  font-size: 13px;
}

.loading-state {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: center;
  min-height: 120px;
  color: hsl(var(--muted-foreground));
}

.empty-state {
  padding: 12px;
  font-size: 13px;
  color: hsl(var(--muted-foreground));
  text-align: center;
  background: hsl(var(--muted) / 50%);
  border-radius: 6px;
}

.history-list {
  padding-left: 5px;
}

.history-item {
  position: relative;
  padding: 0 0 14px 15px;
  border-left: 1px solid hsl(var(--border));
}

.history-item:last-child {
  padding-bottom: 0;
  border-color: transparent;
}

.history-item::before {
  position: absolute;
  top: 5px;
  left: -5px;
  width: 9px;
  height: 9px;
  content: '';
  background: hsl(var(--card));
  border: 2px solid hsl(var(--primary) / 65%);
  border-radius: 50%;
}

.history-status {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin-top: 3px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.history-reason {
  padding: 6px 9px;
  margin-top: 6px;
  font-size: 13px;
  white-space: pre-wrap;
  background: hsl(var(--muted) / 50%);
  border-radius: 6px;
}
</style>
