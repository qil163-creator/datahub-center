<script lang="ts" setup>
import type { GovernanceMetaTagApi } from '#/api/governance/metatag';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { getMetaTag } from '#/api/governance/metatag';
import { auditDetailFields } from '#/views/governance/common/detail-helpers';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

defineOptions({ name: 'GovernanceMetaTagDetail' });

const detail = ref<GovernanceMetaTagApi.Tag>();

const fields = computed<DetailFieldItem[]>(() => {
  const row = detail.value;
  return [
    { key: 'id', label: '编号', value: row?.id },
    { key: 'name', label: '标签名称', value: row?.name },
    { key: 'category', label: '分类', value: row?.category },
    { key: 'color', label: '颜色', value: row?.color },
    { key: 'refCount', label: '引用次数', value: row?.refCount },
    {
      key: 'remark',
      label: '备注',
      value: row?.remark,
      span: 2,
      type: 'textarea',
    },
    ...auditDetailFields(row),
  ];
});

const [Modal, modalApi] = useVbenModal({
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      detail.value = undefined;
      return;
    }
    const data = modalApi.getData() as { id?: number };
    if (!data?.id) {
      return;
    }
    modalApi.lock();
    try {
      detail.value = await getMetaTag(data.id);
    } finally {
      modalApi.unlock();
    }
  },
});
</script>

<template>
  <Modal
    :title="`元数据标签详情${detail?.name ? ` - ${detail.name}` : ''}`"
    class="w-[960px]"
  >
    <DetailFieldForm :fields="fields" class="mx-4" label-width="130px" />
  </Modal>
</template>
