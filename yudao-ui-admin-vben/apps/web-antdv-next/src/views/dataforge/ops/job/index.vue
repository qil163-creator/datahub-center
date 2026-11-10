<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { parseResourceId } from '#/utils/resource-id';

import OpsWorkbench from '../workbench.vue';

defineOptions({ name: 'DataforgeOpsJob' });
const route = useRoute();
const router = useRouter();
const jobId = computed(() => parseResourceId(route.query.jobId));
function clearJobFilter() {
  const { jobId: _jobId, ...query } = route.query;
  void router.replace({ query });
}
</script>

<template><OpsWorkbench kind="JOB" :job-id="jobId" @clear-job-filter="clearJobFilter" /></template>
