<script lang="ts" setup>
import ModuleOverview from '#/views/dashboard/shared/module-overview/module-overview.vue';
import { useModuleOverview } from '#/views/dashboard/shared/module-overview/use-module-overview';

import { governOverviewConfig, loadGovernOverview } from './data';

defineOptions({ name: 'GovernanceHome' });

const { data, failed, loading, refresh } =
  useModuleOverview(loadGovernOverview);
</script>

<template>
  <ModuleOverview
    :config="governOverviewConfig"
    :data="data"
    :failed="failed"
    :loading="loading"
    @refresh="refresh"
  />
</template>
