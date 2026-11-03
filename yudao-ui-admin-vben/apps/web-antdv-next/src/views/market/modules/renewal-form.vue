<script lang="ts" setup>
import type { MarketApi } from '#/api/asset/market';

import { onBeforeUnmount, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { DatePicker, message } from 'antdv-next';

import { createRenewalRequest } from '#/api/asset/market';

import { formatMarketTime } from '../market-time';

defineOptions({ name: 'MarketRenewalForm' });

const emit = defineEmits<{ (e: 'success', newApplyId: MarketApi.Id): void }>();

const original = ref<MarketApi.Apply>({});
const applyEndTime = ref<string>();
/**
 * 一次表单提交固定的幂等身份：提交失败后重试沿用同一个 operationId，
 * 后端按 (原申请, operationId) 幂等，只会产生一个续期申请。
 */
const operationId = ref('');
let generation = 0;
let lockedGeneration: number | undefined;

const [Modal, modalApi] = useVbenModal({
  title: '续期申请（需重新审批）',
  class: 'w-[560px]',
  onOpenChange(isOpen: boolean) {
    if (!isOpen && lockedGeneration === generation) {
      modalApi.unlock();
      lockedGeneration = undefined;
    }
    ++generation;
    if (!isOpen) {
      original.value = {};
      applyEndTime.value = undefined;
      operationId.value = '';
      return;
    }
    const data = modalApi.getData() as { apply?: MarketApi.Apply };
    original.value = data?.apply ?? {};
    applyEndTime.value = undefined;
    operationId.value = `renew-${original.value.id ?? 'unknown'}-${Date.now()}-${Math.random()
      .toString(36)
      .slice(2, 10)}`;
  },
  async onConfirm() {
    await handleSubmit();
  },
});

async function handleSubmit() {
  if (lockedGeneration === generation) return;
  if (!original.value.id) {
    message.warning('缺少原申请编号');
    return;
  }
  if (!applyEndTime.value) {
    message.warning('请选择续期后的截止时间');
    return;
  }
  const submittedGeneration = generation;
  const request = {
    applyEndTime: applyEndTime.value,
    id: original.value.id,
    operationId: operationId.value,
  };
  modalApi.lock();
  lockedGeneration = submittedGeneration;
  try {
    const newApplyId = await createRenewalRequest(request);
    if (submittedGeneration !== generation) return;
    // 原批准仍在其原有期限内有效；新申请等待审批通过后才会投递授权。
    message.success(`已创建续期申请 #${newApplyId}，请等待审批`);
    emit('success', newApplyId);
    if (lockedGeneration === submittedGeneration) {
      modalApi.unlock();
      lockedGeneration = undefined;
    }
    await modalApi.close();
  } finally {
    if (
      lockedGeneration === submittedGeneration &&
      submittedGeneration === generation
    ) {
      modalApi.unlock();
      lockedGeneration = undefined;
    }
  }
}

onBeforeUnmount(() => {
  ++generation;
});
</script>

<template>
  <Modal>
    <div class="governance-modal-form governance-form-item-horizontal">
      <div class="form-item">
        <label class="form-item__label">原申请</label>
        <span>
          {{ original.applyNo ?? original.id ?? '-' }} ·
          {{ original.assetName ?? original.title ?? '-' }}
        </span>
      </div>
      <div class="form-item">
        <label class="form-item__label">原截止时间</label>
        <span>{{ formatMarketTime(original.applyEndTime) }}</span>
      </div>
      <div class="form-item">
        <label class="form-item__label form-item__label--required">
          续期后截止时间
        </label>
        <DatePicker
          v-model:value="applyEndTime"
          class="w-full"
          format="YYYY-MM-DD HH:mm:ss"
          show-time
          value-format="x"
        />
      </div>
      <div class="form-item">
        <span class="text-xs text-gray-500">
          续期是一条新的申请：必须晚于原截止时间，且需要重新审批。
          原批准不会被原地延长，也不会因为提交续期而提前失效。
        </span>
      </div>
    </div>
  </Modal>
</template>

<style lang="scss" scoped>
@import '../../governance/common/custom-modal-form.css';
</style>
