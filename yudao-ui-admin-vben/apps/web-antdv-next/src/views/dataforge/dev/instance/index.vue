<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';
import { formatDate, formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Checkbox,
  Descriptions,
  DescriptionsItem,
  Empty,
  Input,
  message,
  Modal,
  Select,
  Tag,
} from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteDevRecord,
  getDevRecord,
  getDevRecordPage,
  getDevRecordTaskLog,
  QUALITY_STATUS_LABELS,
  resolveDevRecordQuality,
  resolveUnknownDevRecord,
  stopDevRecord,
} from '#/api/dataforge/dev';
import LogViewer from '#/components/monaco/log-viewer.vue';
import PageGuide from '#/components/page-guide/index.vue';
import { $t } from '#/locales';
import { parseResourceId } from '#/utils/resource-id';

import {
  DEV_RECORD_STATUS_OPTIONS,
  DEV_TRIGGER_TYPE_OPTIONS,
  formatDuration,
  optionColor,
  optionLabel,
  useInstanceGridColumns,
  useInstanceGridFormSchema,
} from '../data';
import { appendTaskLogPage, createTaskLogState } from './task-log-state';

defineOptions({ name: 'DataforgeDevInstance' });
const route = useRoute();
const router = useRouter();
const accessStore = useAccessStore();
const { hasAccessByCodes } = useAccess();
/** 操作能力来自菜单按钮权限；接口仍是最终授权边界。 */
const canDevelop = hasAccessByCodes(['dataforge:dev-task:save']);
const canOperate = hasAccessByCodes(['dataforge:dev-task:run']);
function canInspect(row: Pick<DataforgeDevApi.Record, 'nativeProgram'>) {
  return (
    !row.nativeProgram ||
    hasAccessByCodes(['dataforge:dev-task:execute-native'])
  );
}
const activeStatuses = new Set([
  'CANCEL_REQUESTED',
  'RUNNING',
  'SUBMITTING',
  'UNKNOWN',
  'WAITING',
]);
async function loadInstances() {
  void gridApi.query();
  const id = parseResourceId(route.query.recordId);
  if (id) await openLog({ id });
}
onMounted(loadInstances);
watch(
  () => [route.query.recordId, route.query.taskId],
  ([raw]) => {
    void gridApi.query();
    const id = parseResourceId(raw);
    if (id) {
      void openLog({ id });
    } else {
      stopLogTimer();
      logVisible.value = false;
      logRecord.value = undefined;
    }
  },
);
watch(
  () => [accessStore.accessToken, accessStore.refreshToken],
  () => {
    stopLogTimer();
    logVisible.value = false;
    logRecord.value = undefined;
    void loadInstances();
  },
);

const resolutionVisible = ref(false);
const resolutionRecord = ref<DataforgeDevApi.Record>();
const resolutionReason = ref('');
const confirmed = ref(false);
const resolutionStatus = ref<'FAILED' | 'STOPPED' | 'SUCCESS'>('FAILED');
const resolving = ref(false);
const resolvingQuality = ref(false);
function openResolution(row: DataforgeDevApi.Record) {
  if (!hasAccessByCodes(['dataforge:dev-task:run']) || !canInspect(row)) return;
  resolutionRecord.value = row;
  resolvingQuality.value =
    row.status === 'SUCCESS' && row.qualityStatus === 'UNKNOWN';
  resolutionReason.value = '';
  confirmed.value = false;
  resolutionStatus.value = 'FAILED';
  resolutionVisible.value = true;
}
async function resolveUnknown() {
  if (
    !hasAccessByCodes(['dataforge:dev-task:run']) ||
    !resolutionRecord.value ||
    !canInspect(resolutionRecord.value)
  )
    return;
  if (
    !confirmed.value ||
    !resolutionReason.value.trim() ||
    !resolutionRecord.value
  ) {
    message.warning('请确认已核查引擎与数据产出并填写依据');
    return;
  }
  resolving.value = true;
  try {
    await (resolvingQuality.value
      ? resolveDevRecordQuality({
          id: resolutionRecord.value.id,
          expectedStatus: 'UNKNOWN',
          confirmedTerminated: true,
          reason: resolutionReason.value,
        })
      : resolveUnknownDevRecord({
          id: resolutionRecord.value.id,
          expectedStatus: 'UNKNOWN',
          confirmedTerminated: true,
          status: resolutionStatus.value,
          reason: resolutionReason.value,
        }));
    resolutionVisible.value = false;
    message.success('核查结论已登记');
    handleRefresh();
  } finally {
    resolving.value = false;
  }
}

function handleRefresh() {
  gridApi.query();
}

async function handleStop(row: DataforgeDevApi.Record) {
  if (!hasAccessByCodes(['dataforge:dev-task:run']) || !canInspect(row)) return;
  await stopDevRecord(row.id);
  message.success(
    row.nativeProgram ? '已请求停止，等待核对结果' : '停止指令已下发',
  );
  handleRefresh();
}

async function handleDelete(row: DataforgeDevApi.Record) {
  await deleteDevRecord(row.id);
  message.success('删除成功');
  handleRefresh();
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: useInstanceGridFormSchema(),
  },
  gridOptions: {
    showOverflow: false,
    showHeaderOverflow: 'tooltip',
    columns: useInstanceGridColumns(),
    height: 'auto',
    keepSource: true,
    stripe: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          return await getDevRecordPage({
            taskId: parseResourceId(route.query.taskId),
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<DataforgeDevApi.Record>,
});

const logVisible = ref(false);
const logRecord = ref<DataforgeDevApi.Record>();
let logTimer: ReturnType<typeof setTimeout> | undefined;
let logSeq = 0;
const taskLog = ref(createTaskLogState());
const taskLogLoading = ref(false);
const followTaskLog = ref(false);
const logRefreshLabel = computed(() => {
  if (logRecord.value?.status === 'UNKNOWN') return '结果待核查，每 5 秒刷新';
  if (activeStatuses.has(logRecord.value?.status ?? ''))
    return '每 2 秒自动刷新';
  if (['PENDING', 'UNKNOWN'].includes(logRecord.value?.qualityStatus ?? ''))
    return '等待质量结果，每 2 秒刷新';
  return '执行已结束';
});
async function loadTaskLog(reset = false) {
  const record = logRecord.value;
  if (!record || taskLogLoading.value || !canInspect(record)) return;
  const seq = logSeq;
  if (reset) taskLog.value = createTaskLogState();
  taskLogLoading.value = true;
  try {
    const page = await getDevRecordTaskLog({
      id: record.id,
      skipLineNum: taskLog.value.cursor,
      limit: 200,
    });
    if (
      seq !== logSeq ||
      !logVisible.value ||
      String(logRecord.value?.id) !== String(record.id)
    )
      return;
    appendTaskLogPage(taskLog.value, page);
  } finally {
    if (seq === logSeq) taskLogLoading.value = false;
  }
}
async function openLog(
  row: Pick<DataforgeDevApi.Record, 'id' | 'nativeProgram'>,
) {
  if (!canInspect(row)) return;
  stopLogTimer();
  const seq = logSeq;
  logRecord.value = undefined;
  logVisible.value = true;
  const refresh = async () => {
    try {
      const record = await getDevRecord(row.id);
      if (seq !== logSeq || !logVisible.value) return;
      logRecord.value = record;
      if (
        activeStatuses.has(record.status ?? '') ||
        ['PENDING', 'UNKNOWN'].includes(record.qualityStatus ?? '')
      ) {
        logTimer = setTimeout(
          refresh,
          record.status === 'UNKNOWN' ? 5000 : 2000,
        );
      } else {
        handleRefresh();
      }
    } catch {
      if (seq === logSeq && logVisible.value)
        logTimer = setTimeout(refresh, 5000);
    }
  };
  await refresh();
}
function stopLogTimer() {
  ++logSeq;
  if (logTimer) clearTimeout(logTimer);
  logTimer = undefined;
  taskLog.value = createTaskLogState();
  taskLogLoading.value = false;
  followTaskLog.value = false;
}
onBeforeUnmount(() => {
  stopLogTimer();
});
</script>

<template>
  <Page
    auto-content-height
    content-class="flex min-h-0 flex-col gap-3 overflow-hidden"
  >
    <PageGuide
      guide-id="dataforge-dev-instance"
      class="instance-notice"
      message="执行成功后仍需确认质量验收结果。结果待核查的实例可登记核查结论。"
      type="info"
      show-icon
    />
    <Grid
      class="dev-instance-grid min-h-0 flex-1"
      table-title="任务实例"
      table-title-help="查看任务执行状态、运行日志与质量验收结果"
    >
      <template #toolbar-actions>
        <Tag v-if="parseResourceId(route.query.taskId)" color="blue">
          任务 #{{ parseResourceId(route.query.taskId) }}
        </Tag>
      </template>
      <template #qualityStatus="{ row }">
        <Tag>
          {{
            QUALITY_STATUS_LABELS[row.qualityStatus ?? ''] ??
            row.qualityStatus ??
            '未提供结果'
          }}
        </Tag>
      </template>
      <template #triggerType="{ row }">
        <Tag :color="row.triggerType === 'SCHEDULE' ? 'processing' : 'default'">
          {{ optionLabel(DEV_TRIGGER_TYPE_OPTIONS, row.triggerType) }}
        </Tag>
      </template>
      <template #status="{ row }">
        <Tag :color="optionColor(DEV_RECORD_STATUS_OPTIONS, row.status)">
          {{ optionLabel(DEV_RECORD_STATUS_OPTIONS, row.status) }}
        </Tag>
      </template>
      <template #statements="{ row }">
        <span class="statement-count">
          <b>{{ row.statementSuccess ?? 0 }}</b>
          / {{ row.statementTotal ?? 0 }}
        </span>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '日志',
              type: 'link',
              icon: 'lucide:history',
              ifShow: canInspect(row),
              onClick: openLog.bind(null, row),
            },
          ]"
          :drop-down-actions="[
            {
              label: row.status === 'SUCCESS' ? '核查质量异常' : '登记核查结论',
              type: 'link',
              icon: ACTION_ICON.AUDIT,
              auth: ['dataforge:dev-task:run'],
              ifShow:
                (row.status === 'UNKNOWN' ||
                  (row.status === 'SUCCESS' &&
                    row.qualityStatus === 'UNKNOWN')) &&
                canOperate &&
                canInspect(row),
              onClick: () => openResolution(row),
            },
            {
              label: '停止',
              type: 'link',
              danger: true,
              icon: 'lucide:square',
              auth: ['dataforge:dev-task:run'],
              ifShow:
                canOperate &&
                canInspect(row) &&
                activeStatuses.has(row.status ?? '') &&
                row.status !== 'CANCEL_REQUESTED',
              popConfirm: {
                title: `确认停止实例 #${row.id} 吗？`,
                confirm: handleStop.bind(null, row),
              },
            },
            {
              label: $t('common.delete'),
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['dataforge:dev-task:delete'],
              ifShow:
                !['PENDING', 'UNKNOWN'].includes(row.qualityStatus ?? '') &&
                canDevelop &&
                ['SUCCESS', 'FAILED', 'STOPPED'].includes(row.status ?? ''),
              popConfirm: {
                title: `确认删除实例 #${row.id} 吗？`,
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>
    <Modal
      v-model:open="logVisible"
      :footer="null"
      title="运行日志"
      :width="1160"
      destroy-on-hidden
      @cancel="stopLogTimer"
    >
      <div class="log-summary">
        <span class="page-header-icon">
          <IconifyIcon icon="lucide:terminal" />
        </span>
        <div class="page-header-copy">
          <h3>{{ logRecord?.taskName || '正在读取实例' }}</h3>
          <p>实例 #{{ logRecord?.id ?? '-' }}</p>
        </div>
        <Tag :color="optionColor(DEV_RECORD_STATUS_OPTIONS, logRecord?.status)">
          {{ optionLabel(DEV_RECORD_STATUS_OPTIONS, logRecord?.status) }}
        </Tag>
      </div>
      <Descriptions bordered size="small" :column="3" class="log-metadata">
        <DescriptionsItem label="开始时间">
          {{ formatDateTime(logRecord?.startTime) || '-' }}
        </DescriptionsItem>
        <DescriptionsItem label="结束时间">
          {{ formatDateTime(logRecord?.endTime) || '-' }}
        </DescriptionsItem>
        <DescriptionsItem label="运行时长">
          {{ formatDuration(logRecord?.durationMs) }}
        </DescriptionsItem>
        <DescriptionsItem label="运行版本">
          {{
            logRecord?.versionId ? `#${logRecord.versionId}` : '历史实例未记录'
          }}
        </DescriptionsItem>
        <DescriptionsItem label="业务日期">
          {{ formatDate(logRecord?.businessDate) || '-' }}
        </DescriptionsItem>
        <DescriptionsItem label="质量验收">
          {{
            QUALITY_STATUS_LABELS[logRecord?.qualityStatus ?? ''] ??
            '未提供结果'
          }}
          <Button
            v-if="logRecord?.qualityInstanceId"
            v-access:code="'governance:quality-task-instance:query'"
            type="link"
            size="small"
            @click="
              router.push({
                name: 'GovernanceQualityTaskInstance',
                query: { instanceId: String(logRecord.qualityInstanceId) },
              })
            "
          >
            <IconifyIcon icon="lucide:external-link" />
            详情 #{{ logRecord.qualityInstanceId }}
          </Button>
        </DescriptionsItem>
      </Descriptions>
      <div
        v-if="logRecord?.resolvedAt || logRecord?.resolutionReason"
        class="resolution-note"
      >
        <IconifyIcon icon="lucide:clipboard-check" />
        <div>
          <p v-if="logRecord?.resolvedAt">
            核查时间：{{ formatDateTime(logRecord.resolvedAt) || '-' }}
          </p>
          <p v-if="logRecord?.resolutionReason">
            核查说明：{{ logRecord.resolutionReason }}
          </p>
        </div>
      </div>
      <Alert
        v-if="logRecord?.lastError"
        :message="logRecord.lastError"
        show-icon
        type="error"
        class="log-alert"
      />
      <Alert
        v-if="logRecord?.waitReason"
        :message="logRecord.waitReason"
        show-icon
        type="info"
        class="log-alert"
      />
      <section v-if="logRecord?.dependencies?.length" class="dependency-panel">
        <div class="console-header">
          <span class="console-heading">
            <IconifyIcon icon="lucide:git-branch" />
            运行版本依赖
          </span>
          <span class="console-meta">按本实例业务日期核对</span>
        </div>
        <div
          v-for="dependency in logRecord.dependencies"
          :key="String(dependency.taskId)"
          class="dependency-item"
        >
          <div class="dependency-main">
            <Tag :color="dependency.satisfied ? 'success' : 'warning'">
              {{ dependency.satisfied ? '已满足' : '阻塞' }}
            </Tag>
            <span class="dependency-name">{{ dependency.taskName }}</span>
            <span class="console-meta">
              要求版本 #{{ dependency.requiredVersionId }}
            </span>
            <Button
              type="link"
              size="small"
              @click="
                router.push({
                  name: 'DataforgeDevInstance',
                  query: {
                    taskId: String(dependency.taskId),
                    ...(dependency.recordId
                      ? { recordId: String(dependency.recordId) }
                      : {}),
                  },
                })
              "
            >
              <IconifyIcon icon="lucide:external-link" />
              {{ dependency.recordId ? '查看上游实例' : '查看上游记录' }}
            </Button>
          </div>
          <p>{{ dependency.reason }}</p>
          <p v-if="dependency.recordId" class="console-meta">
            实际版本 #{{ dependency.actualVersionId ?? '-' }} ·
            {{ optionLabel(DEV_RECORD_STATUS_OPTIONS, dependency.status) }} ·
            {{
              QUALITY_STATUS_LABELS[dependency.qualityStatus ?? ''] ??
              dependency.qualityStatus ??
              '-'
            }}
          </p>
        </div>
      </section>
      <section class="log-panel">
        <div class="console-header">
          <span class="console-heading">
            <IconifyIcon icon="lucide:file-text" />
            平台运行日志
          </span>
          <span class="console-meta">
            <span
              class="refresh-dot"
              :class="[{ active: activeStatuses.has(logRecord?.status ?? '') }]"
            ></span>
            {{ logRefreshLabel }}
          </span>
        </div>
        <LogViewer
          v-if="logRecord?.log"
          :content="logRecord.log"
          :height="logRecord?.dsInstanceId ? '240px' : '400px'"
        />
        <div v-else class="console-empty">
          <Empty
            :description="
              logRecord
                ? '暂无平台日志输出，任务运行后将在这里显示日志'
                : '正在读取运行实例…'
            "
          />
        </div>
      </section>
      <section v-if="logRecord?.dsInstanceId" class="log-panel task-log-panel">
        <div class="console-header task-console-header">
          <span class="console-heading">
            <IconifyIcon icon="lucide:terminal" />
            执行任务日志
          </span>
          <Checkbox v-model:checked="followTaskLog" class="follow-log">
            跟随到末尾
          </Checkbox>
          <Button
            :loading="taskLogLoading"
            :disabled="!taskLog.mayHaveMore"
            size="small"
            @click="loadTaskLog()"
          >
            <IconifyIcon icon="lucide:chevrons-down" />
            {{ taskLog.cursor ? '读取后续日志' : '读取执行日志' }}
          </Button>
          <Button
            :disabled="taskLogLoading"
            size="small"
            @click="loadTaskLog(true)"
          >
            <IconifyIcon icon="lucide:rotate-ccw" />
            从头读取
          </Button>
        </div>
        <Alert
          v-if="taskLog.statusMessage"
          :message="taskLog.statusMessage"
          type="info"
          show-icon
          class="task-log-alert"
        />
        <Alert
          v-if="taskLog.truncated"
          message="日志内容已截断；当前视图只保留最近一段日志。"
          type="warning"
          show-icon
          class="task-log-alert"
        />
        <LogViewer
          v-if="taskLog.content"
          :content="taskLog.content"
          :auto-scroll="followTaskLog"
          height="320px"
        />
        <div v-else class="console-empty">
          <Empty
            :description="
              taskLogLoading
                ? '正在读取执行日志…'
                : '点击读取执行日志，查看引擎的运行输出'
            "
          />
        </div>
        <div class="console-footer">
          <span>
            {{
              taskLogLoading
                ? '正在读取日志…'
                : taskLog.content
                  ? '已加载执行日志'
                  : '尚未读取执行日志'
            }}
          </span>
          <span v-if="!taskLog.mayHaveMore">
            <IconifyIcon icon="lucide:check" />
            已读取到日志末尾
          </span>
        </div>
      </section>
      <div class="log-modal-footer">
        <span>执行状态与质量验收结果分别记录</span>
        <Button
          @click="
            logVisible = false;
            stopLogTimer();
          "
        >
          关闭
        </Button>
      </div>
    </Modal>
    <Modal
      v-model:open="resolutionVisible"
      :title="resolvingQuality ? '核查质量异常' : '登记未决实例核查结论'"
      :confirm-loading="resolving"
      @ok="resolveUnknown"
    >
      <Alert
        type="warning"
        :message="
          resolvingQuality
            ? '核实原质检已结束后，将质量记为失败并解除阻塞。执行成功状态保留，后续可重新运行；此处不能确认质量通过。'
            : '先核实引擎执行状态和目标数据。此操作登记核查结论并解除阻塞，不会重新执行任务。'
        "
        show-icon
      />
      <Checkbox v-model:checked="confirmed" class="my-3">
        {{
          resolvingQuality
            ? '已核实原质检执行结束，并检查目标数据'
            : '已在调度器核实原执行结束或未启动，并核查数据产出'
        }}
      </Checkbox>
      <Select
        v-if="!resolvingQuality"
        v-model:value="resolutionStatus"
        :options="[
          { label: '失败', value: 'FAILED' },
          { label: '成功', value: 'SUCCESS' },
          { label: '停止', value: 'STOPPED' },
        ]"
        class="mb-3 w-full"
      />
      <Input.TextArea
        v-model:value="resolutionReason"
        :maxlength="1000"
        :rows="4"
        placeholder="核查依据和处置说明（必填）"
      />
    </Modal>
  </Page>
</template>

<style scoped>
.dev-instance-grid :deep(.vxe-table--scroll-x-handle::-webkit-scrollbar) {
  height: 6px;
}

.dev-instance-grid :deep(.vxe-table--scroll-x-handle::-webkit-scrollbar-track) {
  background: transparent;
}

.dev-instance-grid :deep(.vxe-table--scroll-x-handle::-webkit-scrollbar-thumb) {
  background: hsl(var(--foreground) / 20%);
  border-radius: 999px;
}

.dev-instance-grid :deep(.vxe-table--scroll-x-handle::-webkit-scrollbar-thumb:hover) {
  background: hsl(var(--foreground) / 30%);
}

@supports (-moz-appearance: none) {
  .dev-instance-grid :deep(.vxe-table--scroll-x-handle) {
    scrollbar-color: hsl(var(--foreground) / 20%) transparent;
    scrollbar-width: thin;
  }
}

.log-summary {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 3px 0 14px;
}

.page-header-icon {
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

.page-header-copy {
  flex: 1;
  min-width: 0;
}

.page-header-copy h3 {
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
}

.page-header-copy p {
  margin: 4px 0 0;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.instance-notice {
  flex-shrink: 0;
  border-radius: var(--radius);
}

.statement-count {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: hsl(var(--muted-foreground));
}

.statement-count b {
  font-weight: 500;
  color: hsl(var(--foreground));
}

.log-metadata {
  margin-bottom: 14px;
}

.log-metadata :deep(.ant-descriptions-item-label) {
  width: 90px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.log-metadata :deep(.ant-descriptions-item-content) {
  font-size: 12px;
}

.log-metadata :deep(.ant-btn) {
  gap: 4px;
  padding: 0 3px;
  font-size: 12px;
}

.resolution-note {
  display: flex;
  gap: 8px;
  padding: 10px 12px;
  margin-bottom: 12px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
  background: hsl(var(--muted) / 35%);
  border-radius: 4px;
}

.resolution-note > :first-child {
  flex-shrink: 0;
  margin-top: 2px;
  color: hsl(var(--primary));
}

.resolution-note p {
  margin: 0;
}

.log-alert {
  margin-bottom: 12px;
}

.dependency-panel,
.log-panel {
  margin-bottom: 14px;
  overflow: hidden;
  border: 1px solid hsl(var(--border));
  border-radius: 4px;
}

.console-header {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  min-height: 42px;
  padding: 9px 12px;
  background: hsl(var(--muted) / 40%);
  border-bottom: 1px solid hsl(var(--border));
}

.console-heading {
  display: flex;
  gap: 7px;
  align-items: center;
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
}

.console-heading > :first-child {
  color: hsl(var(--primary));
}

.console-meta {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.refresh-dot {
  width: 6px;
  height: 6px;
  background: hsl(var(--muted-foreground) / 50%);
  border-radius: 50%;
}

.refresh-dot.active {
  background: #52a95f;
}

.dependency-item {
  padding: 11px 12px;
}

.dependency-item + .dependency-item {
  border-top: 1px solid hsl(var(--border));
}

.dependency-main {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  align-items: center;
}

.dependency-main :deep(.ant-btn) {
  gap: 4px;
  margin-left: auto;
  font-size: 12px;
}

.dependency-name {
  font-size: 13px;
  font-weight: 500;
}

.dependency-item p {
  margin: 5px 0 0;
  font-size: 12px;
}

.console-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 220px;
  padding: 24px;
  background: hsl(var(--muted) / 10%);
}

.task-console-header .console-heading {
  margin-right: auto;
}

.follow-log {
  font-size: 12px;
  white-space: nowrap;
}

.task-log-alert {
  margin: 10px 12px;
}

.console-footer,
.log-modal-footer {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.console-footer {
  padding: 9px 12px;
  border-top: 1px solid hsl(var(--border));
}

.console-footer > span {
  display: flex;
  gap: 5px;
  align-items: center;
}

.log-modal-footer {
  padding-top: 6px;
}

@media (max-width: 760px) {
  .console-header,
  .console-footer,
  .log-modal-footer {
    flex-wrap: wrap;
  }

  .console-header > .console-meta {
    width: 100%;
  }
}
</style>
