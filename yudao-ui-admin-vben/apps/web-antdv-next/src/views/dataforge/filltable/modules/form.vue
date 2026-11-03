<script lang="ts" setup>
import type { DataforgeFillTableApi } from '#/api/dataforge/filltable';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { message } from 'antdv-next';

import {
  createFillTable,
  getFillTable,
  updateFillTable,
} from '#/api/dataforge/filltable';
import { $t } from '#/locales';
import {
  DATAFORGE_FORM_MODAL_CLASS,
  useDataforgeForm,
} from '#/views/dataforge/common/form-layout';

import { useFormSchema } from '../data';

defineOptions({ name: 'DataforgeFillTableForm' });

const emit = defineEmits(['success']);

const editingId = ref<number>();
const getTitle = computed(() =>
  editingId.value
    ? $t('ui.actionTitle.edit', ['填报表'])
    : $t('ui.actionTitle.create', ['填报表']),
);

const [Form, formApi] = useDataforgeForm({
  schema: useFormSchema(),
});

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await formApi.validate();
    if (!valid) {
      return;
    }
    modalApi.lock();
    try {
      const values =
        (await formApi.getValues()) as DataforgeFillTableApi.FillTable;
      await (editingId.value
        ? updateFillTable(values)
        : createFillTable(values));
      await modalApi.close();
      emit('success');
      message.success($t('ui.actionMessage.operationSuccess'));
    } finally {
      modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      editingId.value = undefined;
      await formApi.reset();
      return;
    }
    await formApi.reset();
    const data = modalApi.getData() as
      | DataforgeFillTableApi.FillTable
      | undefined;
    if (!data?.id) {
      return;
    }
    modalApi.lock();
    try {
      const detail = await getFillTable(data.id);
      editingId.value = detail.id;
      await formApi.setValues(detail);
    } catch (error) {
      console.error(error);
      message.error('加载表单数据失败，请稍后重试');
      await modalApi.close();
    } finally {
      modalApi.unlock();
    }
  },
});
</script>

<template>
  <Modal :title="getTitle" :class="DATAFORGE_FORM_MODAL_CLASS">
    <Form class="mx-4" />
  </Modal>
</template>
