<script lang="ts" setup>
import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { auditDetailFields } from '#/views/governance/common/detail-helpers';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

defineOptions({ name: 'GovernanceMasterCatalogDetail' });

const detail = ref<GovernanceMasterDataApi.Catalog>();

function catalogTypeLabel(type?: number) {
  if (type === 0) {
    return '普通目录';
  }
  if (type === 1) {
    return '主数据';
  }
  return '-';
}

function modelStatusLabel(status?: null | number) {
  if (status === 1) {
    return '已发布';
  }
  if (status === 0) {
    return '未发布';
  }
  return '未建模';
}

const fields = computed<DetailFieldItem[]>(() => {
  const row = detail.value;
  return [
    { key: 'id', label: '编号', value: row?.id },
    { key: 'name', label: '节点名称', value: row?.name },
    { key: 'parentId', label: '上级编号', value: row?.parentId },
    { key: 'type', label: '类型', value: catalogTypeLabel(row?.type) },
    { key: 'path', label: '路径', value: row?.path, span: 2 },
    { key: 'orderNo', label: '排序号', value: row?.orderNo },
    { key: 'orgId', label: '所属机构编号', value: row?.orgId },
    {
      key: 'modelStatus',
      label: '模型状态',
      value: row?.type === 1 ? modelStatusLabel(row.modelStatus) : '-',
    },
    {
      key: 'description',
      label: '描述',
      value: row?.description,
      span: 2,
      type: 'textarea',
    },
    ...auditDetailFields(row),
  ];
});

const [Modal, modalApi] = useVbenModal({
  showConfirmButton: false,
  cancelText: '关闭',
  onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      detail.value = undefined;
      return;
    }
    detail.value = modalApi.getData() as GovernanceMasterDataApi.Catalog;
  },
});
</script>

<template>
  <Modal
    :title="`目录节点详情${detail?.name ? ` - ${detail.name}` : ''}`"
    class="w-[960px]"
  >
    <DetailFieldForm :fields="fields" class="mx-4" label-width="130px" />
  </Modal>
</template>
