<script lang="ts" setup>
import type { TablePaginationConfig } from 'antdv-next';

import type { SnapshotFilter } from '../snapshot-filter';

import type { MarketCheckApi } from '#/api/asset/market/check';

import { computed, ref, watch } from 'vue';

import {
  Alert,
  Button,
  Checkbox,
  Input,
  Select,
  Table,
  Tooltip,
} from 'antdv-next';

import ListPagination from '#/components/list-pagination/index.vue';
import queryIcon from '#/views/governance/metadata-common/reference-assets/icons/query.png';
import resetIcon from '#/views/governance/metadata-common/reference-assets/icons/reset.png';

import {
  compareSnapshotValues,
  filterSnapshotRows,
  SNAPSHOT_OPERATORS,
  snapshotCellText,
} from '../snapshot-filter';

import '#/components/detail-fill-table.css';

const props = defineProps<{
  preview?: MarketCheckApi.Preview;
}>();
const filters = ref<SnapshotFilter[]>([]);
const appliedFilters = ref<SnapshotFilter[]>([]);
const relation = ref<'AND' | 'OR'>('AND');
const appliedRelation = ref<'AND' | 'OR'>('AND');
const pageNo = ref(1);
const pageSize = ref(10);
const pagination = computed<TablePaginationConfig>(() => ({
  current: pageNo.value,
  pageSize: pageSize.value,
  placement: ['none'],
  onChange: pageChanged,
}));
const columns = computed(() =>
  (props.preview?.columns ?? []).map((column) => ({
    key: column.name,
    dataIndex: ['values', column.name],
    title:
      column.label && column.label !== column.name
        ? `${column.name}（${column.label}）`
        : column.name,
    width: 220,
    ellipsis: true,
    sorter: (
      left: { values: Record<string, unknown> },
      right: { values: Record<string, unknown> },
    ) =>
      compareSnapshotValues(
        snapshotCellText(left.values[column.name]),
        snapshotCellText(right.values[column.name]),
      ),
  })),
);
const options = computed(() =>
  (props.preview?.columns ?? []).map((column) => ({
    value: column.name,
    label: column.name,
  })),
);
const rows = computed(() =>
  filterSnapshotRows(
    props.preview?.rows ?? [],
    appliedFilters.value,
    appliedRelation.value,
  ).map((values, index) => ({ key: String(index), values })),
);
function addFilter() {
  if (filters.value.length >= 10) return;
  filters.value.push({
    enabled: true,
    column:
      options.value[filters.value.length]?.value || options.value[0]?.value,
    operator: 'eq',
  });
}
function query() {
  appliedFilters.value = filters.value.map((filter) => ({ ...filter }));
  appliedRelation.value = relation.value;
  pageNo.value = 1;
}
function reset() {
  filters.value = [];
  appliedFilters.value = [];
  relation.value = 'AND';
  appliedRelation.value = 'AND';
  pageNo.value = 1;
}
function pageChanged(page: number, size: number) {
  pageNo.value = page;
  pageSize.value = size;
}
watch(() => props.preview, reset);
</script>

<template>
  <div class="check-preview">
    <div class="check-preview__actions">
      <Button type="primary" size="small" :disabled="!preview" @click="query">
        <img :src="queryIcon" alt="" />查询
      </Button>
      <Button size="small" :disabled="!preview" @click="reset">
        <img :src="resetIcon" alt="" />重置
      </Button>
    </div>
    <div v-if="!filters.length" class="check-preview__add-row">
      <Button
        type="primary"
        size="small"
        aria-label="添加筛选条件"
        :disabled="!options.length"
        @click="addFilter"
      >
        +
      </Button>
      <span>点击“+”以添加筛选准则</span>
    </div>
    <div v-else class="check-preview__filters">
      <div
        v-for="(filter, index) in filters"
        :key="index"
        class="check-preview__filter"
      >
        <Checkbox
          v-model:checked="filter.enabled"
          :aria-label="`启用第${index + 1}个条件`"
        />
        <Select
          v-model:value="filter.column"
          class="check-preview__field"
          :options="options"
          size="small"
          :aria-label="`第${index + 1}个筛选字段`"
        />
        <Select
          v-model:value="filter.operator"
          class="check-preview__operator"
          :options="SNAPSHOT_OPERATORS.map((item) => ({ ...item }))"
          size="small"
          aria-label="筛选运算符"
        />
        <Input
          v-model:value="filter.value"
          class="check-preview__value"
          :disabled="['isNull', 'notNull'].includes(filter.operator)"
          size="small"
          placeholder="请输入值"
          @press-enter="query"
        />
        <Select
          v-if="index < filters.length - 1"
          v-model:value="relation"
          class="check-preview__logic"
          :options="[
            { label: 'AND', value: 'AND' },
            { label: 'OR', value: 'OR' },
          ]"
          size="small"
          aria-label="筛选逻辑"
        />
        <span v-else class="check-preview__relation-space"></span>
        <Button
          class="check-preview__remove"
          size="small"
          aria-label="删除筛选条件"
          @click="filters.splice(index, 1)"
        >
          -
        </Button>
        <Button
          v-if="index === filters.length - 1"
          type="primary"
          size="small"
          aria-label="添加筛选条件"
          :disabled="filters.length >= 10"
          @click="addFilter"
        >
          +
        </Button>
      </div>
    </div>
    <Alert
      v-if="!preview"
      type="info"
      message="暂无已保存的数据预览"
      class="check-preview__notice"
    />
    <Alert
      v-else-if="preview.truncated"
      type="info"
      message="预览仅包含部分记录"
      class="check-preview__notice"
    />
    <Table
      class="check-preview__table detail-fill-table"
      :columns="columns"
      :data-source="rows"
      :pagination="pagination"
      :scroll="{ x: Math.max(columns.length * 220, 700) }"
      row-key="key"
    >
      <template #bodyCell="{ column, record }">
        <Tooltip :title="snapshotCellText(record.values[String(column.key)])">
          <span>{{ snapshotCellText(record.values[String(column.key)]) }}</span>
        </Tooltip>
      </template>
    </Table>
    <ListPagination
      :current="pageNo"
      :page-size="pageSize"
      :page-size-options="[10, 20, 50, 100]"
      :total="rows.length"
      aria-label="审批数据预览分页"
      @change="pageChanged"
    />
  </div>
</template>

<style scoped>
.check-preview {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  padding-top: 18px;
}

.check-preview__table {
  flex: 1;
}

.check-preview__table :deep(.ant-table-thead > tr > th) {
  padding-block: 6px;
}

.check-preview__actions {
  display: flex;
  gap: 12px;
  margin-bottom: 14px;
}

.check-preview__actions :deep(.ant-btn) {
  display: inline-flex;
  gap: 3px;
  align-items: center;
}

.check-preview__actions img {
  width: 16px;
  height: 16px;
  object-fit: contain;
}

.check-preview__add-row {
  display: flex;
  gap: 56px;
  align-items: center;
  margin-bottom: 14px;
}

.check-preview__add-row > span {
  width: 550px;
  max-width: calc(100% - 96px);
  height: 24px;
  padding-left: 16px;
  line-height: 24px;
  color: #a5a8ae;
  background: #f4f4f5;
  border-radius: calc(var(--radius) - 2px);
}

.check-preview__add-row :deep(.ant-btn),
.check-preview__filter :deep(.ant-btn) {
  flex: 0 0 28px;
}

.check-preview__filters {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 6px;
  min-width: 0;
  padding-bottom: 14px;
}

.check-preview__filter {
  display: flex;
  gap: 12px;
  align-items: center;
  width: 800px;
  min-width: 0;
  max-width: 100%;
}

.check-preview__field {
  flex: 0 0 120px;
  width: 120px;
}

.check-preview__operator {
  flex: 0 0 80px;
  width: 80px;
}

.check-preview__value {
  flex: 0 0 170px;
  width: 170px;
}

.check-preview__logic,
.check-preview__relation-space {
  flex: 0 0 75px;
  width: 75px;
}

.check-preview__remove {
  color: hsl(var(--primary-foreground));
  background: #e77979;
  border-color: #e77979;
}

.check-preview__notice {
  margin-bottom: 16px;
}

@media (width <= 768px) {
  .check-preview__filter {
    flex-wrap: wrap;
    gap: 8px;
  }

  .check-preview__add-row {
    gap: 16px;
  }
}
</style>
