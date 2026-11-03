<script lang="ts" setup>
import type { DataforgeFillTableApi } from '#/api/dataforge/filltable';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { formatDateTime } from '@vben/utils';

import { getFillTable } from '#/api/dataforge/filltable';
import { DATAFORGE_DETAIL_MODAL_CLASS } from '#/views/dataforge/common/form-layout';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import { FILL_TABLE_STATUS_OPTIONS, fillTableOptionLabel } from '../data';

defineOptions({ name: 'DataforgeFillTableDetail' });

const detail = ref<DataforgeFillTableApi.FillTable>();

const fields = computed<DetailFieldItem[]>(() => {
  const table = detail.value ?? {};
  return [
    { key: 'id', label: '编号', value: table.id },
    { key: 'tableEngName', label: '表英文名', value: table.tableEngName },
    { key: 'tableCnName', label: '表中文名', value: table.tableCnName },
    { key: 'databaseName', label: '目标数据源', value: table.databaseName },
    { key: 'orgName', label: '所属单位', value: table.orgName },
    {
      key: 'status',
      label: '发布状态',
      value: fillTableOptionLabel(FILL_TABLE_STATUS_OPTIONS, table.status),
    },
    { key: 'releaseUserName', label: '发布人', value: table.releaseUserName },
    {
      key: 'releaseTime',
      label: '发布时间',
      value: formatDateTime(table.releaseTime),
    },
    {
      key: 'description',
      label: '描述',
      value: table.description,
      span: 2,
      type: 'textarea',
      rows: 2,
    },
    {
      key: 'createTime',
      label: '创建时间',
      value: formatDateTime(table.createTime),
    },
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
    const data = modalApi.getData() as undefined | { id?: number | string };
    if (!data?.id) {
      return;
    }
    modalApi.lock();
    try {
      detail.value = await getFillTable(data.id);
    } finally {
      modalApi.unlock();
    }
  },
});
</script>

<template>
  <Modal
    :title="`填报表详情${detail?.tableCnName ? ` - ${detail.tableCnName}` : ''}`"
    :class="DATAFORGE_DETAIL_MODAL_CLASS"
  >
    <DetailFieldForm :fields="fields" class="mx-4" label-width="130px" />
  </Modal>
</template>
