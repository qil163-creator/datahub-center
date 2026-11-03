<script lang="ts" setup>
import type { FoundationDatabaseApi } from '#/api/dataforge/database';

import { computed, ref, watch } from 'vue';

import { Select } from 'antdv-next';

import { getDatabaseTableList } from '#/api/dataforge/database';

defineOptions({ name: 'DataforgeDataPushSourceTableSelect' });

const props = defineProps<{
  disabled?: boolean;
  modelValue?: string;
  sourceDatabaseId?: number | string;
  value?: string;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue' | 'update:value', value?: string): void;
}>();

const selected = computed(() => props.modelValue ?? props.value);
const options = ref<{ label: string; value: string }[]>([]);
const loading = ref(false);
let requestSeq = 0;

function commit(next: unknown) {
  const value = typeof next === 'string' ? next : undefined;
  emit('update:modelValue', value);
  emit('update:value', value);
}

async function loadTables(databaseId?: number | string) {
  const seq = ++requestSeq;
  options.value = [];
  if (!databaseId) {
    return;
  }
  loading.value = true;
  try {
    const list: FoundationDatabaseApi.DatabaseTable[] =
      (await getDatabaseTableList(databaseId)) ?? [];
    if (seq !== requestSeq) {
      return;
    }
    options.value = list.map((item) => ({
      label: item.remarks
        ? `${item.tableName}（${item.remarks}）`
        : item.tableName,
      value: item.tableName,
    }));
  } finally {
    if (seq === requestSeq) {
      loading.value = false;
    }
  }
}

watch(
  () => props.sourceDatabaseId,
  (databaseId) => {
    loadTables(databaseId);
  },
  { immediate: true },
);
</script>

<template>
  <Select
    :disabled="disabled"
    :loading="loading"
    :options="options"
    :value="selected"
    allow-clear
    class="w-full"
    option-filter-prop="label"
    placeholder="请先选择源数据源"
    show-search
    @update:value="commit"
  />
</template>
