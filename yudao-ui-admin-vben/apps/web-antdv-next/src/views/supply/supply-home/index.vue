<script lang="ts" setup>
import ModuleOverview from '#/views/dashboard/shared/module-overview/module-overview.vue';
import { useModuleOverview } from '#/views/dashboard/shared/module-overview/use-module-overview';

import { loadSupplyOverview, supplyOverviewConfig } from './data';

defineOptions({ name: 'SupplyHome' });

const { data, failed, loading, refresh } =
  useModuleOverview(loadSupplyOverview);
</script>

<template>
  <ModuleOverview
    :config="supplyOverviewConfig"
    :data="data"
    :failed="failed"
    :loading="loading"
    @refresh="refresh"
  />
</template>
