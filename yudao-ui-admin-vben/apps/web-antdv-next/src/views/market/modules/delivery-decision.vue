<script setup lang="ts">
import type { MarketApi } from '#/api/asset/market';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Button, message, Select, TextArea } from 'antdv-next';

import {
  getMarketDeliveryOperation,
  getMarketDeliveryReviews,
  recordMarketDeliveryReview,
} from '#/api/asset/market';

import { approvalStatusDisplay } from '../approval-status';
import { deliveryActionLabel } from '../grant-status';
import { formatMarketTime } from '../market-time';

const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const permitted = computed(() => hasAccessByCodes(['asset:market:auth']));
const labels: Record<MarketApi.DeliveryReviewDecision, string> = {
  KEEP_PENDING: '继续待核查',
  REAPPLY_REQUIRED: '需撤销后重新申请（仅记录待办）',
};
const options = Object.entries(labels).map(([value, label]) => ({
  value,
  label,
}));
/** 历史中的实际处置已改变授权或账本，与待办结论分开标注。 */
const historyLabels: Record<string, string> = {
  ...labels,
  REVOKE: '已处置：收回授权',
  CONFIRM_ORIGINAL: '已处置：确认原批准',
};
function historyLabel(value: string) {
  return historyLabels[value] ?? value;
}
const decision = ref<MarketApi.DeliveryReviewDecision>('KEEP_PENDING');
const reason = ref('');
const basis = ref('');
const error = ref('');
const loading = ref(false);
const busy = ref(false);
const operation = ref<MarketApi.DeliveryOperation>();
const history = ref<MarketApi.DeliveryReview[]>([]);
const nextId = ref<MarketApi.Id | null>();
const saved = ref<MarketApi.DeliveryReview>();
const pending = ref<MarketApi.DeliveryReviewRequest>();
let generation = 0;
let opened = false;
let selected: undefined | { applyId: MarketApi.Id; operationId: string };

const canRecord = computed(
  () =>
    permitted.value &&
    !loading.value &&
    !busy.value &&
    !!selected &&
    (!!pending.value ||
      operation.value?.resolutionStatus === 'REVIEW_REQUIRED' ||
      (operation.value?.status === 'MANUAL' &&
        operation.value.resolutionStatus === 'UNKNOWN')),
);

function reset() {
  ++generation;
  if (busy.value) modalApi.unlock();
  busy.value = false;
  loading.value = false;
  operation.value = undefined;
  history.value = [];
  nextId.value = undefined;
  saved.value = undefined;
  pending.value = undefined;
  error.value = '';
  reason.value = '';
  basis.value = '';
  decision.value = 'KEEP_PENDING';
  selected = undefined;
}

const [Modal, modalApi] = useVbenModal({
  title: '交付核查结论与历史',
  class: 'w-[760px]',
  confirmText: '记录核查结论',
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
    void load(false);
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

async function load(more: boolean) {
  if (
    !opened ||
    !selected ||
    !permitted.value ||
    busy.value ||
    loading.value ||
    (more && (nextId.value === undefined || nextId.value === null))
  )
    return;
  const identity = { ...selected };
  const cursor = more ? nextId.value! : 0;
  const current = ++generation;
  loading.value = true;
  error.value = '';
  if (!more) {
    history.value = [];
    nextId.value = undefined;
    operation.value = undefined;
  }
  try {
    const [latest, page] = await Promise.all([
      getMarketDeliveryOperation(identity.operationId),
      getMarketDeliveryReviews(identity.operationId, cursor),
    ]);
    if (current !== generation) return;
    if (
      !matches(latest, identity) ||
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
      history.value = [];
      operation.value = undefined;
      nextId.value = undefined;
      error.value = '返回的核查历史身份或游标不一致，已丢弃结果。';
      return;
    }
    operation.value = latest;
    history.value = more ? [...history.value, ...page.items] : page.items;
    nextId.value = page.hasMore ? page.nextId : undefined;
  } catch {
    if (current === generation) {
      operation.value = undefined;
      error.value = '核查历史读取失败，请重新读取；不会自动提交结论。';
    }
  } finally {
    if (current === generation) loading.value = false;
  }
}

async function submit() {
  if (!opened || !selected || !canRecord.value) return;
  if (!pending.value) {
    if (
      !reason.value.trim() ||
      reason.value.length > 500 ||
      !basis.value.trim() ||
      basis.value.length > 500
    ) {
      message.warning('请填写 1–500 字的核查原因和依据');
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
      decision: decision.value,
      reason: reason.value.trim(),
      basis: basis.value.trim(),
    };
  }
  const request = { ...pending.value };
  const identity = { ...selected };
  const current = ++generation;
  busy.value = true;
  saved.value = undefined;
  error.value = '';
  modalApi.lock();
  try {
    const result = await recordMarketDeliveryReview(request);
    if (current !== generation) return;
    if (
      !matches(result, identity) ||
      result.requestId !== request.requestId ||
      result.decision !== request.decision ||
      result.reason !== request.reason ||
      result.basis !== request.basis ||
      String(result.actorUserId) !== String(user.userInfo?.userId)
    ) {
      error.value =
        '保存回执与本次请求不一致，未确认成功；请保持同一请求编号复查。';
      return;
    }
    saved.value = result;
    pending.value = undefined;
    reason.value = '';
    basis.value = '';
    history.value = [];
    nextId.value = undefined;
    message.success('核查结论已记录，未修改授权状态');
  } catch {
    if (current === generation)
      error.value =
        '提交结果未确认；内容已冻结，再次点击将使用同一请求编号核对保存结果。';
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
  <Modal
    :confirm-disabled="
      !canRecord || (!pending && (!reason.trim() || !basis.trim()))
    "
  >
    <div class="flex flex-col gap-3">
      <Alert
        type="warning"
        show-icon
        message="这里只记录待办结论，不代表已经撤销、恢复或重新批准授权。保存时服务端会重新核验原始证据；请勿填写密钥、连接串或个人敏感数据。"
      />
      <Alert v-if="error" type="error" :message="error" />
      <p v-if="!permitted">需要 asset:market:auth 权限及提供方对象范围。</p>
      <p v-if="selected">原操作：{{ selected.operationId }}</p>
      <dl v-if="operation" class="grid grid-cols-[auto_1fr] gap-3 break-all">
        <dt>请求动作</dt>
        <dd data-testid="delivery-action">
          {{ deliveryActionLabel(operation.action) }}
        </dd>
        <dt>技术操作类型 / 协议</dt>
        <dd>
          {{ operation.opType }} / {{ operation.protocolVersion ?? '未知' }}
        </dd>
        <dt>账本状态 / 结果</dt>
        <dd>{{ operation.status }} / {{ operation.resolutionStatus }}</dd>
      </dl>
      <p v-if="operation">
        请求动作仅表示冻结命令意图，不代表执行成功或当前可访问；技术操作类型
        REVOKE 历史上同时承载暂停和撤销，请结合账本状态与结果核查。
      </p>
      <p v-if="pending">
        待核对请求编号：{{
          pending.requestId
        }}。如关闭弹窗，请先从历史记录核实是否保存成功，避免另建重复记录。
      </p>
      <label for="delivery-decision">核查结论</label>
      <Select
        id="delivery-decision"
        v-model:value="decision"
        :options="options"
        :disabled="!canRecord || !!pending"
      />
      <label for="delivery-decision-reason">核查原因</label>
      <TextArea
        id="delivery-decision-reason"
        v-model:value="reason"
        :maxlength="500"
        :rows="2"
        :disabled="!canRecord || !!pending"
      />
      <label for="delivery-decision-basis">
        <span>核查依据 / 工单引用（不是批准凭证）</span>
      </label>
      <TextArea
        id="delivery-decision-basis"
        v-model:value="basis"
        :maxlength="500"
        :rows="2"
        :disabled="!canRecord || !!pending"
      />
      <p v-if="saved" data-testid="saved-review">
        已保存核查编号 {{ saved.id }}：{{
          historyLabel(saved.decision)
        }}；证据分类 {{ saved.classification }}。未修改授权状态。
      </p>
      <Alert
        v-if="saved"
        data-testid="saved-approval-status"
        :type="approvalStatusDisplay(saved.approvalStatus).type"
        :message="approvalStatusDisplay(saved.approvalStatus).message"
        show-icon
      />
      <Button
        data-testid="refresh-reviews"
        :loading="loading"
        :disabled="busy || loading || !permitted"
        @click="load(false)"
      >
        <template #icon><IconifyIcon icon="lucide:history" /></template>
        <span>读取核查历史</span>
      </Button>
      <p v-if="saved || history.length > 0">
        以下批准凭证核对状态均为记录时结果，不会用当前证据改写旧历史；旧记录缺少该项时显示无法核验。
      </p>
      <ol class="flex flex-col gap-3">
        <li
          v-for="item in history"
          :key="String(item.id)"
          class="rounded border p-3"
        >
          <p>
            核查编号 {{ item.id }} /
            {{ historyLabel(item.decision) }}
          </p>
          <p v-if="item.resultingOperationId">
            收回命令：{{ item.resultingOperationId }}（远端结果以交付账本为准）
          </p>
          <p>请求编号：{{ item.requestId }}</p>
          <p>
            操作者：{{ item.actorUserId }}；部门：{{
              item.actorDeptId ?? '-'
            }}；{{ formatMarketTime(item.occurredAt) }}
          </p>
          <p>
            证据分类：{{ item.classification }}；来源版本：{{
              item.sourceRevision ?? '-'
            }}
          </p>
          <Alert
            :type="approvalStatusDisplay(item.approvalStatus).type"
            :message="approvalStatusDisplay(item.approvalStatus).message"
            show-icon
          />
          <p>原因：{{ item.reason }}</p>
          <p>依据：{{ item.basis }}</p>
        </li>
      </ol>
      <Button
        v-if="nextId !== undefined && nextId !== null"
        data-testid="more-reviews"
        :disabled="loading || busy || !permitted"
        @click="load(true)"
      >
        <span>读取下一页核查历史</span>
      </Button>
    </div>
  </Modal>
</template>
