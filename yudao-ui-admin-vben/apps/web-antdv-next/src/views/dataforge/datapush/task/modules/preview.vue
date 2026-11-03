<script lang="ts" setup>
import type { DataforgeDataPushTaskApi } from '#/api/dataforge/datapush/task';

import { computed, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import { Alert, Table } from 'antdv-next';

import { previewDataPushTask } from '#/api/dataforge/datapush/task';

defineOptions({ name: 'DataforgeDataPushTaskPreview' });

const loading = ref(false);
const result = ref<DataforgeDataPushTaskApi.Preview>();
const taskName = ref('');
let requestSeq = 0;

const tableColumns = computed(() =>
  (result.value?.columns ?? []).map((name) => ({
    title: name,
    dataIndex: name,
    key: name,
    ellipsis: true,
    width: 160,
  })),
);

const getTitle = computed(() =>
  taskName.value ? `试跑预览 - ${taskName.value}` : '试跑预览',
);

const [Modal, modalApi] = useVbenModal({
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      requestSeq++;
      result.value = undefined;
      return;
    }
    const row = modalApi.getData() as DataforgeDataPushTaskApi.Task | undefined;
    taskName.value = row?.name ?? '';
    if (!row?.id) {
      return;
    }
    const seq = ++requestSeq;
    loading.value = true;
    try {
      const preview = await previewDataPushTask({ id: row.id, limit: 10 });
      if (seq !== requestSeq) {
        return;
      }
      result.value = preview;
    } finally {
      if (seq === requestSeq) {
        loading.value = false;
      }
    }
  },
});
</script>

<template>
  <Modal :title="getTitle" class="w-[960px]">
    <div class="mx-4 pb-2">
      <Alert
        v-if="result?.hint"
        class="mb-3"
        :message="result.hint"
        show-icon
        type="info"
      />
      <div v-if="result" class="preview-meta">
        对象名 {{ result.objectName || '-' }}
        <template v-if="result.emptyInterval">
          · 增量区间为空，无需推送</template
        >
        <template v-else-if="result.incrementEnd">
          · 增量 ({{ result.incrementStart || '空' }},
          {{ result.incrementEnd }}]
        </template>
        · {{ result.rowCount ?? 0 }} 行
      </div>
      <Table
        :columns="tableColumns"
        :data-source="result?.rows ?? []"
        :loading="loading"
        :pagination="false"
        :scroll="{ x: 'max-content' }"
        :row-key="
          (_record: Record<string, any>, index?: number) => String(index ?? 0)
        "
        size="small"
      />
      <pre v-if="result?.payloadPreview" class="preview-json">{{
        result.payloadPreview
      }}</pre>
    </div>
  </Modal>
</template>

<style scoped>
.preview-meta {
  margin-bottom: 12px;
  font-size: 13px;
  color: hsl(var(--muted-foreground));
}

.preview-json {
  max-height: 192px;
  padding: 8px;
  margin-top: 12px;
  overflow: auto;
  font-size: 12px;
  background: hsl(var(--muted));
  border-radius: 6px;
}
</style>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
