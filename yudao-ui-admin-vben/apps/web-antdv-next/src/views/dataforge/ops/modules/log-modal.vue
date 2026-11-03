<script setup lang="ts">
import type { OpsInstanceApi as Api } from '#/api/dataforge/ops';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { downloadFileFromBlobPart } from '@vben/utils';

import { Alert, Button, Input, Modal, Spin, Switch } from 'antdv-next';

import {
  downloadOpsInstanceLog,
  getOpsInstance,
  getOpsInstanceLog,
} from '#/api/dataforge/ops';
import { useObjectRequest } from '#/hooks/use-object-request';

import {
  displayDate,
  formatDuration,
  isActiveOpsInstance,
  queueStateLabel,
  requestIdentity,
  rowIdentity,
} from '../model';
import LogViewer from './log-viewer.vue';
import ReferenceIcon from './reference-icon.vue';
import StatusBadge from './status-badge.vue';

const props = defineProps<{ open: boolean; row?: Api.Instance }>();
const emit = defineEmits<{ close: [] }>();
const { hasAccessByCodes } = useAccess();
const canDownload = computed(() =>
  hasAccessByCodes(['dataforge:ops-instance:export']),
);
const record = ref<Api.Instance>();
const summary = computed(() => props.row?.logIdentity ? props.row : record.value ?? props.row);
const message = ref('');
const keyword = ref('');
const loading = ref(false);
const failed = ref(false);
const downloading = ref(false);
const autoRefresh = ref(false);
let timer: ReturnType<typeof setTimeout> | undefined;
const scope = computed(() =>
  props.open && props.row ? rowIdentity(props.row) : '',
);
const request = useObjectRequest(() => scope.value, reset);
const downloadRequest = useObjectRequest(() => scope.value);
function stopTimer() {
  if (timer) clearTimeout(timer);
  timer = undefined;
}
function reset() {
  stopTimer();
  record.value = undefined;
  message.value = '';
  keyword.value = '';
  autoRefresh.value = false;
  failed.value = false;
  loading.value = false;
  downloading.value = false;
}
async function load() {
  if (!props.open || !props.row || loading.value) return;
  stopTimer();
  const current = request.begin();
  const identity = requestIdentity(props.row);
  loading.value = true;
  failed.value = false;
  try {
    const [detail, log] = await Promise.all([
      getOpsInstance(identity),
      getOpsInstanceLog(identity),
    ]);
    if (!current()) return;
    record.value = detail;
    message.value = log.message;
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) {
      loading.value = false;
      if (record.value && !isActiveOpsInstance(record.value))
        autoRefresh.value = false;
      if (autoRefresh.value)
        timer = setTimeout(() => {
          void load();
        }, 3000);
    }
  }
}
async function download() {
  if (!props.row || downloading.value || !canDownload.value) return;
  const identity = requestIdentity(props.row);
  const current = downloadRequest.begin();
  downloading.value = true;
  try {
    const blob = await downloadOpsInstanceLog(identity);
    if (current())
      downloadFileFromBlobPart({
        fileName: `${props.row?.instanceName || identity.id}.log`,
        source: blob,
      });
  } finally {
    if (current()) downloading.value = false;
  }
}
watch(
  scope,
  () => {
    reset();
    if (scope.value) {
      autoRefresh.value =
        isActiveOpsInstance(props.row);
      void load();
    }
  },
  { immediate: true },
);
watch(autoRefresh, (enabled) => {
  stopTimer();
  if (enabled) void load();
});
onBeforeUnmount(stopTimer);
const date = displayDate;
</script>

<template>
  <Modal
    :open="open"
    title="查看日志"
    :width="row?.kind === 'JOB' ? '1200px' : '1040px'"
    :wrap-class-name="`qo-modal${row?.kind === 'JOB' ? ' qo-job-log' : ''}`"
    :style="{ top: '64px' }"
    :mask-closable="false"
    @cancel="emit('close')"
  >
    <div class="qo-log-summary">
      <table class="qo-info-table">
        <tbody>
          <tr>
            <th>{{ row?.kind === 'JOB' ? '作业名称' : '任务名称' }}</th>
            <td class="qo-ellipsis" :title="summary?.taskName">
              {{ row?.kind === 'JOB' ? row.instanceName : row?.taskName || summary?.taskName || '-' }}
            </td>
            <th>编号</th>
            <td>{{ summary?.id || row?.id }}</td>
            <th>执行状态</th>
            <td>
              <StatusBadge
                v-if="summary || row"
                :status="(summary || row)!.status"
                :queue-state="(summary || row)?.queueState"
              />
            </td>
          </tr>
          <tr>
            <th>开始时间</th>
            <td>{{ date(summary?.startTime || row?.startTime) }}</td>
            <th>已运行</th>
            <td>{{ formatDuration(summary?.duration ?? row?.duration) }}</td>
          </tr>
          <tr v-if="summary?.queueState">
            <th>队列状态</th><td>{{ queueStateLabel(summary.queueState) }}</td>
            <th>入队时间</th><td>{{ date(summary.queuedTime, true) }}</td>
            <th>计划时间</th><td>{{ date(summary.scheduledTime, true) }}</td>
          </tr>
          <tr v-if="summary?.queueReason"><th>原因</th><td colspan="5">{{ summary.queueReason }}</td></tr>
        </tbody>
      </table>
    </div>
    <div class="qo-log-toolbar">
      <Input
        v-model:value="keyword"
        placeholder="搜索日志内容"
        allow-clear
        aria-label="搜索日志内容"
      >
        <template #prefix><ReferenceIcon name="search" /></template>
      </Input>
      <div>
        <label>自动刷新<Switch
            v-model:checked="autoRefresh"
            size="small"
            aria-label="自动刷新日志"
/></label><Button
          v-if="canDownload"
          class="qo-download-button"
          size="small"
          :loading="downloading"
          @click="download"
        >
          <ReferenceIcon name="download" />下载日志
        </Button>
      </div>
    </div>
    <Alert v-if="failed" type="error" message="日志加载失败，请重试">
      <template #action>
        <Button size="small" @click="load">重试</Button>
      </template>
    </Alert>
    <Spin :spinning="loading">
      <LogViewer
        :message="message"
        :keyword="keyword"
        :follow-tail="autoRefresh"
        modal
      />
    </Spin>
    <template #footer><Button @click="emit('close')">关闭</Button></template>
  </Modal>
</template>
