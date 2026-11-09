<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { useTabs } from '@vben/hooks';

import { Alert } from 'antdv-next';

import DetailBackButton from '#/components/detail-back-button/index.vue';
import { parseResourceId } from '#/utils/resource-id';

import Detail from './modules/detail.vue';

defineOptions({ name: 'SupplyAppDetailPage' });

const route = useRoute();
const router = useRouter();
const { closeCurrentTab } = useTabs();
const { hasAccessByCodes } = useAccess();
const id = computed(() => parseResourceId(route.params.id));
const canQuery = computed(() => hasAccessByCodes(['supply:app:query']));

async function handleBack() {
  const current = router.currentRoute.value;
  await router.push('/supply/app');
  await closeCurrentTab(current);
}
</script>

<template>
  <Page auto-content-height content-class="app-detail-content">
    <Detail v-if="id && canQuery" embedded :app-id="id" @back="handleBack" />
    <Alert
      v-else
      :message="canQuery ? '无效的应用编号' : '无应用查询权限'"
      type="error"
      show-icon
    >
      <template #action><DetailBackButton @click="handleBack" /></template>
    </Alert>
  </Page>
</template>

<style scoped>
:deep(.app-detail-content) {
  display: flex;
  flex-direction: column;
}
</style>
