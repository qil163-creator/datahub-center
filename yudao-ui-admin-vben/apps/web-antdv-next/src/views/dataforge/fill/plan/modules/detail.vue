<script lang="ts" setup>
import type { DataforgeFillApi } from '#/api/dataforge/fill';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { formatDateTime } from '@vben/utils';

import { getPlan } from '#/api/dataforge/fill';
import { DATAFORGE_DETAIL_MODAL_CLASS } from '#/views/dataforge/common/form-layout';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import { PERIOD_TYPE_TEXT, PLAN_STATUS_TEXT } from '../../shared';

defineOptions({ name: 'DataforgeFillPlanDetail' });

const detail = ref<DataforgeFillApi.Plan>();

const fields = computed<DetailFieldItem[]>(() => {
  const plan: Partial<DataforgeFillApi.Plan> = detail.value ?? {};
  const orgSummary = (plan.orgs ?? [])
    .map((org) => {
      const names = (org.assignees ?? [])
        .map((item) => item.userName)
        .filter(Boolean)
        .join('、');
      return names
        ? `${org.orgName ?? org.reportingOrgId}（${names}）`
        : org.orgName;
    })
    .filter(Boolean)
    .join('；');
  return [
    { key: 'id', label: '编号', value: plan.id },
    { key: 'name', label: '计划名称', value: plan.name },
    {
      key: 'categoryName',
      label: '计划分类',
      value: plan.categoryName || '未分类',
    },
    {
      key: 'status',
      label: '状态',
      value: PLAN_STATUS_TEXT[plan.status ?? -1]?.text ?? plan.status,
    },
    {
      key: 'periodType',
      label: '周期',
      value: PERIOD_TYPE_TEXT[plan.periodType ?? -1] ?? plan.periodType,
    },
    {
      key: 'tableName',
      label: '模板/版本',
      value: plan.tableName
        ? `${plan.tableName} · V${plan.tableVersionNo ?? '-'}`
        : '-',
    },
    {
      key: 'processDefinitionKey',
      label: 'BPM Key',
      value: plan.processDefinitionKey,
    },
    {
      key: 'description',
      label: '业务说明',
      value: plan.description,
      span: 2,
      type: 'textarea',
      rows: 2,
    },
    { key: 'lastPeriodKey', label: '最近期次', value: plan.lastPeriodKey },
    {
      key: 'nextGenerateTime',
      label: '下次生成',
      value: formatDateTime(plan.nextGenerateTime),
    },
    { key: 'orgs', label: '目标组织', value: orgSummary || '-', span: 2 },
    { key: 'creator', label: '发起者', value: plan.creator },
    {
      key: 'createTime',
      label: '创建时间',
      value: formatDateTime(plan.createTime),
    },
  ];
});

const [Modal, modalApi] = useVbenModal<{ id: number | string }>({
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      detail.value = undefined;
      return;
    }
    const data = modalApi.getData();
    if (!data?.id) {
      return;
    }
    modalApi.lock();
    try {
      detail.value = await getPlan(data.id);
    } finally {
      modalApi.unlock();
    }
  },
});
</script>

<template>
  <Modal
    :title="`采集计划详情${detail?.name ? ` - ${detail.name}` : ''}`"
    :class="DATAFORGE_DETAIL_MODAL_CLASS"
  >
    <DetailFieldForm :fields="fields" class="mx-4" label-width="130px" />
  </Modal>
</template>
