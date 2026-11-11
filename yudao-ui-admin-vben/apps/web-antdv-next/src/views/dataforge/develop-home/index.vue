<script lang="ts" setup>
import ModuleOverview from '#/views/dashboard/shared/module-overview/module-overview.vue';
import { useModuleOverview } from '#/views/dashboard/shared/module-overview/use-module-overview';

import { developOverviewConfig, loadDevelopOverview } from './data';

defineOptions({ name: 'DataforgeDevelopHome' });

const { data, failed, loading, refresh } =
  useModuleOverview(loadDevelopOverview);
</script>

<template>
  <ModuleOverview
    :config="developOverviewConfig"
    :data="data"
    :failed="failed"
    :loading="loading"
    @refresh="refresh"
  />
</template>
