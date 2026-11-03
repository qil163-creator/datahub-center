<script lang="ts" setup>
import type { FoundationCategoryDataApi } from '#/api/governance/categorydata';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { message } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import {
  createCategoryData,
  getCategoryData,
  updateCategoryData,
} from '#/api/governance/categorydata';
import { $t } from '#/locales';
import {
  createLatestWins,
  parseResourceId,
  resourceIdKey,
} from '#/utils/resource-id';
import { validCategoryRevision } from '#/views/governance/category/reference-data';
import { useCategorySession } from '#/views/governance/category/use-session';
import { GOVERNANCE_MODAL_FORM } from '#/views/governance/common/form-layout';

import { useFormSchema } from '../data';

const emit = defineEmits(['success']);
const formData = ref<FoundationCategoryDataApi.CategoryData>();
const { identity, can } = useCategorySession();
const guard = createLatestWins();
let opened = false;
let ready = false;
let editing = false;
let saving = false;
const getTitle = computed(() => {
  return formData.value?.id
    ? $t('ui.actionTitle.edit', ['数据分类 类目管理'])
    : $t('ui.actionTitle.create', ['数据分类 类目管理']);
});

const [Form, formApi] = useVbenForm({
  ...GOVERNANCE_MODAL_FORM,
  schema: useFormSchema(),
});

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    if (
      !opened ||
      !ready ||
      saving ||
      !can(editing ? 'update' : 'create', 'category-data')
    )
      return;
    const subject = identity.value;
    const { valid } = await formApi.validate();
    if (
      !valid ||
      subject !== identity.value ||
      !opened ||
      !ready ||
      !can(editing ? 'update' : 'create', 'category-data')
    ) {
      return;
    }
    if (
      editing &&
      (!formData.value || !validCategoryRevision(formData.value.revision))
    )
      return;
    const ticket = guard.next();
    const current = formData.value;
    saving = true;
    modalApi.lock();
    // 提交表单
    try {
      const values =
        (await formApi.getValues()) as FoundationCategoryDataApi.CategoryData;
      if (!ticket.isCurrent()) return;
      const data: FoundationCategoryDataApi.Save = {
        ...values,
        id: undefined,
        revision: undefined,
      };
      await (editing
        ? updateCategoryData({
            ...data,
            id: current!.id,
            revision: current!.revision,
          })
        : createCategoryData(data));
      if (!ticket.isCurrent()) return;
      // 关闭并提示
      saving = false;
      modalApi.unlock();
      await modalApi.close();
      if (subject !== identity.value) return;
      emit('success');
      message.success($t('ui.actionMessage.operationSuccess'));
    } finally {
      if (ticket.isCurrent()) {
        saving = false;
        modalApi.unlock();
      }
    }
  },
  async onOpenChange(isOpen: boolean) {
    guard.invalidate();
    opened = isOpen;
    ready = false;
    saving = false;
    modalApi.unlock();
    if (!isOpen) {
      formData.value = undefined;
      await formApi.resetForm();
      return;
    }
    // 加载数据
    const data = modalApi.getData() as FoundationCategoryDataApi.CategoryData;
    editing = !!parseResourceId(data?.id);
    if (!can(editing ? 'update' : 'create', 'category-data')) {
      await modalApi.close();
      return;
    }
    const ticket = guard.next();
    if (!editing) {
      // 设置上级
      await formApi.setValues(data ?? {});
      if (ticket.isCurrent()) ready = true;
      return;
    }
    modalApi.lock();
    try {
      const fresh = await getCategoryData(data.id);
      if (
        !ticket.isCurrent() ||
        resourceIdKey(fresh.id) !== resourceIdKey(data.id) ||
        !validCategoryRevision(fresh.revision)
      )
        return;
      formData.value = {
        ...fresh,
        parentId: resourceIdKey(fresh.parentId) || '0',
      };
      // 设置到 values
      await formApi.setValues(formData.value);
      if (ticket.isCurrent()) ready = true;
    } finally {
      if (ticket.isCurrent()) modalApi.unlock();
    }
  },
});
watch(
  identity,
  () => {
    guard.invalidate();
    ready = false;
    formData.value = undefined;
    if (opened) void modalApi.close();
  },
  { flush: 'sync' },
);
onBeforeUnmount(() => {
  guard.invalidate();
  opened = false;
  ready = false;
});
</script>

<template>
  <Modal :title="getTitle" class="w-[800px]">
    <Form class="mx-4" />
  </Modal>
</template>
