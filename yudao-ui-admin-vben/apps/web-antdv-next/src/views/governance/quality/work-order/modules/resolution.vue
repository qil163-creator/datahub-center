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
  TextArea,
} from 'antdv-next';

import { replayEtlTargetBatch } from '#/api/dataforge/etl';
import { createCommandTracker } from '#/api/dataforge/execution/command';
import {
  confirmWorkOrder,
  getWorkOrder,
  recheckWorkOrder,
  submitWorkOrderResolution,
} from '#/api/governance/quality/workorder';

import { createWorkOrderRequestGuard } from '../use-work-order-request';
import WorkOrderNotifications from './notifications.vue';
import {
  acceptance,
  CLOSE_LABELS,
  evidenceReasons,
  RECHECK_LABELS,
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
type Operation = 'confirm' | 'recheck' | 'submit';
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
  class: 'w-[800px]',
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
      error.value = '详情加载失败，请重试；未取得最新详情前不可操作。';
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
  if (operation === 'submit') {
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
  if (!permitted(command.operation === 'confirm' ? 'confirm' : 'process'))
    return;
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
      ({ operation: kind, ...body }) => {
        if (kind === 'submit') return submitWorkOrderResolution(body);
        if (kind === 'confirm') return confirmWorkOrder(body);
        return recheckWorkOrder(body);
      },
    );
    if (!current() || !result) return;
    pending.value = undefined;
    order.value = result;
    receipt.value = result.recheckAttempt;
    if (command.operation === 'recheck') {
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
  () => [identity(), canRead.value, permitted('process'), permitted('confirm')],
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
      <Button :disabled="busy || loading || !canRead" @click="load">
        <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
        刷新核对
      </Button>
      <div v-if="loading" role="status"><Spin /> 正在读取最新工单</div>
      <Alert
        v-if="error"
        role="alert"
        type="warning"
        :title="error"
        show-icon
      />
      <Alert v-if="note" type="info" :title="note" show-icon />
      <template v-if="order">
        <h3>{{ order.orderName }} · #{{ order.id }}</h3>
        <p>状态：{{ order.orderStatus }} · 版本：{{ order.lockVersion }}</p>
        <p v-if="order.closeType">
          关闭结论：{{ CLOSE_LABELS[order.closeType] ?? order.closeType }}
        </p>
        <p v-if="order.lastError">处理提示：{{ order.lastError }}</p>
        <Alert
          v-if="isEtlInline"
          type="info"
          title="ETL 工单通过受控回放完成复检：回放会覆盖原目标批次并保留历史证据，治理服务核验原始全量身份、每个目标批次和真实质检实例后才允许验收。"
        />
        <div
          v-if="isEtlInline && order.etlReport"
          class="space-y-1 rounded border p-3"
        >
          <p>
            治理报告 #{{ order.etlReport.reportId }} · 执行
            {{ order.etlReport.runId }} · 节点 {{ order.etlReport.nodeId }} ·
            批次 {{ order.etlReport.batchId }}
          </p>
          <p>
            总行数 {{ order.etlReport.totalCount }} · 合格
            {{ order.etlReport.passCount }} · 警告
            {{ order.etlReport.warnCount }} · 不合格
            {{ order.etlReport.rejectCount }}
          </p>
          <p class="break-all">执行计划 {{ order.etlReport.planHash }}</p>
          <p v-if="order.etlReport.identityHash" class="break-all">
            原始全量身份摘要 {{ order.etlReport.identityHash }}
          </p>
        </div>
        <Alert
          v-if="!hasBaseline && !isEtlInline"
          type="info"
          title="本工单无检测基线，可提交说明或申请例外；这两类结论不代表质量通过。"
        />
        <section v-if="canSubmit" class="space-y-3" aria-label="提交整改">
          <label class="block">整改类型<Select
              v-model:value="form.type"
              aria-label="整改类型"
              class="w-full"
              :options="typeOptions"
              :disabled="busy || !!pending"
          /></label>
          <label class="block">{{
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
          <label class="block">证据引用<Input
              v-model:value="form.evidenceRef"
              aria-label="证据引用"
              :maxlength="512"
              :disabled="busy || !!pending"
          /></label>
          <template v-if="form.type === 'PLATFORM_REPAIR'">
            <label class="block">成功修复单编号<Input
                v-model:value="form.repairOrderId"
                aria-label="修复单编号"
                :maxlength="19"
                :disabled="busy || !!pending"
            /></label>
            <p>
              从修复工单页面复制编号。服务端核对本工单问题对象、审批通过且执行成功；绑定后仍须复检。
            </p>
          </template>
          <template v-if="form.type === 'EXCEPTION'">
            <label class="block">例外范围（必填）<TextArea
                v-model:value="form.exceptionScope"
                aria-label="例外范围"
                :maxlength="512"
                :disabled="busy || !!pending"
            /></label>
            <label class="block">例外到期时间（必填）<DatePicker
                v-model:value="expiry"
                aria-label="例外到期时间"
                show-time
                class="w-full"
                :disabled="busy || !!pending"
            /></label>
          </template>
          <Button
            data-action="submit"
            type="primary"
            :disabled="busy || !!pending"
            @click="send('submit')"
          >
            <template #icon><IconifyIcon icon="lucide:send" /></template>
            提交整改
          </Button>
        </section>
        <section
          v-if="order.currentSubmission"
          class="space-y-2"
          aria-label="当前整改提交"
        >
          <h4>
            提交版本 {{ order.currentSubmission.revision }} ·
            {{
              SUBMISSION_LABELS[
                order.currentSubmission
                  .submissionType as QualityWorkOrderApi.SubmissionType
              ] ?? order.currentSubmission.submissionType
            }}
          </h4>
          <p>{{ order.currentSubmission.content }}</p>
          <p v-if="order.currentSubmission.evidenceRef">
            证据引用：{{ order.currentSubmission.evidenceRef }}
          </p>
          <p v-if="order.currentSubmission.repairOrderId">
            修复单：{{ order.currentSubmission.repairOrderId }}
          </p>
          <p v-if="order.currentSubmission.exceptionScope">
            例外范围：{{ order.currentSubmission.exceptionScope }} · 到期：{{
              formatDateTime(order.currentSubmission.exceptionExpireAt)
            }}
          </p>
          <p>
            复检状态：{{
              RECHECK_LABELS[order.currentSubmission.recheckStatus ?? ''] ??
              order.currentSubmission.recheckStatus ??
              '不适用'
            }}
          </p>
          <p v-if="order.currentSubmission.lastError">
            {{ order.currentSubmission.lastError }}
          </p>
          <Button
            v-if="canRecheck"
            data-action="recheck"
            :disabled="busy || !!pending"
            @click="send('recheck')"
          >
            <template #icon><IconifyIcon icon="lucide:play" /></template>
            启动新一轮复检
          </Button>
          <section
            v-if="canEtlReplay"
            class="space-y-2 rounded border p-3"
            aria-label="ETL 受控回放复检"
          >
            <h4>ETL 受控回放复检</h4>
            <p>
              仅在本次源端整改后使用。回放会对原目标批次执行受控替换，保持原物理批次范围，逐个目标绑定新的治理质检实例；原始报告与旧实例仍保留供审计。
            </p>
            <label class="block">回放配置<Select
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
            <label class="block">回放原因<TextArea
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
          <div v-if="canConfirm" class="space-y-2">
            <label class="block">验收意见 / 退回原因<TextArea
                v-model:value="form.reason"
                aria-label="验收意见"
                :maxlength="1024"
                :disabled="busy || !!pending"
            /></label>
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
            <span v-else>当前凭证不满足接受条件，可退回补充。</span>
            <Button
              data-action="return"
              danger
              :disabled="busy || !!pending"
              @click="send('confirm', 'RETURN')"
            >
              <template #icon><IconifyIcon icon="lucide:undo-2" /></template>
              退回整改
            </Button>
          </div>
          <p v-else-if="order.orderStatus === 'PENDING_CONFIRMATION'">
            等待有验收权限的非提交人确认；指定验收人时仅该人员可办理。
          </p>
          <h4>当前提交的复检轮次（历史轮次不替代本轮结果）</h4>
          <p v-if="receipt">
            本次命令回执：第 {{ receipt.attemptNo }} 轮 · 实例
            {{ receipt.instanceId }}
          </p>
          <p v-if="!order.currentSubmission.rechecks?.length">
            暂无复检轮次记录
          </p>
          <article
            v-for="attempt in order.currentSubmission.rechecks"
            :key="String(attempt.id)"
            class="rounded border p-2"
          >
            <p>
              第 {{ attempt.attemptNo }} 轮 · 实例
              {{ attempt.instanceId ?? '-' }} ·
              {{ RECHECK_LABELS[attempt.status] ?? attempt.status }}
            </p>
            <p>
              {{ attempt.comparableFlag === 1 ? '可比' : '尚不可比 / 未证明' }}
              · {{ evidenceReasons(attempt.evidenceJson) }}
            </p>
            <p>
              {{ formatDateTime(attempt.requestedAt) }} →
              {{
                attempt.finishedAt
                  ? formatDateTime(attempt.finishedAt)
                  : '未结束'
              }}
            </p>
            <p v-if="attempt.lastError">{{ attempt.lastError }}</p>
          </article>
        </section>
        <section aria-label="操作记录">
          <h4>操作记录</h4>
          <p v-for="action in order.history" :key="String(action.id)">
            {{ formatDateTime(action.createTime) }} · {{ action.actionType }} ·
            {{ action.fromStatus }} → {{ action.toStatus }} ·
            {{ action.reason || '-' }}
          </p>
        </section>
        <WorkOrderNotifications
          v-if="shown && order.id"
          :work-order-id="order.id"
        />
      </template>
      <Button
        v-if="pending"
        data-action="retry"
        :loading="busy"
        :disabled="busy"
        @click="send(pending.operation)"
      >
        <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
        原样重试未确认请求
      </Button>
    </div>
  </Modal>
</template>
