<script setup lang="ts">
import type { DataforgeDevApi } from '#/api/dataforge/dev';

import {
  computed,
  nextTick,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  ref,
  watch,
} from 'vue';

import { useAccess } from '@vben/access';
import { useAccessStore } from '@vben/stores';
import { downloadFileFromBlobPart, formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Checkbox,
  Input,
  Pagination,
  Table,
  Tag,
} from 'antdv-next';

import {
  downloadDevRecordLog,
  getDevRecord,
  getDevRecordPage,
  getDevRecordTaskLog,
} from '#/api/dataforge/dev';
import { createLatestWins } from '#/utils/resource-id';

import {
  DEV_RECORD_STATUS_OPTIONS,
  DEV_TRIGGER_TYPE_OPTIONS,
  optionColor,
  optionLabel,
} from '../data';
import {
  appendTaskLogPage,
  createTaskLogState,
} from '../instance/task-log-state';
import { DEV_REFERENCE_ASSETS } from '../reference-assets';

const props = defineProps<{
  mode: 'history' | 'log';
  recordId?: DataforgeDevApi.Id;
  taskId?: DataforgeDevApi.Id;
  taskName?: string;
}>();
const emit = defineEmits<{ collapse: []; openLog: [id: DataforgeDevApi.Id] }>();
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const rows = ref<DataforgeDevApi.Record[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(10);
const historyLoading = ref(false);
const statusLoading = ref(false);
const logLoading = ref(false);
const loading = computed(
  () => historyLoading.value || statusLoading.value || logLoading.value,
);
const failed = ref(false);
const logRecord = ref<DataforgeDevApi.Record>();
const logState = ref(createTaskLogState());
const follow = ref(true);
const keyword = ref('');
const downloading = ref(false);
const consoleElement = ref<HTMLElement>();
let downloadEpoch = 0;
const historyGuard = createLatestWins();
const statusGuard = createLatestWins();
const logGuard = createLatestWins();
function invalidateRequests() {
  historyGuard.invalidate();
  statusGuard.invalidate();
  logGuard.invalidate();
  historyLoading.value = false;
  statusLoading.value = false;
  logLoading.value = false;
}
const activeStatuses = new Set([
  'CANCEL_REQUESTED',
  'RUNNING',
  'SUBMITTING',
  'UNKNOWN',
  'WAITING',
]);
let timer: ReturnType<typeof setTimeout> | undefined;
let alive = true;
const columns = [
  { title: '编号', dataIndex: 'id', key: 'id', width: 80 },
  { title: '节点实例名称', key: 'nodeName', width: 310 },
  { title: '任务名称', key: 'taskName', width: 330 },
  { title: '执行类型', key: 'triggerType', width: 120 },
  { title: '执行状态', key: 'status', width: 110 },
  { title: '开始时间', key: 'startTime', width: 170 },
  { title: '结束时间', key: 'endTime', width: 170 },
  { title: '操作', key: 'actions', width: 205, fixed: 'right' as const },
];
const logText = computed(() =>
  (logState.value.content || logRecord.value?.log || '')
    .split('\n')
    .filter(
      (line) =>
        !keyword.value ||
        line.toLowerCase().includes(keyword.value.toLowerCase()),
    )
    .join('\n'),
);
function clearTimer() {
  if (timer) clearTimeout(timer);
  timer = undefined;
}
function schedule() {
  clearTimer();
  if (!alive || !props.taskId) return;
  const shouldPoll =
    props.mode === 'history'
      ? rows.value.some((row) => activeStatuses.has(row.status ?? ''))
      : activeStatuses.has(logRecord.value?.status ?? '') ||
        (follow.value && logState.value.mayHaveMore);
  if (shouldPoll)
    timer = setTimeout(() => void refresh().catch(() => undefined), 3000);
}
/** 状态请求与日志游标请求分开；日志等待或暂停追尾不能阻塞运行/停止状态刷新。 */
async function refresh(forceLog = false) {
  if (!alive || !props.taskId) return;
  clearTimer();
  if (props.mode === 'history') {
    if (historyLoading.value) return;
    const request = historyGuard.next();
    historyLoading.value = true;
    failed.value = false;
    try {
      const result = await getDevRecordPage({
        taskId: props.taskId,
        pageNo: page.value,
        pageSize: pageSize.value,
      });
      if (!request.isCurrent()) return;
      rows.value = result.list;
      total.value = result.total;
    } catch {
      if (request.isCurrent()) failed.value = true;
    } finally {
      if (request.isCurrent()) {
        historyLoading.value = false;
        schedule();
      }
    }
    return;
  }
  if (statusLoading.value) return;
  const request = statusGuard.next();
  statusLoading.value = true;
  try {
    let id = props.recordId;
    if (id === undefined) {
      const result = await getDevRecordPage({
        taskId: props.taskId,
        pageNo: 1,
        pageSize: 1,
      });
      if (!request.isCurrent()) return;
      id = result.list[0]?.id;
    }
    if (id === undefined) {
      logState.value.mayHaveMore = false;
      return;
    }
    const detail = await getDevRecord(id);
    if (!request.isCurrent()) return;
    if (logRecord.value && String(logRecord.value.id) !== String(detail.id)) {
      logGuard.invalidate();
      logLoading.value = false;
      logState.value = createTaskLogState();
    }
    logRecord.value = detail;
    if (
      detail.nativeProgram &&
      !hasAccessByCodes(['dataforge:dev-task:execute-native'])
    ) {
      logState.value.statusMessage = '原生执行日志需单独授权';
      logState.value.mayHaveMore = false;
      follow.value = false;
      return;
    }
    // 同一上下文的日志未完成时只刷新状态，不重叠读取相同游标。
    if (follow.value || forceLog) void refreshLog(id);
  } catch {
    if (request.isCurrent()) failed.value = true;
  } finally {
    if (request.isCurrent()) {
      statusLoading.value = false;
      schedule();
    }
  }
}
async function refreshLog(id: DataforgeDevApi.Id) {
  if (logLoading.value || !alive) return;
  const request = logGuard.next();
  logLoading.value = true;
  failed.value = false;
  try {
    const result = await getDevRecordTaskLog({
      id,
      skipLineNum: logState.value.cursor,
      limit: 300,
    });
    if (!request.isCurrent()) return;
    appendTaskLogPage(logState.value, result);
    if (!result.available) logState.value.mayHaveMore = false;
  } catch {
    if (request.isCurrent()) failed.value = true;
  } finally {
    if (request.isCurrent()) {
      logLoading.value = false;
      schedule();
    }
  }
}
async function download(id: DataforgeDevApi.Id) {
  if (downloading.value) return;
  const task = String(props.taskId);
  const epoch = downloadEpoch;
  downloading.value = true;
  try {
    const blob = await downloadDevRecordLog(id);
    if (!alive || epoch !== downloadEpoch || task !== String(props.taskId))
      return;
    downloadFileFromBlobPart({
      fileName: `data-development-${id}.log`,
      source: blob,
    });
  } finally {
    if (epoch === downloadEpoch && task === String(props.taskId))
      downloading.value = false;
  }
}
watch(
  () => [
    access.accessToken,
    access.refreshToken,
    props.taskId,
    props.mode,
    props.recordId,
  ],
  () => {
    ++downloadEpoch;
    downloading.value = false;
    clearTimer();
    invalidateRequests();
    failed.value = false;
    rows.value = [];
    total.value = 0;
    page.value = 1;
    logRecord.value = undefined;
    logState.value = createTaskLogState();
    follow.value = true;
    void refresh();
  },
  { immediate: true },
);
watch(follow, schedule);
function dispose() {
  ++downloadEpoch;
  alive = false;
  clearTimer();
  invalidateRequests();
}
watch([logText, follow], async () => {
  if (!follow.value) return;
  await nextTick();
  if (consoleElement.value)
    consoleElement.value.scrollTop = consoleElement.value.scrollHeight;
});
onActivated(() => {
  if (alive) return;
  alive = true;
  void refresh();
});
onDeactivated(dispose);
onBeforeUnmount(dispose);
defineExpose({ refresh });
</script>

<template>
  <section
    class="execution-panel"
    :aria-label="mode === 'history' ? '执行历史记录' : '日志控制台'"
  >
    <header>
      <h3>{{ mode === 'history' ? '执行历史记录' : '日志控制台' }}</h3>
      <Button type="text" aria-label="收起执行面板" @click="emit('collapse')">
        −
      </Button>
    </header>

    <template v-if="mode === 'history'">
      <div class="execution-tools">
        <Button class="reference-button" :loading="loading" @click="refresh()">
          <img :src="DEV_REFERENCE_ASSETS.refresh" alt="" />刷新
        </Button>
      </div>
      <Table
        :columns="columns"
        :data-source="rows"
        :loading="loading"
        :row-key="(row: DataforgeDevApi.Record) => String(row.id)"
        :pagination="false"
        :scroll="{ x: 1600, y: 210 }"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'nodeName'">
            {{
              record.dsInstanceId ? `#${record.dsInstanceId}` : `#${record.id}`
            }}
          </template>
          <template v-else-if="column.key === 'taskName'">
            {{ record.taskName || taskName || '-' }}
          </template>
          <Tag v-else-if="column.key === 'triggerType'" color="blue">
            {{ optionLabel(DEV_TRIGGER_TYPE_OPTIONS, record.triggerType) }}
          </Tag>
          <Tag
            v-else-if="column.key === 'status'"
            :color="optionColor(DEV_RECORD_STATUS_OPTIONS, record.status)"
          >
            {{ optionLabel(DEV_RECORD_STATUS_OPTIONS, record.status) }}
          </Tag>
          <template
            v-else-if="column.key === 'startTime' || column.key === 'endTime'"
          >
            {{ record[column.key] ? formatDateTime(record[column.key]) : '-' }}
          </template>
          <div v-else-if="column.key === 'actions'" class="execution-actions">
            <Button type="link" @click="emit('openLog', record.id)">
              <img :src="DEV_REFERENCE_ASSETS.eye" alt="" />查看日志 </Button
            ><Button
              class="download-action"
              type="link"
              :loading="downloading"
              @click="download(record.id)"
            >
              ↓ 下载日志
            </Button>
          </div>
        </template>
      </Table>
      <Pagination
        v-model:current="page"
        v-model:page-size="pageSize"
        :total="total"
        size="small"
        show-size-changer
        @change="refresh()"
      />
    </template>
    <template v-else>
      <div v-if="logRecord" class="execution-tools">
        <Tag :color="optionColor(DEV_RECORD_STATUS_OPTIONS, logRecord.status)">
          {{ optionLabel(DEV_RECORD_STATUS_OPTIONS, logRecord.status) }} </Tag
        ><Input
          v-model:value="keyword"
          placeholder="搜索日志内容"
          allow-clear
        /><Checkbox v-model:checked="follow">追尾</Checkbox
        ><Button :loading="loading" @click="refresh(true)">刷新</Button
        ><Button :loading="downloading" @click="download(logRecord!.id)">
          下载日志
        </Button>
      </div>
      <Alert
        v-if="logState.statusMessage"
        :message="logState.statusMessage"
        type="info"
        show-icon
      />
      <Alert
        v-if="logState.truncated"
        message="仅保留最近的日志片段，完整日志请下载查阅。"
        type="warning"
        show-icon
      />
      <pre
        v-if="logText"
        ref="consoleElement"
        class="execution-console"
        :class="{ following: follow }"
        >{{ logText }}</pre>
      <div v-else class="execution-empty">
        <img :src="DEV_REFERENCE_ASSETS.emptyLog" alt="" /><span>{{
          loading ? '正在读取日志…' : '暂无日志'
        }}</span>
      </div>
    </template>
  </section>
</template>

<style scoped>
.execution-panel {
  display: flex;
  flex-direction: column;
  width: 100%;
  min-width: 0;
  max-width: 100%;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  color: #606266;
  background: hsl(var(--background));
  border-top: 1px solid #e6e7ed;
}

.execution-panel > header {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  height: 40px;
  padding: 0 20px;
  background: #f8f8fa;
  border-bottom: 1px solid #eee;
}

.execution-panel h3 {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 0;
  font-size: 16px;
  font-weight: 500;
  color: #303133;
}

.execution-panel h3::before {
  width: 6px;
  height: 16px;
  content: '';
  background: #2d63ff;
  border-radius: 4px;
}

.execution-tools {
  display: flex;
  flex: none;
  gap: 10px;
  align-items: center;
  min-height: 58px;
  padding: 12px 16px;
}

.execution-tools img,
.execution-actions img {
  width: 16px;
  height: 16px;
  object-fit: contain;
}

.execution-tools :deep(.ant-input-affix-wrapper) {
  max-width: 240px;
}

.reference-button {
  color: #2d63ff;
  background: #f0f6ff;
  border-color: #a2b8ff;
  border-radius: 1px;
}

.execution-panel :deep(.ant-table-wrapper) {
  flex: 1;
  width: 100%;
  min-width: 0;
  max-width: 100%;
  min-height: 0;
  padding: 0 20px;
  overflow: auto;
}

.execution-panel :deep(.ant-table-thead > tr > th) {
  color: #606266;
  background: #f0f1f7;
}

.execution-panel :deep(.ant-table-tbody > tr:nth-child(even) > td) {
  background: #fafafa;
}

.execution-actions {
  display: flex;
  align-items: center;
  white-space: nowrap;
}

.execution-actions :deep(.ant-btn) {
  gap: 4px;
  padding: 0 4px;
  font-size: 13px;
}

.execution-actions .download-action {
  color: #e6a23c;
}

.execution-panel > :deep(.ant-pagination) {
  flex: none;
  padding: 8px 20px;
  margin: 0;
  text-align: right;
}

.execution-console {
  flex: 1;
  min-width: 0;
  max-width: 100%;
  min-height: 0;
  padding: 12px 20px;
  margin: 0;
  overflow: auto;
  font-family: ui-monospace, monospace;
  font-size: 12px;
  line-height: 20px;
  white-space: pre-wrap;
}

.execution-empty {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  align-items: center;
  justify-content: center;
  min-height: 150px;
  color: #909399;
}

.execution-empty img {
  width: 195px;
  max-height: 130px;
  object-fit: contain;
}

@media (max-width: 900px) {
  .execution-tools {
    flex-wrap: wrap;
  }
}
</style>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
