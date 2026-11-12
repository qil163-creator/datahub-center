<script setup lang="ts">
import type { GovernanceLineageApi } from '#/api/governance/lineage';

import { onBeforeUnmount, ref } from 'vue';

import { Select } from 'antdv-next';

import { searchLineageNodes } from '#/api/governance/lineage';

import { nodeOptionLabel } from '../graph-model';

defineOptions({ name: 'LineageNodeSelect' });

defineProps<{ placeholder?: string }>();
const emit = defineEmits<{ change: [node: GovernanceLineageApi.Node | undefined] }>();
const value = defineModel<string | undefined>('value');

const options = ref<{ label: string; node: GovernanceLineageApi.Node; value: string }[]>([]);
const loading = ref(false);
let controller: AbortController | undefined;
let timer: ReturnType<typeof setTimeout> | undefined;

/** 远程检索，最新请求优先。 */
function search(keyword: string) {
  if (timer) clearTimeout(timer);
  timer = setTimeout(() => void load(keyword), 250);
}

async function load(keyword: string) {
  controller?.abort();
  const current = new AbortController();
  controller = current;
  loading.value = true;
  try {
    const nodes = await searchLineageNodes({ keyword: keyword.trim() || undefined, limit: 30 }, current.signal);
    if (current !== controller) return;
    options.value = (nodes ?? []).map((node) => ({ label: nodeOptionLabel(node), node, value: String(node.id) }));
  } catch {
    if (current === controller) options.value = [];
  } finally {
    if (current === controller) loading.value = false;
  }
}

function changed(next: unknown) {
  const key = next === undefined || next === null ? undefined : String(next);
  value.value = key;
  emit('change', options.value.find((option) => option.value === key)?.node);
}

void load('');

onBeforeUnmount(() => {
  if (timer) clearTimeout(timer);
  controller?.abort();
});
</script>

<template>
  <Select
    :value="value"
    :options="options"
    :loading="loading"
    :filter-option="false"
    :placeholder="placeholder ?? '输入表名或外部数据集检索'"
    allow-clear
    show-search
    style="width: 360px"
    @change="changed"
    @search="search"
  />
</template>
