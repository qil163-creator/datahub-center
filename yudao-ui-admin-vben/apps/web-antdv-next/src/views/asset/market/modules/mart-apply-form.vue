<script lang="ts" setup>
import type { FormInstance } from 'antdv-next';

import type { MarketApi } from '#/api/asset/market';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';

import {
  Button,
  Form,
  FormItem,
  Input,
  message,
  Select,
  TextArea,
} from 'antdv-next';
import dayjs from 'dayjs';

import { RangePicker } from '#/adapter/component/date-picker';
import { createMarketApply } from '#/api/asset/market';
import { createLatestWins, parseResourceId } from '#/utils/resource-id';

defineOptions({ name: 'AssetMarketMartApplyForm' });
const emit = defineEmits<{ success: [] }>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const canApply = computed(
  () =>
    !!access.accessToken &&
    !access.loginExpired &&
    !!parseResourceId(user.userInfo?.userId) &&
    hasAccessByCodes(['asset:market:apply']),
);
const identity = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    access.loginExpired,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]),
);
const form = ref<FormInstance>();
const model = reactive<{
  assetId?: MarketApi.Id;
  dates?: [string, string];
  name: string;
  period?: 'CUSTOM' | 'PERMANENT';
  reason: string;
}>({ name: '', reason: '' });
const saving = ref(false);
const guard = createLatestWins();
let opened = false;
const rules = {
  period: [{ required: true, message: '请选择申请周期' }],
  reason: [
    { required: true, whitespace: true, message: '请输入申请理由' },
    { max: 256, message: '申请理由不能超过256个字符' },
  ],
  dates: [{ required: true, message: '请选择开始日期和结束日期' }],
};
const [Modal, modalApi] = useVbenModal({
  title: '申请资产使用权限',
  fullscreenButton: false,
  closeOnClickModal: false,
  onConfirm: submit,
  onOpenChange(isOpen) {
    opened = isOpen;
    reset();
    if (!isOpen) return;
    const value = modalApi.getData() as {
      assetId?: MarketApi.Id;
      assetName?: string;
    };
    model.assetId = value?.assetId;
    model.name = value?.assetName || '';
    form.value?.clearValidate();
  },
});
function reset() {
  guard.invalidate();
  Object.assign(model, {
    assetId: undefined,
    name: '',
    period: undefined,
    dates: undefined,
    reason: '',
  });
  saving.value = false;
  modalApi.unlock();
}
async function submit() {
  const id = parseResourceId(model.assetId);
  if (!opened || saving.value || !canApply.value || !id) return;
  const ticket = guard.next();
  const owner = identity.value;
  const valid = await form.value
    ?.validate()
    .then(() => true)
    .catch(() => false);
  if (
    !valid ||
    !ticket.isCurrent() ||
    !opened ||
    saving.value ||
    !canApply.value ||
    owner !== identity.value ||
    id !== parseResourceId(model.assetId)
  )
    return;
  if (!model.period || !model.reason.trim() || model.reason.length > 256)
    return;
  if (model.period === 'CUSTOM' && (!model.dates?.[0] || !model.dates[1]))
    return;
  const start =
    model.period === 'CUSTOM'
      ? dayjs(model.dates?.[0]).startOf('day')
      : undefined;
  const end =
    model.period === 'CUSTOM'
      ? dayjs(model.dates?.[1]).endOf('day')
      : undefined;
  if (
    start &&
    end &&
    (!start.isValid() || !end.isValid() || !end.isAfter(start))
  )
    return;
  saving.value = true;
  modalApi.lock();
  try {
    await createMarketApply({
      assetId: id,
      title: `申请使用 ${model.name}`.slice(0, 128),
      purpose: model.reason.trim(),
      applyStartTime: start?.valueOf(),
      applyEndTime: end?.valueOf(),
      applyUseType: 0,
      applyUseTimes: 0,
      submit: true,
    });
    if (!ticket.isCurrent() || !opened) return;
    message.success('申请成功');
    emit('success');
    await modalApi.close();
  } finally {
    if (ticket.isCurrent()) {
      saving.value = false;
      modalApi.unlock();
    }
  }
}
watch(
  identity,
  () => {
    opened = false;
    reset();
    modalApi.setState({ isOpen: false });
  },
  { flush: 'sync' },
);
onBeforeUnmount(() => {
  opened = false;
  reset();
});
</script>

<template>
  <Modal
    class="mart-apply-modal !top-[60px] !w-[600px] !rounded-[var(--radius)]"
    content-class="!p-0"
    header-class="!px-5 !py-3 !bg-[#fafafa] !rounded-t-[var(--radius)]"
    footer-class="!px-5 !py-4 !border-t !border-[#f0f2f5] !rounded-b-[var(--radius)]"
  >
    <Form
      ref="form"
      :model="model"
      :rules="rules"
      :label-col="{ style: { width: '120px' } }"
      :wrapper-col="{ style: { flex: '1', minWidth: '0' } }"
      class="mart-apply-form"
      layout="horizontal"
    >
      <FormItem label="集市资产名称" required>
        <Input :value="model.name" disabled />
      </FormItem>
      <FormItem label="申请周期" name="period">
        <Select
          v-model:value="model.period"
          :options="[
            { label: '长期有效', value: 'PERMANENT' },
            { label: '自定义', value: 'CUSTOM' },
          ]"
          placeholder="请选择申请周期"
          @change="model.dates = undefined"
        />
      </FormItem>
      <FormItem v-if="model.period === 'CUSTOM'" label="申请周期" name="dates">
        <RangePicker
          v-model:value="model.dates"
          value-format="YYYY-MM-DD"
          :placeholder="['开始日期', '结束日期']"
          separator="至"
        />
      </FormItem>
      <FormItem label="申请理由" name="reason">
        <TextArea
          v-model:value="model.reason"
          :rows="4"
          :maxlength="256"
          show-count
          placeholder="请输入申请理由"
        />
      </FormItem>
    </Form>
    <template #footer>
      <Button :disabled="saving" @click="modalApi.close()">取消</Button><Button
        type="primary"
        :loading="saving"
        :disabled="!canApply"
        @click="submit"
      >
        确定
      </Button>
    </template>
  </Modal>
</template>

<style scoped>
.mart-apply-form {
  padding: 20px 40px 36px;
  font-family: Arial, 'Microsoft YaHei', sans-serif;
}

.mart-apply-form :deep(.ant-form-item) {
  margin-bottom: 16px;
}

.mart-apply-form :deep(.ant-form-item:last-child) {
  margin-bottom: 0;
}

.mart-apply-form :deep(.ant-form-item-label > label) {
  font-size: 14px;
  color: #333;
}

.mart-apply-form :deep(.ant-input),
.mart-apply-form :deep(.ant-select-selector),
.mart-apply-form :deep(.ant-picker) {
  border-color: #dcdfe6;
  border-radius: var(--radius);
}

.mart-apply-form :deep(.ant-input-disabled) {
  color: #b0b4bc;
  background: #f5f7fa;
}

.mart-apply-form :deep(.ant-input-textarea-show-count::after) {
  font-size: 12px;
  color: #909399;
}

.mart-apply-form :deep(.ant-picker) {
  width: 100%;
}

@media (width <= 640px) {
  .mart-apply-form {
    padding: 20px 16px 28px;
  }

  .mart-apply-form :deep(.ant-form-item-row) {
    flex-direction: column;
  }

  .mart-apply-form :deep(.ant-form-item-label) {
    width: 100% !important;
    text-align: left;
  }
}
</style>
