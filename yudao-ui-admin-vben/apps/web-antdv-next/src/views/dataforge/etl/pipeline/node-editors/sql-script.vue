<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import { onMounted, ref } from 'vue';

import { Select, TextArea } from 'antdv-next';

import { useWorkspaceDatabases } from '../workspace-context';
defineOptions({ name: 'EtlNodeSqlScript' });

const props = defineProps<{
  config: Record<string, any>;
}>();

const getDatabaseSimpleList = useWorkspaceDatabases();

const databases = ref<{ label: string; value: number | string }[]>([]);

onMounted(async () => {
  const list = await getDatabaseSimpleList();
  databases.value = (list ?? []).map((item) => ({
    label: `${item.name}${item.usageType === 2 ? '（中台库）' : ''}`,
    value: item.id,
  }));
  // 触发响应式初始化
  if (props.config.sql === undefined) {
    props.config.sql = '';
  }
});
</script>

<template>
  <div class="flex flex-col gap-3">
    <div class="form-item">
      <label class="form-item__label form-item__label--required">数据源</label>
      <Select
        v-model:value="config.databaseId"
        :options="databases"
        allow-clear
        option-filter-prop="label"
        placeholder="请选择执行 SQL 的数据源"
        show-search
      />
    </div>
    <div class="form-item">
      <label class="form-item__label form-item__label--required"
        >SQL 语句</label
      >
      <TextArea
        v-model:value="config.sql"
        :rows="10"
        class="font-mono"
        placeholder="多条语句以分号分隔，按顺序执行；支持 ${params.xxx} / ${sys.today} 占位符"
      />
      <div class="form-item__tip">
        本节点仅控制执行顺序，不向下游传递数据（常用于写入后的加工统计）
      </div>
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
