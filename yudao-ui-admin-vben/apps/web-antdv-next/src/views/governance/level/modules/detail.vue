<script lang="ts" setup>
import type { SafetyLevelApi } from '#/api/governance/level';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { getLevel } from '#/api/governance/level';
import { useObjectRequest } from '#/hooks/use-object-request';
import {
  auditDetailFields,
  formatEnableStatus,
} from '#/views/governance/common/detail-helpers';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

defineOptions({ name: 'SafetyLevelDetail' });

const detail = ref<SafetyLevelApi.Level>();
const detailRequest = useObjectRequest(
  () => '',
  () => {
    detail.value = undefined;
    modalApi.setState({ loading: false });
  },
);

const fields = computed<DetailFieldItem[]>(() => {
  const row = detail.value;
  return [
    { key: 'id', label: '编号', value: row?.id },
    { key: 'safetyName', label: '显示名称', value: row?.safetyName },
    { key: 'safetyLevel', label: '安全等级', value: row?.safetyLevel },
    { key: 'color', label: '标志颜色', value: row?.color },
    {
      key: 'status',
      label: '状态',
      value: formatEnableStatus(row?.status),
    },
    { key: 'sort', label: '显示顺序', value: row?.sort },
    {
      key: 'description',
      label: '等级描述',
      value: row?.description,
      span: 2,
      type: 'textarea',
    },
    ...auditDetailFields(row),
  ];
});

const [Modal, modalApi] = useVbenModal({
  class: 'top-[6vh] w-[800px]',
  bordered: false,
  closeOnClickModal: false,
  fullscreenButton: false,
  headerClass: 'px-6 pt-6 pb-2',
  contentClass: 'px-6 py-0',
  footerClass: 'px-6 py-5',
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(isOpen: boolean) {
    const current = detailRequest.begin();
    detail.value = undefined;
    if (!isOpen) {
      modalApi.setState({ loading: false });
      return;
    }
    const data = modalApi.getData() as { id?: number };
    if (!data?.id) {
      return;
    }
    modalApi.setState({ loading: true });
    try {
      const result = await getLevel(data.id);
      if (current()) detail.value = result;
    } finally {
      if (current()) modalApi.setState({ loading: false });
    }
  },
});
</script>

<template>
  <Modal
    :title="`数据分级详情${detail?.safetyName ? ` - ${detail.safetyName}` : ''}`"
  >
    <DetailFieldForm :fields="fields" label-width="110px" />
  </Modal>
</template>
