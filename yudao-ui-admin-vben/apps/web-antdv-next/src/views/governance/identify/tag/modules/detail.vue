<script lang="ts" setup>
import type { SafetyIdentifyApi } from '#/api/governance/identify';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { formatDateTime } from '@vben/utils';

import { auditDetailFields } from '#/views/governance/common/detail-helpers';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import { optionLabel, SOURCE_OPTIONS, TAG_STATUS_OPTIONS } from '../data';

defineOptions({ name: 'SafetyFieldTagDetail' });

const detail = ref<SafetyIdentifyApi.FieldTag>();

const fields = computed<DetailFieldItem[]>(() => {
  const row = detail.value;
  return [
    { key: 'id', label: '编号', value: row?.id },
    { key: 'databaseId', label: '数据源编号', value: row?.databaseId },
    { key: 'tableName', label: '表名', value: row?.tableName },
    { key: 'columnName', label: '字段名', value: row?.columnName },
    { key: 'categoryName', label: '数据分类', value: row?.categoryName },
    { key: 'levelName', label: '数据分级', value: row?.levelName },
    {
      key: 'source',
      label: '来源',
      value: optionLabel(SOURCE_OPTIONS, row?.source),
    },
    {
      key: 'tagStatus',
      label: '打标状态',
      value: optionLabel(TAG_STATUS_OPTIONS, row?.tagStatus),
    },
    {
      key: 'identifyRuleId',
      label: '识别规则编号',
      value: row?.identifyRuleId,
    },
    { key: 'scanRunId', label: '扫描记录编号', value: row?.scanRunId },
    {
      key: 'hitDesc',
      label: '命中说明',
      value: row?.hitDesc,
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
    {
      key: 'confirmTime',
      label: '确认时间',
      value: formatDateTime(row?.confirmTime),
    },
    ...auditDetailFields(row),
  ];
});

const [Modal, modalApi] = useVbenModal({
  showConfirmButton: false,
  cancelText: '关闭',
  onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      detail.value = undefined;
      return;
    }
    detail.value = modalApi.getData() as SafetyIdentifyApi.FieldTag;
  },
});
</script>

<template>
  <Modal
    :title="`敏感字段打标详情${detail?.columnName ? ` - ${detail.columnName}` : ''}`"
    class="w-[960px]"
  >
    <DetailFieldForm :fields="fields" class="mx-4" label-width="130px" />
  </Modal>
</template>
