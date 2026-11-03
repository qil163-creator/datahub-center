<script lang="ts" setup>
import type { SafetyRuleApi } from '#/api/governance/rule';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { DICT_TYPE } from '@vben/constants';
import { getDictLabel } from '@vben/hooks';

import { getRule } from '#/api/governance/rule';
import {
  auditDetailFields,
  formatEnableStatus,
} from '#/views/governance/common/detail-helpers';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

defineOptions({ name: 'SafetyRuleDetail' });

const detail = ref<SafetyRuleApi.Rule>();

function formatConfig(config?: Record<string, any>) {
  if (!config || Object.keys(config).length === 0) {
    return '-';
  }
  return JSON.stringify(config, null, 2);
}

function formatReplaceItems(items?: SafetyRuleApi.ReplaceItem[]) {
  if (!items || items.length === 0) {
    return '-';
  }
  return items
    .map((item) => `${item.matchValue ?? ''} → ${item.replacementValue ?? ''}`)
    .join('\n');
}

const fields = computed<DetailFieldItem[]>(() => {
  const row = detail.value;
  return [
    { key: 'id', label: '编号', value: row?.id },
    { key: 'ruleName', label: '规则名称', value: row?.ruleName },
    {
      key: 'methodCode',
      label: '脱敏方式',
      value: getDictLabel(DICT_TYPE.METHOD_CODE, row?.methodCode) || '-',
    },
    {
      key: 'sourceType',
      label: '规则来源',
      value: getDictLabel(DICT_TYPE.SOURCE_TYPE, row?.sourceType) || '-',
    },
    {
      key: 'currentVersionNo',
      label: '当前版本',
      value: row?.currentVersionNo,
    },
    {
      key: 'status',
      label: '状态',
      value: formatEnableStatus(row?.status),
    },
    { key: 'sort', label: '显示顺序', value: row?.sort },
    {
      key: 'config',
      label: '脱敏参数',
      value: formatConfig(row?.config),
      span: 2,
      type: 'textarea',
    },
    {
      key: 'replaceItems',
      label: '匹配内容',
      value: formatReplaceItems(row?.replaceItems),
      span: 2,
      type: 'textarea',
    },
    {
      key: 'description',
      label: '规则描述',
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
    const data = modalApi.getData() as { id?: number };
    if (!data?.id) {
      return;
    }
    modalApi.lock();
    try {
      detail.value = await getRule(data.id);
    } finally {
      modalApi.unlock();
    }
  },
});
</script>

<template>
  <Modal
    :title="`脱敏规则详情${detail?.ruleName ? ` - ${detail.ruleName}` : ''}`"
    class="w-[960px]"
  >
    <DetailFieldForm :fields="fields" class="mx-4" label-width="130px" />
  </Modal>
</template>
