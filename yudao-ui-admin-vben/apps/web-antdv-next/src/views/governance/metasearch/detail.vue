<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';
import { useTabs } from '@vben/hooks';

import { parseResourceId } from '#/utils/resource-id';

import MetaDetail from '../metaobject/modules/detail.vue';

defineOptions({ name: 'GovernanceMetaSearchDetailPage' });

const route = useRoute();
const router = useRouter();
const { closeCurrentTab } = useTabs();
const id = computed(() => parseResourceId(route.params.id));
const referenceVersion = computed(() =>
  parseResourceId(route.query.referenceVersion),
);

async function handleBack() {
  const current = router.currentRoute.value;
  await router.push('/govern/metadata/metasearch');
  await closeCurrentTab(current);
}
</script>

<template>
  <Page auto-content-height content-class="metadata-search-detail-content">
    <MetaDetail
      embedded
      :table-id="id"
      :reference-version="referenceVersion"
      @back="handleBack"
    />
  </Page>
</template>

<style scoped>
:deep(.metadata-search-detail-content) {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
</style>
