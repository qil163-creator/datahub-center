<script lang="ts" setup>
import type { SafetySecurityApi } from '#/api/governance/security';

import { ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { Descriptions, DescriptionsItem, message } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import { handleAccessAlert } from '#/api/governance/security';

import {
  formatTime,
  ruleLabel,
  ruleUnit,
  useHandleFormSchema,
} from '../data';

const emit = defineEmits(['success']);
const alert = ref<SafetySecurityApi.AccessAlert>();

const [Form, formApi] = useVbenForm({
  commonConfig: {
    componentProps: {
      class: 'w-full',
    },
    labelWidth: 90,
  },
  layout: 'horizontal',
  schema: useHandleFormSchema(),
  showDefaultActions: false,
});

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    const current = alert.value;
    if (!current) return;
    const { valid } = await formApi.validate();
    if (!valid) {
      return;
    }
    modalApi.lock();
    const values = (await formApi.getValues()) as {
      remark: string;
      status: 'CONFIRMED' | 'IGNORED';
    };
    try {
      await handleAccessAlert({
        id: current.id,
        status: values.status,
        remark: values.remark.trim(),
        expectedRevision: current.revision,
      });
      await modalApi.close();
      message.success('已处理');
    } finally {
      modalApi.unlock();
      // 修订冲突时也刷新，拿到最新状态
      emit('success');
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      alert.value = undefined;
      await formApi.resetForm();
      return;
    }
    alert.value = modalApi.getData() as SafetySecurityApi.AccessAlert;
  },
});
</script>

<template>
  <Modal class="w-[640px]" title="处理异常访问告警">
    <div class="mx-4">
      <Descriptions
        v-if="alert"
        bordered
        class="mb-4"
        :column="1"
        size="small"
      >
        <DescriptionsItem label="告警规则">
          {{ ruleLabel(alert.ruleCode) }}
        </DescriptionsItem>
        <DescriptionsItem label="访问用户">
          {{ alert.userName || '-' }}（{{ alert.userId }}）
        </DescriptionsItem>
        <DescriptionsItem label="统计窗口">
          {{ formatTime(alert.windowStart) }} ~
          {{ formatTime(alert.windowEnd) }}
        </DescriptionsItem>
        <DescriptionsItem label="统计值 / 阈值">
          <span class="text-destructive font-medium">
            {{ alert.metricValue }}
          </span>
          / {{ alert.threshold }} {{ ruleUnit(alert.ruleCode) }}
        </DescriptionsItem>
      </Descriptions>
      <Form />
    </div>
  </Modal>
</template>
