<script lang="ts" setup>
import type { FoundationCategoryCleanRuleApi } from '#/api/governance/categorycleanrule';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { message } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import {
  createCategoryCleanRule,
  getCategoryCleanRule,
  updateCategoryCleanRule,
} from '#/api/governance/categorycleanrule';
import { $t } from '#/locales';
import { GOVERNANCE_MODAL_FORM } from '#/views/governance/common/form-layout';

import { useFormSchema } from '../data';

const emit = defineEmits(['success']);
const formData = ref<FoundationCategoryCleanRuleApi.CategoryCleanRule>();
const getTitle = computed(() => {
  return formData.value?.id
    ? $t('ui.actionTitle.edit', ['清洗规则 类目管理'])
    : $t('ui.actionTitle.create', ['清洗规则 类目管理']);
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
      (await formApi.getValues()) as FoundationCategoryCleanRuleApi.CategoryCleanRule;
    try {
      await (formData.value?.id
        ? updateCategoryCleanRule(data)
        : createCategoryCleanRule(data));
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
      modalApi.getData() as FoundationCategoryCleanRuleApi.CategoryCleanRule;
    if (!data || !data.id) {
      // 设置上级
      await formApi.setValues(data ?? {});
      return;
    }
    modalApi.lock();
    try {
      formData.value = await getCategoryCleanRule(data.id);
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
