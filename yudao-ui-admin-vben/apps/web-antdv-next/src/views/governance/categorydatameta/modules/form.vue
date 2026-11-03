<script lang="ts" setup>
import type { FoundationCategoryDatametaApi } from '#/api/governance/categorydatameta';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { message } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import {
  createCategoryDatameta,
  getCategoryDatameta,
  updateCategoryDatameta,
} from '#/api/governance/categorydatameta';
import { $t } from '#/locales';
import { GOVERNANCE_MODAL_FORM } from '#/views/governance/common/form-layout';

import { useFormSchema } from '../data';

const emit = defineEmits(['success']);
const formData = ref<FoundationCategoryDatametaApi.CategoryDatameta>();
const getTitle = computed(() => {
  return formData.value?.id
    ? $t('ui.actionTitle.edit', ['标准数据元类目管理'])
    : $t('ui.actionTitle.create', ['标准数据元类目管理']);
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
    modalApi.lock();
    // 提交表单
    const data =
      (await formApi.getValues()) as FoundationCategoryDatametaApi.CategoryDatameta;
    try {
      await (formData.value?.id
        ? updateCategoryDatameta(data)
        : createCategoryDatameta(data));
      // 关闭并提示
      await modalApi.close();
      emit('success');
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
    // 加载数据
    const data =
      modalApi.getData() as FoundationCategoryDatametaApi.CategoryDatameta;
    if (!data || !data.id) {
      // 设置上级
      await formApi.setValues(data ?? {});
      return;
    }
    modalApi.lock();
    try {
      formData.value = await getCategoryDatameta(data.id);
      // 设置到 values
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
