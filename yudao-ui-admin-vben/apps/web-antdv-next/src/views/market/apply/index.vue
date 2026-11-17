<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { Fallback } from '@vben/common-ui';
import { useTabs } from '@vben/hooks';

import { MY_ASSETS_ROUTE_NAME } from '#/views/asset/market/authorized-assets-navigation';

defineOptions({ name: 'MarketApply' });
const router = useRouter();
const tabs = useTabs();
const unavailable = ref(false);

onMounted(async () => {
  if (router.hasRoute(MY_ASSETS_ROUTE_NAME)) {
    const oldRoute = router.currentRoute.value;
    const failure = await router.replace({
      name: MY_ASSETS_ROUTE_NAME,
      query: { tab: 'applications' },
    });
    if (!failure) await tabs.closeCurrentTab(oldRoute);
  } else {
    unavailable.value = true;
  }
});
</script>

<template>
  <Fallback v-if="unavailable" status="403" />
</template>
