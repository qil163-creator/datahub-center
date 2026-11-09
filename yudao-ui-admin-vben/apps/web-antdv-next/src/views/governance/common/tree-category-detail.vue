<script lang="ts" setup>
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import { auditDetailFields, formatEnableStatus } from './detail-helpers';

export interface TreeCategoryDetailRecord {
  categoryName?: string;
  createTime?: null | number | string;
  creator?: null | number | string;
  id?: number | string;
  parentId?: number | string;
  remark?: string;
  sort?: number;
  status?: number;
  updater?: null | number | string;
  updateTime?: null | number | string;
}

export interface TreeCategoryDetailPayload {
  id?: number | string;
  loader: (id: number | string) => Promise<TreeCategoryDetailRecord>;
  parentLabel?: string;
  parentName?: string;
  title: string;
}

defineOptions({ name: 'GovernanceTreeCategoryDetail' });

const payload = ref<TreeCategoryDetailPayload>();
const detail = ref<TreeCategoryDetailRecord>({});

const fields = computed<DetailFieldItem[]>(() => [
  { key: 'id', label: '编号', value: detail.value.id },
  {
    key: 'parentName',
    label: payload.value?.parentLabel || '上级类目',
    value: payload.value?.parentName || '顶级节点',
  },
  { key: 'categoryName', label: '类目名称', value: detail.value.categoryName },
  { key: 'sort', label: '排序', value: detail.value.sort },
  {
    key: 'status',
    label: '状态',
    value: formatEnableStatus(detail.value.status),
  },
  {
    key: 'remark',
    label: '备注',
    value: detail.value.remark,
    span: 2,
    type: 'textarea',
  },
  ...auditDetailFields(detail.value),
]);

const [Modal, modalApi] = useVbenModal({
  centered: true,
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      payload.value = undefined;
      detail.value = {};
      return;
    }
    const data = modalApi.getData() as TreeCategoryDetailPayload;
    payload.value = data;
    if (data?.id === undefined || data?.id === null || data.id === '') {
      return;
    }
    modalApi.lock();
    try {
      detail.value = await data.loader(data.id);
    } finally {
      modalApi.unlock();
    }
  },
});
</script>

<template>
  <Modal
    :title="`${payload?.title ?? '类目'}详情${detail.categoryName ? ` - ${detail.categoryName}` : ''}`"
    class="w-[960px] max-w-[calc(100vw-32px)]"
  >
    <DetailFieldForm :fields="fields" class="mx-4" label-width="130px" />
  </Modal>
</template>
