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
import { useObjectRequest } from '#/hooks/use-object-request';
import { $t } from '#/locales';

import { SUPPLY_MODAL_FORM } from '../../form-layout';
import { useFormSchema } from '../data';

defineOptions({ name: 'SupplyAppForm' });

const emit = defineEmits<{
  success: [created?: SupplyAppApi.App];
}>();
const formData = ref<SupplyAppApi.App>();
const ready = ref(false);
const detailRequest = useObjectRequest(undefined, () => {
  ready.value = false;
});

const getTitle = computed(() => (formData.value?.id ? '修改应用' : '新增应用'));

const [Form, formApi] = useVbenForm({
  ...SUPPLY_MODAL_FORM,
  schema: useFormSchema(),
});

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    if (!ready.value) return;
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
    const current = detailRequest.begin();
    ready.value = false;
    const data = isOpen ? (modalApi.getData() as SupplyAppApi.App) : undefined;
    formData.value = data;
    if (!isOpen) return;
    modalApi.setState({ loading: true });
    try {
      await formApi.reset();
      if (!current()) return;
      if (data?.id) {
        const detail = await getSupplyApp(data.id);
        if (!current()) return;
        if (!detail?.id) {
          message.warning('应用不存在，请刷新列表后重试');
          await modalApi.close();
          return;
        }
        formData.value = detail;
        await formApi.setValues(detail);
      }
      if (current()) ready.value = true;
    } finally {
      if (current()) modalApi.setState({ loading: false });
    }
  },
});
</script>

<template>
  <Modal :title="getTitle" :confirm-disabled="!ready" class="w-[800px]">
    <div v-show="ready" class="mx-4">
      <Form />
    </div>
  </Modal>
</template>
