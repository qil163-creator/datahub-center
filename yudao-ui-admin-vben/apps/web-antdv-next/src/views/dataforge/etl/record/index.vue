<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Checkbox,
  Input,
  message,
  Modal,
  Select,
  Table,
  Tag,
} from 'antdv-next';

import { TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import { QUALITY_STATUS_LABELS } from '#/api/dataforge/dev';
import {
  deleteEtlRecord,
  getEtlNodeRecords,
  getEtlRecordLog,
  getEtlRecordPage,
  resolveEtlCommand,
  stopEtlCommand,
} from '#/api/dataforge/etl';
import { $t } from '#/locales';
import { parseResourceId } from '#/utils/resource-id';
import { getRunState } from '#/views/dataforge/execution/run-status';
import { useRunCommands } from '#/views/dataforge/execution/use-run-commands';

import {
  optionColor,
  optionLabel,
  RUN_STATUS_OPTIONS,
  useRecordGridColumns,
  useRecordGridFormSchema,
} from '../data';
import {
  formatDurationMs,
  isNodeRecordFinished,
  summarizeNodeRecords,
} from '../pipeline/node-run-status';
import { withLiveCounts } from '../pipeline/runtime-metrics';
import FileOutputs from './file-outputs.vue';
import QualityDetail from './quality-detail.vue';
import RuntimeObservation from './runtime-observation.vue';
import TargetBatches from './target-batches.vue';

defineOptions({ name: 'DataforgeEtlRecord' });

const route = useRoute();
const router = useRouter();

const accessStore = useAccessStore();
const { hasAccessByCodes } = useAccess();
/** 操作能力来自菜单按钮权限；接口仍是最终授权边界。 */
const canOperate = computed(() => hasAccessByCodes(['dataforge:etl:run']));
const canResolve = computed(() => hasAccessByCodes(['dataforge:etl:resolve']));
const canDelete = computed(() => hasAccessByCodes(['dataforge:etl:delete']));
const stopCommands = useRunCommands();
const resolutionCommands = useRunCommands();
const qualityDetailRef = ref<InstanceType<typeof QualityDetail>>();
const targetBatchesRef = ref<InstanceType<typeof TargetBatches>>();
const fileOutputsRef = ref<InstanceType<typeof FileOutputs>>();
async function loadRecords() {
  handleRefresh();
  const recordId = parseResourceId(route.query.recordId);
  if (recordId) await handleShowLog({ id: recordId });
}
onMounted(loadRecords);
watch(
  () => route.query.recordId,
  (value) => {
    const id = parseResourceId(value);
    if (id) void handleShowLog({ id });
  },
);
watch(
  () => [accessStore.accessToken, accessStore.refreshToken],
  () => {
    stopPolling();
    logVisible.value = false;
    logContent.value = '';
    nodeRecords.value = [];
    logRecord.value = undefined;
    closeResolution();
    void loadRecords();
  },
);

function handleRefresh() {
  gridApi.query();
}

const logVisible = ref(false);
const logContent = ref('');

const logRecord = ref<DataforgeEtlApi.EtlRecord>();
const nodeRecords = ref<DataforgeEtlApi.NodeRecord[]>([]);
let pollSeq = 0;
let timer: ReturnType<typeof setTimeout> | undefined;
function stopPolling() {
  ++pollSeq;
  if (timer) clearTimeout(timer);
  timer = undefined;
}
async function handleShowLog(row: DataforgeEtlApi.EtlRecord) {
  stopPolling();
  const seq = pollSeq;
  logContent.value = '';
  logRecord.value = undefined;
  nodeRecords.value = [];
  logVisible.value = true;
  const refresh = async () => {
    try {
      const [record, nodes] = await Promise.all([
        getEtlRecordLog(row.id!),
        getEtlNodeRecords(row.id!),
      ]);
      if (seq !== pollSeq || !logVisible.value) return;
      logRecord.value = record;
      logContent.value = record.log || '（暂无日志）';
      nodeRecords.value = nodes;
      const state = getRunState('ETL', record);
      if (state.shouldPoll)
        timer = setTimeout(refresh, state.canResolve ? 5000 : 2000);
    } catch {
      if (seq === pollSeq && logVisible.value)
        timer = setTimeout(refresh, 5000);
    }
  };
  await refresh();
}
onBeforeUnmount(() => {
  stopPolling();
  closeResolution();
});
const resolutionVisible = ref(false);
const resolutionRecord = ref<DataforgeEtlApi.EtlRecord>();
const resolutionReason = ref('');
const evidenceRef = ref('');
const confirmedStopped = ref(false);
const outputReviewed = ref(false);
const outcome = ref<'FAILED' | 'STOPPED' | 'SUCCESS'>('FAILED');
const resolutionLoading = ref(false);
const resolvingQuality = ref(false);
let resolutionSeq = 0;
function closeResolution() {
  ++resolutionSeq;
  resolutionCommands.invalidate();
  resolutionVisible.value = false;
  resolutionLoading.value = false;
  resolutionRecord.value = undefined;
  resolutionReason.value = '';
  evidenceRef.value = '';
  confirmedStopped.value = false;
  outputReviewed.value = false;
  outcome.value = 'FAILED';
  resolvingQuality.value = false;
}
async function openResolution(row: DataforgeEtlApi.EtlRecord) {
  if (!canResolve.value || row.id === undefined) return;
  closeResolution();
  const seq = resolutionSeq;
  resolutionVisible.value = true;
  resolutionLoading.value = true;
  try {
    const record = await getEtlRecordLog(row.id);
    if (seq !== resolutionSeq || !resolutionVisible.value) return;
    if (!getRunState('ETL', record).canResolve) {
      message.warning('记录状态已变化，请刷新后核对');
      closeResolution();
      handleRefresh();
      return;
    }
    resolutionRecord.value = record;
    resolvingQuality.value =
      record.runStatus === 3 && record.qualityStatus === 'UNKNOWN';
  } catch {
    if (seq === resolutionSeq) closeResolution();
  } finally {
    if (seq === resolutionSeq) resolutionLoading.value = false;
  }
}
async function resolveUnknown() {
  if (
    !canResolve.value ||
    resolutionLoading.value ||
    resolutionCommands.busy.value
  )
    return;
  const record = resolutionRecord.value;
  if (
    !confirmedStopped.value ||
    !outputReviewed.value ||
    !resolutionReason.value.trim() ||
    !evidenceRef.value.trim() ||
    record?.id === undefined ||
    record.rowVersion === undefined
  ) {
    message.warning('请分别确认资源退出和输出核查，并填写说明与证据');
    return;
  }
  const seq = resolutionSeq;
  const receipt = await resolutionCommands.execute(
    `etl:resolve:${record.id}`,
    {
      recordId: record.id,
      expectedVersion: record.rowVersion,
      confirmedStopped: true,
      outputReviewed: true,
      reason: resolutionReason.value.trim(),
      evidenceRef: evidenceRef.value.trim(),
      outcome: resolvingQuality.value ? 'FAILED' : outcome.value,
    } satisfies Omit<Parameters<typeof resolveEtlCommand>[0], 'requestId'>,
    resolveEtlCommand,
  );
  if (!receipt || seq !== resolutionSeq || !resolutionVisible.value) return;
  closeResolution();
  message.success('核查结论已登记，请查看最新执行及质量状态');
  handleRefresh();
}

async function handleStop(row: DataforgeEtlApi.EtlRecord) {
  if (
    !canOperate.value ||
    row.id === undefined ||
    !getRunState('ETL', row).canStop
  )
    return;
  const receipt = await stopCommands.execute(
    `etl:stop:${row.id}`,
    { recordId: row.id },
    stopEtlCommand,
  );
  if (!receipt) return;
  message.success('取消请求已受理，执行终止待确认');
  handleRefresh();
}

async function handleDelete(row: DataforgeEtlApi.EtlRecord) {
  if (!canDelete.value || !getRunState('ETL', row).canDelete) return;
  await deleteEtlRecord(row.id!);
  message.success('记录已删除');
  handleRefresh();
}

/** 节点记录加耗时 / 吞吐 / 最慢标识；运行中节点按观测快照的实时计数和轮询时刻推算 */
const nodeSummaries = computed(() =>
  summarizeNodeRecords(
    withLiveCounts(nodeRecords.value, logRecord.value?.runtimeMetrics),
    Date.now(),
  ),
);
/** 弹窗顶部汇总：节点总数、已完成数、最慢节点（没有已完成节点时不显示最慢部分） */
const nodeSummaryText = computed(() => {
  const list = nodeSummaries.value;
  if (list.length === 0) return '';
  const finished = list.filter((item) => isNodeRecordFinished(item)).length;
  const slowest = list.find((item) => item.slowest);
  const parts = [`节点 ${list.length} 个`, `已完成 ${finished} 个`];
  if (slowest)
    parts.push(
      `最慢节点：${slowest.nodeName ?? slowest.nodeNo ?? '-'} ${formatDurationMs(slowest.durationMs)}`,
    );
  return parts.join(' · ');
});
function formatThroughput(value: null | number) {
  return value === null ? '-' : value.toFixed(1);
}

const nodeColumns = [
  { title: '节点', dataIndex: 'nodeName', key: 'nodeName' },
  { title: '状态', dataIndex: 'runStatus', key: 'runStatus', width: 90 },
  { title: '耗时', dataIndex: 'durationMs', key: 'durationMs', width: 120 },
  {
    title: '吞吐（行/秒）',
    dataIndex: 'throughputPerSecond',
    key: 'throughputPerSecond',
    width: 110,
  },
  {
    title: '开始时间',
    dataIndex: 'startTime',
    key: 'startTime',
    width: 180,
    customRender: ({ text }: { text?: number | string }) =>
      formatDateTime(text) || '-',
  },
  {
    title: '结束时间',
    dataIndex: 'endTime',
    key: 'endTime',
    width: 180,
    customRender: ({ text }: { text?: number | string }) =>
      formatDateTime(text) || '-',
  },
  { title: '读取行数', dataIndex: 'readCount', key: 'readCount', width: 90 },
  { title: '写入行数', dataIndex: 'writeCount', key: 'writeCount', width: 90 },
  {
    title: '端口行数',
    dataIndex: 'portCounts',
    key: 'portCounts',
    width: 220,
  },
  {
    title: '文件结果',
    dataIndex: 'fileOutputState',
    key: 'fileOutputState',
    width: 160,
  },
];

function formatPortCounts(counts?: null | Record<string, number | string>) {
  if (counts === null || counts === undefined) return '未记录';
  return (
    Object.entries(counts)
      .map(([port, count]) => `${port}: ${count}`)
      .join('；') || '无数据端口输出'
  );
}

const FILE_OUTPUT_LABELS: Record<string, string> = {
  PUBLISHED: '已发布',
  DISCARDED: '已丢弃临时文件',
  UNKNOWN: '退出未确认',
  CLEANUP_PENDING: '临时文件待清理',
  PUBLISHED_CLEANUP_PENDING: '已发布，临时文件待清理',
};

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: useRecordGridFormSchema(),
  },
  gridOptions: {
    showOverflow: false,
    showHeaderOverflow: 'tooltip',
    columns: useRecordGridColumns(),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          return await getEtlRecordPage({
            pipelineId: parseResourceId(route.query.pipelineId),
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
            archived: formValues.archived ?? false,
          });
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<DataforgeEtlApi.EtlRecord>,
});
</script>

<template>
  <Page auto-content-height>
    <Grid table-title="流程日志">
      <template #qualityStatus="{ row }">
        <Tag>
          {{ QUALITY_STATUS_LABELS[row.qualityStatus ?? ''] ?? '未提供结果' }}
        </Tag>
      </template>
      <template #runStatus="{ row }">
        <Tag :color="getRunState('ETL', row).color">
          {{ getRunState('ETL', row).label }}
        </Tag>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              icon: 'lucide:clipboard-check',
              label: row.runStatus === 3 ? '核查质量异常' : '登记核查结论',
              type: 'link',
              auth: ['dataforge:etl:resolve'],
              ifShow: getRunState('ETL', row).canResolve && canResolve,
              disabled: resolutionCommands.busy.value,
              onClick: () => openResolution(row),
            },
            {
              icon: 'lucide:scroll-text',
              label: '日志',
              type: 'link',
              onClick: handleShowLog.bind(null, row),
            },
            {
              icon: 'lucide:list-checks',
              label: '行级质量',
              type: 'link',
              auth: ['dataforge:etl:query'],
              onClick: () => {
                if (row.id !== undefined) void qualityDetailRef?.open(row.id);
              },
            },
            {
              icon: 'lucide:layers',
              label: '目标批次',
              type: 'link',
              auth: ['dataforge:etl:query'],
              onClick: () => {
                if (row.id !== undefined) void targetBatchesRef?.open(row.id);
              },
            },
            {
              icon: 'lucide:files',
              label: '文件回执',
              type: 'link',
              auth: ['dataforge:etl:query'],
              onClick: () => {
                if (row.id !== undefined) void fileOutputsRef?.open(row.id);
              },
            },
            {
              icon: 'lucide:square',
              label: '停止',
              auth: ['dataforge:etl:run'],
              type: 'link',
              danger: true,
              ifShow: canOperate && getRunState('ETL', row).canStop,
              disabled: stopCommands.busy.value,
              popConfirm: {
                title: '确认停止该次执行吗？',
                confirm: handleStop.bind(null, row),
              },
            },
            {
              icon: 'lucide:trash-2',
              label: $t('common.delete'),
              auth: ['dataforge:etl:delete'],
              type: 'link',
              danger: true,
              ifShow: canDelete && getRunState('ETL', row).canDelete,
              popConfirm: {
                title: '确认删除该执行记录吗？',
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>

    <QualityDetail ref="qualityDetailRef" />
    <TargetBatches ref="targetBatchesRef" />
    <FileOutputs ref="fileOutputsRef" />

    <Modal
      v-model:open="logVisible"
      :footer="null"
      title="流程日志与节点诊断"
      width="1000px"
      @cancel="stopPolling"
    >
      <Tag v-if="logRecord" :color="getRunState('ETL', logRecord).color">
        {{ getRunState('ETL', logRecord).label }}
      </Tag>
      <Tag>
        {{
          QUALITY_STATUS_LABELS[logRecord?.qualityStatus ?? ''] ??
          '未提供质量结果'
        }}
      </Tag>
      <span v-if="logRecord?.qualityInstanceId"
        >质量实例 #{{ logRecord.qualityInstanceId }}</span
      >
      <div class="mb-2 mt-2 flex flex-wrap gap-x-4 gap-y-1 text-sm">
        <span>开始时间：{{ formatDateTime(logRecord?.startTime) || '-' }}</span>
        <span>结束时间：{{ formatDateTime(logRecord?.endTime) || '-' }}</span>
        <span
          >创建时间：{{ formatDateTime(logRecord?.createTime) || '-' }}</span
        >
        <span v-if="nodeSummaryText" class="etl-record__node-summary">
          {{ nodeSummaryText }}
        </span>
      </div>
      <Table
        :data-source="nodeSummaries"
        :columns="nodeColumns"
        :pagination="false"
        row-key="id"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'nodeName'">
            <span>{{ record.nodeName ?? record.nodeNo ?? '-' }}</span>
            <Tag v-if="record.slowest" class="ml-1" color="orange">最慢</Tag>
          </template>
          <Tag
            v-else-if="column.key === 'runStatus'"
            :color="optionColor(RUN_STATUS_OPTIONS, record.runStatus)"
          >
            {{ optionLabel(RUN_STATUS_OPTIONS, record.runStatus) }}
          </Tag>
          <template v-else-if="column.key === 'durationMs'">
            <span>{{ formatDurationMs(record.durationMs) }}</span>
            <span v-if="record.running" class="text-muted-foreground ml-1">
              （进行中）
            </span>
          </template>
          <template v-else-if="column.key === 'throughputPerSecond'">
            {{ formatThroughput(record.throughputPerSecond) }}
          </template>
          <template v-else-if="column.key === 'portCounts'">
            {{ formatPortCounts(record.portCounts) }}
          </template>
          <template v-else-if="column.key === 'fileOutputState'">
            {{
              FILE_OUTPUT_LABELS[record.fileOutputState ?? ''] ??
              record.fileOutputState ??
              '-'
            }}
          </template>
        </template>
        <template #expandedRowRender="{ record }">
          <pre class="etl-record__node-log">{{
            record.log || '（暂无节点日志）'
          }}</pre>
        </template>
      </Table>
      <RuntimeObservation
        v-if="logRecord"
        :metrics="logRecord.runtimeMetrics"
        :node-records="nodeRecords"
      />
      <Button
        v-if="logRecord?.qualityInstanceId"
        v-access:code="'governance:quality-task-instance:query'"
        type="link"
        @click="
          router.push({
            name: 'GovernanceQualityTaskInstance',
            query: { instanceId: String(logRecord.qualityInstanceId) },
          })
        "
      >
        <template #icon><IconifyIcon icon="lucide:clipboard-check" /></template>
        查看质量验收详情
      </Button>
      <pre class="etl-record__log">{{ logContent }}</pre>
    </Modal>
    <Modal
      v-model:open="resolutionVisible"
      :title="resolvingQuality ? '核查质量异常' : '登记未决实例核查结论'"
      :confirm-loading="resolutionCommands.busy.value"
      :ok-button-props="{
        disabled: resolutionLoading || !resolutionRecord || !canResolve,
      }"
      @ok="resolveUnknown"
      @cancel="closeResolution"
    >
      <Alert
        type="warning"
        :message="
          resolvingQuality
            ? '确认原质检和写入已结束后，将质量记为失败并解除阻塞，执行成功状态保留；此处不能确认质量通过。'
            : '必须核实实际资源退出与目标产出。登记后是否解除阻塞，以最新执行及质量状态为准。'
        "
        show-icon
      />
      <p class="my-2">
        当前核查版本：{{ resolutionRecord?.rowVersion ?? '加载中' }}
      </p>
      <Checkbox
        v-model:checked="confirmedStopped"
        :disabled="resolutionLoading || resolutionCommands.busy.value"
        class="my-3"
      >
        已确认执行资源退出（包括原质检及写入）
      </Checkbox>
      <Checkbox
        v-model:checked="outputReviewed"
        :disabled="resolutionLoading || resolutionCommands.busy.value"
        class="my-3"
      >
        已核查输出及部分写入影响
      </Checkbox>
      <Select
        v-if="!resolvingQuality"
        v-model:value="outcome"
        :options="[
          { label: '失败', value: 'FAILED' },
          { label: '停止', value: 'STOPPED' },
          { label: '成功', value: 'SUCCESS' },
        ]"
        class="mb-3 w-full"
      />
      <Input.TextArea
        v-model:value="resolutionReason"
        :rows="4"
        :maxlength="500"
        :disabled="resolutionLoading || resolutionCommands.busy.value"
        placeholder="核查依据、产出核对及处置说明（必填）"
      />
      <Input
        v-model:value="evidenceRef"
        :maxlength="500"
        :disabled="resolutionLoading || resolutionCommands.busy.value"
        class="mt-3"
        placeholder="证据引用，如工单或核查报告编号（必填）"
      />
    </Modal>
  </Page>
</template>

<style scoped>
.etl-record__node-summary {
  color: hsl(var(--muted-foreground));
}

/* 节点日志：展开行内可滚动，便于定位单节点错误 */
.etl-record__node-log {
  max-height: 200px;
  padding: 8px 12px;
  margin: 0;
  overflow: auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  word-break: break-all;
  white-space: pre-wrap;
  background: hsl(var(--muted) / 50%);
  border-radius: 6px;
}

.etl-record__log {
  max-height: 520px;
  padding: 12px;
  overflow: auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  word-break: break-all;
  white-space: pre-wrap;
  background: hsl(var(--muted) / 50%);
  border-radius: 6px;
}
</style>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
