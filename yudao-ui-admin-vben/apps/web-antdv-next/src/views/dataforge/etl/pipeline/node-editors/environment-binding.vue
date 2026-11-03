<script setup lang="ts">
import type { EtlEnvironmentBinding } from '#/api/dataforge/etl/environment';

/* eslint-disable vue/no-mutating-props -- 节点编辑器使用设计器的配置副本。 */
import { computed, inject, onBeforeUnmount, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import { Alert, Button, Select } from 'antdv-next';

import { getEtlEnvironmentBindings } from '#/api/dataforge/etl/environment';

import { etlEnvironmentKey } from '../environment-context';

const props = defineProps<{ config: Record<string, any> }>();
const environment = inject(
  etlEnvironmentKey,
  computed(() => undefined),
);
const access = useAccessStore();
const bindings = ref<EtlEnvironmentBinding[]>([]);
const loading = ref(false);
const error = ref('');
let sequence = 0;
const options = computed(() =>
  bindings.value.map((item) => ({
    value: item.logicalKey,
    label: `${item.logicalKey} · 数据源 ${item.databaseId} / ${item.schemaName}`,
  })),
);
async function load() {
  const current = ++sequence;
  bindings.value = [];
  error.value = '';
  loading.value = false;
  if (environment.value === undefined) return;
  loading.value = true;
  try {
    const result = await getEtlEnvironmentBindings(environment.value);
    if (current === sequence) bindings.value = result;
  } catch {
    if (current === sequence) error.value = '环境连接读取失败，请重试。';
  } finally {
    if (current === sequence) loading.value = false;
  }
}
function select(value: unknown) {
  props.config.logicalDatabaseKey = value || undefined;
  // The server resolves physical identity at validation/publication, never trust cached UI mappings.
  props.config.schemaName = undefined;
}
watch(
  () => [environment.value, access.accessToken, access.refreshToken],
  load,
  {
    immediate: true,
  },
);
onBeforeUnmount(() => {
  ++sequence;
});
</script>
<template>
  <div class="form-item">
    <label class="form-item__label">环境逻辑连接（可选）</label>
    <Select
      :value="config.logicalDatabaseKey"
      :options="options"
      :loading="loading"
      allow-clear
      show-search
      option-filter-prop="label"
      placeholder="使用流程环境中的连接；留空为直接数据源"
      @change="select"
    />
    <Alert v-if="error" type="error" :message="error" /><Button
      v-if="error"
      size="small"
      @click="load"
    >
      <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
      重试环境连接
    </Button>
    <Alert
      v-if="config.logicalDatabaseKey && environment === undefined"
      type="warning"
      message="此节点使用逻辑连接，请先在流程配置中选择环境。"
    />
    <p class="text-xs text-muted-foreground">
      环境映射在发布时冻结。修改映射会阻止旧版本运行，需要重新发布。
    </p>
  </div>
</template>
