<script lang="ts" setup>
import type { DataforgeDataPushConfigApi } from '#/api/dataforge/datapush/config';
import type { DataforgeDataPushTaskApi } from '#/api/dataforge/datapush/task';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, onBeforeUnmount, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { formatDateTime } from '@vben/utils';

import { getDataPushConfig } from '#/api/dataforge/datapush/config';
import { getDataPushTask } from '#/api/dataforge/datapush/task';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import { getConfigDetailFields } from '../../config/detail-fields';
import {
  CHANNEL_TYPE_OPTIONS,
  optionLabel,
  PUSH_MODE,
  PUSH_MODE_OPTIONS,
  PUSH_TYPE,
  PUSH_TYPE_OPTIONS,
  RUN_STATUS_OPTIONS,
  STATUS_OPTIONS,
  TASK_TYPE,
  TASK_TYPE_OPTIONS,
} from '../data';
import { runStateLabel } from '../run-state';

defineOptions({ name: 'DataforgeDataPushTaskDetail' });

const detail = ref<DataforgeDataPushTaskApi.Task>();
const receiver = ref<DataforgeDataPushConfigApi.Config>();
let detailEpoch = 0;

const receiverFields = computed(() =>
  receiver.value
    ? getConfigDetailFields(receiver.value).filter(
        (field) =>
          ![
            'channelType',
            'createTime',
            'description',
            'id',
            'locked',
            'name',
            'orgName',
          ].includes(field.key),
      )
    : [],
);

onBeforeUnmount(() => ++detailEpoch);

const fields = computed<DetailFieldItem[]>(() => {
  const task = detail.value ?? {};
  const items: DetailFieldItem[] = [
    { key: 'id', label: '编号', value: task.id },
    { key: 'name', label: '任务名称', value: task.name },
    { key: 'pushConfigName', label: '接收端', value: task.pushConfigName },
    {
      key: 'channelType',
      label: '通道类型',
      value: optionLabel(CHANNEL_TYPE_OPTIONS, task.channelType),
    },
    {
      key: 'sourceDatabaseName',
      label: '源数据源',
      value: task.sourceDatabaseName,
    },
    { key: 'orgName', label: '所属单位', value: task.orgName },
    {
      key: 'pushType',
      label: '推送类型',
      value: optionLabel(PUSH_TYPE_OPTIONS, task.pushType),
    },
    { key: 'objectName', label: '对象名', value: task.objectName },
  ];
  if (task.pushType === PUSH_TYPE.TABLE) {
    items.push({
      key: 'tableName',
      label: '源表',
      value: task.tableName,
      span: 2,
    });
  }
  if (task.pushType === PUSH_TYPE.SQL) {
    items.push({
      key: 'sqlText',
      label: '自定义 SQL',
      value: task.sqlText,
      span: 2,
      type: 'textarea',
      rows: 5,
    });
  }
  items.push(
    {
      key: 'pushMode',
      label: '推送方式',
      value: optionLabel(PUSH_MODE_OPTIONS, task.pushMode),
    },
    { key: 'batchSize', label: '每批行数', value: task.batchSize },
  );
  if (task.pushMode === PUSH_MODE.INCREMENT) {
    items.push(
      {
        key: 'incrementColumn',
        label: '增量字段',
        value: task.incrementColumn,
      },
      { key: 'initStartVal', label: '首次起始水位', value: task.initStartVal },
      {
        key: 'watermarkEndVal',
        label: '当前水位上界',
        value: task.watermarkEndVal,
      },
    );
  }
  items.push({
    key: 'taskType',
    label: '调度类型',
    value: optionLabel(TASK_TYPE_OPTIONS, task.taskType),
  });
  if (task.taskType === TASK_TYPE.CRON) {
    items.push({ key: 'cron', label: 'Cron 表达式', value: task.cron });
  }
  items.push(
    {
      key: 'status',
      label: '发布状态',
      value: optionLabel(STATUS_OPTIONS, task.status),
    },
    {
      key: 'running',
      label: '运行状态',
      value: runStateLabel(task),
    },
    {
      key: 'lastRunStatus',
      label: '最近执行',
      value: optionLabel(RUN_STATUS_OPTIONS, task.lastRunStatus),
    },
    { key: 'lastDataCount', label: '最近行数', value: task.lastDataCount },
    {
      key: 'lastStartTime',
      label: '最近开始',
      value: formatDateTime(task.lastStartTime),
    },
    {
      key: 'lastEndTime',
      label: '最近结束',
      value: formatDateTime(task.lastEndTime),
    },
    {
      key: 'nextRunTime',
      label: '下次执行时间',
      value: formatDateTime(task.nextRunTime),
    },
    { key: 'releaseUserName', label: '发布人', value: task.releaseUserName },
    {
      key: 'releaseTime',
      label: '发布时间',
      value: formatDateTime(task.releaseTime),
    },
    {
      key: 'description',
      label: '说明',
      value: task.description,
      span: 2,
      type: 'textarea',
      rows: 2,
    },
    {
      key: 'lastErrorInfo',
      label: '最近错误',
      value: task.lastErrorInfo,
      span: 2,
      type: 'textarea',
      rows: 3,
    },
    {
      key: 'createTime',
      label: '创建时间',
      value: formatDateTime(task.createTime),
    },
  );
  return items;
});

const [Modal, modalApi] = useVbenModal({
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(isOpen: boolean) {
    const epoch = ++detailEpoch;
    detail.value = undefined;
    receiver.value = undefined;
    if (!isOpen) {
      return;
    }
    const data = modalApi.getData() as undefined | { id?: number | string };
    if (!data?.id) {
      return;
    }
    modalApi.lock();
    try {
      const task = await getDataPushTask(data.id);
      if (epoch !== detailEpoch) return;
      detail.value = task;
      if (task.pushConfigId !== undefined) {
        const config = await getDataPushConfig(task.pushConfigId);
        if (epoch !== detailEpoch) return;
        receiver.value = config;
      }
    } finally {
      if (epoch === detailEpoch) modalApi.unlock();
    }
  },
});
</script>

<template>
  <Modal
    :title="`推送任务详情${detail?.name ? ` - ${detail.name}` : ''}`"
    class="w-[960px]"
  >
    <DetailFieldForm :fields="fields" class="mx-4" label-width="130px" />
    <template v-if="receiver">
      <h3 class="mx-4 mb-3 mt-6 font-medium">接收端配置</h3>
      <DetailFieldForm
        :fields="receiverFields"
        class="mx-4"
        label-width="130px"
      />
    </template>
  </Modal>
</template>
