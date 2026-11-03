<script setup lang="ts">
import type { MarketApi } from '#/api/asset/market';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Checkbox, message, Select, TextArea } from 'antdv-next';

import {
  getMarketDeliveryOperation,
  getMarketDeliveryReviews,
  recordMarketDeliveryDisposal,
} from '#/api/asset/market';

import { approvalStatusDisplay } from '../approval-status';
import { deliveryActionLabel } from '../grant-status';

const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const permitted = computed(() => hasAccessByCodes(['asset:market:auth']));
const labels: Record<MarketApi.DeliveryDisposalAction, string> = {
  REVOKE: '收回授权（真实撤销，申请人需重新申请）',
  CONFIRM_ORIGINAL: '确认原批准已生效（仅更新交付账本）',
};
const decisionLabels: Record<MarketApi.DeliveryReviewDecision, string> = {
  KEEP_PENDING: '继续待核查',
  REAPPLY_REQUIRED: '需撤销后重新申请（仅记录待办）',
};
function decisionLabel(value: string) {
  return decisionLabels[value as MarketApi.DeliveryReviewDecision] ?? value;
}
const effects: Record<MarketApi.DeliveryDisposalAction, string> = {
  REVOKE:
    '将按普通收回流程立即撤销该申请授权，冻结新的收回命令并通知申请人；不会创建新申请。',
  CONFIRM_ORIGINAL:
    '仅当原回执已按冻结命令生效、正式批准凭证匹配且申请仍有效时，把原操作记为已确认；不重发命令、不扩大授权，条件不满足时服务端拒绝。',
};
const options = Object.entries(labels).map(([value, label]) => ({
  value,
  label,
}));
const action = ref<MarketApi.DeliveryDisposalAction>('REVOKE');
const reason = ref('');
const basis = ref('');
const acknowledged = ref(false);
const error = ref('');
const loading = ref(false);
const decisionLoading = ref(false);
const busy = ref(false);
const operation = ref<MarketApi.DeliveryOperation>();
const basisDecision = ref<MarketApi.DeliveryReview>();
const basisReviewRequestId = ref<string>();
const saved = ref<MarketApi.DeliveryReview>();
const pending = ref<MarketApi.DeliveryDisposalRequest>();
let generation = 0;
let opened = false;
let selected: undefined | { applyId: MarketApi.Id; operationId: string };

const disposable = computed(
  () =>
    operation.value?.resolutionStatus === 'REVIEW_REQUIRED' ||
    (operation.value?.status === 'MANUAL' &&
      operation.value.resolutionStatus === 'UNKNOWN'),
);
const canSubmit = computed(
  () =>
    permitted.value &&
    !loading.value &&
    !decisionLoading.value &&
    !busy.value &&
    !saved.value &&
    !!selected &&
    (!!pending.value ||
      (disposable.value &&
        acknowledged.value &&
        !!reason.value.trim() &&
        !!basis.value.trim() &&
        !!basisReviewRequestId.value)),
);

function reset() {
  ++generation;
  if (busy.value) modalApi.unlock();
  busy.value = false;
  loading.value = false;
  decisionLoading.value = false;
  operation.value = undefined;
  basisDecision.value = undefined;
  basisReviewRequestId.value = undefined;
  saved.value = undefined;
  pending.value = undefined;
  error.value = '';
  reason.value = '';
  basis.value = '';
  acknowledged.value = false;
  action.value = 'REVOKE';
  selected = undefined;
}

const [Modal, modalApi] = useVbenModal({
  title: '交付历史处置',
  class: 'w-[720px]',
  confirmText: '执行处置',
  onConfirm: submit,
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
      error.value = '缺少原申请或交付身份，请刷新列表。';
      return;
    }
    selected = { applyId: data.applyId, operationId: data.operationId };
    void load();
  },
});

function matches(
  value: Pick<MarketApi.DeliveryReview, 'applyId' | 'operationId'>,
  identity: NonNullable<typeof selected>,
) {
  return (
    value?.operationId === identity.operationId &&
    String(value.applyId) === String(identity.applyId)
  );
}

async function load() {
  if (!opened || !selected || !permitted.value) return;
  const identity = { ...selected };
  const current = ++generation;
  loading.value = true;
  error.value = '';
  operation.value = undefined;
  try {
    const latest = await getMarketDeliveryOperation(identity.operationId);
    if (current !== generation) return;
    if (!matches(latest, identity)) {
      error.value = '返回的交付身份不一致，已丢弃结果。';
      return;
    }
    operation.value = latest;
  } catch {
    if (current === generation)
      error.value = '交付记录读取失败，请重新打开；未执行任何处置。';
  } finally {
    if (current === generation) loading.value = false;
  }
  if (current === generation && operation.value) void loadBasisDecision();
}

/**
 * 从核查历史（按编号升序）找最新一条 DECISION 决定作为处置依据；
 * 每页最多 50 条，hasMore 时用 nextId 无损续页，最多翻 10 页。
 */
async function loadBasisDecision() {
  if (!opened || !selected || !permitted.value || busy.value) return;
  const identity = { ...selected };
  const current = ++generation;
  decisionLoading.value = true;
  basisDecision.value = undefined;
  basisReviewRequestId.value = undefined;
  let cursor: MarketApi.Id = 0;
  let found: MarketApi.DeliveryReview | undefined;
  try {
    for (let pageNo = 0; pageNo < 10; pageNo++) {
      const page = await getMarketDeliveryReviews(identity.operationId, cursor);
      if (current !== generation) return;
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
        error.value = '返回的核查决定历史身份或游标不一致，已丢弃结果。';
        return;
      }
      for (const item of page.items) {
        if (item.kind === 'DECISION') found = item;
      }
      if (!page.hasMore) break;
      cursor = page.nextId as MarketApi.Id;
    }
    if (current !== generation) return;
    if (found) {
      basisDecision.value = found;
      basisReviewRequestId.value = found.requestId;
    }
  } catch {
    if (current === generation)
      error.value = '核查决定读取失败，未绑定处置依据；请重新打开后再处置。';
  } finally {
    if (current === generation) decisionLoading.value = false;
  }
}

async function submit() {
  if (!opened || !selected || !canSubmit.value) return;
  if (!pending.value) {
    if (reason.value.length > 500 || basis.value.length > 500) {
      message.warning('处置原因和依据各不超过 500 字');
      return;
    }
    const basisRequestId = basisReviewRequestId.value;
    if (!basisRequestId) {
      error.value = '请先在核查结论中记录决定后重试。';
      return;
    }
    const requestId = globalThis.crypto?.randomUUID?.();
    if (!requestId) {
      error.value = '当前浏览器无法生成安全请求编号，请使用 HTTPS 后重试。';
      return;
    }
    pending.value = {
      requestId,
      operationId: selected.operationId,
      action: action.value,
      reason: reason.value.trim(),
      basis: basis.value.trim(),
      basisReviewRequestId: basisRequestId,
    };
  }
  const request = { ...pending.value };
  const identity = { ...selected };
  const current = ++generation;
  busy.value = true;
  error.value = '';
  modalApi.lock();
  try {
    const result = await recordMarketDeliveryDisposal(request);
    if (current !== generation) return;
    if (
      !matches(result, identity) ||
      result.kind !== 'DISPOSAL' ||
      result.requestId !== request.requestId ||
      result.decision !== request.action ||
      result.reason !== request.reason ||
      result.basis !== request.basis ||
      String(result.actorUserId) !== String(user.userInfo?.userId)
    ) {
      error.value =
        '处置回执与本次请求不一致，未确认成功；请保持同一请求编号复查。';
      return;
    }
    saved.value = result;
    pending.value = undefined;
    message.success(
      result.decision === 'REVOKE'
        ? '已收回授权并冻结收回命令，远端结果以交付账本为准'
        : '已确认原批准，交付账本已更新',
    );
  } catch {
    if (current === generation)
      error.value =
        '处置结果未确认；内容已冻结，再次点击将使用同一请求编号核对，服务端不会重复处置。';
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
onBeforeUnmount(() => {
  reset();
  opened = false;
});
</script>

<template>
  <Modal :confirm-disabled="!canSubmit">
    <div class="flex flex-col gap-3">
      <Alert
        type="error"
        show-icon
        message="实际处置会改变授权或交付账本。服务端会重新核验原始证据、归属与批准凭证，任何条件变化都会拒绝；请勿填写密钥、连接串或个人敏感数据。"
      />
      <Alert v-if="error" type="error" :message="error" />
      <p v-if="!permitted">需要 asset:market:auth 权限及提供方对象范围。</p>
      <p v-if="selected">原操作：{{ selected.operationId }}</p>
      <dl v-if="operation" class="grid grid-cols-[auto_1fr] gap-3 break-all">
        <dt>请求动作</dt>
        <dd data-testid="disposal-action">
          {{ deliveryActionLabel(operation.action) }}
        </dd>
        <dt>账本状态 / 结果</dt>
        <dd>{{ operation.status }} / {{ operation.resolutionStatus }}</dd>
      </dl>
      <p v-if="operation && !disposable" data-testid="not-disposable">
        该操作已不处于待核查状态，不能处置；可在核查历史中查看已有记录。
      </p>
      <p v-if="decisionLoading" role="status">正在读取核查决定…</p>
      <p v-else-if="basisDecision" data-testid="basis-decision">
        处置依据核查决定：编号 {{ basisDecision.id }}（{{
          decisionLabel(basisDecision.decision)
        }}）；请求编号 {{ basisDecision.requestId }}
      </p>
      <Alert
        v-else-if="operation && !loading && !saved"
        data-testid="no-basis-decision"
        type="warning"
        message="请先在核查结论中记录决定后重试。"
      />
      <p v-if="pending" data-testid="pending-disposal">
        待核对请求编号：{{
          pending.requestId
        }}。如关闭弹窗，请先从核查历史核实是否已处置，避免重复处置。
      </p>
      <label for="delivery-disposal-action">处置方式</label>
      <Select
        id="delivery-disposal-action"
        v-model:value="action"
        :options="options"
        :disabled="!disposable || !!pending || !!saved || busy"
      />
      <p data-testid="disposal-effect">{{ effects[action] }}</p>
      <label for="delivery-disposal-reason">处置原因</label>
      <TextArea
        id="delivery-disposal-reason"
        v-model:value="reason"
        :maxlength="500"
        :rows="2"
        :disabled="!disposable || !!pending || !!saved || busy"
      />
      <label for="delivery-disposal-basis">
        <span>处置依据 / 工单引用（不是批准凭证）</span>
      </label>
      <TextArea
        id="delivery-disposal-basis"
        v-model:value="basis"
        :maxlength="500"
        :rows="2"
        :disabled="!disposable || !!pending || !!saved || busy"
      />
      <Checkbox
        v-model:checked="acknowledged"
        data-testid="disposal-ack"
        :disabled="!disposable || !!pending || !!saved || busy"
      >
        我已核对原始证据，确认执行上述处置
      </Checkbox>
      <p v-if="saved" data-testid="saved-disposal">
        已保存处置编号 {{ saved.id }}：{{ labels[action] }}；证据分类
        {{ saved.classification }}。
        <span v-if="saved.resultingOperationId">
          收回命令
          {{ saved.resultingOperationId }} 已冻结，远端结果以交付账本为准。
        </span>
      </p>
      <Alert
        v-if="saved"
        :type="approvalStatusDisplay(saved.approvalStatus).type"
        :message="approvalStatusDisplay(saved.approvalStatus).message"
        show-icon
      />
    </div>
  </Modal>
</template>
