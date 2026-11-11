<script lang="ts" setup>
import ModuleOverview from '#/views/dashboard/shared/module-overview/module-overview.vue';
import { useModuleOverview } from '#/views/dashboard/shared/module-overview/use-module-overview';

import { basicOverviewConfig, loadBasicOverview } from './data';

defineOptions({ name: 'BasicHome' });

const { data, failed, loading, refresh } = useModuleOverview(loadBasicOverview);
</script>

<template>
  <ModuleOverview
    :config="basicOverviewConfig"
    :data="data"
    :failed="failed"
    :loading="loading"
    @refresh="refresh"
  />
</template>
