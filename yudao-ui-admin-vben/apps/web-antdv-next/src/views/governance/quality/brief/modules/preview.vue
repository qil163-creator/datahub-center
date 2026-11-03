<script lang="ts" setup>
import type { QualityBriefApi } from '#/api/governance/quality/brief';

import { computed, ref } from 'vue';

import { Modal, Table } from 'antdv-next';

import { getBrief } from '#/api/governance/quality/brief';

const visible = ref(false);
const brief = ref<QualityBriefApi.Brief>();

interface ContentBlock {
  paraType?: string;
  title?: string;
  content?: string;
  columns?: { colTitle: string; dataField: string }[];
  rows?: Record<string, any>[];
}

const blocks = computed<ContentBlock[]>(() => {
  if (!brief.value?.briefContent) {
    return [];
  }
  try {
    return JSON.parse(brief.value.briefContent);
  } catch {
    return [];
  }
});

async function open(id: number) {
  visible.value = true;
  brief.value = await getBrief(id);
}

defineExpose({ open });
</script>

<template>
  <Modal v-model:open="visible" :footer="null" :width="960" title="简报预览">
    <div class="max-h-[70vh] overflow-auto">
      <h2 class="mb-1 text-center text-xl font-bold">
        {{ brief?.briefTitle }}
      </h2>
      <div class="text-muted-foreground mb-4 text-center text-sm">
        评估周期：{{ brief?.assessStart }} ~ {{ brief?.assessEnd }}
        <span v-if="brief?.issueNo">｜第 {{ brief?.issueNo }} 期</span>
      </div>
      <div v-for="(block, index) in blocks" :key="index" class="mb-4">
        <h3 v-if="block.title" class="mb-2 font-semibold">
          {{ block.title }}
        </h3>
        <template v-if="block.paraType === 'TABLE'">
          <Table
            :columns="
              (block.columns ?? []).map((column) => ({
                title: column.colTitle,
                dataIndex: column.dataField,
                key: column.dataField,
              }))
            "
            :data-source="block.rows ?? []"
            :pagination="false"
            bordered
            size="small"
          />
        </template>
        <p v-else class="whitespace-pre-wrap leading-7">
          {{ block.content }}
        </p>
      </div>
      <div
        v-if="blocks.length === 0"
        class="text-muted-foreground py-10 text-center"
      >
        暂无内容
      </div>
    </div>
  </Modal>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
