<script setup lang="ts">
import type { GovernanceMasterManageApi } from '#/api/governance/mastermanage';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';
import { CommonStatusEnum } from '@vben/constants';
import { formatDateTime } from '@vben/utils';

import { Alert } from 'antdv-next';

import { getMasterCodeRule } from '#/api/governance/mastermanage';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import { useMasterScope } from '../../mastercommon/use-master-scope';
import { CODE_SEGMENT_TYPE_OPTIONS } from '../data';

defineOptions({ name: 'GovernanceMasterCodeRuleDetail' });

const rule = ref<GovernanceMasterManageApi.CodeRule>();
const failed = ref(false);
const scope = useMasterScope(() => {
  rule.value = undefined;
  void drawerApi.close();
});
const fields = computed<DetailFieldItem[]>(() => [
  { key: 'id', label: '编号', value: rule.value?.id },
  { key: 'name', label: '规则名称', value: rule.value?.name },
  { key: 'code', label: '规则编码', value: rule.value?.code },
  {
    key: 'model',
    label: '所属模型',
    value: rule.value?.modelName || rule.value?.modelId,
  },
  { key: 'fieldName', label: '编码字段', value: rule.value?.fieldName },
  {
    key: 'status',
    label: '状态',
    value: formatStatus(rule.value?.status),
  },
  {
    key: 'example',
    label: '编码示例',
    value: rule.value?.example,
    span: 2,
  },
  {
    key: 'description',
    label: '描述',
    value: rule.value?.description,
    span: 2,
    type: 'textarea',
  },
  {
    key: 'createTime',
    label: '创建时间',
    value: formatDateTime(rule.value?.createTime),
  },
]);
function formatStatus(status?: number) {
  if (status === CommonStatusEnum.ENABLE) return '启用';
  if (status === CommonStatusEnum.DISABLE) return '停用';
  return '-';
}
function segmentFields(
  segment: GovernanceMasterManageApi.Segment,
): DetailFieldItem[] {
  return [
    {
      key: 'segmentType',
      label: '段类型',
      value:
        CODE_SEGMENT_TYPE_OPTIONS.find(
          (item) => item.value === segment.segmentType,
        )?.label ?? segment.segmentType,
    },
    { key: 'segmentValue', label: '段值', value: segment.segmentValue },
    { key: 'sepChar', label: '分隔符', value: segment.sepChar },
  ];
}
const [Drawer, drawerApi] = useVbenDrawer<{
  id?: GovernanceMasterManageApi.Id;
}>({
  class: 'w-[960px] max-w-full',
  placement: 'right',
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(open) {
    scope.invalidate();
    rule.value = undefined;
    failed.value = false;
    drawerApi.setState({ loading: false });
    if (!open) return;
    const data = drawerApi.getData();
    if (data?.id === undefined || data.id === null) return;
    const current = scope.begin('detail');
    drawerApi.setState({ loading: true });
    try {
      const result = await getMasterCodeRule(data.id);
      if (current()) rule.value = result;
    } catch {
      if (current()) failed.value = true;
    } finally {
      if (current()) drawerApi.setState({ loading: false });
    }
  },
});
</script>

<template>
  <Drawer :title="`编码规则详情 - ${rule?.name ?? ''}`">
    <Alert
      v-if="failed"
      class="mx-4 mb-4"
      type="error"
      show-icon
      message="编码规则详情加载失败，请重新打开。"
    />
    <div v-else class="mx-4 flex flex-col gap-4">
      <DetailFieldForm :fields="fields" />
      <template v-if="rule">
        <strong>编码段配置</strong>
        <div
          v-for="(segment, index) in rule.segments"
          :key="index"
          class="rounded-md border p-4"
        >
          <div class="mb-3 font-medium">第 {{ index + 1 }} 段</div>
          <DetailFieldForm :fields="segmentFields(segment)" />
        </div>
        <p v-if="!rule.segments.length" class="text-sm text-muted-foreground">
          暂无编码段配置
        </p>
      </template>
    </div>
  </Drawer>
</template>
