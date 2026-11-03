<script lang="ts" setup>
import type { DataforgeFillApi } from '#/api/dataforge/fill';

import { computed, onMounted, ref } from 'vue';

import { formatDateTime } from '@vben/utils';

import { Descriptions, DescriptionsItem, Spin, Table, Tag } from 'antdv-next';

import { getSubmissionPreview } from '#/api/dataforge/fill';

import { APPROVAL_STATUS_TEXT } from '../shared';

/**
 * 提交快照只读预览（审批人视图）
 *
 * 挂载方式：BPM 流程定义配置 formType=业务表单、
 * formCustomViewPath=dataforge/fill/approval-view/index；
 * BPM 审批详情页动态加载本组件并传入 businessKey（= 提交快照编号）
 */
defineOptions({ name: 'DataforgeFillApprovalView' });

const props = defineProps<{ id: number | string }>();

const loading = ref(true);
const preview = ref<DataforgeFillApi.SubmissionPreview>();

interface PreviewField {
  fieldCode: string;
  name: string;
  required?: boolean;
}

const fields = computed<PreviewField[]>(() => {
  if (!preview.value?.fieldSchema) return [];
  try {
    const parsed: any[] = JSON.parse(preview.value.fieldSchema) ?? [];
    return parsed
      .filter((field) => !field.hidden)
      .map((field) => ({
        fieldCode: field.fieldCode,
        name: field.name ?? field.fieldCode,
        required: field.required,
      }));
  } catch {
    return [];
  }
});

const columns = computed(() => [
  { key: '__no', title: '#', width: 60 },
  ...fields.value.map((field) => ({
    dataIndex: ['data', field.fieldCode],
    key: field.fieldCode,
    title: field.required ? `${field.name} *` : field.name,
  })),
]);

const dataSource = computed(() =>
  [...(preview.value?.rows ?? [])]
    .toSorted((a, b) => Number(a.rowOrder) - Number(b.rowOrder))
    .map((row, index) => ({ ...row, __no: index + 1 })),
);

onMounted(async () => {
  try {
    preview.value = await getSubmissionPreview(Number(props.id));
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <Spin :spinning="loading">
    <div class="flex flex-col gap-3 p-1">
      <Descriptions v-if="preview" :column="3" bordered size="small">
        <DescriptionsItem label="采集任务">{{
          preview.taskName
        }}</DescriptionsItem>
        <DescriptionsItem label="报送组织">{{
          preview.orgName
        }}</DescriptionsItem>
        <DescriptionsItem label="数据所属期">{{
          preview.periodKey
        }}</DescriptionsItem>
        <DescriptionsItem label="提交次数"
          >第 {{ preview.attemptNo }} 次</DescriptionsItem
        >
        <DescriptionsItem label="提交人编号">{{
          preview.submitterUserId
        }}</DescriptionsItem>
        <DescriptionsItem label="提交时间">
          {{ formatDateTime(preview.submitTime) || '-' }}
        </DescriptionsItem>
        <DescriptionsItem label="数据行数">
          {{ preview.rowCount }}
          <Tag v-if="preview.submissionType === 2" class="ml-1" color="warning"
            >零上报</Tag
          >
        </DescriptionsItem>
        <DescriptionsItem label="审批状态">
          <Tag :color="APPROVAL_STATUS_TEXT[preview.approvalStatus]?.color">
            {{ APPROVAL_STATUS_TEXT[preview.approvalStatus]?.text }}
          </Tag>
        </DescriptionsItem>
        <DescriptionsItem label="快照哈希">
          <span class="font-mono text-xs"
            >{{ preview.snapshotHash?.slice(0, 16) }}…</span
          >
        </DescriptionsItem>
        <DescriptionsItem
          v-if="preview.emptyReason"
          :span="3"
          label="零上报说明"
        >
          {{ preview.emptyReason }}
        </DescriptionsItem>
      </Descriptions>

      <div
        v-if="preview?.submissionType === 2"
        class="rounded-md border border-amber-300 bg-amber-50 p-3 text-sm dark:bg-amber-950"
      >
        本次为零上报：该组织申报本期无数据。审批通过即视为按期完成报送
        {{ preview.emptyReason ? `（说明：${preview.emptyReason}）` : '' }}。
      </div>

      <Table
        :columns="columns"
        :data-source="dataSource"
        :pagination="dataSource.length > 100 ? { pageSize: 100 } : false"
        :scroll="{ x: 'max-content', y: 480 }"
        bordered
        row-key="rowId"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === '__no'">{{ record.__no }}</template>
        </template>
      </Table>

      <p class="text-muted-foreground text-xs">
        以上为不可变提交快照内容，与审批结论一一对应；审批人不能修改表格内容。
      </p>
    </div>
  </Spin>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
