<script setup lang="ts">
import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { onBeforeUnmount, ref } from 'vue';

import { Select } from 'antdv-next';

import { getQualityTaskSimpleList } from '#/api/governance/quality/task';

/** 成果质量验收任务选择（选填），研发任务与 ETL 流程共用。 */
const qualityTaskId = defineModel<DataforgeDevApi.Id>('qualityTaskId');
const qualityTasks = ref<{ label: string; value: string }[]>([]);
const loading = ref(false);
let alive = true;

onBeforeUnmount(() => {
  alive = false;
});

async function loadQualityTasks() {
  if (loading.value) return;
  loading.value = true;
  try {
    const list = await getQualityTaskSimpleList();
    if (alive)
      qualityTasks.value = list
        .filter((item) => item.id !== null && item.id !== undefined)
        .map((item) => ({
          label: item.taskName ?? String(item.id),
          value: String(item.id),
        }));
  } catch {
    // 请求失败时保留当前选项。
  } finally {
    if (alive) loading.value = false;
  }
}
</script>

<template>
  <div class="mb-3 grid grid-cols-1 gap-3">
    <label class="flex flex-col gap-1">
      <span>质量验收任务（选填）</span>
      <Select
        :value="
          qualityTaskId === null || qualityTaskId === undefined
            ? undefined
            : String(qualityTaskId)
        "
        :options="qualityTasks"
        :loading="loading"
        allow-clear
        show-search
        option-filter-prop="label"
        placeholder="选择质量任务，运行后进行质量验收"
        @focus="loadQualityTasks"
        @change="(id?: string) => (qualityTaskId = id)"
      />
    </label>
  </div>
</template>
