<script lang="ts" setup>
import type { ModelingBizCategoryApi } from '#/api/modeling/bizcategory';

import { computed, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { CommonStatusEnum } from '@vben/constants';
import { useAccessStore } from '@vben/stores';

import { message } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import {
  createBizCategory,
  getBizCategory,
  updateBizCategory,
} from '#/api/modeling/bizcategory';
import { $t } from '#/locales';
import { parseResourceId } from '#/utils/resource-id';

import { useModelingRequest } from '../../modelcommon/use-modeling-request';
import { getUserMobile, useFormSchema } from '../data';

defineOptions({ name: 'ModelingBizCategoryForm' });

const emit = defineEmits(['success']);
const formData = ref<ModelingBizCategoryApi.BizCategory>();
const identity = ref('closed');
const access = useAccessStore();
const request = useModelingRequest(
  () => identity.value,
  () => {
    formData.value = undefined;
  },
);

const getTitle = computed(() =>
  formData.value?.id ? '修改业务分类' : '新增业务分类',
);

const [Form, formApi] = useVbenForm({
  commonConfig: {
    componentProps: {
      class: 'w-full',
    },
    formItemClass: 'col-span-1',
    labelWidth: 130,
  },
  wrapperClass: 'grid-cols-2',
  layout: 'horizontal',
  schema: useFormSchema(),
  showDefaultActions: false,
  handleValuesChange: async (values, changedFields) => {
    // 负责人电话随负责人自动带出
    if (changedFields.includes('leaderUserId')) {
      await formApi.setFieldValue(
        'leaderPhone',
        getUserMobile(values.leaderUserId),
      );
    }
  },
});

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    const current = request.begin();
    const { valid } = await formApi.validate();
    if (!valid || !current()) {
      return;
    }
    modalApi.lock();
    const raw =
      (await formApi.getValues()) as ModelingBizCategoryApi.BizCategory;
    const selectedIds = Array.isArray(raw.dataDomainIds)
      ? raw.dataDomainIds
      : [];
    const dataDomainIds = selectedIds
      .map((id) => parseResourceId(id))
      .filter((id): id is string => id !== undefined);
    if (dataDomainIds.length !== selectedIds.length) {
      message.warning('关联数据域编号无效，请重新选择');
      modalApi.unlock();
      return;
    }
    if (dataDomainIds.length === 0) {
      message.warning('请选择关联数据域');
      modalApi.unlock();
      return;
    }
    const data: ModelingBizCategoryApi.BizCategory = {
      ...raw,
      dataDomainIds,
    };
    try {
      if (!current()) return;
      await (formData.value?.id
        ? updateBizCategory(data)
        : createBizCategory(data));
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
      modalApi.unlock();
      formData.value = undefined;
      await formApi.resetForm();
      return;
    }
    const data = modalApi.getData() as ModelingBizCategoryApi.BizCategory;
    identity.value = String(data?.id ?? 'new');
    const current = request.begin();
    await formApi.resetForm();
    if (!current()) return;
    if (!data?.id) {
      // 新建：支持从「新增下级」带入上级分类
      await formApi.setValues({
        parentId: data?.parentId ?? 0,
        status: CommonStatusEnum.ENABLE,
      });
      return;
    }
    modalApi.lock();
    try {
      const category = await getBizCategory(data.id);
      if (!current()) return;
      formData.value = category;
      await formApi.setValues(formData.value);
    } finally {
      if (current()) modalApi.unlock();
    }
  },
});
watch(
  () => [access.accessToken, access.refreshToken],
  () => modalApi.close(),
);
</script>

<template>
  <Modal :title="getTitle" class="w-[800px]">
    <Form class="mx-4" />
  </Modal>
</template>
