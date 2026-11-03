<script lang="ts" setup>
import type { GovernanceMasterDistributeApi } from '#/api/governance/masterdistribute';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Checkbox,
  Input,
  message,
  Modal as ReviewModal,
  Select,
  Table,
  Tag,
  TextArea,
} from 'antdv-next';

import {
  getMasterDistributeLog,
  getMasterDistributeLogPage,
  resolveMasterDistributeRun,
  stopMasterDistributeRun,
} from '#/api/governance/masterdistribute';
import { useRunCommands } from '#/views/dataforge/execution/use-run-commands';

import {
  optionColor,
  optionLabel,
  RUN_STATUS_OPTIONS,
  RUN_TYPE_OPTIONS,
} from '../data';

defineOptions({ name: 'GovernanceMasterDistributeLog' });
const emit = defineEmits<{ success: [] }>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const commands = useRunCommands();
const canReview = computed(() =>
  hasAccessByCodes(['governance:master-distribute:resolve']),
);
let opened = false;
const canStop = computed(() =>
  hasAccessByCodes(['governance:master-distribute:run']),
);
const stopVisible = ref(false);
const stopLoading = ref(false);
const stopBusy = ref(false);
const stopRecord = ref<GovernanceMasterDistributeApi.DistributeLog>();
const stopReason = ref('');
let stopSeq = 0;
let sentStopReason: string | undefined;
const stopValid = computed(
  () =>
    canStop.value &&
    !stopLoading.value &&
    !!stopRecord.value?.id &&
    [1, 2].includes(stopRecord.value.runStatus ?? 0) &&
    !!stopReason.value.trim() &&
    stopReason.value.length <= 500,
);
function resetStop() {
  ++stopSeq;
  stopRecord.value = undefined;
  stopReason.value = '';
  sentStopReason = undefined;
  stopLoading.value = false;
  stopBusy.value = false;
}
async function handleStop(row: GovernanceMasterDistributeApi.DistributeLog) {
  if (
    !opened ||
    !canStop.value ||
    !row.id ||
    ![1, 2].includes(row.runStatus ?? 0)
  )
    return;
  resetStop();
  stopVisible.value = true;
  stopLoading.value = true;
  const seq = ++stopSeq;
  try {
    const fresh = await getMasterDistributeLog(row.id);
    if (seq !== stopSeq || !opened || !stopVisible.value) return;
    if (
      String(fresh.id) !== String(row.id) ||
      String(fresh.distributeId) !== String(task.value?.id) ||
      ![1, 2].includes(fresh.runStatus ?? 0)
    ) {
      message.warning('运行状态已变化，请刷新执行记录，不会改为停止其他运行');
      return;
    }
    stopRecord.value = fresh;
  } finally {
    if (seq === stopSeq) stopLoading.value = false;
  }
}
async function submitStop() {
  if (
    !opened ||
    !stopVisible.value ||
    !stopValid.value ||
    stopBusy.value ||
    !stopRecord.value?.id
  )
    return;
  const payload = {
    recordId: stopRecord.value.id,
    reason: stopReason.value.trim(),
  };
  if (sentStopReason !== undefined && sentStopReason !== payload.reason) {
    message.warning('停止结果可能已落库，重试请保持原原因，或刷新执行记录核查');
    return;
  }
  sentStopReason = payload.reason;
  const seq = stopSeq;
  stopBusy.value = true;
  try {
    const receipt = await stopMasterDistributeRun(payload);
    if (seq !== stopSeq || !opened || !stopVisible.value) return;
    if (
      String(receipt.id) !== String(payload.recordId) ||
      String(receipt.distributeId) !== String(task.value?.id) ||
      ![4, 5, 6].includes(receipt.runStatus ?? 0) ||
      !receipt.stopRequestedAt ||
      receipt.stopReason !== payload.reason
    ) {
      message.warning('停止回执不匹配，请刷新执行记录核查');
      return;
    }
    message.success('停止请求已记录；发布及排期未改变，已发送的数据仍须核查');
    stopVisible.value = false;
    ++logRequestSeq;
    logLoading.value = false;
    currentLog.value = receipt;
    observedStatuses.set(String(receipt.id), receipt.runStatus);
    emit('success');
    await loadData();
  } finally {
    if (seq === stopSeq) stopBusy.value = false;
  }
}
watch(
  stopVisible,
  (visible) => {
    if (!visible) resetStop();
  },
  { flush: 'sync' },
);

const reviewVisible = ref(false);
const reviewLoading = ref(false);
const reviewRecord = ref<GovernanceMasterDistributeApi.DistributeLog>();
let reviewSeq = 0;
const review = reactive({
  confirmedStopped: false,
  outputReviewed: false,
  outcome:
    'NO_EFFECT' as GovernanceMasterDistributeApi.ResolveCommand['outcome'],
  reason: '',
  stoppedEvidenceRef: '',
  outputEvidenceRef: '',
});
const evidencePattern = /^[A-Z0-9][\w.:/#-]{0,159}$/i;
const reviewValid = computed(
  () =>
    canReview.value &&
    !reviewLoading.value &&
    reviewRecord.value?.runStatus === 5 &&
    review.confirmedStopped &&
    review.outputReviewed &&
    ['COMPENSATED', 'NO_EFFECT'].includes(review.outcome) &&
    !!review.reason.trim() &&
    review.reason.trim().length <= 500 &&
    evidencePattern.test(review.stoppedEvidenceRef.trim()) &&
    evidencePattern.test(review.outputEvidenceRef.trim()),
);

function resetReview() {
  ++reviewSeq;
  commands.invalidate();
  reviewRecord.value = undefined;
  reviewLoading.value = false;
  Object.assign(review, {
    confirmedStopped: false,
    outputReviewed: false,
    outcome: 'NO_EFFECT',
    reason: '',
    stoppedEvidenceRef: '',
    outputEvidenceRef: '',
  });
}

async function handleReview(row: GovernanceMasterDistributeApi.DistributeLog) {
  if (!opened || !canReview.value || !row.id || row.runStatus !== 5) return;
  resetReview();
  reviewVisible.value = true;
  const seq = ++reviewSeq;
  reviewLoading.value = true;
  try {
    const fresh = await getMasterDistributeLog(row.id);
    if (seq !== reviewSeq || !opened || !reviewVisible.value) return;
    if (
      String(fresh.id) !== String(row.id) ||
      String(fresh.distributeId) !== String(task.value?.id) ||
      fresh.runStatus !== 5
    ) {
      message.warning('运行状态已变化，请刷新日志后重新核查');
      return;
    }
    reviewRecord.value = fresh;
  } finally {
    if (seq === reviewSeq) reviewLoading.value = false;
  }
}

async function submitReview() {
  if (!opened || !reviewValid.value || !reviewRecord.value?.id) return;
  const payload: Omit<
    GovernanceMasterDistributeApi.ResolveCommand,
    'requestId'
  > = {
    recordId: reviewRecord.value.id,
    outcome: review.outcome,
    confirmedStopped: true,
    outputReviewed: true,
    reason: review.reason.trim(),
    stoppedEvidenceRef: review.stoppedEvidenceRef.trim(),
    outputEvidenceRef: review.outputEvidenceRef.trim(),
  };
  const receipt = await commands.execute(
    `master-distribute-resolve:${payload.recordId}`,
    payload,
    resolveMasterDistributeRun,
  );
  if (!receipt) return;
  message.success(
    '已记录人工处置；任务保持未发布，不代表派发成功，请单独评估后续执行',
  );
  reviewVisible.value = false;
  ++logRequestSeq;
  logLoading.value = false;
  currentLog.value = receipt;
  observedStatuses.set(String(receipt.id), receipt.runStatus);
  emit('success');
  await loadData();
}
watch(
  reviewVisible,
  (visible) => {
    if (!visible) resetReview();
  },
  { flush: 'sync' },
);

const POLL_MS = 5000;

const task = ref<GovernanceMasterDistributeApi.Distribute>();
const rows = ref<GovernanceMasterDistributeApi.DistributeLog[]>([]);
const loading = ref(false);
const pagination = reactive({ pageNo: 1, pageSize: 10, total: 0 });
const runStatus = ref<number>();

const currentLog = ref<GovernanceMasterDistributeApi.DistributeLog>();
const logLoading = ref(false);

let listRequestSeq = 0;
let logRequestSeq = 0;
let refreshTimer: null | ReturnType<typeof setInterval> = null;
const observedStatuses = new Map<string, number | undefined>();

function observedRunFinished(log: GovernanceMasterDistributeApi.DistributeLog) {
  const key = String(log.id);
  const previous = observedStatuses.get(key);
  observedStatuses.set(key, log.runStatus);
  return [1, 2].includes(previous ?? 0) && ![1, 2].includes(log.runStatus ?? 0);
}

const tableColumns = [
  { title: '编号', dataIndex: 'id', key: 'id', width: 70 },
  { title: '触发方式', key: 'runType', width: 90 },
  { title: '运行状态', key: 'runStatus', width: 90 },
  {
    title: '开始时间',
    dataIndex: 'startTime',
    key: 'startTime',
    width: 160,
    customRender: ({ text }: { text?: number }) => formatDateTime(text) || '-',
  },
  {
    title: '结束时间',
    dataIndex: 'endTime',
    key: 'endTime',
    width: 160,
    customRender: ({ text }: { text?: number }) => formatDateTime(text) || '-',
  },
  { title: '派发记录数', dataIndex: 'dataCount', key: 'dataCount', width: 100 },
  { title: '数据量', dataIndex: 'byteCount', key: 'byteCount', width: 90 },
  { title: '操作', key: 'actions', width: 240, fixed: 'right' as const },
];

const isPolling = computed(
  () =>
    rows.value.some((item) => item.runStatus === 2 || item.runStatus === 1) ||
    currentLog.value?.runStatus === 2 ||
    currentLog.value?.runStatus === 1,
);

const logTitle = computed(() =>
  currentLog.value ? `实时日志（记录 #${currentLog.value.id}）` : '实时日志',
);

async function loadData() {
  if (!opened || !task.value?.id) {
    return;
  }
  const seq = ++listRequestSeq;
  loading.value = true;
  try {
    const result = await getMasterDistributeLogPage({
      pageNo: pagination.pageNo,
      pageSize: pagination.pageSize,
      distributeId: task.value.id,
      runStatus: runStatus.value,
    });
    if (seq !== listRequestSeq) {
      return;
    }
    rows.value = result.list ?? [];
    const finished = rows.value.map(observedRunFinished).some(Boolean);
    if (finished) emit('success');
    pagination.total = Number(result.total ?? 0);
  } finally {
    if (seq === listRequestSeq) {
      loading.value = false;
    }
  }
}

async function loadLogDetail(id: number | string) {
  const seq = ++logRequestSeq;
  logLoading.value = true;
  try {
    const log = await getMasterDistributeLog(id);
    if (seq !== logRequestSeq) {
      return;
    }
    currentLog.value = log;
    if (observedRunFinished(log)) emit('success');
  } finally {
    if (seq === logRequestSeq) {
      logLoading.value = false;
    }
  }
}

function handleSearch() {
  pagination.pageNo = 1;
  loadData();
}

function handlePageChange(page: number, pageSize: number) {
  pagination.pageNo = page;
  pagination.pageSize = pageSize;
  loadData();
}

function handleShowLog(row: GovernanceMasterDistributeApi.DistributeLog) {
  if (!opened || !row.id) {
    return;
  }
  currentLog.value = row;
  loadLogDetail(row.id);
}

function startAutoRefresh() {
  stopAutoRefresh();
  refreshTimer = setInterval(() => {
    if (!isPolling.value) {
      return;
    }
    if (!loading.value) {
      loadData();
    }
    if (
      currentLog.value?.id &&
      [1, 2].includes(currentLog.value.runStatus ?? 0)
    ) {
      loadLogDetail(currentLog.value.id);
    }
  }, POLL_MS);
}

function stopAutoRefresh() {
  if (refreshTimer) {
    clearInterval(refreshTimer);
    refreshTimer = null;
  }
}

function reset() {
  opened = false;
  stopAutoRefresh();
  listRequestSeq++;
  logRequestSeq++;
  reviewVisible.value = false;
  resetReview();
  stopVisible.value = false;
  resetStop();
  rows.value = [];
  task.value = undefined;
  runStatus.value = undefined;
  currentLog.value = undefined;
  pagination.total = 0;
  loading.value = false;
  logLoading.value = false;
  observedStatuses.clear();
}
onBeforeUnmount(reset);
watch(
  () => [access.accessToken, access.refreshToken, user.userInfo?.userId],
  () => {
    reset();
    void modalApi.close();
  },
  { flush: 'sync' },
);

const getTitle = computed(() =>
  task.value ? `执行记录 - ${task.value.name}` : '执行记录',
);

const [Modal, modalApi] = useVbenModal({
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(isOpen: boolean) {
    if (!isOpen) {
      if (opened) emit('success');
      reset();
      return;
    }
    reset();
    opened = true;
    const data = modalApi.getData() as
      | GovernanceMasterDistributeApi.Distribute
      | undefined;
    task.value = data;
    pagination.pageNo = 1;
    const seq = listRequestSeq + 1;
    await loadData();
    if (opened && seq === listRequestSeq) startAutoRefresh();
  },
});
</script>

<template>
  <Modal :title="getTitle" class="w-[960px]">
    <div class="mx-4 pb-2">
      <div class="mb-3 flex items-center gap-2">
        <span class="text-sm">运行状态</span>
        <Select
          v-model:value="runStatus"
          :options="
            RUN_STATUS_OPTIONS.map(({ label, value }) => ({ label, value }))
          "
          allow-clear
          class="w-36"
          placeholder="全部"
          @change="handleSearch"
        />
        <Button size="small" @click="loadData">
          <template #icon>
            <IconifyIcon icon="lucide:refresh-cw" />
          </template>
          刷新
        </Button>
      </div>
      <Table
        :columns="tableColumns"
        :scroll="{ x: 'max-content' }"
        :data-source="rows"
        :loading="loading"
        :pagination="{
          current: pagination.pageNo,
          pageSize: pagination.pageSize,
          total: pagination.total,
          showSizeChanger: true,
          showTotal: (total: number) => `共 ${total} 条`,
          onChange: handlePageChange,
        }"
        row-key="id"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'runType'">
            {{ optionLabel(RUN_TYPE_OPTIONS, record.runType) }}
          </template>
          <template v-else-if="column.key === 'runStatus'">
            <Tag :color="optionColor(RUN_STATUS_OPTIONS, record.runStatus)">
              {{ optionLabel(RUN_STATUS_OPTIONS, record.runStatus) }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'actions'">
            <Button size="small" type="link" @click="handleShowLog(record)">
              <template #icon><IconifyIcon icon="lucide:file-text" /></template>
              日志
            </Button>
            <Button
              v-if="canStop && [1, 2].includes(record.runStatus ?? 0)"
              danger
              size="small"
              type="link"
              @click="handleStop(record)"
            >
              <template #icon><IconifyIcon icon="lucide:square" /></template>
              停止本次运行
            </Button>
            <Button
              v-if="canReview && record.runStatus === 5"
              size="small"
              type="link"
              @click="handleReview(record)"
            >
              <template #icon>
                <IconifyIcon icon="lucide:shield-check" />
              </template>
              核查处置
            </Button>
          </template>
        </template>
      </Table>

      <div v-if="currentLog" class="mt-4">
        <div class="mb-2 flex items-center justify-between">
          <div class="flex items-center gap-2">
            <span class="text-sm font-medium">{{ logTitle }}</span>
            <Tag :color="optionColor(RUN_STATUS_OPTIONS, currentLog.runStatus)">
              {{ optionLabel(RUN_STATUS_OPTIONS, currentLog.runStatus) }}
            </Tag>
            <span
              v-if="currentLog.runStatus === 2"
              class="text-muted-foreground text-xs"
            >
              运行中会每 {{ POLL_MS / 1000 }} 秒自动刷新日志
            </span>
          </div>
          <Button
            :loading="logLoading"
            size="small"
            @click="currentLog.id && loadLogDetail(currentLog.id)"
          >
            <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
            刷新日志
          </Button>
        </div>
        <pre class="whitespace-pre-wrap">{{
          currentLog.errorInfo || '无错误信息'
        }}</pre>
        <div v-if="currentLog.stopRequestedAt" class="mt-3 text-sm">
          <p>
            停止操作者：{{ currentLog.stopRequestedBy }} ·
            {{ formatDateTime(currentLog.stopRequestedAt) }}
          </p>
          <p>停止原因：{{ currentLog.stopReason }}</p>
          <p>这是停止请求回执，不是远端终止或回滚证明。</p>
        </div>
        <div v-if="currentLog.resolutionRequestId" class="mt-3 text-sm">
          <p>人工处置：{{ currentLog.resolutionOutcome }}（不代表派发成功）</p>
          <p>
            核查人：{{ currentLog.resolvedBy }} ·
            {{ formatDateTime(currentLog.resolvedAt) }}
          </p>
          <p>请求编号：{{ currentLog.resolutionRequestId }}</p>
          <p>原因：{{ currentLog.resolutionReason }}</p>
          <p>终止证据：{{ currentLog.stoppedEvidenceRef }}</p>
          <p>输出证据：{{ currentLog.outputEvidenceRef }}</p>
        </div>
      </div>
    </div>
  </Modal>
  <ReviewModal
    v-model:open="stopVisible"
    title="停止指定派发运行"
    :footer="null"
    :closable="!stopBusy"
    :mask-closable="false"
    :keyboard="!stopBusy"
  >
    <Alert
      type="warning"
      show-icon
      message="只停止下面这条运行，不改变发布状态和周期排期。等待发送的记录会终止；已进入发送的记录保留 UNKNOWN 并阻止重跑。远端请求或异步消费可能继续，必须另行核查；如需停用后续排期，请取消发布。"
    />
    <div v-if="stopLoading" class="my-3">正在读取最新运行凭证...</div>
    <div v-if="stopRecord" class="my-3 flex flex-col gap-3">
      <p>执行记录 #{{ stopRecord.id }}</p>
      <TextArea
        v-model:value="stopReason"
        aria-label="停止原因"
        placeholder="请说明停止原因，不含凭据或业务载荷"
        :maxlength="500"
        :disabled="stopBusy"
      />
      <Button
        danger
        type="primary"
        :loading="stopBusy"
        :disabled="!stopValid || stopBusy"
        @click="submitStop"
      >
        确认停止本次运行
      </Button>
    </div>
  </ReviewModal>
  <ReviewModal
    v-model:open="reviewVisible"
    title="未知派发人工核查"
    :footer="null"
    :closable="!commands.busy.value"
    :mask-closable="false"
    :keyboard="!commands.busy.value"
  >
    <Alert
      type="warning"
      show-icon
      message="仅有证据证明旧工作者及远端请求已终止，且全部输出未生效或已完整补偿，才可处置。租约到期、超时或点击停止都不是证明。已生效/部分生效/仍未知必须保留 UNKNOWN。处置不会发送数据或自动重启任务。"
    />
    <div v-if="reviewLoading" class="my-3">正在读取最新运行凭证...</div>
    <div v-if="reviewRecord" class="my-3">
      <p>记录 #{{ reviewRecord.id }}</p>
      <p>原始错误：{{ reviewRecord.errorInfo }}</p>
      <fieldset
        :disabled="commands.busy.value"
        class="mt-3 flex flex-col gap-3"
      >
        <Checkbox
          v-model:checked="review.confirmedStopped"
          :disabled="commands.busy.value"
        >
          已验证旧工作者及所有远端请求/异步消费终止，不会继续写入
        </Checkbox>
        <Checkbox
          v-model:checked="review.outputReviewed"
          :disabled="commands.busy.value"
        >
          已逐批核对全部输出及补偿范围，确认后续重新派发安全
        </Checkbox>
        <Select
          v-model:value="review.outcome"
          :disabled="commands.busy.value"
          :options="[
            { label: '确认全部未生效', value: 'NO_EFFECT' },
            { label: '确认已完整补偿', value: 'COMPENSATED' },
          ]"
        />
        <TextArea
          v-model:value="review.reason"
          aria-label="核查原因"
          placeholder="核查原因（不含凭据或业务载荷）"
          :maxlength="500"
          :disabled="commands.busy.value"
        />
        <Input
          v-model:value="review.stoppedEvidenceRef"
          aria-label="终止证据编号"
          placeholder="受控终止证据编号，如 CASE-STOP-001"
          :maxlength="160"
          :disabled="commands.busy.value"
        />
        <Input
          v-model:value="review.outputEvidenceRef"
          aria-label="输出证据编号"
          placeholder="受控输出/补偿证据编号，如 CASE-OUTPUT-001"
          :maxlength="160"
          :disabled="commands.busy.value"
        />
        <p class="text-muted-foreground text-xs">
          证据编号仅允许字母、数字、点、下划线、冒号、斜杠、#
          和连字符。请在受控工单保留不可变核查材料；系统不自动验证外部材料真实性。失败重试请保留原内容。
        </p>
        <Button
          type="primary"
          :loading="commands.busy.value"
          :disabled="!reviewValid || commands.busy.value"
          @click="submitReview"
        >
          <template #icon>
            <IconifyIcon icon="lucide:clipboard-check" />
          </template>
          提交核查并停用排期
        </Button>
      </fieldset>
    </div>
  </ReviewModal>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
