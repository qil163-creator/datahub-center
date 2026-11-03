<script lang="ts" setup>
import type { DataforgeDataPushTaskApi } from '#/api/dataforge/datapush/task';

import { computed, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Button, message, Select } from 'antdv-next';

import {
  getDataPushTableColumns,
  parseDataPushSqlColumns,
} from '#/api/dataforge/datapush/task';

defineOptions({ name: 'DataforgeDataPushIncrementColumnSelect' });

const props = defineProps<{
  disabled?: boolean;
  modelValue?: string;
  pushType?: number;
  sourceDatabaseId?: number | string;
  sqlText?: string;
  tableName?: string;
  value?: string;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue' | 'update:value', value?: string): void;
}>();

/** 与 PUSH_TYPE.SQL 一致，避免与 data.ts 循环依赖 */
const PUSH_TYPE_SQL = 2;

const selected = computed(() => props.modelValue ?? props.value);
const options = ref<{ label: string; value: string }[]>([]);
const loading = ref(false);
const isSql = computed(() => props.pushType === PUSH_TYPE_SQL);
let requestSeq = 0;

function commit(next: unknown) {
  const value = typeof next === 'string' ? next : undefined;
  emit('update:modelValue', value);
  emit('update:value', value);
}

function toOptions(list: DataforgeDataPushTaskApi.Column[]) {
  return (list ?? [])
    .filter((item) => item.incrementSupported !== false)
    .map((item) => {
      let label = item.name;
      if (item.comment) {
        label = `${item.name}（${item.comment}）`;
      } else if (item.dataType) {
        label = `${item.name}（${item.dataType}）`;
      }
      return { label, value: item.name };
    });
}

async function loadTableColumns() {
  const seq = ++requestSeq;
  options.value = [];
  if (!props.sourceDatabaseId || !props.tableName) {
    return;
  }
  loading.value = true;
  try {
    const list = await getDataPushTableColumns(
      props.sourceDatabaseId,
      props.tableName,
    );
    if (seq !== requestSeq) {
      return;
    }
    options.value = toOptions(list);
  } finally {
    if (seq === requestSeq) {
      loading.value = false;
    }
  }
}

async function parseSqlColumns(notify: boolean) {
  if (!props.sourceDatabaseId) {
    if (notify) {
      message.warning('请先选择源数据源');
    }
    return;
  }
  if (!props.sqlText?.trim()) {
    if (notify) {
      message.warning('请先填写 SQL');
    }
    return;
  }
  const seq = ++requestSeq;
  loading.value = true;
  try {
    const list = await parseDataPushSqlColumns(
      props.sourceDatabaseId,
      props.sqlText,
    );
    if (seq !== requestSeq) {
      return;
    }
    options.value = toOptions(list);
    if (notify) {
      message.success(`已解析 ${options.value.length} 列`);
    }
  } finally {
    if (seq === requestSeq) {
      loading.value = false;
    }
  }
}

function handleParseSql() {
  parseSqlColumns(true);
}

watch(
  () =>
    [
      props.sourceDatabaseId,
      props.tableName,
      props.pushType,
      props.sqlText,
    ] as const,
  (current, previous) => {
    const sqlText = current[3];
    if (!isSql.value) {
      loadTableColumns();
      return;
    }
    if (!sqlText?.trim()) {
      options.value = [];
      return;
    }
    const prevPushType = previous?.[2];
    const prevSql = previous?.[3];
    if (prevPushType !== PUSH_TYPE_SQL || !prevSql?.trim()) {
      parseSqlColumns(false);
    }
  },
  { immediate: true },
);
</script>

<template>
  <div class="flex w-full items-start gap-2">
    <Select
      :disabled="disabled"
      :loading="loading"
      :options="options"
      :placeholder="isSql ? '请先解析 SQL 列' : '请先选择源表'"
      :value="selected"
      allow-clear
      class="min-w-0 flex-1"
      option-filter-prop="label"
      show-search
      @update:value="commit"
    />
    <Button
      v-if="isSql"
      :disabled="disabled"
      :loading="loading"
      @click="handleParseSql"
    >
      <template #icon><IconifyIcon icon="lucide:scan-text" /></template>
      解析列
    </Button>
  </div>
</template>
