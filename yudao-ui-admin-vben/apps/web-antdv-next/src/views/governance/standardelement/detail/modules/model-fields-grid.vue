<script lang="ts" setup>
import { computed, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useAccessStore } from '@vben/stores';

import { Alert, Table } from 'antdv-next';

import { getStandardElementModelFields } from '#/api/governance/standardelement';
import { useObjectRequest } from '#/hooks/use-object-request';

const props = defineProps<{
  elementId?: number;
}>();

const loading = ref(false);
const failed = ref(false);
const rows = ref<Awaited<ReturnType<typeof getStandardElementModelFields>>>([]);
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const canQueryModels = computed(() => hasAccessByCodes(['modeling:model:query']));
const request = useObjectRequest(
  () => JSON.stringify([props.elementId, access.accessCodes]),
  () => { rows.value = []; loading.value = false; },
);

const MODEL_STATUS_LABELS: Record<string, string> = {
  DISCARDED: '已废弃',
  DRAFT: '草稿',
  PUBLISHED: '已发布',
};

const MAPPING_STATUS_LABELS: Record<string, string> = {
  CONFIRMED: '已确认',
  PROPOSED: '待确认',
  REVOKED: '已撤销',
  STALE: '已失效',
};

const columns = [
  { dataIndex: 'modelName', title: '物理模型' },
  { dataIndex: 'tableName', title: '表名' },
  {
    customRender: ({ value }: { value?: string }) =>
      value ? (MODEL_STATUS_LABELS[value] ?? value) : '-',
    dataIndex: 'modelStatus',
    title: '模型状态',
    width: 130,
  },
  { dataIndex: 'fieldName', title: '字段名' },
  { dataIndex: 'cnName', title: '中文名称' },
  { dataIndex: 'dataType', title: '数据类型', width: 100 },
  { dataIndex: 'dataLength', title: '长度', width: 70 },
  {
    customRender: ({ value }: { value?: string }) =>
      value
        ? (MAPPING_STATUS_LABELS[value] ?? value)
        : '未建事实（需回填 fieldUid）',
    dataIndex: 'mappingStatus',
    title: '落标状态',
    width: 160,
  },
];

async function loadData() {
  const current = request.begin();
  rows.value = [];
  failed.value = false;
  if (!props.elementId || !canQueryModels.value) {
    return;
  }
  loading.value = true;
  try {
    const result = await getStandardElementModelFields(props.elementId);
    if (current()) rows.value = result;
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}

watch(() => [props.elementId, access.accessToken, access.accessCodes], loadData, { immediate: true, deep: true });
</script>

<template>
  <div>
    <div class="mb-2 text-[15px] font-medium">
      设计态落标（建模模块绑定本标准的模型字段）
    </div>
    <Alert v-if="!canQueryModels" type="warning" message="暂无逻辑模型查询权限" show-icon />
    <Alert v-else-if="failed" type="error" message="设计态落标加载失败" show-icon />
    <Table
      v-if="canQueryModels"
      class="standard-detail-table"
      :columns="columns"
      :data-source="rows"
      :loading="loading"
      :pagination="false"
      row-key="fieldUid"
      size="small"
    />
  </div>
</template>
