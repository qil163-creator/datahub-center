<script lang="ts" setup>
import type { SafetyIdentifyApi } from '#/api/governance/identify';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { getIdentifyRule } from '#/api/governance/identify';
import {
  auditDetailFields,
  formatEnableStatus,
} from '#/views/governance/common/detail-helpers';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import { MATCH_TYPE_OPTIONS, optionLabel } from '../data';

defineOptions({ name: 'SafetyIdentifyRuleDetail' });

const detail = ref<SafetyIdentifyApi.IdentifyRule>();

const fields = computed<DetailFieldItem[]>(() => {
  const row = detail.value;
  return [
    { key: 'id', label: '编号', value: row?.id },
    { key: 'ruleName', label: '规则名称', value: row?.ruleName },
    {
      key: 'matchType',
      label: '匹配方式',
      value: optionLabel(MATCH_TYPE_OPTIONS, row?.matchType),
    },
    {
      key: 'pattern',
      label: '匹配模式',
      value: row?.pattern,
      span: 2,
      type: 'textarea',
    },
    { key: 'categoryName', label: '数据分类', value: row?.categoryName },
    { key: 'levelName', label: '数据分级', value: row?.levelName },
    {
      key: 'minHitRatio',
      label: '命中率阈值',
      value:
        row?.matchType === 'DATA_REGEX' ? `${row.minHitRatio ?? 80}%` : '-',
    },
    {
      key: 'sampleRows',
      label: '采样行数',
      value: row?.matchType === 'DATA_REGEX' ? row.sampleRows : '-',
    },
    {
      key: 'status',
      label: '状态',
      value: formatEnableStatus(row?.status),
    },
    { key: 'sort', label: '显示顺序', value: row?.sort },
    {
      key: 'description',
      label: '规则说明',
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
    if (!data?.id) {
      return;
    }
    modalApi.lock();
    try {
      detail.value = await getIdentifyRule(data.id);
    } finally {
      modalApi.unlock();
    }
  },
});
</script>

<template>
  <Modal
    :title="`识别规则详情${detail?.ruleName ? ` - ${detail.ruleName}` : ''}`"
    class="w-[960px]"
  >
    <DetailFieldForm :fields="fields" class="mx-4" label-width="130px" />
  </Modal>
</template>
