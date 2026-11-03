<script lang="ts" setup>
import type { DataforgeEtlApi } from '#/api/dataforge/etl';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { useAccessStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import { getEtlPipeline } from '#/api/dataforge/etl';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import {
  optionLabel,
  RUN_STATUS_OPTIONS,
  STATUS_OPTIONS,
  TASK_TYPE_OPTIONS,
} from '../data';

defineOptions({ name: 'DataforgeEtlPipelineDetail' });

const detail = ref<DataforgeEtlApi.Pipeline>({});
const accessStore = useAccessStore();
let detailSeq = 0;
function invalidateDetail() {
  ++detailSeq;
  detail.value = {};
  modalApi.unlock();
}
watch(
  () => [accessStore.accessToken, accessStore.refreshToken],
  () => {
    invalidateDetail();
    modalApi.close();
  },
  { flush: 'sync' },
);
onBeforeUnmount(invalidateDetail);

const fields = computed<DetailFieldItem[]>(() => [
  { key: 'id', label: '编号', value: detail.value.id },
  { key: 'name', label: '流程名称', value: detail.value.name },
  { key: 'orgName', label: '所属单位', value: detail.value.orgName },
  { key: 'revision', label: '草稿修订', value: detail.value.revision },
  {
    key: 'publishedRevision',
    label: '发布版本',
    value: detail.value.publishedRevision,
  },
  {
    key: 'taskType',
    label: '调度类型',
    value: optionLabel(TASK_TYPE_OPTIONS, detail.value.taskType),
  },
  {
    key: 'status',
    label: '任务状态',
    value: optionLabel(STATUS_OPTIONS, detail.value.status),
  },
  {
    key: 'scheduleStatus',
    label: '调度状态',
    value: formatScheduleStatus(detail.value),
  },
  { key: 'cron', label: 'Cron 表达式', value: detail.value.cron },
  {
    key: 'nextRunTime',
    label: '下次执行时间',
    value: formatDateTime(detail.value.nextRunTime),
  },
  {
    key: 'runStatus',
    label: '最近运行摘要',
    value: optionLabel(RUN_STATUS_OPTIONS, detail.value.runStatus),
  },
  {
    key: 'nodeCount',
    label: '节点数量',
    value: detail.value.nodes?.length ?? 0,
  },
  {
    key: 'startTime',
    label: '开始时间',
    value: formatDateTime(detail.value.startTime),
  },
  {
    key: 'endTime',
    label: '结束时间',
    value: formatDateTime(detail.value.endTime),
  },
  {
    key: 'releaseUserName',
    label: '上线人',
    value: detail.value.releaseUserName,
  },
  {
    key: 'releaseTime',
    label: '上线时间',
    value: formatDateTime(detail.value.releaseTime),
  },
  {
    key: 'description',
    label: '描述',
    value: detail.value.description,
    span: 2,
    type: 'textarea',
  },
  {
    key: 'parameters',
    label: '流程参数',
    value: formatParameters(detail.value.parameters),
    span: 2,
    type: 'textarea',
  },
  {
    key: 'createTime',
    label: '创建时间',
    value: formatDateTime(detail.value.createTime),
  },
]);

const [Modal, modalApi] = useVbenModal({
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(isOpen: boolean) {
    invalidateDetail();
    const seq = detailSeq;
    if (!isOpen) {
      return;
    }
    const data = modalApi.getData() as { id?: DataforgeEtlApi.Id };
    if (data?.id === undefined || data.id === '') {
      return;
    }
    modalApi.lock();
    try {
      const result = await getEtlPipeline(data.id);
      if (seq === detailSeq) detail.value = result;
    } finally {
      if (seq === detailSeq) modalApi.unlock();
    }
  },
});

function formatScheduleStatus(pipeline: DataforgeEtlApi.Pipeline) {
  if (pipeline.taskType !== 2) {
    return '-';
  }
  return pipeline.scheduleStatus === 1 ? '已开启' : '已关闭';
}

function formatParameters(parameters?: DataforgeEtlApi.ParamItem[]) {
  if (!parameters?.length) {
    return '';
  }
  return parameters
    .map((item) => {
      const name = item.name || '-';
      const value = item.defaultValue ? ` = ${item.defaultValue}` : '';
      const desc = item.description ? `（${item.description}）` : '';
      return `${name}${value}${desc}`;
    })
    .join('\n');
}
</script>

<template>
  <Modal :title="`流程详情 - ${detail.name ?? ''}`" class="w-[960px]">
    <p class="mx-4 mb-3 text-sm text-muted-foreground">
      最近运行仅为任务摘要；取消确认、执行占位和质量核查请查看对应运行实例。
    </p>
    <DetailFieldForm :fields="fields" class="mx-4" label-width="130px" />
  </Modal>
</template>
