<script lang="ts" setup>
import type { GovernanceMasterDistributeApi } from '#/api/governance/masterdistribute';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';

import { getMasterDistribute } from '#/api/governance/masterdistribute';
import {
  auditDetailFields,
  formatJsonText,
} from '#/views/governance/common/detail-helpers';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import { useMasterScope } from '../../mastercommon/use-master-scope';
import {
  DISTRIBUTE_TYPE_OPTIONS,
  nextRunTimeLabel,
  optionLabel,
  releaseTimeLabel,
  releaseUserLabel,
  RUN_STATUS_OPTIONS,
  runBlockedReason,
  STATUS_OPTIONS,
  TASK_TYPE_OPTIONS,
} from '../data';

defineOptions({ name: 'GovernanceMasterDistributeDetail' });

const detail = ref<GovernanceMasterDistributeApi.Distribute>();

const fields = computed<DetailFieldItem[]>(() => {
  const row = detail.value;
  const items: DetailFieldItem[] = [
    { key: 'id', label: '编号', value: row?.id },
    { key: 'name', label: '派发名称', value: row?.name },
    {
      key: 'masterModelName',
      label: '主数据模型',
      value: row?.masterModelName,
    },
    {
      key: 'masterModelTableName',
      label: '模型物理表',
      value: row?.masterModelTableName,
    },
    {
      key: 'distributeType',
      label: '派发类型',
      value: optionLabel(DISTRIBUTE_TYPE_OPTIONS, row?.distributeType),
    },
    {
      key: 'taskType',
      label: '调度类型',
      value: optionLabel(TASK_TYPE_OPTIONS, row?.taskType),
    },
    { key: 'cron', label: 'Cron', value: row?.cron },
    {
      key: 'nextRunTime',
      label: '下次执行时间',
      value: row ? nextRunTimeLabel(row) : undefined,
    },
    {
      key: 'status',
      label: '发布状态',
      value: optionLabel(STATUS_OPTIONS, row?.status),
    },
    {
      key: 'blockingRunStatus',
      label: '执行状态',
      value:
        row?.blockingRunStatus === 0
          ? '空闲'
          : optionLabel(RUN_STATUS_OPTIONS, row?.blockingRunStatus),
    },
    ...(row && runBlockedReason(row)
      ? [
          {
            key: 'runBlockedReason',
            label: '执行说明',
            value: runBlockedReason(row),
            span: 2 as const,
          },
        ]
      : []),
    { key: 'orgName', label: '所属机构', value: row?.orgName },
    {
      key: 'releaseUserName',
      label: '最近发布人',
      value: row ? releaseUserLabel(row) : undefined,
    },
    {
      key: 'releaseTime',
      label: '最近发布时间',
      value: row ? releaseTimeLabel(row) : undefined,
    },
    {
      key: 'description',
      label: '描述',
      value: row?.description || '未填写',
      span: 2,
      type: 'textarea',
    },
    {
      key: 'config',
      label: '派发配置',
      value: formatJsonText(row?.config),
      span: 2,
      type: 'textarea',
      rows: 6,
    },
    ...auditDetailFields(row),
  ];
  return items;
});

const scope = useMasterScope(() => {
  detail.value = undefined;
  drawerApi.setState({ isOpen: false, loading: false });
});

const [Drawer, drawerApi] = useVbenDrawer({
  class: 'w-[960px] max-w-full',
  placement: 'right',
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(isOpen: boolean) {
    scope.invalidate();
    detail.value = undefined;
    drawerApi.setState({ loading: false });
    if (!isOpen) return;
    const data = drawerApi.getData() as { id?: number | string };
    if (data?.id === undefined || data.id === null || data.id === '') {
      return;
    }
    const current = scope.begin('detail');
    drawerApi.setState({ loading: true });
    try {
      const result = await getMasterDistribute(data.id);
      if (current()) detail.value = result;
    } finally {
      if (current()) drawerApi.setState({ loading: false });
    }
  },
});
</script>

<template>
  <Drawer :title="`主数据派发详情${detail?.name ? ` - ${detail.name}` : ''}`">
    <DetailFieldForm :fields="fields" class="mx-4" label-width="130px" />
  </Drawer>
</template>
