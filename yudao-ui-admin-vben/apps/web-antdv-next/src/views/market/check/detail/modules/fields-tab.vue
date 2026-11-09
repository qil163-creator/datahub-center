<script lang="ts" setup>
import type { TablePaginationConfig } from 'antdv-next';

import type { MarketCheckApi } from '#/api/asset/market/check';

import { computed, ref, watch } from 'vue';

import { Button, CheckboxGroup, Dropdown, Input, Table, Tag } from 'antdv-next';

import ListPagination from '#/components/list-pagination/index.vue';
import gridIcon from '#/views/governance/metadata-common/reference-assets/icons/grid.png';
import refreshIcon from '#/views/governance/metadata-common/reference-assets/icons/refresh.png';

import { formatMarketCheckTime, marketCheckTime } from '../../data';

type DisplayColumn = MarketCheckApi.Column;

const props = defineProps<{
  active?: boolean;
  columns: DisplayColumn[];
  columnWidths?: Record<string, number>;
  loading?: boolean;
  timePrecision?: 'minute';
}>();
const emit = defineEmits<{ refresh: [] }>();
const keyword = ref('');
const settingsOpen = ref(false);
const page = ref(1);
const pageSize = ref(10);
const pagination = computed<TablePaginationConfig>(() => ({
  current: page.value,
  pageSize: pageSize.value,
  placement: ['none'],
  onChange: pageChanged,
}));
function pageChanged(current: number, size: number) {
  page.value = current;
  pageSize.value = size;
}
watch(
  () => props.active,
  (active) => {
    if (!active) settingsOpen.value = false;
  },
);
const baseColumns = [
  {
    title: '编号',
    key: 'id',
    dataIndex: 'id',
    width: 68,
    sorter: (a: DisplayColumn, b: DisplayColumn) =>
      String(a.id ?? '').localeCompare(String(b.id ?? ''), undefined, {
        numeric: true,
      }),
  },
  { title: '中文名称', key: 'businessName', width: 165 },
  { title: '英文名称', key: 'columnName', dataIndex: 'columnName', width: 160 },
  { title: '描述', key: 'businessDefinition', width: 180 },
  { title: '字段类型', key: 'dataType', dataIndex: 'dataType', width: 100 },
  { title: '字段长度', key: 'dataLength', dataIndex: 'dataLength', width: 85 },
  { title: '小数位', key: 'dataScale', dataIndex: 'dataScale', width: 75 },
  { title: '是否可空', key: 'nullableFlag', width: 90 },
  { title: '是否主键', key: 'pkFlag', width: 90 },
  {
    title: '数据分类',
    key: 'safetyLevelName',
    dataIndex: 'safetyLevelName',
    width: 110,
  },
  { title: '字段术语', key: 'termName', dataIndex: 'termName', width: 160 },
  {
    title: '关联代码表',
    key: 'codeTableName',
    dataIndex: 'codeTableName',
    width: 170,
  },
  {
    title: '关联数据元',
    key: 'standardElementName',
    dataIndex: 'standardElementName',
    width: 170,
  },
  { title: '创建人', key: 'creatorName', dataIndex: 'creatorName', width: 100 },
  { title: '创建时间', key: 'createTime', width: 160 },
];
const visibleKeys = ref(baseColumns.map((column) => column.key));
const tableColumns = computed(() =>
  baseColumns
    .filter((column) => visibleKeys.value.includes(column.key))
    .map((column) => ({
      ...column,
      width: props.columnWidths?.[column.key] ?? column.width,
    })),
);
const scrollWidth = computed(() =>
  props.columnWidths
    ? tableColumns.value.reduce((sum, column) => sum + column.width, 0)
    : 1983,
);
const rows = computed(() => {
  const text = keyword.value.trim().toLowerCase();
  return text
    ? props.columns.filter((column) =>
        [
          column.columnName,
          column.businessName,
          column.customComment,
          column.sourceComment,
          column.termName,
        ].some((value) => value?.toLowerCase().includes(text)),
      )
    : props.columns;
});
watch(rows, () => {
  page.value = 1;
});
function fieldText(row: DisplayColumn, key: unknown) {
  if (key === 'businessName')
    return row.businessName || row.customComment || row.sourceComment || '-';
  if (key === 'businessDefinition')
    return row.businessDefinition || row.remark || '-';
  if (key === 'createTime')
    return props.timePrecision === 'minute'
      ? marketCheckTime(row.createTime)
      : formatMarketCheckTime(row.createTime);
  return row[key as keyof DisplayColumn] ?? '-';
}
</script>

<template>
  <div class="check-fields">
    <div class="check-fields__toolbar">
      <Button
        size="small"
        aria-label="刷新字段"
        :loading="loading"
        @click="emit('refresh')"
      >
        <img :src="refreshIcon" alt="" />
      </Button>
      <Dropdown v-model:open="settingsOpen" :trigger="['click']">
        <Button size="small" aria-label="表格设置">
          <img :src="gridIcon" alt="" />
        </Button>
        <template #popupRender>
          <div class="check-fields__settings" @click.stop>
            <Input
              v-model:value="keyword"
              allow-clear
              placeholder="搜索字段名 / 中文名 / 术语"
              size="small"
            />
            <CheckboxGroup
              v-model:value="visibleKeys"
              :options="
                baseColumns.map((column) => ({
                  label: column.title,
                  value: column.key,
                }))
              "
            />
          </div>
        </template>
      </Dropdown>
    </div>
    <Table
      :columns="tableColumns"
      :data-source="rows"
      :loading="loading"
      :pagination="pagination"
      :row-key="
        (row: DisplayColumn) =>
          String(row.id ?? row.resourceUrn ?? row.columnName)
      "
      :scroll="{ x: scrollWidth }"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <Tag
          v-if="column.key === 'nullableFlag' || column.key === 'pkFlag'"
          class="check-fields__flag"
          :bordered="false"
        >
          {{
            record[column.key] === undefined || record[column.key] === null
              ? '-'
              : record[column.key] === 1
                ? '是'
                : '否'
          }}
        </Tag>
        <span v-else>{{ fieldText(record as DisplayColumn, column.key) }}</span>
      </template>
    </Table>
    <ListPagination
      :current="page"
      :page-size="pageSize"
      :page-size-options="[10, 20, 50, 100]"
      :total="rows.length"
      aria-label="审批资产字段分页"
      @change="pageChanged"
    />
  </div>
</template>

<style scoped>
.check-fields__toolbar {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
  height: 58px;
  padding-top: 14px;
}

.check-fields__toolbar :deep(.ant-btn) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.check-fields__toolbar img {
  width: 14px;
  height: 14px;
  object-fit: contain;
}

.check-fields__settings {
  display: grid;
  gap: 14px;
  width: 310px;
  padding: 16px;
  background: hsl(var(--background));
  border: 1px solid hsl(var(--border));
  box-shadow: 0 4px 12px #0002;
}

.check-fields__settings :deep(.ant-checkbox-group) {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.check-fields__flag {
  padding: 0 8px;
  margin: 0;
  color: #9360c0;
  background: #f0ecf6;
  border-radius: calc(var(--radius) - 2px);
}
</style>
