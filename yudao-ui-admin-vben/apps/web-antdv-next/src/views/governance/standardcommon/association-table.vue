<script setup lang="ts">
import type { StandardAssociation } from '#/api/governance/standardcommon/association';

import { computed, h, ref, watch } from 'vue';

import { useAccessStore } from '@vben/stores';
import { formatDate } from '@vben/utils';

import { Table } from 'antdv-next';

import { getStandardAssociations } from '#/api/governance/standardcommon/association';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { useObjectRequest } from '#/hooks/use-object-request';

import StandardPagination from './standard-pagination.vue';
import StandardToolbar from './standard-toolbar.vue';

const props = defineProps<{
  kind: 'codeset' | 'element';
  ownerId: number | string;
}>();
const emptyImage = '/images/quality-reference/empty.png';
const access = useAccessStore();
const rows = ref<StandardAssociation[]>([]);
const page = ref(1);
const pageSize = ref(10);
const total = ref(0);
const loading = ref(false);
const failed = ref(false);
const definitions = [
  { key: 'id', title: '编号', dataIndex: 'id', width: 90 },
  { key: 'assetName', title: '资产名称', dataIndex: 'assetName', width: 220 },
  {
    key: 'description',
    title: '描述',
    dataIndex: 'description',
    width: 260,
    customRender: ({ text }: { text?: unknown }) =>
      h(TextEllipsisCell, {
        content: text === undefined || text === null ? undefined : String(text),
        line: 2,
        center: true,
      }),
  },
  { key: 'tableName', title: '数据表', dataIndex: 'tableName', width: 220 },
  {
    key: 'columnNames',
    title: '关联字段',
    dataIndex: 'columnNames',
    width: 220,
  },
  { key: 'creatorName', title: '创建人', dataIndex: 'creatorName', width: 120 },
  { key: 'createTime', title: '创建时间', dataIndex: 'createTime', width: 180 },
  { key: 'updateTime', title: '更新时间', dataIndex: 'updateTime', width: 180 },
];
const visibleColumns = ref(definitions.map((column) => column.key));
const columns = computed(() =>
  definitions.filter((column) => visibleColumns.value.includes(column.key)),
);
const requests = useObjectRequest(
  () =>
    JSON.stringify([
      props.kind,
      props.ownerId,
      access.accessCodes,
      page.value,
      pageSize.value,
    ]),
  () => {
    rows.value = [];
    total.value = 0;
    loading.value = false;
  },
);
async function load() {
  const current = requests.begin();
  if (!props.ownerId) return;
  loading.value = true;
  failed.value = false;
  try {
    const response = await getStandardAssociations(props.kind, {
      id: props.ownerId,
      pageNo: page.value,
      pageSize: pageSize.value,
    });
    if (current()) {
      rows.value = response.list;
      total.value = response.total;
    }
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
watch(
  () => [
    props.kind,
    props.ownerId,
    access.accessToken,
    access.refreshToken,
    access.accessCodes,
  ],
  () => {
    page.value = 1;
    void load();
  },
  { immediate: true, deep: true },
);
</script>

<template>
  <section
    class="standard-association-table"
    data-testid="standard-associations"
  >
    <StandardToolbar
      v-model:visible-columns="visibleColumns"
      :columns="definitions"
      hide-search
      settings-icon="lucide:settings"
      @refresh="load"
    >
      <template #title><span aria-hidden="true"></span></template>
    </StandardToolbar>
    <Table
      class="standard-table"
      :columns="columns"
      :data-source="rows"
      :loading="loading"
      :pagination="false"
      :scroll="{ x: 1400 }"
      row-key="id"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <span v-if="column.key === 'columnNames'">{{
          Array.isArray(record.columnNames)
            ? record.columnNames.join('、')
            : record.columnNames || '-'
        }}</span>
        <span
          v-else-if="column.key === 'createTime' || column.key === 'updateTime'"
          >{{
            record[column.key]
              ? formatDate(record[column.key], 'YYYY-MM-DD HH:mm')
              : '-'
          }}</span>
      </template>
      <template #emptyText>
        <div class="standard-empty">
          <img :src="emptyImage" alt="" /><span>暂无记录</span>
        </div>
      </template>
    </Table>
    <StandardPagination
      v-model:page="page"
      v-model:page-size="pageSize"
      :total="total"
      @change="load"
    />
  </section>
</template>

<style scoped>
.standard-association-table :deep(.standard-toolbar) {
  gap: 8px;
  min-height: 30px;
  margin-bottom: 8px;
}

.standard-association-table :deep(.standard-toolbar-tools) {
  gap: 8px;
}

.standard-association-table :deep(.standard-toolbar-tools .ant-btn) {
  width: 26px;
  min-width: 26px;
  height: 26px;
  padding: 0;
  border-radius: 50%;
}

.standard-association-table :deep(.standard-toolbar-tools svg) {
  width: 12px;
  height: 12px;
}

.standard-association-table :deep(.ant-table-thead > tr > th) {
  padding: 7px 10px;
  line-height: 20px;
  background: hsl(var(--muted));
}

.standard-association-table :deep(.ant-table-thead > tr > th),
.standard-association-table :deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}

.standard-association-table :deep(.ant-table-tbody > tr > td) {
  padding: 8px 10px;
}

.standard-association-table :deep(.ant-table-content) {
  scrollbar-color: hsl(var(--muted-foreground) / 20%) transparent;
  scrollbar-width: thin;
}

.standard-association-table :deep(.ant-table-content::-webkit-scrollbar) {
  width: 6px;
  height: 6px;
}

.standard-association-table :deep(.ant-table-content::-webkit-scrollbar-thumb) {
  background: hsl(var(--muted-foreground) / 20%);
  border-radius: 4px;
}
</style>
