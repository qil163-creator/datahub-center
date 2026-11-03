<script lang="ts" setup>
import type { SafetyWhitelistApi } from '#/api/governance/whitelist';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { Alert, message, TextArea } from 'antdv-next';

import { approveWhitelist, rejectWhitelist } from '#/api/governance/whitelist';

defineOptions({ name: 'SafetyWhitelistApprovalAction' });

const emit = defineEmits<{ (e: 'success'): void }>();

const row = ref<SafetyWhitelistApi.Whitelist>();
const action = ref<'approve' | 'reject'>('approve');
const opinion = ref('');

const title = computed(() =>
  action.value === 'approve' ? '审批通过' : '审批驳回',
);

const [ModalComponent, modalApi] = useVbenModal<{
  action: 'approve' | 'reject';
  row: SafetyWhitelistApi.Whitelist;
}>({
  class: 'w-[800px]',
  async onConfirm() {
    if (!row.value?.id) {
      return;
    }
    if (action.value === 'reject' && !opinion.value.trim()) {
      message.error('驳回时请填写审批意见');
      return;
    }
    modalApi.lock();
    try {
      await (action.value === 'approve'
        ? approveWhitelist(row.value.id, opinion.value || undefined)
        : rejectWhitelist(row.value.id, opinion.value || undefined));
      await modalApi.close();
      emit('success');
      message.success(
        action.value === 'approve' ? '已通过，白名单开始生效' : '已驳回',
      );
    } finally {
      modalApi.unlock();
    }
  },
  onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      row.value = undefined;
      opinion.value = '';
      return;
    }
    const data = modalApi.getData();
    row.value = data?.row;
    action.value = data?.action ?? 'approve';
  },
});
</script>

<template>
  <ModalComponent :title="title">
    <div class="flex flex-col gap-3 px-1 py-2">
      <Alert
        v-if="action === 'approve'"
        :message="`通过后【${row?.name ?? ''}】将在生效时段内豁免脱敏，请确认豁免范围与生效主体无误。`"
        show-icon
        type="warning"
      />
      <Alert
        v-else
        :message="`驳回后【${row?.name ?? ''}】不会生效，申请人可修改后重新提交。`"
        show-icon
        type="info"
      />
      <div v-if="row?.applyReason" class="text-sm">
        <span class="text-gray-500">申请理由：</span>{{ row.applyReason }}
      </div>
      <TextArea
        v-model:value="opinion"
        :auto-size="{ minRows: 3, maxRows: 6 }"
        :maxlength="500"
        :placeholder="
          action === 'reject' ? '请填写驳回理由（必填）' : '审批意见（选填）'
        "
        show-count
      />
    </div>
  </ModalComponent>
</template>
