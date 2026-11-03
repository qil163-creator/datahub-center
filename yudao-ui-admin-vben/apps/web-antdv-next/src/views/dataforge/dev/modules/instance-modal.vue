<script lang="ts" setup>
import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { computed, onBeforeUnmount, onDeactivated, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';
import { downloadFileFromBlobPart, formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Descriptions,
  DescriptionsItem,
  Input,
  message,
  Modal,
  Pagination,
  Switch,
  Table,
  TabPane,
  Tabs,
  Tag,
} from 'antdv-next';

import {
  downloadDevRecordLog,
  getDevRecord,
  getDevRecordPage,
  getDevRecordStatistics,
  getDevRecordTaskLog,
  stopDevRecord,
} from '#/api/dataforge/dev';

import {
  DEV_RECORD_STATUS_OPTIONS,
  DEV_TRIGGER_TYPE_OPTIONS,
  formatDuration,
  optionColor,
  optionLabel,
} from '../data';
import {
  appendTaskLogPage,
  createTaskLogState,
} from '../instance/task-log-state';

const props = defineProps<{ readOnly?: boolean }>();
const accessStore = useAccessStore();
const { hasAccessByCodes } = useAccess();
const canRun = computed(
  () => !props.readOnly && hasAccessByCodes(['dataforge:dev-task:run']),
);
function canInspect(row?: DataforgeDevApi.Record) {
  return (
    !row?.nativeProgram ||
    hasAccessByCodes(['dataforge:dev-task:execute-native'])
  );
}
const visible = ref(false);
const loading = ref(false);
const taskId = ref<DataforgeDevApi.Id>();
const records = ref<DataforgeDevApi.Record[]>([]);
const statistics = ref<DataforgeDevApi.RecordStatistics>();
const status = ref('ALL');
const pageNo = ref(1);
const pageSize = ref(10);
const total = ref(0);
let listSeq = 0;
let modalGeneration = 0;
let listTimer: ReturnType<typeof setTimeout> | undefined;
const activeStatuses = new Set([
  'CANCEL_REQUESTED',
  'RUNNING',
  'SUBMITTING',
  'UNKNOWN',
  'WAITING',
]);
function scheduleListRefresh() {
  if (listTimer) clearTimeout(listTimer);
  listTimer = undefined;
  if (
    !visible.value ||
    !records.value.some((item) => activeStatuses.has(item.status ?? ''))
  )
    return;
  listTimer = setTimeout(() => {
    listTimer = undefined;
    void load().catch(() => undefined);
  }, 3000);
}
const logVisible = ref(false);
const record = ref<DataforgeDevApi.Record>();
const logState = ref(createTaskLogState());
const logLoading = ref(false);
const autoRefresh = ref(false);
const keyword = ref('');
const downloading = ref(false);
let logSeq = 0;
let downloadSeq = 0;
let timer: ReturnType<typeof setTimeout> | undefined;
function logLevel(text: string) {
  if (/\bERROR\b/.test(text)) return 'error';
  if (/\bWARN\b/.test(text)) return 'warn';
  return 'info';
}
const logLines = computed(() =>
  (logState.value.content || record.value?.log || '')
    .split('\n')
    .map((text, index) => ({
      text,
      line: index + 1,
      level: logLevel(text),
    }))
    .filter(
      (line) =>
        !keyword.value ||
        line.text.toLowerCase().includes(keyword.value.toLowerCase()),
    ),
);
const taskName = computed(() => records.value[0]?.taskName || '数据开发任务');
const visibleLogLines = computed(
  () => logLines.value.filter((line) => line.text).length,
);
const columns = [
  { title: '编号', dataIndex: 'id', key: 'id', width: 85 },
  { title: '任务名称', dataIndex: 'taskName', key: 'taskName', width: 240 },
  { title: '执行类型', key: 'triggerType', width: 90 },
  { title: '执行状态', key: 'status', width: 110 },
  { title: '当前节点', key: 'currentNode', width: 100 },
  { title: '开始时间', key: 'startTime', width: 155 },
  { title: '运行时长', key: 'durationMs', width: 90 },
  { title: '操作', key: 'actions', width: 225, fixed: 'right' as const },
];
async function load() {
  if (taskId.value === undefined || !visible.value) return;
  if (listTimer) clearTimeout(listTimer);
  listTimer = undefined;
  const seq = ++listSeq;
  loading.value = true;
  try {
    const [result, stats] = await Promise.all([
      getDevRecordPage({
        taskId: taskId.value,
        pageNo: pageNo.value,
        pageSize: pageSize.value,
        status: status.value === 'ALL' ? undefined : status.value,
      }),
      getDevRecordStatistics(taskId.value),
    ]);
    if (seq !== listSeq || !visible.value) return;
    records.value = result.list;
    total.value = result.total;
    statistics.value = stats;
  } finally {
    if (seq === listSeq) {
      loading.value = false;
      scheduleListRefresh();
    }
  }
}
async function open(id: DataforgeDevApi.Id, recordId?: DataforgeDevApi.Id) {
  close();
  visible.value = true;
  taskId.value = id;
  pageNo.value = 1;
  status.value = 'ALL';
  const request = load();
  const seq = listSeq;
  await request;
  if (
    seq === listSeq &&
    visible.value &&
    recordId !== undefined &&
    String(taskId.value) === String(id)
  )
    await openLog(recordId);
}
function closeLog() {
  ++logSeq;
  ++downloadSeq;
  logVisible.value = false;
  autoRefresh.value = false;
  if (timer) clearTimeout(timer);
  timer = undefined;
  logLoading.value = false;
  downloading.value = false;
  record.value = undefined;
  logState.value = createTaskLogState();
}
function close() {
  ++modalGeneration;
  if (listTimer) clearTimeout(listTimer);
  listTimer = undefined;
  ++listSeq;
  visible.value = false;
  loading.value = false;
  records.value = [];
  statistics.value = undefined;
  closeLog();
}
async function refreshLog() {
  if (
    !record.value ||
    logLoading.value ||
    !logVisible.value ||
    !canInspect(record.value)
  )
    return;
  if (timer) clearTimeout(timer);
  timer = undefined;
  const id = record.value.id;
  const seq = logSeq;
  logLoading.value = true;
  try {
    const [detail, page] = await Promise.all([
      getDevRecord(id),
      getDevRecordTaskLog({
        id,
        skipLineNum: logState.value.cursor,
        limit: 300,
      }),
    ]);
    if (seq !== logSeq || !logVisible.value) return;
    record.value = detail;
    records.value = records.value.map((item) =>
      String(item.id) === String(detail.id) ? detail : item,
    );
    appendTaskLogPage(logState.value, page);
    if (!activeStatuses.has(detail.status ?? '') && !page.mayHaveMore)
      autoRefresh.value = false;
  } finally {
    if (seq === logSeq) {
      logLoading.value = false;
      if (autoRefresh.value && logVisible.value)
        timer = setTimeout(
          () => void refreshLog().catch(() => undefined),
          3000,
        );
    }
  }
}
async function openLog(id: DataforgeDevApi.Id) {
  if (!canInspect(records.value.find((row) => String(row.id) === String(id))))
    return;
  closeLog();
  const seq = logSeq;
  keyword.value = '';
  logVisible.value = true;
  logLoading.value = true;
  try {
    const detail = await getDevRecord(id);
    if (seq !== logSeq || !logVisible.value) return;
    record.value = detail;
    autoRefresh.value = activeStatuses.has(detail.status ?? '');
  } finally {
    if (seq === logSeq) logLoading.value = false;
  }
  if (seq === logSeq && record.value) await refreshLog();
}
async function download(id: DataforgeDevApi.Id) {
  if (
    downloading.value ||
    !canInspect(records.value.find((row) => String(row.id) === String(id)))
  )
    return;
  const seq = ++downloadSeq;
  downloading.value = true;
  try {
    const blob = await downloadDevRecordLog(id);
    if (seq !== downloadSeq || !visible.value) return;
    downloadFileFromBlobPart({
      fileName: `data-development-${String(id).replaceAll(/[^\d]/g, '')}.log`,
      source: blob,
    });
  } finally {
    if (seq === downloadSeq) downloading.value = false;
  }
}
function stop(id: DataforgeDevApi.Id) {
  const row = records.value.find((item) => String(item.id) === String(id));
  if (!canRun.value || !canInspect(row)) return;
  const generation = modalGeneration;
  Modal.confirm({
    title: '停止实例',
    content: '向执行引擎发送停止请求，实际结果会在实例中更新。',
    okText: '确认',
    cancelText: '取消',
    async onOk() {
      if (
        generation !== modalGeneration ||
        !visible.value ||
        !canRun.value ||
        !canInspect(row)
      )
        return;
      await stopDevRecord(id);
      if (generation === modalGeneration && visible.value) {
        message.success(
          row?.nativeProgram ? '已请求停止，等待核对结果' : '停止指令已下发',
        );
        await load();
      }
    },
  });
}
watch(status, () => {
  pageNo.value = 1;
  void load();
});
watch(autoRefresh, (enabled) => {
  if (timer) clearTimeout(timer);
  timer = undefined;
  if (enabled) void refreshLog().catch(() => undefined);
});
watch(() => [accessStore.accessToken, accessStore.refreshToken], close, {
  flush: 'sync',
});
onDeactivated(close);
onBeforeUnmount(close);
defineExpose({ open, close });
</script>

<template>
  <Modal
    v-model:open="visible"
    title="运行实例"
    :width="1160"
    destroy-on-hidden
    @cancel="close"
  >
    <div class="instance-summary">
      <span class="summary-icon"><IconifyIcon icon="lucide:workflow" /></span>
      <div class="summary-copy">
        <strong>{{ taskName }}</strong>
        <span>任务 #{{ taskId }} · 查看执行记录与运行日志</span>
      </div>
    </div>
    <div class="instance-statistics">
      <div class="stat-item">
        <IconifyIcon class="running" icon="lucide:activity" />
        <span>运行中</span>
        <b class="running">{{ statistics?.running ?? '-' }}</b>
      </div>
      <div class="stat-item">
        <IconifyIcon class="success" icon="lucide:circle-check" />
        <span>执行成功</span>
        <b class="success">{{ statistics?.success ?? '-' }}</b>
      </div>
      <div class="stat-item">
        <IconifyIcon class="failed" icon="lucide:circle-x" />
        <span>执行失败</span>
        <b class="failed">{{ statistics?.failed ?? '-' }}</b>
      </div>
      <div class="statistics-refresh">
        <span>
          统计更新：{{
            statistics?.refreshedAt
              ? formatDateTime(statistics.refreshedAt)
              : '-'
          }}
        </span>
        <Button
          type="text"
          size="small"
          :loading="loading"
          aria-label="刷新实例"
          @click="load"
        >
          <IconifyIcon icon="lucide:refresh-cw" />
        </Button>
      </div>
    </div>
    <div class="instance-list-panel">
      <div class="instance-list-header">
        <Tabs v-model:active-key="status" class="instance-tabs">
          <TabPane key="ALL" tab="全部实例" />
          <TabPane key="RUNNING" tab="运行中" />
          <TabPane key="SUCCESS" tab="成功" />
          <TabPane key="FAILED" tab="失败" />
        </Tabs>
        <span class="list-count">共 {{ total }} 条记录</span>
      </div>
      <Table
        :columns="columns"
        :data-source="records"
        :row-key="(row: DataforgeDevApi.Record) => String(row.id)"
        :loading="loading"
        :pagination="false"
        :scroll="{ x: 1095, y: 420 }"
        size="small"
        class="instance-table"
      >
        <template #bodyCell="{ column, record: row }">
          <template v-if="column.key === 'id'">
            <span class="instance-id">#{{ row.id }}</span>
          </template>
          <template v-else-if="column.key === 'taskName'">
            <div class="table-task-name">{{ row.taskName || '-' }}</div>
            <div class="table-task-meta">
              运行版本 {{ row.versionId ? `#${row.versionId}` : '未记录' }}
            </div>
          </template>
          <template v-else-if="column.key === 'triggerType'">
            <Tag color="blue">
              {{ optionLabel(DEV_TRIGGER_TYPE_OPTIONS, row.triggerType) }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'status'">
            <Tag :color="optionColor(DEV_RECORD_STATUS_OPTIONS, row.status)">
              {{ optionLabel(DEV_RECORD_STATUS_OPTIONS, row.status) }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'currentNode'">
            <span class="current-node">
              {{
                row.waitReason || (row.status === 'RUNNING' ? '执行作业' : '-')
              }}
            </span>
          </template>
          <template v-else-if="column.key === 'startTime'">
            {{ row.startTime ? formatDateTime(row.startTime) : '-' }}
          </template>
          <template v-else-if="column.key === 'durationMs'">
            <span class="duration-value">
              {{ formatDuration(row.durationMs) }}
            </span>
          </template>
          <template v-else-if="column.key === 'actions'">
            <div class="instance-actions">
              <Button
                v-if="canInspect(row)"
                type="link"
                size="small"
                @click="openLog(row.id)"
              >
                <IconifyIcon icon="lucide:file-text" />
                查看日志
              </Button>
              <Button
                v-if="canInspect(row)"
                type="link"
                size="small"
                :loading="downloading"
                @click="download(row.id)"
              >
                <IconifyIcon icon="lucide:download" />
                下载日志
              </Button>
              <Button
                v-if="
                  canRun &&
                  canInspect(row) &&
                  ['RUNNING', 'SUBMITTING', 'WAITING', 'UNKNOWN'].includes(
                    row.status ?? '',
                  )
                "
                type="link"
                danger
                size="small"
                @click="stop(row.id)"
              >
                <IconifyIcon icon="lucide:square" />
                停止
              </Button>
              <span v-if="!canInspect(row)" class="table-task-meta">
                原生执行需单独授权
              </span>
            </div>
          </template>
        </template>
      </Table>
      <div class="instance-pagination">
        <span>执行成功后仍需确认质量验收结果</span>
        <Pagination
          v-model:current="pageNo"
          v-model:page-size="pageSize"
          :total="total"
          :show-total="(count) => `共 ${count} 条`"
          size="small"
          show-size-changer
          show-quick-jumper
          @change="load"
        />
      </div>
    </div>
    <template #footer><Button @click="close">关闭</Button></template>
  </Modal>
  <Modal
    v-model:open="logVisible"
    title="运行日志"
    :width="1160"
    destroy-on-hidden
    @cancel="closeLog"
  >
    <div class="instance-summary log-summary">
      <span class="summary-icon"><IconifyIcon icon="lucide:terminal" /></span>
      <div class="summary-copy">
        <strong>{{ record?.taskName || '正在读取实例' }}</strong>
        <span>实例 #{{ record?.id ?? '-' }}</span>
      </div>
      <Tag :color="optionColor(DEV_RECORD_STATUS_OPTIONS, record?.status)">
        {{ optionLabel(DEV_RECORD_STATUS_OPTIONS, record?.status) }}
      </Tag>
    </div>
    <Descriptions bordered size="small" :column="3" class="log-descriptions">
      <DescriptionsItem label="开始时间">
        {{ record?.startTime ? formatDateTime(record.startTime) : '-' }}
      </DescriptionsItem>
      <DescriptionsItem label="结束时间">
        {{ record?.endTime ? formatDateTime(record.endTime) : '-' }}
      </DescriptionsItem>
      <DescriptionsItem label="运行时长">
        {{ formatDuration(record?.durationMs) }}
      </DescriptionsItem>
      <DescriptionsItem label="触发方式">
        {{ optionLabel(DEV_TRIGGER_TYPE_OPTIONS, record?.triggerType) }}
      </DescriptionsItem>
      <DescriptionsItem label="运行版本">
        {{ record?.versionId ? `#${record.versionId}` : '历史实例未记录' }}
      </DescriptionsItem>
      <DescriptionsItem label="当前节点">
        {{
          record?.waitReason ||
          (record?.status === 'RUNNING' ? '执行作业' : '-')
        }}
      </DescriptionsItem>
    </Descriptions>
    <Alert
      v-if="record?.lastError"
      :message="record.lastError"
      show-icon
      type="error"
      class="log-alert"
    />
    <div class="log-console">
      <div class="log-toolbar">
        <span class="console-title">
          <IconifyIcon icon="lucide:file-text" />
          日志控制台
        </span>
        <Input
          v-model:value="keyword"
          allow-clear
          placeholder="搜索日志内容"
          size="small"
          class="log-search"
        >
          <template #prefix><IconifyIcon icon="lucide:search" /></template>
        </Input>
        <span class="refresh-switch">
          自动刷新
          <Switch v-model:checked="autoRefresh" size="small" />
        </span>
        <Button :loading="logLoading" size="small" @click="refreshLog">
          <IconifyIcon icon="lucide:refresh-cw" />
          刷新
        </Button>
        <Button
          type="primary"
          size="small"
          :disabled="!record"
          :loading="downloading"
          @click="record && download(record.id)"
        >
          <IconifyIcon icon="lucide:download" />
          下载日志
        </Button>
      </div>
      <Alert
        v-if="logState.statusMessage"
        :message="logState.statusMessage"
        type="info"
        show-icon
        class="log-alert"
      />
      <Alert
        v-if="logState.truncated"
        message="预览保留最近的日志片段，下载日志可查看服务端完整输出。"
        type="warning"
        show-icon
        class="log-alert"
      />
      <div class="execution-log" :aria-busy="logLoading">
        <div v-for="line in logLines" :key="line.line" class="log-line">
          <span class="log-number">{{ line.line }}</span>
          <span class="log-text" :class="[line.level]">{{ line.text }}</span>
        </div>
        <div v-if="!visibleLogLines" class="log-empty">
          <IconifyIcon
            :icon="logLoading ? 'lucide:loader-circle' : 'lucide:file-search'"
            :class="{ 'animate-spin': logLoading }"
          />
          <strong>
            {{
              logLoading
                ? '正在读取日志…'
                : keyword
                  ? '没有匹配的日志'
                  : '暂无日志输出'
            }}
          </strong>
          <span>
            {{
              keyword
                ? '调整搜索内容，或清空关键词查看全部日志。'
                : '刷新日志以查看任务的最新执行输出。'
            }}
          </span>
        </div>
      </div>
      <div class="log-footer">
        <span>
          <IconifyIcon icon="lucide:text" />
          {{ keyword ? '匹配' : '当前显示' }} {{ visibleLogLines }} 行
        </span>
        <span>{{ autoRefresh ? '每 3 秒自动刷新' : '自动刷新已关闭' }}</span>
        <span v-if="!logState.mayHaveMore" class="log-end">
          <IconifyIcon icon="lucide:check" />
          已读取到日志末尾
        </span>
      </div>
    </div>
    <template #footer>
      <Button
        v-if="logState.mayHaveMore"
        :loading="logLoading"
        @click="refreshLog"
      >
        <IconifyIcon icon="lucide:chevrons-down" />
        加载后续日志
      </Button>
      <Button @click="closeLog">关闭</Button>
    </template>
  </Modal>
</template>

<style scoped>
.instance-summary {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 3px 0 16px;
}

.summary-icon {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  font-size: 18px;
  color: hsl(var(--primary));
  background: hsl(var(--primary) / 8%);
  border: 1px solid hsl(var(--primary) / 12%);
  border-radius: 4px;
}

.summary-copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.summary-copy strong {
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
}

.summary-copy > span,
.list-count,
.table-task-meta,
.statistics-refresh,
.instance-pagination > span {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.instance-statistics {
  display: flex;
  gap: 24px;
  align-items: center;
  padding: 12px 16px;
  margin-bottom: 14px;
  background: hsl(var(--muted) / 35%);
  border: 1px solid hsl(var(--border));
  border-radius: 4px;
}

.stat-item {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 13px;
}

.stat-item + .stat-item {
  padding-left: 24px;
  border-left: 1px solid hsl(var(--border));
}

.stat-item b {
  font-variant-numeric: tabular-nums;
}

.running {
  color: hsl(var(--primary));
}

.success {
  color: #52a95f;
}

.failed {
  color: #e15f60;
}

.statistics-refresh {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-left: auto;
}

.instance-list-panel,
.log-console {
  overflow: hidden;
  border: 1px solid hsl(var(--border));
  border-radius: 4px;
}

.instance-list-header {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  padding: 0 14px;
}

.instance-tabs {
  flex: 1;
  min-width: 0;
}

.instance-tabs :deep(.ant-tabs-nav) {
  margin-bottom: 0;
}

.instance-table {
  min-height: 385px;
}

.instance-table :deep(.ant-table-thead > tr > th) {
  font-weight: 500;
  color: hsl(var(--muted-foreground));
  background: hsl(var(--muted) / 65%);
}

.instance-table :deep(.ant-table-tbody > tr:nth-child(even) > td) {
  background: hsl(var(--muted) / 25%);
}

.instance-table :deep(.ant-table-tbody > tr > td) {
  padding-top: 14px;
  padding-bottom: 14px;
}

.instance-id,
.duration-value {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.instance-id {
  color: hsl(var(--muted-foreground));
}

.table-task-name {
  margin-bottom: 3px;
  font-weight: 500;
}

.current-node {
  font-size: 12px;
  overflow-wrap: anywhere;
}

.instance-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 2px;
}

.instance-actions :deep(.ant-btn) {
  gap: 4px;
  padding: 0 3px;
  font-size: 12px;
}

.instance-pagination {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  padding: 13px 14px;
  border-top: 1px solid hsl(var(--border));
}

.log-summary {
  padding-bottom: 12px;
}

.log-descriptions {
  margin-bottom: 14px;
}

.log-descriptions :deep(.ant-descriptions-item-label) {
  width: 90px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.log-descriptions :deep(.ant-descriptions-item-content) {
  font-size: 12px;
}

.log-toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 10px 12px;
  background: hsl(var(--muted) / 35%);
  border-bottom: 1px solid hsl(var(--border));
}

.console-title {
  display: flex;
  gap: 7px;
  align-items: center;
  margin-right: auto;
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
}

.console-title > :first-child {
  color: hsl(var(--primary));
}

.log-search {
  width: 230px;
}

.refresh-switch {
  display: flex;
  gap: 7px;
  align-items: center;
  font-size: 12px;
  white-space: nowrap;
}

.log-alert {
  margin-bottom: 10px;
}

.log-console > .log-alert {
  margin: 10px 12px;
}

.execution-log {
  height: 385px;
  padding: 14px 16px;
  overflow: auto;
  font:
    12px/1.9 Consolas,
    'Courier New',
    monospace;
  color: #cbd5e1;
  background: #162234;
}

.log-line {
  display: flex;
  gap: 14px;
}

.log-number {
  flex-shrink: 0;
  width: 36px;
  color: #60758e;
  text-align: right;
  user-select: none;
}

.log-text {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.error {
  color: #ff9695;
}

.warn {
  color: #f0c56a;
}

.log-empty {
  display: flex;
  flex-direction: column;
  gap: 9px;
  align-items: center;
  justify-content: center;
  min-height: 300px;
  font-family: inherit;
  color: #8fa1b8;
  text-align: center;
}

.log-empty > :first-child {
  margin-bottom: 3px;
  font-size: 28px;
  color: #60758e;
}

.log-empty strong {
  font-size: 13px;
  font-weight: 500;
}

.log-footer {
  display: flex;
  gap: 20px;
  align-items: center;
  padding: 9px 12px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
  border-top: 1px solid hsl(var(--border));
}

.log-footer > span {
  display: flex;
  gap: 5px;
  align-items: center;
}

.log-end {
  margin-left: auto;
}

@media (max-width: 900px) {
  .instance-statistics,
  .log-toolbar,
  .instance-pagination,
  .log-footer {
    flex-wrap: wrap;
  }

  .statistics-refresh {
    width: 100%;
    margin-left: 0;
  }

  .log-search {
    flex: 1;
    min-width: 180px;
  }
}

@media (max-width: 600px) {
  .instance-statistics {
    gap: 12px;
  }

  .stat-item + .stat-item {
    padding-left: 12px;
  }

  .instance-pagination > span,
  .list-count {
    display: none;
  }
}
</style>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
