<script lang="ts" setup>
import type { StandardDocApi } from '#/api/governance/doc';
import type { AxiosProgressEvent } from '#/api/infra/file';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { message } from 'antdv-next';
import dayjs from 'dayjs';

import { useVbenForm } from '#/adapter/form';
import { createDoc, getDoc, updateDoc } from '#/api/governance/doc';
import { useUpload } from '#/components/upload/use-upload';
import { $t } from '#/locales';
import { GOVERNANCE_MODAL_FORM } from '#/views/governance/common/form-layout';

import { useFormSchema } from '../data';

const emit = defineEmits(['success']);

const DATE_FORMAT = 'YYYY-MM-DD';

const currentDocId = ref<StandardDocApi.Id>();
const isUploading = ref(false);
const { httpRequestWithInfo } = useUpload('standard/doc');

const getTitle = computed(() => {
  return currentDocId.value
    ? $t('ui.actionTitle.edit', ['标准文件登记'])
    : $t('ui.actionTitle.create', ['标准文件登记']);
});

function getCreateDefaultValues(): StandardDocApi.DocSave {
  return {
    fileId: null,
    fileName: null,
    fileUrl: null,
    implementDate: null,
    implementStatus: 'CURRENT',
    nameCn: '',
    publishDate: null,
    publishOrg: null,
    remark: null,
    replaceDocId: null,
    sort: 0,
    status: 0,
    stdCode: null,
    stdType: 'LOCAL',
    summary: null,
  };
}

async function handleFileUpload(
  file: File,
  onUploadProgress?: AxiosProgressEvent,
) {
  isUploading.value = true;
  modalApi.lock();
  try {
    formApi.setDisabled(true);
    const fileInfo = await httpRequestWithInfo(file, onUploadProgress);
    await formApi.setValues({
      fileId: fileInfo.id,
      fileName: fileInfo.name,
    });
    return fileInfo;
  } finally {
    isUploading.value = false;
    formApi.setDisabled(false);
    modalApi.unlock();
  }
}

async function handleFileDelete() {
  await formApi.setValues({
    fileId: null,
    fileName: null,
    fileUrl: null,
  });
}

const [Form, formApi] = useVbenForm({
  ...GOVERNANCE_MODAL_FORM,
  schema: useFormSchema({
    getCurrentDocId: () => currentDocId.value,
    onFileDelete: handleFileDelete,
    uploadFile: handleFileUpload,
  }),
});

const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    if (isUploading.value) {
      return;
    }
    const { valid } = await formApi.validate();
    if (!valid) {
      return;
    }
    modalApi.lock();
    const values = (await formApi.getValues()) as StandardDocApi.DocSave;
    const data: StandardDocApi.DocSave = {
      ...values,
      fileId: values.fileUrl ? values.fileId : null,
      fileName: values.fileUrl ? values.fileName : null,
      fileUrl: values.fileUrl || null,
    };
    try {
      await (currentDocId.value
        ? updateDoc({ ...data, id: currentDocId.value })
        : createDoc(data));
      await modalApi.close();
      emit('success');
      message.success($t('ui.actionMessage.operationSuccess'));
    } finally {
      modalApi.unlock();
    }
  },
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      currentDocId.value = undefined;
      await formApi.resetForm();
      return;
    }
    const data = modalApi.getData() as StandardDocApi.Doc;
    currentDocId.value = data?.id;
    await formApi.resetForm();
    await formApi.setValues(getCreateDefaultValues());
    if (!currentDocId.value) {
      return;
    }
    modalApi.lock();
    try {
      const detail = await getDoc(currentDocId.value);
      await formApi.setValues({
        ...detail,
        implementDate: detail.implementDate
          ? dayjs(detail.implementDate).format(DATE_FORMAT)
          : null,
        publishDate: detail.publishDate
          ? dayjs(detail.publishDate).format(DATE_FORMAT)
          : null,
      });
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
