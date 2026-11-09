<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';
import { useTabs } from '@vben/hooks';

import { parseResourceId } from '#/utils/resource-id';

import ModelDetail from './modules/model-detail.vue';

defineOptions({ name: 'GovernanceMasterModelDetailPage' });

const route = useRoute();
const router = useRouter();
const { closeCurrentTab } = useTabs();
const id = computed(() => parseResourceId(route.params.id));

async function handleBack() {
  const current = router.currentRoute.value;
  await router.push('/govern/master/masterdata');
  await closeCurrentTab(current);
}
</script>

<template>
  <Page auto-content-height content-class="master-model-detail-content">
    <ModelDetail :model-id="id" @back="handleBack" />
  </Page>
</template>

<style scoped>
:deep(.master-model-detail-content) {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
</style>
