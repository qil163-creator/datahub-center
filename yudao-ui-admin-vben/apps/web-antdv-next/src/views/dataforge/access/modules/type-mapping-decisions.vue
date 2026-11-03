<script lang="ts" setup>
import type { DataforgeAccessApi } from '#/api/dataforge/access';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Alert, Button, Checkbox, Select, Table, Tag } from 'antdv-next';

import { previewTypeMap } from '#/api/dataforge/access';

import {
  isTypeMappingPlan,
  matchesMappingDecision,
  sameMappingTable,
} from './type-mapping';

const props = defineProps<{
  modelValue: DataforgeAccessApi.TypeMappingAcknowledgement[];
  request: DataforgeAccessApi.TypeMappingPreviewReq;
  tables: { label: string; value: string }[];
}>();
const emit = defineEmits<{
  'update:modelValue': [DataforgeAccessApi.TypeMappingAcknowledgement[]];
}>();
const table = ref<string>();
const plan = ref<DataforgeAccessApi.TypeMappingPlan>();
const loading = ref(false);
const failed = ref(false);
let requestId = 0;
let active = true;
const context = computed(() => JSON.stringify(props.request));
const columns = [
  { title: '目标列', dataIndex: 'column', key: 'column' },
  { title: '来源类型', dataIndex: 'sourceNativeType', key: 'sourceNativeType' },
  { title: '目标类型', dataIndex: 'targetNativeType', key: 'targetNativeType' },
  { title: '转换结果', key: 'fidelity', width: 120 },
  { title: '需要了解的变化', key: 'issues' },
  { title: '明确确认', key: 'accept', width: 130 },
];
const labels = {
  EXACT: '保持语义',
  LOSSY: '存在变化',
  UNSUPPORTED: '尚未支持',
};

function invalidate() {
  ++requestId;
  plan.value = undefined;
  loading.value = false;
  failed.value = false;
}
watch(context, invalidate, { flush: 'sync' });
watch(table, invalidate, { flush: 'sync' });
watch(
  () => props.tables,
  () => {
    if (table.value && !props.tables.some((item) => item.value === table.value))
      table.value = undefined;
  },
  { deep: true },
);

function accepted(column: DataforgeAccessApi.TypeMappingColumn) {
  return (
    !!plan.value &&
    props.modelValue.some((decision) =>
      matchesMappingDecision(decision, plan.value!, column),
    )
  );
}

function select(
  column: DataforgeAccessApi.TypeMappingColumn,
  checked: boolean,
) {
  if (
    !plan.value ||
    plan.value.truncated ||
    loading.value ||
    column.fidelity !== 'LOSSY'
  )
    return;
  const current = plan.value;
  const decisions = props.modelValue.filter(
    (decision) =>
      !(
        sameMappingTable(decision, current) && decision.column === column.column
      ),
  );
  if (checked)
    decisions.push({
      targetProduct: current.targetProduct,
      targetSchema: current.targetSchema,
      targetTable: current.targetTable,
      column: column.column,
      mappingFingerprint: column.mappingFingerprint,
      lossCodes: [...column.lossCodes],
    });
  emit('update:modelValue', decisions);
}

async function preview() {
  if (
    !table.value ||
    !props.request.sourceDatabaseId ||
    !props.request.targetDatabaseId
  )
    return;
  const started = ++requestId;
  const scope = context.value;
  const selected = table.value;
  plan.value = undefined;
  failed.value = false;
  loading.value = true;
  try {
    const result = await previewTypeMap({
      ...props.request,
      previewTableName: selected,
    });
    if (
      !active ||
      started !== requestId ||
      scope !== context.value ||
      selected !== table.value
    )
      return;
    if (!isTypeMappingPlan(result) || result.sourceTable !== selected) {
      failed.value = true;
      return;
    }
    plan.value = result;
    if (result.truncated) return;
    // 再次预览只保留仍然完全匹配的已有选择，绝不自动接受新的 LOSSY。
    emit(
      'update:modelValue',
      props.modelValue.filter(
        (decision) =>
          !sameMappingTable(decision, result) ||
          result.columns.some((column) =>
            matchesMappingDecision(decision, result, column),
          ),
      ),
    );
  } catch {
    if (active && started === requestId) failed.value = true;
  } finally {
    if (active && started === requestId) loading.value = false;
  }
}
onBeforeUnmount(() => {
  active = false;
  invalidate();
});
</script>

<template>
  <div class="mt-4" data-testid="type-mapping-decisions">
    <h3 class="mb-2 text-base font-medium">跨数据库类型映射</h3>
    <p class="mb-3 text-sm text-muted-foreground">
      逐表查看转换结果，只确认你接受的列变化。尚未支持的列需要调整迁移方案；执行前会再次校验。
    </p>
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <Select
        v-model:value="table"
        :options="tables"
        placeholder="选择要预览的来源表"
        class="min-w-64"
        data-testid="type-mapping-table"
      />
      <Button
        :loading="loading"
        :disabled="!table"
        data-testid="type-mapping-preview"
        @click="preview"
      >
        <template #icon>
          <IconifyIcon icon="lucide:git-compare-arrows" />
        </template>
        查看类型映射
      </Button>
      <span class="text-sm">已明确确认 {{ modelValue.length }} 列</span>
    </div>
    <Alert
      v-if="failed"
      type="warning"
      show-icon
      message="未取得当前映射结果，不能确认新的列变化，请重新预览。"
    />
    <template v-if="plan">
      <p class="mb-2 text-sm">
        {{ plan.sourceSchema }}.{{ plan.sourceTable }} →
        {{ plan.targetSchema }}.{{ plan.targetTable }}
      </p>
      <Alert
        v-if="plan.truncated"
        class="mb-3"
        type="warning"
        show-icon
        message="预览超过展示上限，当前仅显示部分诊断，不能确认列变化。请缩小字段范围后重新预览。"
      />
      <Table
        :columns="columns"
        :data-source="plan.columns"
        :pagination="false"
        row-key="column"
        size="small"
        :scroll="{ x: 900 }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'fidelity'">
            <Tag
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
          </template>
          <template v-else-if="column.key === 'issues'">
            <div
              v-for="(issue, index) in record.issues"
              :key="`${index}:${issue.code}`"
              class="text-xs"
            >
              {{ issue.detail }}（{{ issue.code }}）
            </div>
          </template>
          <template v-else-if="column.key === 'accept'">
            <Checkbox
              v-if="record.fidelity === 'LOSSY'"
              :checked="accepted(record)"
              :disabled="plan.truncated"
              :data-testid="`type-mapping-accept-${record.column}`"
              @update:checked="select(record, $event)"
            >
              接受这些变化
            </Checkbox>
            <span v-else>{{
              record.fidelity === 'EXACT' ? '无需确认' : '不能确认放行'
            }}</span>
          </template>
        </template>
      </Table>
    </template>
  </div>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
