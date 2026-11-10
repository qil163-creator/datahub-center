<script lang="ts" setup>
import type { QualityWorkOrderApi } from '#/api/governance/quality/workorder';
import type {
  NotifyRetryRequest,
  NotifyReviewRequest,
  WorkOrderNotify,
} from '#/api/governance/quality/workorder-notify';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { confirm } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Input,
  Modal,
  Select,
  Table,
  Tag,
  TextArea,
} from 'antdv-next';

import { createCommandTracker } from '#/api/dataforge/execution/command';
import {
  authorizeWorkOrderNotifyRetry,
  getWorkOrderNotifyDetail,
  getWorkOrderNotifyPage,
  reviewWorkOrderNotify,
} from '#/api/governance/quality/workorder-notify';
import { parseResourceId } from '#/utils/resource-id';

const props = defineProps<{ workOrderId: QualityWorkOrderApi.Id }>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const canRead = computed(() =>
  hasAccessByCodes(['governance:quality-work-order:query']),
);
const canProcess = computed(
  () =>
    canRead.value &&
    hasAccessByCodes(['governance:quality-work-order:process']),
);
const identity = () =>
  JSON.stringify([
    props.workOrderId,
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
  ]);
const commands = createCommandTracker(identity);
const expanded = ref(false);
const rows = ref<WorkOrderNotify[]>([]);
const pageNo = ref(1);
const total = ref(0);
const loading = ref(false);
const error = ref('');
const detailOpen = ref(false);
const detail = ref<WorkOrderNotify>();
const detailLoading = ref(false);
const detailError = ref('');
const note = ref('');
const busy = ref(false);
const form = reactive({
  outcome: 'NOT_DELIVERED' as NotifyReviewRequest['outcome'],
  reason: '',
  messageId: '',
});
type Pending =
  | { body: Omit<NotifyRetryRequest, 'requestId'>; kind: 'retry' }
  | { body: Omit<NotifyReviewRequest, 'requestId'>; kind: 'review' };
const pending = ref<Pending>();
let listSeq = 0;
let detailSeq = 0;
const statuses: Record<string, { color: string; label: string }> = {
  PENDING: { color: 'default', label: '待投递' },
  SENDING: { color: 'processing', label: '发送中' },
  SENT: { color: 'success', label: '已获发送回执' },
  FAILED: { color: 'error', label: '明确未发送，等待自动重试' },
  UNKNOWN: { color: 'warning', label: '结果未知，将按幂等键自动重试' },
  EXHAUSTED: { color: 'error', label: '自动投递次数耗尽，待人工核验' },
};
/** 结果未知或自动投递次数耗尽的通知才进入人工核验 */
function isManuallyReviewable(status?: string) {
  return status === 'UNKNOWN' || status === 'EXHAUSTED';
}
const resolutions: Record<string, string> = {
  UNRESOLVED: '尚未人工核验',
  MANUAL_DELIVERED: '人工确认已送达（未系统核验）',
  MANUAL_NOT_DELIVERED: '人工确认未送达（尚未授权重投）',
  RETRY_AUTHORIZED: '已人工授权重新投递',
};
const columns = [
  { title: '通知 / 事件', key: 'event', width: 180 },
  { title: '接收人', dataIndex: 'receiverUserId', key: 'receiver', width: 100 },
  { title: '投递状态', key: 'status', width: 180 },
  { title: '人工处置', key: 'resolution', width: 240 },
  { title: '操作', key: 'actions', width: 160, fixed: 'right' as const },
];
const canReview = computed(
  () =>
    canProcess.value &&
    isManuallyReviewable(detail.value?.sendStatus) &&
    (detail.value?.resolutionStatus ?? 'UNRESOLVED') === 'UNRESOLVED',
);
const retryDecision = computed(() =>
  detail.value?.reviews?.find(
    (review) =>
      review.outcome === 'NOT_DELIVERED' &&
      review.resultingResolutionVersion === detail.value?.resolutionVersion,
  ),
);
const canRetry = computed(
  () =>
    canProcess.value &&
    isManuallyReviewable(detail.value?.sendStatus) &&
    detail.value?.resolutionStatus === 'MANUAL_NOT_DELIVERED' &&
    detail.value.manualRetryCount < 8 &&
    !!retryDecision.value,
);
function row(record: object) {
  return record as WorkOrderNotify;
}
function resolutionLabel(record: WorkOrderNotify) {
  if (
    isManuallyReviewable(record.sendStatus) &&
    record.resolutionStatus === 'UNRESOLVED' &&
    record.resolutionVersion > 0
  )
    return '需重新人工核验';
  return (
    resolutions[record.resolutionStatus ?? 'UNRESOLVED'] ??
    record.resolutionStatus
  );
}
function closeDetail() {
  detailSeq++;
  commands.invalidate();
  detailOpen.value = false;
  detail.value = undefined;
  detailError.value = '';
  detailLoading.value = false;
  note.value = '';
  busy.value = false;
  pending.value = undefined;
  Object.assign(form, { outcome: 'NOT_DELIVERED', reason: '', messageId: '' });
}
function reset() {
  listSeq++;
  expanded.value = false;
  rows.value = [];
  total.value = 0;
  pageNo.value = 1;
  loading.value = false;
  error.value = '';
  closeDetail();
}
async function loadPage(page = pageNo.value) {
  if (!expanded.value || !canRead.value) return;
  const seq = ++listSeq;
  rows.value = [];
  pageNo.value = page;
  loading.value = true;
  error.value = '';
  try {
    const result = await getWorkOrderNotifyPage({
      workOrderId: props.workOrderId,
      pageNo: page,
      pageSize: 10,
    });
    if (seq !== listSeq) return;
    if (
      result.list.some(
        (item) => String(item.workOrderId) !== String(props.workOrderId),
      )
    )
      throw new Error('NOTIFY_IDENTITY');
    rows.value = result.list;
    total.value = result.total;
  } catch {
    if (seq === listSeq) error.value = '通知记录读取失败，请重试。';
  } finally {
    if (seq === listSeq) loading.value = false;
  }
}
function toggle() {
  if (expanded.value) reset();
  else {
    expanded.value = true;
    void loadPage(1);
  }
}
async function openDetail(id: QualityWorkOrderApi.Id, refresh = false) {
  if (!canRead.value || busy.value) return;
  if (!refresh) closeDetail();
  const seq = ++detailSeq;
  detailOpen.value = true;
  detail.value = undefined;
  detailLoading.value = true;
  detailError.value = '';
  try {
    const result = await getWorkOrderNotifyDetail(id);
    if (seq !== detailSeq || !detailOpen.value) return;
    if (
      String(result.id) !== String(id) ||
      String(result.workOrderId) !== String(props.workOrderId)
    )
      throw new Error('NOTIFY_IDENTITY');
    detail.value = result;
  } catch {
    if (seq === detailSeq)
      detailError.value = '通知详情未取得，不能提交人工结论或授权重投。';
  } finally {
    if (seq === detailSeq) detailLoading.value = false;
  }
}
async function send(command: Pending) {
  if (!canProcess.value || busy.value || detailLoading.value || !detail.value)
    return;
  pending.value = command;
  const seq = detailSeq;
  const scope = identity();
  const current = () =>
    seq === detailSeq &&
    scope === identity() &&
    detailOpen.value &&
    canProcess.value;
  busy.value = true;
  detailError.value = '';
  try {
    const result =
      command.kind === 'review'
        ? await commands.execute(
            'notify-review',
            command.body,
            reviewWorkOrderNotify,
          )
        : await commands.execute(
            'notify-retry',
            command.body,
            authorizeWorkOrderNotifyRetry,
          );
    if (!result || !current()) return;
    if (
      String(result.id) !== String(command.body.id) ||
      String(result.workOrderId) !== String(props.workOrderId)
    )
      throw new Error('NOTIFY_IDENTITY');
    detail.value = result;
    pending.value = undefined;
    form.reason = '';
    form.messageId = '';
    note.value =
      command.kind === 'review'
        ? '人工结论已记录；这不代表系统自动核实，也不会自动重新投递。'
        : '重新投递已获人工授权，实际发送结果以投递状态为准。';
    await loadPage();
  } catch {
    if (current())
      detailError.value =
        '提交结果未确认。请刷新核查；如需重试，仅原样重试当前请求，勿重新填写另一结论。';
  } finally {
    if (current()) busy.value = false;
  }
}
function review() {
  if (!canReview.value || pending.value || !detail.value) return;
  const reason = form.reason.trim();
  const operatorMessageId =
    form.outcome === 'DELIVERED'
      ? parseResourceId(form.messageId.trim())
      : undefined;
  if (
    !reason ||
    reason.length > 1000 ||
    (form.outcome === 'DELIVERED' && !operatorMessageId)
  ) {
    detailError.value =
      '请填写不超过 1000 字的核验依据；确认已送达时还需填写完整的站内信消息编号。';
    return;
  }
  void send({
    kind: 'review',
    body: {
      id: detail.value.id,
      expectedResolutionVersion: detail.value.resolutionVersion,
      outcome: form.outcome,
      reason,
      ...(operatorMessageId ? { operatorMessageId } : {}),
    },
  });
}
async function authorizeRetry() {
  if (
    !canRetry.value ||
    pending.value ||
    busy.value ||
    !detail.value ||
    !retryDecision.value
  )
    return;
  const reason = form.reason.trim();
  if (!reason || reason.length > 1000) {
    detailError.value = '请填写不超过 1000 字的重新投递授权原因。';
    return;
  }
  const command: Pending = {
    kind: 'retry',
    body: {
      id: detail.value.id,
      decisionId: retryDecision.value.id,
      expectedResolutionVersion: detail.value.resolutionVersion,
      reason,
    },
  };
  const seq = detailSeq;
  busy.value = true;
  try {
    await confirm(
      '此结论来自人工核验，系统不能自动证明未发送。重新投递可能产生重复提醒，确认授权吗？',
    );
    if (seq !== detailSeq || !detailOpen.value || !canRetry.value) return;
    busy.value = false;
    await send(command);
  } catch {
    /* 取消确认不产生请求。 */
  } finally {
    if (seq === detailSeq) busy.value = false;
  }
}
watch(
  () => [
    props.workOrderId,
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
    canRead.value,
    canProcess.value,
  ],
  reset,
  { flush: 'sync' },
);
onBeforeUnmount(reset);
</script>

<template>
  <section v-if="canRead" class="space-y-3" aria-label="工单通知投递">
    <Button :aria-expanded="expanded" @click="toggle">
      <template #icon>
        <IconifyIcon
          :icon="expanded ? 'lucide:chevron-up' : 'lucide:chevron-down'"
        />
      </template>
      {{ expanded ? '收起通知记录' : '查看通知记录' }}
    </Button>
    <template v-if="expanded">
      <Alert
        type="info"
        message="发送中与发送结果未知不等于未送达。UNKNOWN 不自动重发，人工核验结论与实际投递回执分别保留。"
      />
      <Alert v-if="error && (!/(?:加载|读取|获取).*失败/.test(error) || /保存|冲突|未确认/.test(error))" type="error" :message="error" />
      <Table
        :columns="columns"
        :data-source="rows"
        :loading="loading"
        size="small"
        :scroll="{ x: 'max-content' }"
        :row-key="(record: WorkOrderNotify) => String(record.id)"
        :pagination="{
          current: pageNo,
          pageSize: 10,
          total,
          showSizeChanger: false,
          onChange: loadPage,
        }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'event'">
            #{{ row(record).id }} · {{ row(record).eventType }}
          </template>
          <Tag
            v-else-if="column.key === 'status'"
            :color="statuses[row(record).sendStatus]?.color ?? 'default'"
          >
            {{
              statuses[row(record).sendStatus]?.label ?? row(record).sendStatus
            }}
          </Tag>
          <template v-else-if="column.key === 'resolution'">
            {{ resolutionLabel(row(record)) }}
          </template>
          <Button
            v-else-if="column.key === 'actions'"
            size="small"
            :disabled="busy"
            @click="openDetail(row(record).id)"
          >
            <template #icon><IconifyIcon icon="lucide:eye" /></template>
            查看通知详情
          </Button>
        </template>
      </Table>
      <Button :disabled="loading || busy" @click="loadPage()">
        <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
        刷新通知列表
      </Button>
    </template>
    <Modal
      :open="detailOpen"
      title="通知核验与处置"
      width="880px"
      :footer="null"
      destroy-on-hidden
      @cancel="closeDetail"
    >
      <p v-if="detailLoading">正在读取最新通知详情…</p>
      <Alert
        v-if="detailError"
        type="error"
        :message="detailError"
        class="mb-3"
      />
      <Alert v-if="note" type="info" :message="note" class="mb-3" />
      <template v-if="detail">
        <p>
          通知 #{{ detail.id }} · 接收人 {{ detail.receiverUserId }} ·
          {{ formatDateTime(detail.createTime) }}
        </p>
        <p>{{ detail.payloadSummary }}</p>
        <p>
          <Tag :color="statuses[detail.sendStatus]?.color">
            {{ statuses[detail.sendStatus]?.label ?? detail.sendStatus }}
          </Tag>
          · {{ resolutionLabel(detail) }} · 处置版本
          {{ detail.resolutionVersion }}
        </p>
        <p v-if="detail.lastError">{{ detail.lastError }}</p>
        <p v-if="detail.operatorMessageId">
          人工登记消息编号：{{
            detail.operatorMessageId
          }}（仅为参考，未自动核验）
        </p>
        <p>
          自动投递失败 {{ detail.retryCount }} / 8 次 · 人工授权重试
          {{ detail.manualRetryCount }} / 8 次
        </p>
        <p
          v-if="
            isManuallyReviewable(detail.sendStatus) &&
            detail.manualRetryCount >= 8
          "
        >
          已达到 8 次人工重试上限，不能再次授权重投，请联系运维处理。
        </p>
        <Button :disabled="busy" @click="openDetail(detail.id, true)">
          <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
          刷新核验状态
        </Button>
        <div v-if="canReview || canRetry" class="my-3 space-y-2">
          <Alert
            type="warning"
            message="请先独立核对系统站内信和运维证据。填写消息编号不构成系统验证，找不到消息也不能直接证明未发送。"
          />
          <Select
            v-if="canReview"
            v-model:value="form.outcome"
            aria-label="人工核验结论"
            :disabled="busy || !!pending"
            :options="[
              { value: 'NOT_DELIVERED', label: '人工确认未送达' },
              { value: 'DELIVERED', label: '人工确认已送达' },
            ]"
            class="w-full"
          />
          <Input
            v-if="canReview && form.outcome === 'DELIVERED'"
            v-model:value="form.messageId"
            aria-label="人工登记消息编号"
            placeholder="站内信消息编号（必填，保持完整数字）"
            :disabled="busy || !!pending"
          />
          <TextArea
            v-model:value="form.reason"
            aria-label="通知核验依据"
            :maxlength="1000"
            :rows="3"
            :disabled="busy || !!pending"
            :placeholder="
              canRetry
                ? '授权重新投递的原因（必填）'
                : '人工核验依据与结论原因（必填）'
            "
          />
          <Button
            v-if="canReview"
            :loading="busy"
            :disabled="!!pending"
            @click="review"
          >
            <template #icon>
              <IconifyIcon icon="lucide:notebook-pen" />
            </template>
            记录人工结论
          </Button>
          <Button
            v-if="canRetry"
            :loading="busy"
            :disabled="!!pending"
            @click="authorizeRetry"
          >
            <template #icon><IconifyIcon icon="lucide:send" /></template>
            授权重新投递
          </Button>
        </div>
        <Button
          v-if="pending"
          :loading="busy"
          :disabled="detailLoading || !canProcess"
          @click="send(pending)"
        >
          <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
          原样重试未确认通知请求
        </Button>
        <h4 class="mt-4">人工处置记录</h4>
        <Alert
          v-if="detail.reviewsTruncated"
          type="info"
          message="这里只展示最近 50 条人工处置记录，较早历史未在本页展开。"
        />
        <p v-if="!detail.reviews?.length">暂无人工处置记录</p>
        <article
          v-for="item in detail.reviews"
          :key="String(item.id)"
          class="my-2 rounded border p-2"
        >
          <p>
            #{{ item.id }} ·
            {{
              item.actionType === 'AUTHORIZE_RETRY'
                ? '授权重新投递'
                : '人工核验'
            }}
            ·
            {{
              item.outcome === 'DELIVERED'
                ? '人工确认已送达（未系统核验）'
                : item.outcome === 'NOT_DELIVERED'
                  ? '人工确认未送达'
                  : item.outcome
            }}
            · 操作者 {{ item.actorUserId }} ·
            {{ formatDateTime(item.createTime) }}
          </p>
          <p>{{ item.reason }}</p>
          <p v-if="item.decisionId">依据核验记录 #{{ item.decisionId }}</p>
          <p v-if="item.operatorMessageId">
            人工登记消息 #{{ item.operatorMessageId }}（仅参考）
          </p>
        </article>
      </template>
    </Modal>
  </section>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
