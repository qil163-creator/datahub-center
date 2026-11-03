<script lang="ts" setup>
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { h, onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { formatDateTime, handleTree } from '@vben/utils';

import { Tag } from 'antdv-next';

import { getEtlToolList } from '#/api/dataforge/etl';
import TableViewport from '#/components/table-viewport/index.vue';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';

defineOptions({ name: 'DataforgeEtlToolbox' });

const tools = ref<DataforgeEtlApi.Tool[]>([]);
const loading = ref(false);

const TYPE_LABELS: Record<number, { color: string; label: string }> = {
  [-1]: { label: '目录', color: 'default' },
  1: { label: '输入', color: 'blue' },
  2: { label: '转换', color: 'purple' },
  3: { label: '输出', color: 'green' },
  4: { label: '脚本', color: 'orange' },
};

const columns = [
  { title: '名称', key: 'name', width: 280 },
  { title: '类型', key: 'type', width: 100 },
  { title: '组件代码', dataIndex: 'code', key: 'code', width: 160 },
  { title: '是否内置', key: 'builtIn', width: 90 },
  {
    title: '描述',
    dataIndex: 'description',
    key: 'description',
    customRender: ({ text }: { text?: unknown }) =>
      h(TextEllipsisCell, {
        content: text === undefined || text === null ? undefined : String(text),
        line: 2,
        center: true,
      }),
  },
  { title: '排序', dataIndex: 'orderNo', key: 'orderNo', width: 70 },
  {
    title: '创建时间',
    dataIndex: 'createTime',
    key: 'createTime',
    width: 180,
    customRender: ({ text }: { text: unknown }) =>
      formatDateTime(text as any) || '-',
  },
];

async function loadTools() {
  loading.value = true;
  try {
    tools.value = handleTree(await getEtlToolList());
  } finally {
    loading.value = false;
  }
}

onMounted(loadTools);
</script>

<template>
  <Page
    auto-content-height
    content-class="flex min-h-0 flex-col overflow-hidden"
    class="toolbox-page"
  >
    <section class="toolbox">
      <header class="toolbox__header">
        <h2 class="toolbox__title">工具箱</h2>
        <span class="toolbox__tip">
          可视化 ETL 的节点组件清单，在「生产流程」设计器中拖拽使用
        </span>
      </header>
      <TableViewport
        :columns="columns"
        :data-source="tools"
        :expandable="{ defaultExpandAllRows: true }"
        :loading="loading"
        :pagination="false"
        :scroll="{ x: 1100 }"
        row-key="id"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'name'">
            <span class="inline-flex items-center gap-2">
              <IconifyIcon
                :icon="(record as any).icon || 'lucide:box'"
                class="size-4 text-blue-500"
              />
              {{ (record as any).name }}
            </span>
          </template>
          <template v-else-if="column.key === 'type'">
            <Tag :color="TYPE_LABELS[(record as any).type]?.color">
              {{ TYPE_LABELS[(record as any).type]?.label ?? '-' }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'builtIn'">
            {{ (record as any).builtIn === 1 ? '是' : '否' }}
          </template>
          <template v-else-if="column.key === 'createTime'">
            {{ formatDateTime((record as any).createTime) || '-' }}
          </template>
        </template>
      </TableViewport>
    </section>
  </Page>
</template>

<style scoped>
.toolbox :deep(.ant-table-thead > tr > th),
.toolbox :deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}

.toolbox-page {
  background: hsl(var(--muted));
}

.toolbox {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  padding: 12px 14px 20px;
  overflow: hidden;
  background: hsl(var(--background));
  border-radius: var(--radius);
}

.toolbox__header {
  display: flex;
  flex-shrink: 0;
  flex-wrap: wrap;
  gap: 4px 12px;
  align-items: center;
  min-height: 32px;
  margin-bottom: 14px;
}

.toolbox__title {
  margin: 0;
  font-size: 16px;
  font-weight: 500;
}

.toolbox__tip {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}
</style>
