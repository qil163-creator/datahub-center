<script lang="ts" setup>
import type { GovernanceMetaCollectTaskApi } from '#/api/governance/metacollecttask';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { getMetaCollectTask } from '#/api/governance/metacollecttask';
import {
  auditDetailFields,
  formatJsonText,
} from '#/views/governance/common/detail-helpers';
import { getUserNickname } from '#/views/governance/common/user';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import { optionLabel, SCOPE_TYPE_OPTIONS } from '../data';

defineOptions({ name: 'GovernanceMetaCollectTaskDetail' });

const detail = ref<GovernanceMetaCollectTaskApi.CollectTask>();

const fields = computed<DetailFieldItem[]>(() => {
  const row = detail.value;
  return [
    { key: 'id', label: '编号', value: row?.id },
    { key: 'name', label: '任务名称', value: row?.name },
    { key: 'datasourceId', label: '数据源编号', value: row?.datasourceId },
    {
      key: 'sourceSystemName',
      label: '来源系统',
      value: row?.sourceSystemName,
    },
    { key: 'dbType', label: '数据库类型', value: row?.dbType },
    {
      key: 'scopeType',
      label: '采集范围',
      value: optionLabel(SCOPE_TYPE_OPTIONS, row?.scopeType),
    },
    {
      key: 'scopeConfig',
      label: '范围配置',
      value: formatJsonText(row?.scopeConfig),
      span: 2,
      type: 'textarea',
      rows: 4,
    },
    {
      key: 'blacklist',
      label: '黑名单',
      value: formatJsonText(row?.blacklist),
      span: 2,
      type: 'textarea',
    },
    { key: 'cron', label: '调度周期', value: row?.cron },
    { key: 'scheduler', label: '调度器', value: row?.scheduler },
    {
      key: 'enabled',
      label: '任务状态',
      value: row?.enabled === 1 ? '上线' : '下线',
    },
    {
      key: 'scheduleEnabled',
      label: '采集调度',
      value: row?.scheduleEnabled === 1 ? '启动' : '停止',
    },
    {
      key: 'owner',
      label: '责任人',
      value: getUserNickname(row?.ownerUserId),
    },
    { key: 'ownerPhone', label: '联系电话', value: row?.ownerPhone },
    {
      key: 'description',
      label: '描述',
      value: row?.description,
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
      detail.value = undefined;
      return;
    }
    const data = modalApi.getData() as { id?: number | string };
    if (data?.id === undefined || data.id === null || data.id === '') {
      return;
    }
    modalApi.lock();
    try {
      detail.value = await getMetaCollectTask(data.id as number);
    } finally {
      modalApi.unlock();
    }
  },
});
</script>

<template>
  <Modal
    :title="`采集任务详情${detail?.name ? ` - ${detail.name}` : ''}`"
    class="w-[960px]"
  >
    <DetailFieldForm :fields="fields" class="mx-4" label-width="130px" />
  </Modal>
</template>
