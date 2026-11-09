<script lang="ts" setup>
import type { QualityTaskInstanceApi } from '#/api/governance/quality/instance';

import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import { Alert } from 'antdv-next';

import DetailBackButton from '#/components/detail-back-button/index.vue';
import { parseResourceId } from '#/utils/resource-id';

import ReportDetail from './modules/report-detail.vue';
import ReportList from './modules/report-list.vue';

import './report.css';

defineOptions({ name: 'GovernanceQualityReport' });
const route = useRoute();
const router = useRouter();
const instanceId = computed(() => parseResourceId(route.query.instanceId));
const taskId = computed(() => parseResourceId(route.query.taskId));
const invalidId = computed(
  () => route.query.instanceId !== undefined && !instanceId.value,
);
function enter(row: QualityTaskInstanceApi.Instance) {
  if (row.id === undefined || row.id === null) return;
  void router.push({
    path: route.path,
    query: { ...route.query, instanceId: String(row.id) },
  });
}
function back() {
  const query = { ...route.query };
  delete query.instanceId;
  void router.push({ path: route.path, query });
}
</script>

<template>
  <Page auto-content-height content-class="quality-report-page">
    <Alert v-if="invalidId" message="报告编号无效" type="error">
      <template #action><DetailBackButton @click="back" /></template>
    </Alert>
    <ReportDetail
      v-else-if="instanceId"
      :instance-id="instanceId"
      @back="back"
    />
    <ReportList v-else :task-id="taskId" @enter="enter" />
  </Page>
</template>
