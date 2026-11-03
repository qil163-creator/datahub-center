<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { Checkbox, RadioGroup, Select } from 'antdv-next';

import { getDatabaseTableList } from '#/api/dataforge/database';

import { useWorkspaceDatabases } from '../workspace-context';
defineOptions({ name: 'EtlNodeInsertDb' });

const props = defineProps<{
  config: Record<string, any>;
  inputFields?: DataforgeEtlApi.FieldMeta[];
}>();

const getDatabaseSimpleList = useWorkspaceDatabases();

const databases = ref<{ label: string; value: number | string }[]>([]);
const tables = ref<{ label: string; value: string }[]>([]);

const SYNC_TYPE_OPTIONS = [
  { label: '仅插入', value: 1 },
  { label: '更新及插入', value: 2 },
  { label: '仅更新', value: 3 },
];

const BATCH_OPTIONS = [500, 1000, 2000, 5000].map((v) => ({
  label: String(v),
  value: v,
}));

const pkOptions = computed(() =>
  (props.inputFields ?? []).map((field) => ({
    label: `${field.name}（${field.dataType ?? '-'}）`,
    value: field.name!,
  })),
);

const pkValues = computed<string[]>({
  get: () =>
    props.config.sourcePrimaryKeys
      ? String(props.config.sourcePrimaryKeys).split(',').filter(Boolean)
      : [],
  set: (values) => {
    props.config.sourcePrimaryKeys = values.join(',');
  },
});

let tableSeq = 0;
onBeforeUnmount(() => {
  ++tableSeq;
});
async function loadTables(databaseId?: number | string) {
  const seq = ++tableSeq;
  tables.value = [];
  if (!databaseId) {
    return;
  }
  const list: FoundationDatabaseApi.DatabaseTable[] =
    await getDatabaseTableList(databaseId);
  if (seq !== tableSeq) return;
  tables.value = (list ?? []).map((item) => ({
    label: item.remarks
      ? `${item.tableName}（${item.remarks}）`
      : item.tableName,
    value: item.tableName,
  }));
}

watch(
  () => props.config.databaseId,
  (databaseId) => loadTables(databaseId),
);

onMounted(async () => {
  if (
    props.config.dataSyncType === null ||
    props.config.dataSyncType === undefined
  ) {
    props.config.dataSyncType = 1;
  }
  if (props.config.batchSize === null || props.config.batchSize === undefined) {
    props.config.batchSize = 1000;
  }
  const list = await getDatabaseSimpleList();
  databases.value = (list ?? []).map((item) => ({
    label: `${item.name}${item.usageType === 2 ? '（中台库）' : ''}`,
    value: item.id,
  }));
  if (props.config.databaseId) {
    loadTables(props.config.databaseId);
  }
});
</script>

<template>
  <div class="flex flex-col gap-3">
    <div class="form-item">
      <label class="form-item__label form-item__label--required">
        目标数据源
      </label>
      <Select
        v-model:value="config.databaseId"
        :options="databases"
        allow-clear
        option-filter-prop="label"
        placeholder="请选择目标数据源"
        show-search
      />
    </div>
    <div class="form-item">
      <label class="form-item__label form-item__label--required">目标表</label>
      <Select
        v-model:value="config.tableName"
        :options="tables"
        allow-clear
        option-filter-prop="label"
        placeholder="目标表须已存在"
        show-search
      />
    </div>
    <div class="form-item">
      <label class="form-item__label">写入方式</label>
      <RadioGroup
        v-model:value="config.dataSyncType"
        :options="SYNC_TYPE_OPTIONS"
        option-type="button"
      />
    </div>
    <div v-if="config.dataSyncType !== 1" class="form-item">
      <label class="form-item__label form-item__label--required">
        主键字段
      </label>
      <Select
        v-model:value="pkValues"
        :options="pkOptions"
        allow-clear
        mode="tags"
        placeholder="按主键定位更新行，可多选"
      />
    </div>
    <div class="form-item">
      <label class="form-item__label">写入选项</label>
      <div class="flex items-center gap-4">
        <Checkbox
          :checked="config.targetTruncate === true"
          @update:checked="(v: boolean) => (config.targetTruncate = v)"
        >
          写入前清空目标表
        </Checkbox>
      </div>
    </div>
    <div class="form-item">
      <label class="form-item__label">批量大小</label>
      <Select v-model:value="config.batchSize" :options="BATCH_OPTIONS" />
    </div>
  </div>
</template>

<style scoped>
.form-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-item__label {
  font-size: 14px;
  font-weight: 500;
}

.form-item__label--required::before {
  margin-right: 4px;
  color: hsl(var(--destructive));
  content: '*';
}
</style>
