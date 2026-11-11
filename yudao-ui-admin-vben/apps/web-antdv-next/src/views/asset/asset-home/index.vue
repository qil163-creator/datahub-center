<script lang="ts" setup>
import ModuleOverview from '#/views/dashboard/shared/module-overview/module-overview.vue';
import { useModuleOverview } from '#/views/dashboard/shared/module-overview/use-module-overview';

import { assetOverviewConfig, loadAssetOverview } from './data';

defineOptions({ name: 'AssetHome' });

const { data, failed, loading, refresh } = useModuleOverview(loadAssetOverview);
</script>

<template>
  <ModuleOverview
    :config="assetOverviewConfig"
    :data="data"
    :failed="failed"
    :loading="loading"
    @refresh="refresh"
  />
</template>
