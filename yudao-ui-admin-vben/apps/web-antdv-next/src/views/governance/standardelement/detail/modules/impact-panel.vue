<script lang="ts" setup>
import { ref, watch } from 'vue';

import { Alert, Table } from 'antdv-next';

import { getStandardElementImpact } from '#/api/governance/standardelement';

const props = defineProps<{
  elementId?: number;
}>();

const loading = ref(false);
const mappedColumnCount = ref(0);
const directTables = ref<any[]>([]);

const directColumns = [
  { dataIndex: 'tableName', title: '表名' },
  { dataIndex: 'tableBusinessName', title: '表业务名称' },
  { dataIndex: 'mappedColumnCount', title: '落标字段数', width: 110 },
];

async function loadData() {
  if (!props.elementId) {
    return;
  }
  loading.value = true;
  try {
    const data = await getStandardElementImpact(props.elementId);
    mappedColumnCount.value = data.mappedColumnCount ?? 0;
    directTables.value = data.directTables ?? [];
  } finally {
    loading.value = false;
  }
}

watch(() => props.elementId, loadData, { immediate: true });
</script>

<template>
  <div class="flex h-full min-h-0 flex-col gap-3 overflow-auto">
    <Alert show-icon type="info">
      <template #message>
        本标准共落标 {{ mappedColumnCount }} 个字段、{{ directTables.length }}
        张表。
      </template>
    </Alert>
    <div>
      <div class="mb-2 text-[15px] font-medium">直接落标表</div>
      <Table
        class="standard-detail-table"
        :columns="directColumns"
        :data-source="directTables"
        :loading="loading"
        :pagination="false"
        row-key="tableId"
        size="small"
      />
    </div>
  </div>
</template>
