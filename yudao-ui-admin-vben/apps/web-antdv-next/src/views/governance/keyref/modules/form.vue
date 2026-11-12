<script lang="ts" setup>
import { useVbenModal } from '@vben/common-ui';

import { Alert, message } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import { createKeyRef } from '#/api/governance/security';
import { $t } from '#/locales';
import {
  GOVERNANCE_FORM_MODAL_CLASS,
  GOVERNANCE_MODAL_FORM,
} from '#/views/governance/common/form-layout';

import { useFormSchema } from '../data';

const emit = defineEmits(['success']);

const [Form, formApi] = useVbenForm({
  ...GOVERNANCE_MODAL_FORM,
  schema: useFormSchema(),
});

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await formApi.validate();
    if (!valid) {
      return;
    }
    modalApi.lock();
    const values = (await formApi.getValues()) as {
      keyCode: string;
      keyName: string;
      remark?: string;
    };
    try {
      await createKeyRef({
        keyCode: values.keyCode.trim(),
        keyName: values.keyName.trim(),
        remark: values.remark?.trim() || undefined,
      });
      await modalApi.close();
      emit('success');
      message.success($t('ui.actionMessage.operationSuccess'));
    } finally {
      modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      await formApi.resetForm();
    }
  },
});
</script>

<template>
  <Modal
    :class="GOVERNANCE_FORM_MODAL_CLASS"
    :title="$t('ui.actionTitle.create', ['密钥引用'])"
  >
    <Alert
      class="mx-4 mb-4"
      type="info"
      show-icon
      message="引用只登记名称和编码；数据密钥由主密钥派生，平台不保存、不展示任何密钥。编码创建后不可修改。"
    />
    <Form class="mx-4" />
  </Modal>
</template>
