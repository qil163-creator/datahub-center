<script lang="ts" setup>
import type { SupplyAppApi } from '#/api/supply/app';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { message } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import {
  createSupplyApp,
  getSupplyApp,
  updateSupplyApp,
} from '#/api/supply/app';
import { $t } from '#/locales';

import { SUPPLY_MODAL_FORM } from '../../form-layout';
import { useFormSchema } from '../data';

defineOptions({ name: 'SupplyAppForm' });

const emit = defineEmits<{
  success: [created?: SupplyAppApi.App];
}>();
const formData = ref<SupplyAppApi.App>();

const getTitle = computed(() => (formData.value?.id ? '修改应用' : '新增应用'));

const [Form, formApi] = useVbenForm({
  ...SUPPLY_MODAL_FORM,
  schema: useFormSchema(),
});

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await formApi.validate();
    if (!valid) {
      return;
    }
    modalApi.lock();
    const data = (await formApi.getValues()) as SupplyAppApi.App;
    try {
      const created = formData.value?.id
        ? undefined
        : await createSupplyApp(data);
      if (formData.value?.id) {
        await updateSupplyApp(data);
      }
      emit('success', created);
      await modalApi.close();
      message.success($t('ui.actionMessage.operationSuccess'));
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
    const data = modalApi.getData() as SupplyAppApi.App;
    await formApi.resetForm();
    if (!data?.id) {
      return;
    }
    modalApi.lock();
    try {
      formData.value = await getSupplyApp(data.id);
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
