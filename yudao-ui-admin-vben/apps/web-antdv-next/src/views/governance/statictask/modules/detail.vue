<script lang="ts" setup>
import type { SafetyStaticTaskApi } from '#/api/governance/statictask';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { CommonStatusEnum } from '@vben/constants';

import { getSafetyStaticTask } from '#/api/governance/statictask';
import {
  auditDetailFields,
  formatJsonText,
} from '#/views/governance/common/detail-helpers';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import { optionLabel, WRITE_MODE_OPTIONS } from '../data';

defineOptions({ name: 'SafetyStaticTaskDetail' });

const detail = ref<SafetyStaticTaskApi.Task>();

function formatTaskStatus(status?: number) {
  if (status === CommonStatusEnum.ENABLE) {
    return '上线';
  }
  if (status === CommonStatusEnum.DISABLE) {
    return '下线';
  }
  return '-';
}

const fields = computed<DetailFieldItem[]>(() => {
  const row = detail.value;
  return [
    { key: 'id', label: '编号', value: row?.id },
    { key: 'taskName', label: '任务名称', value: row?.taskName },
    {
      key: 'sourceDatabaseId',
      label: '源数据源编号',
      value: row?.sourceDatabaseId,
    },
    { key: 'sourceTableName', label: '源表', value: row?.sourceTableName },
    {
      key: 'targetDatabaseId',
      label: '目标数据源编号',
      value: row?.targetDatabaseId,
    },
    { key: 'targetTableName', label: '目标表', value: row?.targetTableName },
    {
      key: 'writeMode',
      label: '写入模式',
      value: optionLabel(WRITE_MODE_OPTIONS, row?.writeMode),
    },
    { key: 'batchSize', label: '批次大小', value: row?.batchSize },
    {
      key: 'cronExpression',
      label: '调度周期',
      value: row?.cronExpression || '手动',
    },
    {
      key: 'status',
      label: '状态',
      value: formatTaskStatus(row?.status),
    },
    {
      key: 'fieldRules',
      label: '脱敏字段',
      value: formatJsonText(row?.fieldRules),
      span: 2,
      type: 'textarea',
      rows: 4,
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
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      detail.value = undefined;
      return;
    }
    const data = modalApi.getData() as { id?: number | string };
    if (data?.id === undefined || data.id === null || data.id === '') {
      return;
    }
    modalApi.lock();
    try {
      detail.value = await getSafetyStaticTask(data.id);
    } finally {
      modalApi.unlock();
    }
  },
});
</script>

<template>
  <Modal
    :title="`静态脱敏任务详情${detail?.taskName ? ` - ${detail.taskName}` : ''}`"
    class="w-[960px]"
  >
    <DetailFieldForm :fields="fields" class="mx-4" label-width="130px" />
  </Modal>
</template>
