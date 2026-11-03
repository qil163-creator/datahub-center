<script lang="ts" setup>
import type { DataforgeAccessApi } from '#/api/dataforge/access';

import { Table, Tag } from 'antdv-next';

defineProps<{ plan: DataforgeAccessApi.TypeMappingPlan }>();
const columns = [
  { title: '目标列', dataIndex: 'column', key: 'column' },
  { title: '来源类型', dataIndex: 'sourceNativeType', key: 'sourceNativeType' },
  { title: '目标类型', dataIndex: 'targetNativeType', key: 'targetNativeType' },
  { title: '转换结果', key: 'fidelity' },
  { title: '本次检查', key: 'accepted' },
  { title: '诊断', key: 'issues' },
];
const labels = {
  EXACT: '保持语义',
  LOSSY: '存在变化',
  UNSUPPORTED: '尚未支持',
};
</script>

<template>
  <div data-testid="type-mapping-diagnostic">
    <p class="mb-2">
      {{ plan.sourceSchema }}.{{ plan.sourceTable }} →
      {{ plan.targetSchema }}.{{ plan.targetTable }}
    </p>
    <p class="mb-3">
      类型映射检查：{{
        plan.accepted ? '已通过' : '未通过'
      }}。执行是否成功以表级同步结果为准。
    </p>
    <p v-if="plan.truncated" role="alert" class="mb-3 text-amber-700">
      本表共
      {{ plan.totalColumns }}
      列，当前为部分诊断摘要；未展示的列和原因不能据此推断。
    </p>
    <Table
      :columns="columns"
      :data-source="plan.columns"
      :pagination="false"
      row-key="column"
      size="small"
      :scroll="{ x: 900 }"
    >
      <template #bodyCell="{ column, record }">
        <Tag
          v-if="column.key === 'fidelity'"
          :color="
            record.fidelity === 'EXACT'
              ? 'success'
              : record.fidelity === 'LOSSY'
                ? 'warning'
                : 'error'
          "
        >
          {{ labels[record.fidelity as DataforgeAccessApi.TypeFidelity] }}
        </Tag>
        <span v-else-if="column.key === 'accepted'">{{
          record.accepted ? '已通过' : '未通过'
        }}</span>
        <div v-else-if="column.key === 'issues'">
          <p
            v-for="(issue, index) in record.issues"
            :key="`${index}:${issue.code}`"
            class="text-xs"
          >
            {{ issue.detail }}（{{ issue.code }}）
          </p>
        </div>
      </template>
    </Table>
  </div>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
