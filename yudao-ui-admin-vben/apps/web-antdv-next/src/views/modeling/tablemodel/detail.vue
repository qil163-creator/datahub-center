<script setup lang="ts">
import type { TableModelApi } from '#/api/modeling/tablemodel';

import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import { Alert } from 'antdv-next';

import ModelProfile from '../logictable/components/logic-detail.vue';

defineOptions({ name: 'ModelingTableModelDetail' });
const route = useRoute();
const router = useRouter();
const id = computed(() =>
  typeof route.params.id === 'string' && /^[1-9][0-9]*$/.test(route.params.id)
    ? route.params.id
    : undefined,
);
function edit(model: TableModelApi.Model) {
  const kind =
    (
      {
        SOURCE: 'source',
        DIMENSION: 'dimension',
        DETAIL: 'fact',
        SUMMARY: 'summary',
        APPLICATION: 'application',
      } as Record<string, string>
    )[model.tableType || ''] || 'application';
  void router.push({
    name: 'ModelingLogicTableEdit',
    params: { kind },
    query: {
      id: String(model.id),
      from: String(route.query.from || route.meta.activePath || ''),
    },
  });
}
</script>
<template>
  <Page auto-content-height content-class="model-detail-content p-0">
    <ModelProfile v-if="id" :model-id="id" @back="router.back()" @edit="edit" />
    <Alert v-else message="无效的模型编号" type="error" />
  </Page>
</template>

<style scoped>
:deep(.model-detail-content) {
  display: flex;
  flex-direction: column;
}
</style>
