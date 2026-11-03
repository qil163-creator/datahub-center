<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { TabPane, Tabs } from 'antdv-next';

import MetaFile from '../metafile/index.vue';
import TableList from './modules/table-list.vue';
defineOptions({ name: 'GovernanceMetaObject' });
const route = useRoute();
const router = useRouter();
const kind = computed(() => (route.query.kind === 'file' ? 'file' : 'table'));
function changeKind(value: 'file' | 'table') {
  void router.replace({
    path: route.path,
    query: value === 'file' ? { kind: 'file' } : {},
  });
}
</script>
<template>
  <div class="metadata-entry">
    <Tabs
      class="metadata-entry__switch"
      :active-key="kind"
      @change="(value) => changeKind(value === 'file' ? 'file' : 'table')"
    >
      <TabPane key="table" tab="最新元数据（库表）" />
      <TabPane key="file" tab="最新元数据（文件）" />
    </Tabs>
    <MetaFile v-if="kind === 'file'" />
    <TableList v-else />
  </div>
</template>
<style scoped>
.metadata-entry {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.metadata-entry__switch {
  flex-shrink: 0;
  padding: 0 16px;
  background: hsl(var(--card));
}

.metadata-entry > :last-child {
  flex: 1;
  min-height: 0;
}
</style>
