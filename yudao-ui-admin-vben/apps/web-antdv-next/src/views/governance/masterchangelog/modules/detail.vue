<script setup lang="ts">
import type { GovernanceMasterManageApi } from '#/api/governance/mastermanage';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { formatDateTime } from '@vben/utils';

import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import { operationLabel } from '../../mastercommon/enums';
import { useMasterScope } from '../../mastercommon/use-master-scope';

defineOptions({ name: 'GovernanceMasterChangeLogDetail' });

const detail = ref<GovernanceMasterManageApi.ChangeLog>();
const fields = computed<DetailFieldItem[]>(() => {
  const row = detail.value;
  return [
    { key: 'id', label: '日志ID', value: row?.id },
    { key: 'changeId', label: '变更ID', value: row?.changeId },
    {
      key: 'modelName',
      label: '所属模型',
      value: row?.modelName || row?.modelId,
    },
    { key: 'dataId', label: '数据ID', value: row?.dataId },
    {
      key: 'changeType',
      label: '变更类型',
      value: row ? operationLabel(row.changeType) : undefined,
    },
    { key: 'propertyName', label: '属性名称', value: row?.propertyName },
    {
      key: 'beforeValue',
      label: '变更前',
      value: row?.beforeValue,
      type: 'textarea',
      span: 2,
      rows: 6,
    },
    {
      key: 'afterValue',
      label: '变更后',
      value: row?.afterValue,
      type: 'textarea',
      span: 2,
      rows: 6,
    },
    {
      key: 'changedBy',
      label: '变更人',
      value: row?.changedByName || row?.changedBy,
    },
    {
      key: 'changedAt',
      label: '变更时间',
      value: formatDateTime(row?.changedAt),
    },
  ];
});
useMasterScope(() => {
  detail.value = undefined;
  modalApi.setState({ isOpen: false });
});

const [Modal, modalApi] = useVbenModal<GovernanceMasterManageApi.ChangeLog>({
  title: '主数据变更日志详情',
  class: 'w-[960px]',
  centered: true,
  fullscreenButton: false,
  showConfirmButton: false,
  cancelText: '关闭',
  onOpenChange(isOpen) {
    detail.value = isOpen ? modalApi.getData() : undefined;
  },
});
</script>

<template>
  <Modal>
    <DetailFieldForm :fields="fields" class="mx-4" label-width="100px" />
  </Modal>
</template>
