<script lang="ts" setup>
import type { SafetySecurityApi } from '#/api/governance/security';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { formatDateTime } from '@vben/utils';

import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import { ACCESS_TYPE_OPTIONS, MASKED_FLAG_OPTIONS, optionLabel } from '../data';

defineOptions({ name: 'SafetyAccessLogDetail' });

const detail = ref<SafetySecurityApi.AccessLog>();

const fields = computed<DetailFieldItem[]>(() => {
  const row = detail.value;
  return [
    { key: 'id', label: '编号', value: row?.id },
    { key: 'userName', label: '访问用户', value: row?.userName },
    { key: 'userId', label: '用户编号', value: row?.userId },
    {
      key: 'accessType',
      label: '访问类型',
      value: optionLabel(ACCESS_TYPE_OPTIONS, row?.accessType),
    },
    { key: 'resourceName', label: '资源名称', value: row?.resourceName },
    { key: 'appId', label: '应用编号', value: row?.appId },
    { key: 'databaseId', label: '数据源编号', value: row?.databaseId },
    { key: 'tableName', label: '涉及表', value: row?.tableName },
    {
      key: 'sensitiveColumns',
      label: '敏感字段',
      value: row?.sensitiveColumns,
      span: 2,
      type: 'textarea',
    },
    { key: 'columnCount', label: '字段数', value: row?.columnCount },
    {
      key: 'maskedFlag',
      label: '脱敏结果',
      value: optionLabel(MASKED_FLAG_OPTIONS, row?.maskedFlag),
    },
    { key: 'whitelistId', label: '白名单编号', value: row?.whitelistId },
    { key: 'callerIp', label: '来源 IP', value: row?.callerIp },
    {
      key: 'accessTime',
      label: '访问时间',
      value: formatDateTime(row?.accessTime),
    },
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
    detail.value = modalApi.getData() as SafetySecurityApi.AccessLog;
  },
});
</script>

<template>
  <Modal
    :title="`访问审计详情${detail?.resourceName ? ` - ${detail.resourceName}` : ''}`"
    class="w-[960px]"
  >
    <DetailFieldForm :fields="fields" class="mx-4" label-width="130px" />
  </Modal>
</template>
