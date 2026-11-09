<script setup lang="ts">
import type { JobNode } from './graph-model';

import type { JobApi as Api } from '#/api/dataforge/job';

import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { formatDate } from '@vben/utils';

import { Alert, Button, ConfigProvider, Spin } from 'antdv-next';

import { getJob, getJobTaskOptions } from '#/api/dataforge/job';
import DetailBackButton from '#/components/detail-back-button/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';

import { useFullSyncScope } from '../fullsync/use-scope';
import JobFlow from './modules/job-flow.vue';
import JobTaskEditor from './modules/job-task-editor.vue';

import './designer.css';

defineOptions({ name: 'DataforgeJobDetail' });
const route = useRoute();
const router = useRouter();
const { hasAccessByCodes } = useAccess();
const id = computed(() =>
  typeof route.params.id === 'string' && /^\d+$/.test(route.params.id)
    ? route.params.id
    : '',
);
const allowed = computed(() => hasAccessByCodes(['dataforge:job:query']));
const job = ref<Api.Job>();
const taskOptions = ref<Api.TaskOption[]>([]);
const loading = ref(false);
const failed = ref('');
const tab = ref('flow');
const selected = ref<JobNode>();
const nodeOpen = ref(false);
const { active, scope } = useFullSyncScope(() => id.value);
const request = useObjectRequest(() => scope.value);
const strategies: Record<Api.Strategy, string> = {
  PARALLEL: '并行',
  SERIAL_DISCARD: '串行抛弃',
  SERIAL_PRIORITY: '串行优先',
  SERIAL_WAIT: '串行等待',
};
const date = (value?: null | string) =>
  value ? formatDate(value, 'YYYY-MM-DD HH:mm') : '-';
const details = computed(() => [
  ['最近运行时间', date(job.value?.lastRunTime)],
  ['调度周期', job.value?.cronExpression || '-'],
  ['责任人', job.value?.ownerName || '-'],
  ['责任电话', job.value?.ownerPhone || '-'],
  ['创建人', job.value?.creatorName || '-'],
  ['创建时间', date(job.value?.createTime)],
  ['更新人', '-'],
  ['更新时间', date(job.value?.updateTime)],
]);
async function load() {
  if (!active.value || !allowed.value || !id.value) return;
  const current = request.begin();
  loading.value = true;
  failed.value = '';
  try {
    const [result, options] = await Promise.allSettled([
      getJob(id.value),
      getJobTaskOptions(),
    ]);
    if (!current()) return;
    if (result.status === 'rejected') {
      failed.value = '作业详情加载失败，请重试';
      return;
    }
    job.value = result.value;
    taskOptions.value = options.status === 'fulfilled' ? options.value : [];
  } catch {
    if (current()) failed.value = '作业详情加载失败，请重试';
  } finally {
    if (current()) loading.value = false;
  }
}
function inspect(node: JobNode) {
  selected.value = node;
  nodeOpen.value = true;
}
watch(
  scope,
  () => {
    job.value = undefined;
    taskOptions.value = [];
    loading.value = false;
    failed.value = '';
    tab.value = 'flow';
    nodeOpen.value = false;
    selected.value = undefined;
    void load();
  },
  { immediate: true },
);
</script>

<template>
  <ConfigProvider
    :theme="{
      inherit: false,
      token: { colorPrimary: '#2663ff', borderRadius: 2, fontSize: 14 },
    }"
  >
    <Page
      auto-content-height
      content-class="flex min-h-0 flex-col overflow-hidden p-0"
    >
      <div class="qj-designer qj-detail">
        <Alert
          v-if="!id || !allowed"
          type="warning"
          title="作业编号无效或没有查询权限"
          show-icon
        />
        <Alert v-if="failed" type="error" :title="failed" show-icon>
          <template #action>
            <Button @click="load">重试</Button>
          </template>
        </Alert>
        <Spin :spinning="loading" class="qj-designer-spin">
          <section v-if="job" class="qj-detail-header">
            <div class="qj-detail-heading">
              <span class="qj-detail-id">{{ job.id }}</span><strong>{{ job.name }}</strong><span class="qj-detail-status">{{
                job.status === 1 ? '已上线' : '未上线'
              }}</span><DetailBackButton @click="router.push('/develop/job')" />
            </div>
            <div class="qj-info-grid">
              <span class="qj-info-label">作业类目</span><span>{{ job.categoryName || '-' }}</span><span class="qj-info-label">执行策略</span><span>{{ strategies[job.strategy] || job.strategy }}</span><span class="qj-info-label">调度状态</span><span>{{ job.scheduleStatus === 1 ? '开启' : '关闭' }}</span>
              <span class="qj-info-label">描述</span><span class="qj-info-wide">{{ job.description || '-' }}</span>
              <span class="qj-info-label">创建人</span><span>{{ job.creatorName || '-' }}</span><span class="qj-info-label">创建时间</span><span>{{ date(job.createTime) }}</span><span class="qj-info-label">更新时间</span><span>{{ date(job.updateTime) }}</span>
            </div>
          </section>
          <section v-if="job" class="qj-detail-main">
            <nav class="qj-detail-tabs" aria-label="作业管理详情">
              <button :class="{ active: tab === 'flow' }" @click="tab = 'flow'">
                作业详情
</button><button
                :class="{ active: tab === 'info' }"
                @click="tab = 'info'"
              >
                详细信息
              </button>
            </nav>
            <div v-if="tab === 'flow'" class="qj-detail-flow">
              <JobFlow
                :flow-json="job.flowJson"
                :task-options="taskOptions"
                readonly
                @refresh="load"
                @inspect="inspect"
                @error="failed = $event"
              />
            </div>
            <div v-else class="qj-extra-grid">
              <template v-for="(entry, index) in details" :key="index">
                <span class="qj-info-label">{{ entry[0] }}</span><span>{{ entry[1] }}</span>
              </template>
            </div>
          </section>
        </Spin>
      </div>
      <JobTaskEditor v-model:open="nodeOpen" :node="selected" readonly />
    </Page>
  </ConfigProvider>
</template>

<style scoped>
.qj-detail-header,
.qj-detail-main {
  padding: 20px;
  background: hsl(var(--card));
  border-radius: var(--radius);
}

.qj-detail-header {
  margin-bottom: 16px;
}

.qj-detail-main {
  padding-top: 8px;
}

.qj-detail-heading {
  margin-bottom: 20px;
}

.qj-detail .qj-info-grid,
.qj-detail .qj-extra-grid {
  gap: 1px;
  overflow: hidden;
  background: hsl(var(--border));
  border: 1px solid hsl(var(--border));
  border-radius: var(--radius);
}

.qj-detail .qj-info-grid > span,
.qj-detail .qj-extra-grid > span {
  color: hsl(var(--foreground));
  background: hsl(var(--card));
  border: 0;
}

.qj-detail .qj-info-grid > .qj-info-label,
.qj-detail .qj-extra-grid > .qj-info-label {
  color: hsl(var(--muted-foreground));
  background: hsl(var(--muted) / 45%);
}

@media (width <= 640px) {
  .qj-detail-header {
    padding: 16px;
  }

  .qj-detail-main {
    padding: 8px 16px 16px;
  }
}
</style>
