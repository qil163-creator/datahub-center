<script setup lang="ts">
import type { GovernanceLineageApi } from '#/api/governance/lineage';

import { onBeforeUnmount, ref, watch } from 'vue';

import { Alert, Empty, Spin } from 'antdv-next';

import { resolveLineageNode } from '#/api/governance/lineage';
import LineageExplorer from '#/views/governance/lineage/components/lineage-explorer.vue';

defineOptions({ name: 'MetaObjectLineageTab' });

const props = defineProps<{ metaTableId?: GovernanceLineageApi.Id }>();

const node = ref<GovernanceLineageApi.Node | null>();
const loading = ref(false);
const error = ref('');
let controller: AbortController | undefined;

watch(
  () => props.metaTableId,
  async (metaTableId) => {
    controller?.abort();
    node.value = undefined;
    error.value = '';
    if (metaTableId === undefined || metaTableId === null || metaTableId === '') return;
    const current = new AbortController();
    controller = current;
    loading.value = true;
    try {
      const resolved = await resolveLineageNode(metaTableId, current.signal);
      if (current === controller) node.value = resolved ?? null;
    } catch {
      if (current === controller) error.value = '血缘信息加载失败，请稍后重试';
    } finally {
      if (current === controller) loading.value = false;
    }
  },
  { immediate: true },
);

onBeforeUnmount(() => controller?.abort());
</script>

<template>
  <Spin :spinning="loading">
    <Alert v-if="error" type="error" show-icon :message="error" />
    <div v-else-if="node" class="meta-lineage-tab">
      <LineageExplorer :node-id="node.id" />
    </div>
    <Empty
      v-else-if="node === null"
      description="暂无血缘：还没有开发任务、同步、集成或 ETL 流程读写这张表"
    />
  </Spin>
</template>

<style scoped>
.meta-lineage-tab {
  height: 560px;
}
</style>
