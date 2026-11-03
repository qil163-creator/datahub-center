<script lang="ts" setup>
import type { FoundationDatasourceDriverApi } from '#/api/dataforge/driver';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { CommonStatusEnum } from '@vben/constants';

import { message } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import {
  createDatasourceDriver,
  getDatasourceDriver,
  updateDatasourceDriver,
} from '#/api/dataforge/driver';
import { $t } from '#/locales';

import { DRIVER_SOURCE_BUILTIN, useFormSchema } from '../data';

const emit = defineEmits(['success']);
const formData = ref<FoundationDatasourceDriverApi.DatasourceDriver>();

const getTitle = computed(() => {
  return formData.value?.id
    ? $t('ui.actionTitle.edit', ['数据源驱动管理'])
    : $t('ui.actionTitle.create', ['数据源驱动管理']);
});

function extractFileName(path?: string) {
  if (!path) {
    return '';
  }
  return path.slice(Math.max(0, path.lastIndexOf('/') + 1));
}

async function clearJarFields() {
  await formApi.setFieldValue('jarFileName', '');
  await formApi.setFieldValue('jarFilePath', '');
  await formApi.setFieldValue('jarFileMd5', '');
  await formApi.setFieldValue('jarFileSize', undefined);
}

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
    if (
      changedFields.includes('driverSource') &&
      values.driverSource === DRIVER_SOURCE_BUILTIN
    ) {
      await clearJarFields();
      return;
    }
    if (changedFields.includes('jarFilePath')) {
      await formApi.setFieldValue(
        'jarFileName',
        extractFileName(values.jarFilePath),
      );
    }
  },
});

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await formApi.validate();
    if (!valid) {
      return;
    }
    modalApi.lock();
    const data =
      (await formApi.getValues()) as FoundationDatasourceDriverApi.DatasourceDriver;
    try {
      await (formData.value?.id
        ? updateDatasourceDriver(data)
        : createDatasourceDriver(data));
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
      return;
    }
    const data =
      modalApi.getData() as FoundationDatasourceDriverApi.DatasourceDriver;
    if (!data?.id) {
      await formApi.resetForm();
      await formApi.setValues({
        driverSource: DRIVER_SOURCE_BUILTIN,
        status: CommonStatusEnum.ENABLE,
        supportSchema: 0,
        sort: 0,
      });
      return;
    }
    modalApi.lock();
    try {
      formData.value = await getDatasourceDriver(data.id);
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
