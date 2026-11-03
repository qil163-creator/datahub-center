<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import type { FoundationDatabaseApi } from '#/api/dataforge/database';

import { onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { Input, RadioGroup, Select, TextArea } from 'antdv-next';

import { getDatabaseTableList } from '#/api/dataforge/database';

import { useWorkspaceDatabases } from '../workspace-context';
import EnvironmentBinding from './environment-binding.vue';
defineOptions({ name: 'EtlNodeReadDb' });

const props = defineProps<{
  config: Record<string, any>;
}>();

const getDatabaseSimpleList = useWorkspaceDatabases();

const databases = ref<{ label: string; value: number | string }[]>([]);
const tables = ref<{ label: string; value: string }[]>([]);

const MODE_OPTIONS = [
  { label: '整表读取', value: 0 },
  { label: '自定义 SQL', value: 1 },
];

const FETCH_OPTIONS = [1000, 2000, 5000, 10_000].map((v) => ({
  label: String(v),
  value: v,
}));

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
watch(
  () => props.config.logicalDatabaseKey,
  (key) => {
    if (key) props.config.ifSql = 0;
  },
  { immediate: true },
);

onMounted(async () => {
  if (props.config.ifSql === null || props.config.ifSql === undefined) {
    props.config.ifSql = 0;
  }
  if (props.config.fetchSize === null || props.config.fetchSize === undefined) {
    props.config.fetchSize = 5000;
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
    <EnvironmentBinding :config="config" />
    <div v-if="!config.logicalDatabaseKey" class="form-item">
      <label class="form-item__label form-item__label--required">数据源</label>
      <Select
        v-model:value="config.databaseId"
        :options="databases"
        allow-clear
        option-filter-prop="label"
        placeholder="请选择数据源"
        show-search
      />
    </div>
    <div class="form-item">
      <label class="form-item__label">读取方式</label>
      <RadioGroup
        v-model:value="config.ifSql"
        :disabled="!!config.logicalDatabaseKey"
        :options="MODE_OPTIONS"
        option-type="button"
      />
      <p v-if="config.logicalDatabaseKey" class="form-item__tip">
        环境逻辑连接当前支持整表读取；自定义 SQL 使用直接数据源并单独校验权限。
      </p>
    </div>
    <div v-if="config.ifSql !== 1" class="form-item">
      <label class="form-item__label form-item__label--required">表名</label>
      <Input
        v-if="config.logicalDatabaseKey"
        v-model:value="config.tableName"
        placeholder="逻辑连接 Schema 内的表名；大小写名称请加数据库引用符"
      />
      <Select
        v-else
        v-model:value="config.tableName"
        :options="tables"
        allow-clear
        option-filter-prop="label"
        placeholder="请选择表"
        show-search
      />
    </div>
    <div v-else class="form-item">
      <label class="form-item__label form-item__label--required"
        >查询 SQL</label
      >
      <TextArea
        v-model:value="config.sql"
        :rows="6"
        class="font-mono"
        placeholder="SELECT id, name FROM t_user WHERE ..."
      />
      <div class="form-item__tip">
        支持 ${params.xxx} / ${sys.today} 等流程变量占位符
      </div>
    </div>
    <div class="form-item">
      <label class="form-item__label">批量大小</label>
      <Select v-model:value="config.fetchSize" :options="FETCH_OPTIONS" />
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

.form-item__tip {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}
</style>
