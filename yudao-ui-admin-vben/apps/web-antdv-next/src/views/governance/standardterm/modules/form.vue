<script lang="ts" setup>
import type { GovernanceStandardTermApi } from '#/api/governance/standardterm';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { CommonStatusEnum } from '@vben/constants';

import { message } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import {
  createStandardTerm,
  getStandardTerm,
  updateStandardTerm,
} from '#/api/governance/standardterm';
import { useObjectRequest } from '#/hooks/use-object-request';
import { $t } from '#/locales';
import { GOVERNANCE_MODAL_FORM } from '#/views/governance/common/form-layout';

import { useFormSchema } from '../data';

const emit = defineEmits(['success']);
const formData = ref<GovernanceStandardTermApi.StandardTerm>();
const identity = ref('closed');
const request = useObjectRequest(() => identity.value, () => {
  formData.value = undefined;
});
const getTitle = computed(() => {
  return formData.value?.id
    ? $t('ui.actionTitle.edit', ['业务术语'])
    : $t('ui.actionTitle.create', ['业务术语']);
});

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
    const current = request.begin();
    modalApi.lock();
    const values =
      (await formApi.getValues()) as GovernanceStandardTermApi.StandardTerm;
    const data = { ...values, termCategoryId: values.termCategoryId ?? null };
    try {
      await (formData.value?.id
        ? updateStandardTerm(data)
        : createStandardTerm(data));
      if (!current()) return;
      await modalApi.close();
      emit('success');
      message.success($t('ui.actionMessage.operationSuccess'));
    } finally {
      if (current()) modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      identity.value = 'closed';
      request.begin();
      formData.value = undefined;
      modalApi.unlock();
      await formApi.reset();
      return;
    }
    const data = modalApi.getData() as GovernanceStandardTermApi.StandardTerm;
    identity.value = data?.id ? String(data.id) : 'new';
    const current = request.begin();
    if (!data || !data.id) {
      await formApi.setValues({
        status: CommonStatusEnum.ENABLE,
        termCategoryId: data?.termCategoryId,
      });
      return;
    }
    modalApi.lock();
    try {
      const result = await getStandardTerm(data.id);
      if (!current()) return;
      formData.value = result;
      await formApi.setValues({
        ...formData.value,
        categoryId: formData.value.categoryId === null || formData.value.categoryId === undefined ? undefined : String(formData.value.categoryId),
        termCategoryId: formData.value.termCategoryId === null || formData.value.termCategoryId === undefined ? undefined : String(formData.value.termCategoryId),
      });
    } finally {
      if (current()) modalApi.unlock();
    }
  },
});
</script>

<template>
  <Modal :title="getTitle" class="w-[800px]">
    <Form class="mx-4" />
  </Modal>
</template>
