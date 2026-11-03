<script setup lang="ts">
import type { MarketApi } from '#/api/asset/market';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Button, Checkbox, message, TextArea } from 'antdv-next';

import {
  getMarketDeliveryEvidence,
  getMarketDeliveryOperation,
  getMarketDeliveryReviews,
  recheckMarketDelivery,
  recordMarketDeliveryRedeliver,
} from '#/api/asset/market';

import { approvalStatusDisplay } from '../approval-status';
import { deliveryActionLabel } from '../grant-status';
import { formatMarketTime } from '../market-time';
import DeliveryDecision from './delivery-decision.vue';
import DeliveryDisposal from './delivery-disposal.vue';

const emit = defineEmits<{ success: [] }>();
const [DisposalModal, disposalModalApi] = useVbenModal({
  connectedComponent: DeliveryDisposal,
  destroyOnClose: true,
});

const [DecisionModal, decisionModalApi] = useVbenModal({
  connectedComponent: DeliveryDecision,
  destroyOnClose: true,
});
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const permitted = computed(() => hasAccessByCodes(['asset:market:auth']));
const redeliverPermitted = computed(() =>
  hasAccessByCodes(['asset:market:redeliver']),
);
const record = ref<MarketApi.DeliveryOperation>();
const evidence = ref<MarketApi.DeliveryEvidence>();
const approvalDisplay = computed(() =>
  approvalStatusDisplay(evidence.value?.approvalStatus),
);
const evidenceLoading = ref(false);
const evidenceLabels: Record<
  MarketApi.DeliveryEvidence['classification'],
  string
> = {
  LEGACY_REVIEW_REQUIRED: '旧协议记录，缺少 v2 原始证据，仍需人工复核',
  FROZEN_COMMAND_UNAVAILABLE: '冻结命令缺失、损坏或超出读取上限',
  FROZEN_COMMAND_CONFLICT: '冻结命令与原账本身份或摘要不一致',
  LOCAL_BINDING_CONFLICT: '当前申请绑定与原操作不一致，已停止远端核对',
  RECEIPT_UNAVAILABLE: '原回执暂不可读取，不能推断为未执行',
  RECEIPT_CONFLICT: '提供方回执与原命令不一致，不能作为匹配证据',
  RECEIPT_NOT_FOUND: '提供方未记录原操作；没有自动重发',
  RECEIPT_UNKNOWN: '提供方结果仍未知，保持待核查',
  ORIGINAL_APPLIED: '原命令与已执行回执匹配；不代表当前仍可访问',
  ORIGINAL_STALE: '原命令与过期回执匹配；已被后续来源版本覆盖',
  ORIGINAL_REJECTED: '原命令与拒绝回执匹配；不代表授权成功',
  LOCAL_STATE_CHANGED: '核对期间本地状态发生变化，旧结果已丢弃，请刷新原操作',
};
const note = ref('');
const error = ref('');
const loading = ref(false);
const busy = ref(false);
let generation = 0;
let opened = false;
let selected: undefined | { applyId: MarketApi.Id; operationId: string };
const canRecheck = computed(
  () =>
    permitted.value &&
    !loading.value &&
    !busy.value &&
    !evidenceLoading.value &&
    record.value?.protocolVersion === 2 &&
    record.value.status === 'MANUAL' &&
    record.value.resolutionStatus === 'UNKNOWN',
);

// ==================== 受控重投（仅撤销/暂停，按原 operationId 重发一次） ====================

/** 仅冻结动作是撤销/暂停的 MANUAL/UNKNOWN 原操作可受控重投；GRANT 走重新申请。 */
const canRedeliver = computed(
  () =>
    redeliverPermitted.value &&
    !!record.value &&
    record.value.status === 'MANUAL' &&
    record.value.resolutionStatus === 'UNKNOWN' &&
    (record.value.action === 'REVOKE' || record.value.action === 'SUSPEND'),
);
const redeliverUnavailableReason = computed(() => {
  if (!redeliverPermitted.value) {
    return '受控重投需要 asset:market:redeliver 权限。';
  }
  if (!record.value) return '';
  if (
    record.value.status !== 'MANUAL' ||
    record.value.resolutionStatus !== 'UNKNOWN'
  ) {
    return '仅 MANUAL / UNKNOWN 的未确认原操作可以受控重投；当前账本状态不支持。';
  }
  if (record.value.action !== 'REVOKE' && record.value.action !== 'SUSPEND') {
    return '仅冻结动作是撤销或暂停的原操作可以受控重投；GRANT 必须重新走申请流程。';
  }
  return '';
});
const decisionLabels: Record<MarketApi.DeliveryReviewDecision, string> = {
  KEEP_PENDING: '继续待核查',
  REAPPLY_REQUIRED: '需撤销后重新申请（仅记录待办）',
};
function decisionLabel(value: string) {
  return decisionLabels[value as MarketApi.DeliveryReviewDecision] ?? value;
}
const redeliverNote = ref('');
const redeliverAcknowledged = ref(false);
const redeliverError = ref('');
const redeliverBusy = ref(false);
const redeliverDecisionLoading = ref(false);
const redeliverBasisDecision = ref<MarketApi.DeliveryReview>();
const redeliverBasisReviewRequestId = ref<string>();
const redeliverSaved = ref<MarketApi.DeliveryOperation>();
const redeliverPending = ref<MarketApi.DeliveryRedeliverRequest>();
let redeliverGeneration = 0;
let redeliverOpened = false;
let redeliverSelected:
  | undefined
  | {
      action: 'REVOKE' | 'SUSPEND';
      applyId: MarketApi.Id;
      operationId: string;
    };

const canSubmitRedeliver = computed(() => {
  // 先读全部响应式依赖，再评估非响应式 opened/selected，避免短路导致依赖未注册、
  // 弹窗状态变化后计算属性长期停留在挂载时的旧值。
  const perm = redeliverPermitted.value;
  const busy = redeliverBusy.value;
  const decisionLoading = redeliverDecisionLoading.value;
  const saved = redeliverSaved.value;
  const pending = redeliverPending.value;
  const acknowledged = redeliverAcknowledged.value;
  const note = redeliverNote.value;
  const basisRequestId = redeliverBasisReviewRequestId.value;
  return (
    perm &&
    redeliverOpened &&
    !busy &&
    !decisionLoading &&
    !saved &&
    !!redeliverSelected &&
    (!!pending || (acknowledged && !!note.trim() && !!basisRequestId))
  );
});

function resetRedeliver() {
  ++redeliverGeneration;
  if (redeliverBusy.value) redeliverModalApi.unlock();
  redeliverBusy.value = false;
  redeliverDecisionLoading.value = false;
  redeliverBasisDecision.value = undefined;
  redeliverBasisReviewRequestId.value = undefined;
  redeliverSaved.value = undefined;
  redeliverPending.value = undefined;
  redeliverError.value = '';
  redeliverNote.value = '';
  redeliverAcknowledged.value = false;
  redeliverSelected = undefined;
}

const [RedeliverModal, redeliverModalApi] = useVbenModal({
  title: '受控重投（仅撤销/暂停）',
  class: 'w-[720px]',
  confirmText: '按原操作号重发一次',
  onConfirm: submitRedeliver,
  onOpenChange(isOpen: boolean) {
    resetRedeliver();
    redeliverOpened = isOpen;
    if (!isOpen) return;
    if (!selected || !record.value || !canRedeliver.value) {
      redeliverError.value = '缺少受控重投条件，请刷新原操作后再试。';
      return;
    }
    const action = record.value.action;
    if (action !== 'REVOKE' && action !== 'SUSPEND') {
      redeliverError.value = '仅冻结动作是撤销或暂停的原操作可以受控重投。';
      return;
    }
    redeliverSelected = {
      applyId: selected.applyId,
      operationId: selected.operationId,
      action,
    };
    void loadRedeliverBasis();
  },
});

/**
 * 从核查历史（按编号升序）找最新一条 DECISION 决定作为重投依据；
 * 每页最多 50 条，hasMore 时用 nextId 无损续页，最多翻 10 页。
 */
async function loadRedeliverBasis() {
  if (
    !redeliverOpened ||
    !redeliverSelected ||
    !redeliverPermitted.value ||
    redeliverBusy.value
  )
    return;
  const identity = { ...redeliverSelected };
  const current = ++redeliverGeneration;
  redeliverDecisionLoading.value = true;
  redeliverBasisDecision.value = undefined;
  redeliverBasisReviewRequestId.value = undefined;
  redeliverError.value = '';
  let cursor: MarketApi.Id = 0;
  let found: MarketApi.DeliveryReview | undefined;
  try {
    for (let pageNo = 0; pageNo < 10; pageNo++) {
      const page = await getMarketDeliveryReviews(identity.operationId, cursor);
      if (current !== redeliverGeneration) return;
      if (
        !Array.isArray(page?.items) ||
        page.items.length > 50 ||
        page.items.some((item) => !matches(item, identity)) ||
        (page.hasMore &&
          (page.nextId === undefined ||
            page.nextId === null ||
            page.items.length === 0 ||
            String(page.nextId) !== String(page.items.at(-1)?.id) ||
            String(page.nextId) === String(cursor)))
      ) {
        redeliverError.value =
          '返回的核查决定历史身份或游标不一致，已丢弃结果。';
        return;
      }
      for (const item of page.items) {
        if (item.kind === 'DECISION') found = item;
      }
      if (!page.hasMore) break;
      cursor = page.nextId as MarketApi.Id;
    }
    if (current !== redeliverGeneration) return;
    if (found) {
      redeliverBasisDecision.value = found;
      redeliverBasisReviewRequestId.value = found.requestId;
    }
  } catch {
    if (current === redeliverGeneration)
      redeliverError.value =
        '核查决定读取失败，未绑定重投依据；请重新打开后再重投。';
  } finally {
    if (current === redeliverGeneration) redeliverDecisionLoading.value = false;
  }
}

async function submitRedeliver() {
  if (!redeliverOpened || !redeliverSelected || !canSubmitRedeliver.value)
    return;
  if (!redeliverPending.value) {
    const note = redeliverNote.value.trim();
    if (!note || note.length > 500) {
      message.warning('请填写 1–500 字的重投说明');
      return;
    }
    const basisRequestId = redeliverBasisReviewRequestId.value;
    if (!basisRequestId) {
      redeliverError.value = '请先在核查结论中记录决定后重试。';
      return;
    }
    const requestId = globalThis.crypto?.randomUUID?.();
    if (!requestId) {
      redeliverError.value =
        '当前浏览器无法生成安全请求编号，请使用 HTTPS 后重试。';
      return;
    }
    redeliverPending.value = {
      requestId,
      operationId: redeliverSelected.operationId,
      note,
      basisReviewRequestId: basisRequestId,
    };
  }
  const request = { ...redeliverPending.value };
  const identity = { ...redeliverSelected };
  const current = ++redeliverGeneration;
  redeliverBusy.value = true;
  redeliverError.value = '';
  redeliverModalApi.lock();
  try {
    const result = await recordMarketDeliveryRedeliver(request);
    if (current !== redeliverGeneration) return;
    if (!matches(result, identity) || result.action !== identity.action) {
      redeliverError.value =
        '重投回执与本次请求不一致，未确认成功；请保持同一请求编号复查。';
      return;
    }
    redeliverSaved.value = result;
    redeliverPending.value = undefined;
    record.value = result;
    message.success('已按原操作号重发一次，远端结果以交付账本为准');
    emit('success');
  } catch {
    if (current === redeliverGeneration)
      redeliverError.value =
        '重投结果未确认；内容已冻结，再次点击将使用同一请求编号核对，服务端不会重复重发。';
  } finally {
    if (current === redeliverGeneration) {
      redeliverBusy.value = false;
      redeliverModalApi.unlock();
    }
  }
}

function openRedeliver() {
  if (
    !selected ||
    !record.value ||
    !canRedeliver.value ||
    busy.value ||
    loading.value ||
    evidenceLoading.value
  )
    return;
  redeliverModalApi.open();
}

function reset() {
  ++generation;
  record.value = undefined;
  evidence.value = undefined;
  evidenceLoading.value = false;
  note.value = '';
  error.value = '';
  loading.value = false;
  if (busy.value) modalApi.unlock();
  busy.value = false;
  selected = undefined;
}

const [Modal, modalApi] = useVbenModal({
  title: '原交付操作核查（不重发）',
  class: 'w-[720px]',
  confirmText: '按原身份复查',
  onOpenChange(isOpen: boolean) {
    reset();
    opened = isOpen;
    if (!isOpen) return;
    const data = modalApi.getData() as {
      applyId?: MarketApi.Id;
      operationId?: string;
    };
    if (
      data?.applyId === undefined ||
      data.applyId === null ||
      !data.operationId ||
      data.operationId.length > 128
    ) {
      error.value =
        '缺少原交付身份，请刷新申请列表；不能根据当前申请版本猜测操作号。';
      return;
    }
    selected = { applyId: data.applyId, operationId: data.operationId };
    void load();
  },
  onConfirm: recheck,
});

function matches(
  value: Pick<MarketApi.DeliveryOperation, 'applyId' | 'operationId'>,
  identity: NonNullable<typeof selected>,
) {
  return (
    value?.operationId === identity.operationId &&
    String(value.applyId) === String(identity.applyId)
  );
}

function openDecision() {
  if (
    !selected ||
    !record.value ||
    busy.value ||
    loading.value ||
    evidenceLoading.value ||
    !permitted.value
  )
    return;
  decisionModalApi.setData({ ...selected }).open();
}

function openDisposal() {
  if (
    !selected ||
    !record.value ||
    busy.value ||
    loading.value ||
    evidenceLoading.value ||
    !permitted.value
  )
    return;
  disposalModalApi.setData({ ...selected }).open();
}

async function load() {
  if (!opened || !selected || busy.value || !permitted.value) return;
  const identity = { ...selected };
  const current = ++generation;
  loading.value = true;
  record.value = undefined;
  evidence.value = undefined;
  evidenceLoading.value = false;
  error.value = '';
  try {
    const value = await getMarketDeliveryOperation(identity.operationId);
    if (current !== generation) return;
    if (!matches(value, identity)) {
      error.value = '返回的操作身份与当前申请不一致，已停止核查。';
      return;
    }
    record.value = value;
  } catch {
    if (current === generation) error.value = '读取失败，请刷新重试。';
  } finally {
    if (current === generation) loading.value = false;
  }
}

async function inspectEvidence() {
  if (
    !opened ||
    !selected ||
    !record.value ||
    loading.value ||
    busy.value ||
    evidenceLoading.value ||
    !permitted.value
  )
    return;
  const identity = { ...selected };
  const sourceRevision = record.value.sourceRevision;
  const current = ++generation;
  evidenceLoading.value = true;
  evidence.value = undefined;
  error.value = '';
  try {
    const value = await getMarketDeliveryEvidence(identity.operationId);
    if (current !== generation) return;
    if (
      !matches(value, identity) ||
      String(value.sourceRevision) !== String(sourceRevision)
    ) {
      record.value = undefined;
      error.value = '证据身份或来源版本不一致，已丢弃结果，请刷新原操作。';
      return;
    }
    evidence.value = value;
    if (value.classification === 'LOCAL_STATE_CHANGED')
      record.value = undefined;
  } catch {
    if (current === generation)
      error.value = '原始证据读取失败，未修改授权，请稍后重试。';
  } finally {
    if (current === generation) evidenceLoading.value = false;
  }
}

async function recheck() {
  if (!canRecheck.value || !selected) return;
  const reason = note.value.trim();
  if (!reason || reason.length > 500) {
    message.warning('请填写 1–500 字的核查原因');
    return;
  }
  const identity = { ...selected };
  const current = ++generation;
  busy.value = true;
  evidence.value = undefined;
  error.value = '';
  modalApi.lock();
  try {
    const value = await recheckMarketDelivery({
      operationId: identity.operationId,
      note: reason,
    });
    if (current !== generation) return;
    if (!matches(value, identity)) {
      record.value = undefined;
      error.value = '返回的操作身份与当前申请不一致，请重新读取。';
      return;
    }
    record.value = value;
    note.value = '';
    message.success('本次核查已记录，请以当前账本状态为准');
    emit('success');
  } catch {
    if (current === generation) {
      record.value = undefined;
      error.value =
        '复查未返回确定结果，请重新读取账本；没有发送新的交付命令。';
    }
  } finally {
    if (current === generation) {
      busy.value = false;
      modalApi.unlock();
    }
  }
}

watch(
  () => [
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
    permitted.value,
  ],
  () => {
    reset();
    if (opened) void modalApi.close();
    opened = false;
  },
  { flush: 'sync' },
);
watch(
  () => [
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
    redeliverPermitted.value,
  ],
  () => {
    resetRedeliver();
    if (redeliverOpened) void redeliverModalApi.close();
    redeliverOpened = false;
  },
  { flush: 'sync' },
);
onBeforeUnmount(() => {
  reset();
  opened = false;
  resetRedeliver();
  redeliverOpened = false;
});
</script>

<template>
  <DecisionModal />
  <DisposalModal />
  <RedeliverModal :confirm-disabled="!canSubmitRedeliver">
    <div class="flex flex-col gap-3">
      <Alert
        type="error"
        show-icon
        message="受控重投会按原 operationId 重发一次冻结命令（仅撤销/暂停），不重建命令、不扩大授权。服务端会重新核验冻结命令、账本身份与绑定核查决定，任何条件不满足都会拒绝；请勿填写密钥、连接串或个人敏感数据。"
      />
      <Alert v-if="redeliverError" type="error" :message="redeliverError" />
      <p v-if="!redeliverPermitted">
        需要 asset:market:redeliver 权限，并由后端校验提供方范围。
      </p>
      <dl
        v-if="redeliverSelected"
        class="grid grid-cols-[auto_1fr] gap-3 break-all"
      >
        <dt>原操作号</dt>
        <dd>{{ redeliverSelected.operationId }}</dd>
        <dt>申请编号</dt>
        <dd>{{ redeliverSelected.applyId }}</dd>
        <dt>冻结动作</dt>
        <dd data-testid="redeliver-action">
          {{ deliveryActionLabel(redeliverSelected.action) }}
        </dd>
      </dl>
      <p v-if="redeliverDecisionLoading" role="status">
        正在读取核查决定…
      </p>
      <p v-else-if="redeliverBasisDecision" data-testid="redeliver-basis-decision">
        重投依据核查决定：编号 {{ redeliverBasisDecision.id }}（{{
          decisionLabel(redeliverBasisDecision.decision)
        }}）；请求编号 {{ redeliverBasisDecision.requestId }}
      </p>
      <Alert
        v-else-if="!redeliverSaved && !redeliverDecisionLoading"
        data-testid="redeliver-no-basis-decision"
        type="warning"
        message="请先在核查结论中记录决定后重试。"
      />
      <p v-if="redeliverPending" data-testid="pending-redeliver">
        待核对请求编号：{{
          redeliverPending.requestId
        }}。如关闭弹窗，请先从核查历史核实是否已重发，服务端不会重复重发。
      </p>
      <label for="redeliver-note">重投说明（必填，将记录审计）</label>
      <TextArea
        id="redeliver-note"
        v-model:value="redeliverNote"
        :maxlength="500"
        :rows="2"
        :disabled="!!redeliverPending || !!redeliverSaved || redeliverBusy"
      />
      <Checkbox
        v-model:checked="redeliverAcknowledged"
        data-testid="redeliver-ack"
        :disabled="!!redeliverPending || !!redeliverSaved || redeliverBusy"
      >
        我已确认按原 operationId 重发一次，不重建命令
      </Checkbox>
      <p v-if="redeliverSaved" data-testid="saved-redeliver">
        重投回执：账本状态 {{ redeliverSaved.status }} /
        {{ redeliverSaved.resolutionStatus }}；动作
        {{ deliveryActionLabel(redeliverSaved.action) }}；结果引用
        {{ redeliverSaved.resultRef ?? '-' }}；最近原因
        {{ redeliverSaved.lastError ?? '-' }}。远端结果以交付账本为准。
      </p>
    </div>
  </RedeliverModal>
  <Modal :confirm-disabled="!canRecheck || !note.trim()">
    <div class="flex flex-col gap-4">
      <Alert
        type="info"
        show-icon
        message="仅核查原操作回执及批准凭证，不重新发送授权或撤销命令。查无回执仍保持待核查；单次交付确认不等于原批准匹配或当前聚合权限可用。"
      />
      <Alert
        v-if="!permitted"
        type="warning"
        message="需要 asset:market:auth 权限，并由后端校验提供方范围。"
      />
      <Alert v-if="error" type="error" :message="error" />
      <Button :loading="loading" :disabled="busy || !permitted" @click="load">
        <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
        <span>刷新原操作状态</span>
      </Button>
      <Button
        data-testid="inspect-original-evidence"
        :loading="evidenceLoading"
        :disabled="!record || loading || busy || evidenceLoading || !permitted"
        @click="inspectEvidence"
      >
        <template #icon><IconifyIcon icon="lucide:file-search" /></template>
        核对原始证据（只读）
      </Button>
      <Button
        data-testid="open-delivery-decision"
        :disabled="!record || busy || loading || evidenceLoading || !permitted"
        @click="openDecision"
      >
        <template #icon><IconifyIcon icon="lucide:file-check" /></template>
        <span>核查结论与历史（不改授权）</span>
      </Button>
      <Button
        danger
        data-testid="open-delivery-disposal"
        :disabled="!record || busy || loading || evidenceLoading || !permitted"
        @click="openDisposal"
      >
        <template #icon><IconifyIcon icon="lucide:shield-check" /></template>
        <span>实际处置（收回 / 确认原批准）</span>
      </Button>
      <Button
        v-if="canRedeliver"
        danger
        data-testid="open-delivery-redeliver"
        :disabled="busy || loading || evidenceLoading"
        @click="openRedeliver"
      >
        <template #icon><IconifyIcon icon="lucide:rotate-cw" /></template>
        <span>受控重投（仅撤销/暂停）</span>
      </Button>
      <p
        v-else-if="record && redeliverUnavailableReason"
        data-testid="redeliver-unavailable"
      >
        {{ redeliverUnavailableReason }}
      </p>
      <div v-if="evidence" class="flex flex-col gap-2">
        <Alert
          type="info"
          show-icon
          :message="
            evidenceLabels[evidence.classification] ??
            '未知证据分类，不能作为授权或迁移依据'
          "
        />
        <Alert
          data-testid="approval-status"
          :type="approvalDisplay.type"
          :message="approvalDisplay.message"
          show-icon
        />
        <p>
          原回执与原批准凭证分别核对；已执行回执不代表原批准匹配。本次不形成复核批准、不迁移授权，也不证明当前可访问。
        </p>
        <p>
          原始证据中的请求动作：{{
            deliveryActionLabel(evidence.originalAction)
          }}
        </p>
        <p v-if="evidence.frozenFieldCount != null">
          原冻结字段数：{{ evidence.frozenFieldCount }}
        </p>
      </div>
      <dl v-if="record" class="grid grid-cols-[auto_1fr] gap-3 break-all">
        <dt>原操作号</dt>
        <dd>{{ record.operationId }}</dd>
        <dt>申请编号</dt>
        <dd>{{ record.applyId }}</dd>
        <dt>请求动作</dt>
        <dd data-testid="delivery-action">
          {{ deliveryActionLabel(record.action) }}
        </dd>
        <dt>技术操作类型 / 协议</dt>
        <dd>{{ record.opType }} / {{ record.protocolVersion ?? '未知' }}</dd>
        <dt>来源版本</dt>
        <dd>{{ record.sourceRevision }}</dd>
        <dt>账本状态 / 结果</dt>
        <dd>{{ record.status }} / {{ record.resolutionStatus }}</dd>
        <dt>尝试次数</dt>
        <dd>{{ record.attemptCount ?? '-' }}</dd>
        <dt>结果引用</dt>
        <dd>{{ record.resultRef ?? '-' }}</dd>
        <dt>最近原因</dt>
        <dd>{{ record.lastError ?? '-' }}</dd>
        <dt>更新时间</dt>
        <dd>{{ formatMarketTime(record.updateTime) }}</dd>
      </dl>
      <p v-if="record">
        请求动作仅表示冻结命令意图，不代表执行成功或当前可访问；技术操作类型
        REVOKE 历史上同时承载暂停和撤销，请结合账本状态与结果核查。
      </p>
      <p v-if="record && !canRecheck && !busy">
        仅 v2、MANUAL / UNKNOWN
        支持记录复查结果；其他状态可核对原始证据，但不允许强制确认或重发。
      </p>
      <label for="delivery-review-note">核查原因（必填，将记录审计）</label>
      <TextArea
        id="delivery-review-note"
        v-model:value="note"
        :disabled="!canRecheck"
        :maxlength="500"
        :rows="3"
      />
    </div>
  </Modal>
</template>
