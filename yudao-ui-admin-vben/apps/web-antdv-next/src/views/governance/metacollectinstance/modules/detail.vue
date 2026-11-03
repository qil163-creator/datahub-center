<script lang="ts" setup>
import type { GovernanceMetaCollectInstanceApi } from '#/api/governance/metacollectinstance';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { getMetaCollectInstance } from '#/api/governance/metacollectinstance';
import {
  auditDetailFields,
  formatJsonText,
} from '#/views/governance/common/detail-helpers';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import {
  formatDuration,
  INSTANCE_STATUS_OPTIONS,
  optionLabel,
  TRIGGER_TYPE_OPTIONS,
} from '../data';

defineOptions({ name: 'GovernanceMetaCollectInstanceDetail' });

const detail = ref<GovernanceMetaCollectInstanceApi.CollectInstance>();

const fields = computed<DetailFieldItem[]>(() => {
  const row = detail.value;
  return [
    { key: 'id', label: '编号', value: row?.id },
    { key: 'taskId', label: '任务编号', value: row?.taskId },
    { key: 'taskName', label: '任务名称', value: row?.taskName },
    {
      key: 'triggerType',
      label: '触发方式',
      value: optionLabel(TRIGGER_TYPE_OPTIONS, row?.triggerType),
    },
    {
      key: 'status',
      label: '采集状态',
      value: optionLabel(INSTANCE_STATUS_OPTIONS, row?.status),
    },
    { key: 'stage', label: '阶段', value: row?.stage },
    { key: 'errorType', label: '错误类型', value: row?.errorType },
    {
      key: 'durationMs',
      label: '采集耗时',
      value: formatDuration(row?.durationMs),
    },
    { key: 'dbCount', label: '采集库数', value: row?.dbCount },
    { key: 'tableCount', label: '采集表数', value: row?.tableCount },
    { key: 'columnCount', label: '采集字段数', value: row?.columnCount },
    {
      key: 'tableChange',
      label: '表变更',
      value: `+${row?.tableAdd ?? 0} ~${row?.tableUpdate ?? 0} -${row?.tableDelete ?? 0}`,
    },
    {
      key: 'columnChange',
      label: '字段变更',
      value: `+${row?.columnAdd ?? 0} ~${row?.columnUpdate ?? 0} -${row?.columnDelete ?? 0}`,
    },
    {
      key: 'failDetail',
      label: '失败清单',
      value: formatJsonText(row?.failDetail),
      span: 2,
      type: 'textarea',
      rows: 4,
    },
    {
      key: 'report',
      label: '采集报告',
      value: formatJsonText(row?.report),
      span: 2,
      type: 'textarea',
      rows: 4,
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
    const data = modalApi.getData() as
      | GovernanceMetaCollectInstanceApi.CollectInstance
      | undefined;
    if (data?.id === undefined || data.id === null) {
      return;
    }
    modalApi.lock();
    try {
      const loaded = await getMetaCollectInstance(data.id);
      detail.value = {
        ...loaded,
        taskName: loaded.taskName || data.taskName,
      };
    } finally {
      modalApi.unlock();
    }
  },
});
</script>

<template>
  <Modal
    :title="`采集实例详情${detail?.id ? ` - #${detail.id}` : ''}`"
    class="w-[960px]"
  >
    <DetailFieldForm :fields="fields" class="mx-4" label-width="130px" />
  </Modal>
</template>
