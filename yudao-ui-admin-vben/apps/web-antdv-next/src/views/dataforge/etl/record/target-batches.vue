<script setup lang="ts">
import type { DataforgeEtlApi } from '#/api/dataforge/etl';
import type {
  EtlReplayStatus,
  EtlTargetBatch,
} from '#/api/dataforge/etl/target-batch';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import { useClipboard } from '@vueuse/core';
import { Alert, Button, Drawer, Modal, Table, Tag, TextArea } from 'antdv-next';

import {
  getEtlReplayStatus,
  getEtlTargetBatches,
  getEtlTargetRows,
  retryEtlReplayEvidence,
  retryEtlTargetBatch,
} from '#/api/dataforge/etl/target-batch';

import Lifecycle from './lifecycle.vue';

const access = useAccessStore();
const { copy: copyBatchId } = useClipboard({ legacy: true });
const copyError = ref('');
async function copyId(id: DataforgeEtlApi.Id) {
  copyError.value = '';
  try {
    await copyBatchId(String(id));
  } catch {
    copyError.value = '复制失败，请选中详情中的完整批次 ID 手动复制。';
  }
}
const { hasAccessByCodes } = useAccess();
const canRetry = computed(
  () =>
    hasAccessByCodes(['dataforge:etl:run']) &&
    hasAccessByCodes(['dataforge:etl:query']),
);
const visible = ref(false);
const consumptionAvailable = ref(false);
const loading = ref(false);
const error = ref('');
const recordId = ref<DataforgeEtlApi.Id>();
const batches = ref<EtlTargetBatch[]>([]);
const retryTarget = ref<EtlTargetBatch>();
const retryMode = ref<'batch' | 'recheck'>('batch');
const retryVisible = ref(false);
const replay = ref<EtlReplayStatus | null>(null);
const replayError = ref('');
const reason = ref('');
const retryError = ref('');
const retrying = ref(false);
const dataTarget = ref<EtlTargetBatch>();
const data = ref<Record<string, unknown>[]>([]);
const cursor = ref<DataforgeEtlApi.Id>(0);
const hasMore = ref(false);
const dataLoading = ref(false);
const dataError = ref('');
let listSeq = 0;
let retrySeq = 0;
let dataSeq = 0;
const commits: Record<string, string> = {
  STAGED: '已暂存',
  READY: '待提交',
  COMMITTING: '提交中',
  COMMITTED: '已提交',
  RETRY: '等待重试',
  UNKNOWN: '提交待确认',
  FAILED: '待人工恢复',
  BLOCKED: '整批已阻断',
  SUPERSEDED: '已由回放替换',
};
const qualities: Record<string, string> = {
  PENDING: '待检测',
  REGISTERING: '登记中',
  UNKNOWN: '结果待确认',
  RUNNING: '检测中',
  PASSED: '通过',
  FAILED: '不通过',
  UNAVAILABLE: '质检服务不可用',
  NOT_RUN: '未执行',
  EMPTY: '无可检测记录',
};
const attachmentLabels: Record<string, string> = {
  PENDING: '等待回放结果',
  ATTACHING: '投递中',
  RETRY: '等待重试',
  ATTACHED: '已关联治理复检',
  FAILED: '待人工恢复',
};
const columns = [
  { title: '目标节点', dataIndex: 'nodeNo', key: 'nodeNo' },
  { title: '原始输入', dataIndex: 'inputCount', key: 'inputCount' },
  { title: '实际写入', dataIndex: 'outputCount', key: 'outputCount' },
  { title: '允许准入', dataIndex: 'admittedCount', key: 'admittedCount' },
  { title: '不合格', dataIndex: 'rejectedCount', key: 'rejectedCount' },
  { title: '提交状态', key: 'commit' },
  { title: '质量验收', key: 'quality' },
  { title: '操作', key: 'actions', width: 260, fixed: 'right' as const },
];
function closeRows() {
  ++dataSeq;
  dataTarget.value = undefined;
  data.value = [];
  dataLoading.value = false;
  dataError.value = '';
  hasMore.value = false;
  cursor.value = 0;
}
function closeRetry() {
  ++retrySeq;
  retryTarget.value = undefined;
  retryVisible.value = false;
  retrying.value = false;
  retryError.value = '';
  reason.value = '';
}
function close() {
  ++listSeq;
  visible.value = false;
  consumptionAvailable.value = false;
  loading.value = false;
  batches.value = [];
  replay.value = null;
  replayError.value = '';
  error.value = '';
  closeRows();
  closeRetry();
}
async function open(id: DataforgeEtlApi.Id) {
  close();
  if (!hasAccessByCodes(['dataforge:etl:query'])) return;
  recordId.value = id;
  visible.value = true;
  await load();
}
defineExpose({ open, close });
async function load() {
  if (recordId.value === undefined) return;
  const current = ++listSeq;
  loading.value = true;
  error.value = '';
  replayError.value = '';
  try {
    const [result, status] = await Promise.allSettled([
      getEtlTargetBatches(recordId.value),
      getEtlReplayStatus(recordId.value),
    ]);
    if (current !== listSeq || !visible.value) return;
    if (result.status === 'fulfilled') batches.value = result.value;
    else error.value = '目标批次加载失败，请重试。';
    if (status.status === 'fulfilled') replay.value = status.value;
    else replayError.value = '回放复检关联状态读取失败，请刷新重试。';
  } catch {
    if (current === listSeq) error.value = '目标批次加载失败，请重试。';
  } finally {
    if (current === listSeq) loading.value = false;
  }
}
function requestRetry(batch: EtlTargetBatch) {
  if (!canRetry.value || retrying.value) return;
  closeRetry();
  retryMode.value = 'batch';
  retryVisible.value = true;
  retryTarget.value = batch;
}
function requestEvidenceRetry() {
  if (
    !canRetry.value ||
    retrying.value ||
    replay.value?.attachStatus !== 'FAILED'
  )
    return;
  closeRetry();
  retryMode.value = 'recheck';
  retryVisible.value = true;
}
async function retry() {
  if (!canRetry.value || retrying.value || !retryVisible.value) return;
  if (
    (retryMode.value === 'batch' && !retryTarget.value) ||
    (retryMode.value === 'recheck' && !replay.value)
  )
    return;
  if (!reason.value.trim()) {
    retryError.value = '请填写恢复原因。';
    return;
  }
  const current = ++retrySeq;
  retrying.value = true;
  retryError.value = '';
  try {
    await (retryMode.value === 'recheck'
      ? retryEtlReplayEvidence({
          recordId: replay.value!.recordId,
          reason: reason.value.trim(),
        })
      : retryEtlTargetBatch({
          batchId: retryTarget.value!.id,
          reason: reason.value.trim(),
        }));
    if (current !== retrySeq) return;
    closeRetry();
    await load();
  } catch {
    if (current === retrySeq) {
      retryError.value =
        '操作结果未确认，请刷新核对状态；恢复复用原批次，不创建新的 ETL 运行。';
      await load();
    }
  } finally {
    if (current === retrySeq) retrying.value = false;
  }
}
async function showRows(batch: EtlTargetBatch) {
  if (!consumptionAvailable.value) return;
  closeRows();
  dataTarget.value = batch;
  await loadRows();
}
async function loadRows() {
  if (!dataTarget.value || dataLoading.value) return;
  const current = ++dataSeq;
  dataLoading.value = true;
  dataError.value = '';
  try {
    const result = await getEtlTargetRows(dataTarget.value.id, cursor.value);
    if (current !== dataSeq) return;
    data.value = result.rows;
    cursor.value = result.nextAfterRow;
    hasMore.value = result.hasMore;
  } catch {
    if (current === dataSeq)
      dataError.value =
        '当前批次尚未通过准入、数据已变化或读取权限不足；未展示原始暂存载荷。';
  } finally {
    if (current === dataSeq) dataLoading.value = false;
  }
}
watch(() => [access.accessToken, access.refreshToken], close);
onBeforeUnmount(close);
</script>
<template>
  <Drawer
    :open="visible"
    title="目标批次与准入"
    size="1100"
    :push="false"
    destroy-on-hidden
    @close="close"
  >
    <Alert
      class="mb-3"
      type="info"
      show-icon
      message="执行、目标提交与质量验收是不同状态。只有全部目标提交且本批次质量通过后，才能读取受控批次；读取还会应用当前用户行权限与脱敏。直接访问目标库不经过此入口。"
    />
    <Button class="mb-3" :loading="loading" @click="load"
      ><template #icon><IconifyIcon icon="lucide:refresh-cw" /></template
      >刷新状态</Button
    >
    <Lifecycle
      v-if="visible && recordId !== undefined"
      :record-id="recordId"
      @availability="
        (available) => {
          consumptionAvailable = available;
          if (!available) closeRows();
        }
      "
    />
    <Alert v-if="error" class="mb-3" type="error" :message="error" />
    <Alert v-if="copyError" class="mb-3" type="error" :message="copyError" />
    <Alert
      v-if="replayError"
      class="mb-3"
      type="error"
      :message="replayError"
    />
    <div v-if="replay" class="mb-3 rounded border p-3">
      <p>
        源运行 {{ replay.sourceRecordId }} · 工单 {{ replay.workOrderId }} ·
        整改提交 {{ replay.submissionId }}
      </p>
      <p>
        快照：{{
          replay.snapshotMode === 'ORIGINAL'
            ? '原运行版本'
            : '回放时最新发布版本'
        }}
        · 复检证据：{{
          attachmentLabels[replay.attachStatus] ?? replay.attachStatus
        }}
      </p>
      <p v-if="replay.lastError" class="text-destructive">
        {{ replay.lastError }}
      </p>
      <Button
        v-if="canRetry && replay.attachStatus === 'FAILED'"
        size="small"
        @click="requestEvidenceRetry"
      >
        <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
        重报复检证据
      </Button>
    </div>
    <Table
      :scroll="{ x: 'max-content' }"
      :columns="columns"
      :data-source="batches"
      :row-key="(row: EtlTargetBatch) => String(row.id)"
      :pagination="false"
      :loading="loading"
    >
      <template #bodyCell="{ column, record }">
        <Tag v-if="column.key === 'commit'">
          {{ commits[record.commitStatus] ?? record.commitStatus }}
        </Tag>
        <Tag
          v-else-if="column.key === 'quality'"
          :color="
            record.qualityStatus === 'PASSED'
              ? 'success'
              : record.qualityStatus === 'FAILED'
                ? 'error'
                : 'default'
          "
        >
          {{ qualities[record.qualityStatus] ?? record.qualityStatus }}
        </Tag>
        <template v-else-if="column.key === 'actions'">
          <Button
            size="small"
            :disabled="
              !consumptionAvailable ||
              record.commitStatus !== 'COMMITTED' ||
              record.qualityStatus !== 'PASSED'
            "
            @click="showRows(record)"
          >
            <template #icon><IconifyIcon icon="lucide:eye" /></template>
            查看准入数据
          </Button>
          <Button
            v-if="
              canRetry &&
              (record.commitStatus === 'FAILED' ||
                record.qualityStatus === 'UNAVAILABLE')
            "
            size="small"
            @click="requestRetry(record)"
          >
            <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
            恢复对账
          </Button>
        </template>
      </template>
      <template #expandedRowRender="{ record }">
        <p>
          批次 ID {{ record.id }} · 检测任务 {{ record.qualityTaskId ?? '—' }} ·
          质量实例 {{ record.qualityInstanceId ?? '—' }}
        </p>
        <Button size="small" @click="copyId(record.id)"
          ><template #icon><IconifyIcon icon="lucide:copy" /></template>复制批次
          ID</Button
        >
        <p v-if="record.replayOfBatchId">
          来源批次 {{ record.replayOfBatchId }} · 来源运行
          {{ record.replayOfRecordId }} · 工单 {{ record.workOrderId }} ·
          整改提交 {{ record.workOrderSubmissionId }}
        </p>
        <p v-if="record.replacedByBatchId">
          已由批次 {{ record.replacedByBatchId }} 替换，旧批次不再开放准入读取。
        </p>
        <p v-if="record.lastError" class="text-destructive">
          {{ record.lastError }}
        </p>
      </template>
    </Table>
  </Drawer>
  <Modal
    :open="retryVisible"
    :title="retryMode === 'recheck' ? '重报原回放复检证据' : '恢复原批次对账'"
    :confirm-loading="retrying"
    :closable="!retrying"
    :mask-closable="false"
    :cancel-button-props="{ disabled: retrying }"
    @ok="retry"
    @cancel="closeRetry"
  >
    <p v-if="retryMode === 'batch'" class="mb-3">
      只恢复原批次提交回执或原质量实例对账，不重跑
      ETL，不把质量不通过改为通过。请填写核查与恢复原因。
    </p>
    <p v-else class="mb-3">
      只重报现有回放结果与治理复检的关联证据，不重跑
      ETL，不生成新的回放。请填写恢复原因。
    </p>
    <TextArea
      v-model:value="reason"
      :maxlength="1000"
      :disabled="retrying"
      placeholder="恢复原因（必填）"
    />
    <Alert v-if="retryError" class="mt-3" type="error" :message="retryError" />
  </Modal>
  <Drawer
    :open="!!dataTarget"
    title="准入批次数据"
    size="900"
    :push="false"
    destroy-on-hidden
    @close="closeRows"
  >
    <Alert v-if="dataError" class="mb-3" type="error" :message="dataError" />
    <Button :loading="dataLoading" :disabled="!hasMore" @click="loadRows">
      下一页（最多 100 行）
    </Button>
    <pre class="mt-3 overflow-auto whitespace-pre-wrap">{{
      JSON.stringify(data, null, 2)
    }}</pre>
  </Drawer>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
