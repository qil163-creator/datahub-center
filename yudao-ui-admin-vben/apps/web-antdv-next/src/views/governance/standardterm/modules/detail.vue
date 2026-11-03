<script lang="ts" setup>
import type { GovernanceCategoryTermApi } from '#/api/governance/categoryterm';
import type { GovernanceStandardTermApi } from '#/api/governance/standardterm';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { useAccessStore } from '@vben/stores';

import { getStandardTerm, getStandardTermCategories } from '#/api/governance/standardterm';
import { useObjectRequest } from '#/hooks/use-object-request';
import {
  auditDetailFields,
  formatEnableStatus,
} from '#/views/governance/common/detail-helpers';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import { formatTermUser, loadTermUserList } from '../data';

defineOptions({ name: 'GovernanceStandardTermDetail' });

const detail = ref<GovernanceStandardTermApi.StandardTerm>();
const categories = ref<GovernanceCategoryTermApi.Category[]>([]);
const identity = ref('closed');
const access = useAccessStore();
const request = useObjectRequest(
  () => identity.value,
  () => {
    detail.value = undefined;
    categories.value = [];
  },
);

const fields = computed<DetailFieldItem[]>(() => {
  const row = detail.value;
  return [
    { key: 'id', label: '编号', value: row?.id },
    { key: 'name', label: '术语名称', value: row?.name },
    { key: 'engName', label: '英文名称', value: row?.engName },
    { key: 'alias', label: '别名/同义词', value: row?.alias },
    {
      key: 'termCategoryId',
      label: '术语类目',
      value: row?.termCategoryId === null || row?.termCategoryId === undefined
        ? '未分类'
        : categories.value.find((item) => String(item.id) === String(row.termCategoryId))?.categoryName || String(row.termCategoryId),
    },
    {
      key: 'ownerUserId',
      label: '负责人',
      value: formatTermUser(row?.ownerUserId),
    },
    {
      key: 'stewardUserId',
      label: '数据管家',
      value: formatTermUser(row?.stewardUserId),
    },
    {
      key: 'status',
      label: '状态',
      value: formatEnableStatus(row?.status),
    },
    {
      key: 'relatedElementId',
      label: '关联数据元',
      value: row?.relatedElementId,
    },
    {
      key: 'definition',
      label: '业务定义',
      value: row?.definition,
      span: 2,
      type: 'textarea',
    },
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
      identity.value = 'closed';
      request.begin();
      modalApi.unlock();
      detail.value = undefined;
      return;
    }
    const data = modalApi.getData() as { id?: number | string };
    if (!data?.id) {
      return;
    }
    identity.value = String(data.id);
    const current = request.begin();
    modalApi.lock();
    try {
      const [term, categoryList] = await Promise.all([
        getStandardTerm(data.id),
        getStandardTermCategories().catch(() => []),
        loadTermUserList().catch(() => []),
      ]);
      if (current()) {
        detail.value = term;
        categories.value = categoryList;
      }
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
  <Modal
    :title="`业务术语详情${detail?.name ? ` - ${detail.name}` : ''}`"
    class="w-[960px]"
  >
    <DetailFieldForm :fields="fields" class="mx-4" label-width="130px" />
  </Modal>
</template>
