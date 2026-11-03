<script setup lang="ts">
import type { StandardDocApi } from '#/api/governance/doc';
import type { AxiosProgressEvent } from '#/api/infra/file';

import { computed, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { useAccessStore } from '@vben/stores';

import { Alert, Button, message } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import {
  createDocRegistration,
  getDoc,
  getDocCategories,
  getDocSimpleList,
  updateDocRegistration,
} from '#/api/governance/doc';
import { uploadFileWithInfo } from '#/api/infra/file';
import { useObjectRequest } from '#/hooks/use-object-request';
import { GOVERNANCE_MODAL_FORM } from '#/views/governance/common/form-layout';

import { useFormSchema } from '../data';

const emit = defineEmits<{ success: [] }>();
const access = useAccessStore();
const currentDocId = ref<StandardDocApi.Id>();
const categories = ref<StandardDocApi.Category[]>([]);
const documents = ref<StandardDocApi.SimpleDoc[]>([]);
const identity = ref('closed');
const ready = ref(false);
const failed = ref(false);
const isUploading = ref(false);
let sequence = 0;
const request = useObjectRequest(
  () => `${identity.value}/${access.accessCodes.join(',')}`,
  () => {
    ready.value = false;
    categories.value = [];
    documents.value = [];
    isUploading.value = false;
  },
);
const title = computed(() => (currentDocId.value ? '修改标准' : '新增标准'));
function defaults(
  context: Partial<StandardDocApi.Doc>,
): StandardDocApi.DocSave {
  return {
    categoryId: context.categoryId ? String(context.categoryId) : null,
    fileId: null,
    fileName: null,
    fileUrl: null,
    implementDate: null,
    implementStatus: 'DRAFT',
    nameCn: '',
    publishDate: null,
    abolishDate: null,
    publishOrg: null,
    versionNo: null,
    remark: null,
    replaceDocId: null,
    sort: 0,
    status: 0,
    stdCode: null,
    stdType: context.stdType || 'NATIONAL',
    summary: null,
  };
}
async function upload(file: File, onUploadProgress?: AxiosProgressEvent) {
  const current = request.begin();
  isUploading.value = true;
  modalApi.lock();
  formApi.setDisabled(true);
  try {
    const info = await uploadFileWithInfo(
      { file, directory: 'standard/doc' },
      onUploadProgress,
    );
    if (!current()) throw new Error('当前标准表单已关闭，请重新上传');
    if (!info.id || !info.url) throw new Error('文件登记尚未完成，请重新上传');
    await formApi.setValues({
      fileId: info.id,
      fileName: info.name,
      fileUrl: info.url,
    });
    return info;
  } finally {
    if (current()) {
      isUploading.value = false;
      formApi.setDisabled(false);
      modalApi.unlock();
    }
  }
}
async function deleteFile() {
  await formApi.setValues({ fileId: null, fileName: null, fileUrl: null });
}
const [Form, formApi] = useVbenForm({
  ...GOVERNANCE_MODAL_FORM,
  schema: useFormSchema({
    categories: () => categories.value,
    documents: () => documents.value,
    onFileDelete: deleteFile,
    uploadFile: upload,
  }),
});
async function load() {
  const context = (modalApi.getData() ?? {}) as Partial<StandardDocApi.Doc>;
  currentDocId.value = context.id;
  const current = request.begin();
  ready.value = false;
  failed.value = false;
  modalApi.setState({ confirmDisabled: true });
  modalApi.lock();
  try {
    const [categoryRows, docRows, detail] = await Promise.all([
      getDocCategories(),
      getDocSimpleList(context.id),
      context.id ? getDoc(context.id) : Promise.resolve(undefined),
    ]);
    if (!current()) return;
    categories.value = categoryRows;
    documents.value = docRows;
    if (
      detail?.categoryId &&
      !categoryRows.some(
        (item) => String(item.id) === String(detail.categoryId),
      )
    ) {
      categories.value = [
        ...categoryRows,
        {
          id: detail.categoryId,
          categoryName: detail.categoryName || String(detail.categoryId),
        },
      ];
    }
    await formApi.resetForm();
    if (!current()) return;
    await formApi.setValues(
      detail
        ? {
            ...detail,
            categoryId:
              detail.categoryId === null || detail.categoryId === undefined
                ? undefined
                : String(detail.categoryId),
            replaceDocId:
              detail.replaceDocId === null || detail.replaceDocId === undefined
                ? undefined
                : String(detail.replaceDocId),
          }
        : defaults(context),
    );
    if (current()) {
      ready.value = true;
      modalApi.setState({ confirmDisabled: false });
    }
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) modalApi.unlock();
  }
}
const [Modal, modalApi] = useVbenModal({
  async onConfirm() {
    if (!ready.value || isUploading.value) return;
    const current = request.begin();
    const result = await formApi.validate();
    if (!result.valid || !current()) return;
    modalApi.lock();
    try {
      const values = (await formApi.getValues()) as StandardDocApi.DocSave;
      if (!current()) return;
      const payload: StandardDocApi.DocSave = {
        ...values,
        categoryId: values.categoryId || null,
        publishDate: values.publishDate || null,
        implementDate: values.implementDate || null,
        abolishDate: values.abolishDate || null,
        replaceDocId: values.replaceDocId || null,
        fileId: values.fileUrl ? values.fileId : null,
        fileName: values.fileUrl ? values.fileName : null,
        fileUrl: values.fileUrl || null,
      };
      if (!payload.categoryId || !payload.fileId || !payload.stdCode) {
        message.warning('请选择标准类目、填写标准号并完成文件上传');
        return;
      }
      const registration = {
        ...payload,
        categoryId: payload.categoryId,
        fileId: payload.fileId,
        stdCode: payload.stdCode,
      };
      await (currentDocId.value
        ? updateDocRegistration({ ...registration, id: currentDocId.value })
        : createDocRegistration(registration));
      if (!current()) return;
      emit('success');
      message.success('保存成功');
      await modalApi.close();
    } finally {
      if (current()) modalApi.unlock();
    }
  },
  async onOpenChange(open) {
    identity.value = open ? String(++sequence) : 'closed';
    if (open) {
      await load();
      return;
    }
    ready.value = false;
    currentDocId.value = undefined;
    failed.value = false;
    modalApi.unlock();
    formApi.setDisabled(false);
    await formApi.resetForm();
  },
});
watch(
  () => [access.accessToken, access.refreshToken, access.accessCodes.join(',')],
  () => modalApi.close(),
);
</script>
<template>
  <Modal :title="title" class="w-[1000px] max-w-[95vw]">
    <Alert
      v-if="failed"
      type="error"
      message="标准或选项加载失败，请重试"
      class="mb-4"
    >
      <template #action><Button @click="load">重试</Button></template>
    </Alert>
    <Form class="mx-4" />
  </Modal>
</template>
