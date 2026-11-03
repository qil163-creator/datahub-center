<script lang="ts" setup>
import type { GovernanceMetaTagApi } from '#/api/governance/metatag';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { message } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import {
  createMetaTag,
  getMetaTag,
  updateMetaTag,
} from '#/api/governance/metatag';
import { GOVERNANCE_MODAL_FORM } from '#/views/governance/common/form-layout';

import { useFormSchema } from '../data';

defineOptions({ name: 'GovernanceMetaTagForm' });

const emit = defineEmits<{ (e: 'success'): void }>();
const formData = ref<GovernanceMetaTagApi.Tag>();
const getTitle = computed(() =>
  formData.value?.id ? '编辑元数据标签' : '新增元数据标签',
);

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
    const data = (await formApi.getValues()) as GovernanceMetaTagApi.Tag;
    try {
      await (formData.value?.id ? updateMetaTag(data) : createMetaTag(data));
      await modalApi.close();
      emit('success');
      message.success('保存成功');
    } finally {
      modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      formData.value = undefined;
      await formApi.resetForm();
      return;
    }
    const data = modalApi.getData() as GovernanceMetaTagApi.Tag;
    if (!data?.id) {
      return;
    }
    modalApi.lock();
    try {
      formData.value = await getMetaTag(data.id);
      await formApi.setValues(formData.value);
    } finally {
      modalApi.unlock();
    }
  },
});
</script>

<template>
  <Modal :title="getTitle" class="w-[800px]">
    <Form class="mx-4" />
  </Modal>
</template>
