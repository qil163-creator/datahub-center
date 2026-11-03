<script lang="ts" setup>
import type { DataforgeEtlApi } from '#/api/dataforge/etl';
import type { EtlFileOutput } from '#/api/dataforge/etl/file-output';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { downloadFileFromBlob, formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Drawer,
  Modal,
  Select,
  Table,
  TextArea,
} from 'antdv-next';

import {
  downloadEtlFileOutput,
  getEtlFileOutputs,
  retireEtlFileOutput,
  retryEtlFileOutput,
} from '#/api/dataforge/etl/file-output';

const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const canRetry = computed(
  () =>
    hasAccessByCodes(['dataforge:etl:query']) &&
    (hasAccessByCodes(['dataforge:etl:run']) ||
      hasAccessByCodes(['dataforge:etl:file-cleanup'])),
);
const canDownload = computed(() => hasAccessByCodes(['dataforge:etl:query']));
const canCleanup = computed(
  () => canDownload.value && hasAccessByCodes(['dataforge:etl:file-cleanup']),
);
const visible = ref(false);
const loading = ref(false);
const retrying = ref(false);
const retiring = ref(false);
const retirementOutput = ref<EtlFileOutput>();
const retirementReason = ref('');
const retirementDays = ref(7);
const retirementError = ref('');
const downloadingId = ref<DataforgeEtlApi.Id>();
const error = ref('');
const reason = ref('');
const recordId = ref<DataforgeEtlApi.Id>();
const outputs = ref<EtlFileOutput[]>([]);
let sequence = 0;
let downloadSequence = 0;
let retirementSequence = 0;
const retirementOptions = [1, 7, 30, 90].map((value) => ({
  label: `${value} 天后清理`,
  value,
}));
const columns = [
  { title: '节点', dataIndex: 'nodeNo', key: 'nodeNo' },
  { title: '逻辑存储', dataIndex: 'storageKey', key: 'storageKey' },
  { title: '文件名', dataIndex: 'fileName', key: 'fileName' },
  { title: '文件状态', dataIndex: 'state', key: 'state' },
  { title: '恢复状态', dataIndex: 'recoveryStatus', key: 'recoveryStatus' },
  { title: '保留状态', key: 'retentionStatus' },
  { title: '清理时间', key: 'retireAfter' },
  { title: '重试次数', dataIndex: 'attempts', key: 'attempts' },
  { title: '字节数', dataIndex: 'payloadBytes', key: 'payloadBytes' },
  { title: '失败分类', dataIndex: 'lastErrorCode', key: 'lastErrorCode' },
  { title: '操作', key: 'actions', width: 380, fixed: 'right' as const },
];

function close() {
  sequence++;
  downloadSequence++;
  visible.value = false;
  loading.value = false;
  retrying.value = false;
  downloadingId.value = undefined;
  error.value = '';
  reason.value = '';
  recordId.value = undefined;
  outputs.value = [];
  closeRetirement();
}
function downloadable(output: EtlFileOutput) {
  return (
    output.state === 'PUBLISHED' &&
    output.recoveryStatus === 'DONE' &&
    (output.retentionStatus === null ||
      output.retentionStatus === undefined ||
      output.retentionStatus === 'KEEP')
  );
}
function eligibleForRetirement(output: EtlFileOutput) {
  return (
    output.state === 'PUBLISHED' &&
    output.recoveryStatus === 'DONE' &&
    output.retentionStatus === 'KEEP'
  );
}
function retryable(output: EtlFileOutput) {
  return (
    canRetry.value &&
    ['MANUAL', 'RETRY'].includes(output.recoveryStatus) &&
    output.retentionStatus !== 'PURGED' &&
    (output.retentionStatus === 'REQUESTED'
      ? canCleanup.value
      : hasAccessByCodes(['dataforge:etl:run']))
  );
}
function retentionLabel(output: EtlFileOutput) {
  const labels = { KEEP: '保留', REQUESTED: '已申请清理', PURGED: '已清理' };
  return output.retentionStatus === null || output.retentionStatus === undefined
    ? '未记录'
    : (labels[output.retentionStatus] ?? '未知状态');
}
function closeRetirement() {
  retirementSequence++;
  retirementOutput.value = undefined;
  retiring.value = false;
  retirementReason.value = '';
  retirementDays.value = 7;
  retirementError.value = '';
}
function requestRetirement(output: EtlFileOutput) {
  if (
    !canCleanup.value ||
    !eligibleForRetirement(output) ||
    loading.value ||
    retrying.value ||
    retiring.value ||
    downloadingId.value !== undefined
  )
    return;
  closeRetirement();
  retirementOutput.value = output;
}
async function retire() {
  const output = retirementOutput.value;
  if (
    !output ||
    !canCleanup.value ||
    !eligibleForRetirement(output) ||
    !visible.value ||
    loading.value ||
    retrying.value ||
    retiring.value ||
    downloadingId.value !== undefined
  )
    return;
  const text = retirementReason.value.trim();
  if (
    !text ||
    text.length > 500 ||
    !retirementOptions.some((option) => option.value === retirementDays.value)
  ) {
    retirementError.value = '请填写清理原因并选择保留期限。';
    return;
  }
  if (
    !outputs.value.some(
      (row) =>
        String(row.id) === String(output.id) &&
        String(row.revision) === String(output.revision) &&
        eligibleForRetirement(row),
    )
  ) {
    retirementError.value = '文件回执已变化，请关闭窗口后刷新。';
    return;
  }
  const current = ++retirementSequence;
  const selectedRecord = recordId.value;
  const actorContext = currentActorContext();
  const stillCurrent = () =>
    current === retirementSequence &&
    visible.value &&
    String(recordId.value) === String(selectedRecord) &&
    currentActorContext() === actorContext &&
    canCleanup.value;
  retiring.value = true;
  retirementError.value = '';
  try {
    const accepted = await retireEtlFileOutput({
      id: output.id,
      expectedRevision: output.revision,
      retireAfter: Date.now() + retirementDays.value * 86_400_000,
      reason: text,
      confirmDeletion: true,
    });
    if (
      !accepted ||
      String(accepted.id) !== String(output.id) ||
      String(accepted.recordId) !== String(output.recordId) ||
      !['PURGED', 'REQUESTED'].includes(accepted.retentionStatus ?? '')
    ) {
      throw new Error('Retirement was not confirmed');
    }
    if (!stillCurrent()) return;
    closeRetirement();
    await refresh();
  } catch {
    if (stillCurrent())
      retirementError.value =
        '清理申请未确认，请关闭窗口并刷新回执；不要据此认为文件已清理。';
  } finally {
    if (current === retirementSequence) retiring.value = false;
  }
}
function currentActorContext() {
  return [
    String(access.accessToken ?? ''),
    String(access.refreshToken ?? ''),
    String(user.userInfo?.userId ?? ''),
  ].join('|');
}
function safeDownloadName(fileName: string) {
  const safe = [...fileName]
    .map((character) => {
      const codePoint = character.codePointAt(0) ?? 0;
      return /[\\/:*?"<>|]/.test(character) ||
        codePoint <= 0x1f ||
        (codePoint >= 0x7f && codePoint <= 0x9f)
        ? '_'
        : character;
    })
    .join('')
    .trim()
    .replace(/^\.+/, '');
  return safe && safe !== '.' && safe !== '..' ? safe : 'etl-file';
}
async function download(output: EtlFileOutput) {
  if (
    !canDownload.value ||
    !downloadable(output) ||
    downloadingId.value !== undefined ||
    loading.value ||
    retrying.value ||
    retiring.value ||
    retirementOutput.value !== undefined
  ) {
    return;
  }
  const current = ++downloadSequence;
  const outputId = String(output.id);
  const selectedRecord = recordId.value;
  const actorContext = currentActorContext();
  downloadingId.value = output.id;
  error.value = '';
  try {
    const blob = await downloadEtlFileOutput(output.id);
    const stillCurrent =
      current === downloadSequence &&
      visible.value &&
      String(recordId.value) === String(selectedRecord) &&
      currentActorContext() === actorContext &&
      canDownload.value &&
      outputs.value.some(
        (row) => String(row.id) === outputId && downloadable(row),
      );
    if (!stillCurrent) return;
    downloadFileFromBlob({
      fileName: safeDownloadName(output.fileName),
      source: blob,
    });
  } catch {
    if (
      current === downloadSequence &&
      visible.value &&
      currentActorContext() === actorContext
    ) {
      error.value = '文件下载未完成，请刷新回执并确认文件仍可下载。';
    }
  } finally {
    if (current === downloadSequence) downloadingId.value = undefined;
  }
}
async function refresh() {
  if (
    recordId.value === undefined ||
    !canDownload.value ||
    loading.value ||
    retrying.value ||
    retiring.value
  )
    return;
  const current = ++sequence;
  const id = recordId.value;
  const actorContext = currentActorContext();
  const stillCurrent = () =>
    current === sequence &&
    visible.value &&
    currentActorContext() === actorContext &&
    String(recordId.value) === String(id) &&
    canDownload.value;
  loading.value = true;
  error.value = '';
  try {
    const rows = await getEtlFileOutputs(id);
    if (stillCurrent()) outputs.value = rows;
  } catch {
    if (stillCurrent()) {
      outputs.value = [];
      error.value = '文件回执加载失败，或当前用户不是该运行的文件所有人。';
    }
  } finally {
    if (current === sequence) loading.value = false;
  }
}
async function open(id: DataforgeEtlApi.Id) {
  close();
  recordId.value = id;
  visible.value = true;
  await refresh();
}
async function retry(output: EtlFileOutput) {
  if (
    !retryable(output) ||
    loading.value ||
    retrying.value ||
    downloadingId.value !== undefined ||
    retiring.value ||
    retirementOutput.value !== undefined
  )
    return;
  if (!reason.value.trim()) {
    error.value = '请先填写重新对账的原因。';
    return;
  }
  const current = sequence;
  const actorContext = currentActorContext();
  const selectedRecord = recordId.value;
  const stillCurrent = () =>
    current === sequence &&
    visible.value &&
    currentActorContext() === actorContext &&
    String(recordId.value) === String(selectedRecord) &&
    outputs.value.some(
      (row) =>
        String(row.id) === String(output.id) &&
        String(row.revision) === String(output.revision) &&
        retryable(row),
    );
  retrying.value = true;
  error.value = '';
  try {
    const accepted = await retryEtlFileOutput({
      id: output.id,
      expectedRevision: output.revision,
      reason: reason.value.trim(),
    });
    if (!accepted) throw new Error('Reconciliation was not accepted');
    if (!stillCurrent()) return;
    reason.value = '';
    retrying.value = false;
    await refresh();
  } catch {
    if (stillCurrent())
      error.value = '重新对账未受理，请刷新核对当前权限、修订号和运行租约。';
  } finally {
    if (current === sequence) retrying.value = false;
  }
}
watch(
  () => [access.accessToken, access.refreshToken, user.userInfo?.userId],
  close,
);
watch(canCleanup, (allowed) => {
  if (!allowed) closeRetirement();
});
watch(canDownload, (allowed) => {
  if (!allowed) close();
});
onBeforeUnmount(close);
defineExpose({ open, close });
</script>

<template>
  <Drawer :open="visible" title="本人文件输出回执" :width="1100" @close="close">
    <Alert
      type="info"
      show-icon
      message="文件逐个发布。恢复仅核验已登记的文件，不代表运行或数据库成果已成功；无法证明结果时保留待人工核查。"
    />
    <Alert v-if="error" class="mt-3" type="error" :message="error" />
    <div class="my-3 flex gap-3">
      <Button
        :loading="loading"
        :disabled="retrying || retiring || retirementOutput !== undefined"
        @click="refresh"
      >
        <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
        刷新回执
      </Button>
      <TextArea
        v-if="canRetry"
        v-model:value="reason"
        :maxlength="500"
        :disabled="retrying"
        placeholder="重新对账原因（必填）；已申请清理的文件会继续核验清理进度"
      />
    </div>
    <Table
      :data-source="outputs"
      :columns="columns"
      :loading="loading"
      :pagination="false"
      :row-key="(row: EtlFileOutput) => String(row.id)"
      :scroll="{ x: 1400 }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'retentionStatus'">
          {{ retentionLabel(record) }}
        </template>
        <template v-else-if="column.key === 'retireAfter'">
          {{
            record.retireAfter == null
              ? '—'
              : formatDateTime(record.retireAfter)
          }}
        </template>
        <template v-else-if="column.key === 'actions'">
          <Button
            v-if="canDownload && downloadable(record)"
            :loading="String(downloadingId) === String(record.id)"
            :disabled="
              loading ||
              retrying ||
              retiring ||
              retirementOutput !== undefined ||
              downloadingId !== undefined
            "
            @click="download(record)"
          >
            <template #icon><IconifyIcon icon="lucide:download" /></template>
            下载
          </Button>
          <Button
            v-if="retryable(record)"
            :loading="retrying"
            :disabled="loading || retiring || retirementOutput !== undefined"
            @click="retry(record)"
          >
            <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
            重新对账
          </Button>
          <Button
            v-if="canCleanup && eligibleForRetirement(record)"
            danger
            :disabled="
              loading ||
              retrying ||
              retiring ||
              retirementOutput !== undefined ||
              downloadingId !== undefined
            "
            @click="requestRetirement(record)"
          >
            <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
            申请清理
          </Button>
        </template>
      </template>
    </Table>
  </Drawer>
  <Modal
    :open="retirementOutput !== undefined && canCleanup"
    title="申请文件清理"
    ok-text="确认申请"
    cancel-text="取消"
    :confirm-loading="retiring"
    :ok-button-props="{
      danger: true,
      disabled: !retirementReason.trim() || retiring,
    }"
    :cancel-button-props="{ disabled: retiring }"
    :closable="!retiring"
    :mask-closable="!retiring"
    @ok="retire"
    @cancel="closeRetirement"
  >
    <p class="mb-3">文件：{{ retirementOutput?.fileName }}</p>
    <Alert
      type="warning"
      show-icon
      message="申请后停止新下载，到期由后台核验并清理文件。历史运行回执保留，清理后无法恢复，请确认文件不再需要。"
    />
    <Select
      v-model:value="retirementDays"
      class="my-3 w-full"
      :options="retirementOptions"
      :disabled="retiring"
    />
    <TextArea
      v-model:value="retirementReason"
      :maxlength="500"
      :disabled="retiring"
      placeholder="清理原因（必填）"
    />
    <Alert
      v-if="retirementError"
      class="mt-3"
      type="error"
      :message="retirementError"
    />
  </Modal>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
