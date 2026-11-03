<script lang="ts" setup>
import type { DataforgeDataPushTaskApi } from '#/api/dataforge/datapush/task';

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
  getDataPushTaskLog,
  getDataPushTaskLogPage,
  resolveDataPushRun,
} from '#/api/dataforge/datapush/task';
import { useRunCommands } from '#/views/dataforge/execution/use-run-commands';

import LogViewer from '../../../common/log-viewer.vue';
import {
  optionColor,
  optionLabel,
  RUN_STATUS,
  RUN_STATUS_OPTIONS,
  RUN_TYPE_OPTIONS,
} from '../data';

defineOptions({ name: 'DataforgeDataPushTaskLog' });
const emit = defineEmits<{ success: [] }>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const commands = useRunCommands();
const canReview = computed(() =>
  hasAccessByCodes(['dataforge:data-push:resolve']),
);
let opened = false;
const reviewVisible = ref(false);
const reviewLoading = ref(false);
const reviewRecord = ref<DataforgeDataPushTaskApi.TaskLog>();
let reviewSeq = 0;
const review = reactive({
  confirmedStopped: false,
  outputReviewed: false,
  outcome: 'NO_EFFECT' as DataforgeDataPushTaskApi.ResolveCommand['outcome'],
  reason: '',
  stoppedEvidenceRef: '',
  outputEvidenceRef: '',
});
const evidencePattern = /^[A-Z0-9][\w.:/#-]{0,159}$/i;
const reviewValid = computed(
  () =>
    canReview.value &&
    !reviewLoading.value &&
    reviewRecord.value?.runStatus === RUN_STATUS.UNKNOWN &&
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

async function handleReview(row: DataforgeDataPushTaskApi.TaskLog) {
  if (
    !opened ||
    !canReview.value ||
    !row.id ||
    row.runStatus !== RUN_STATUS.UNKNOWN
  )
    return;
  resetReview();
  reviewVisible.value = true;
  const seq = ++reviewSeq;
  reviewLoading.value = true;
  try {
    const fresh = await getDataPushTaskLog(row.id);
    if (seq !== reviewSeq || !opened || !reviewVisible.value) return;
    if (
      String(fresh.id) !== String(row.id) ||
      String(fresh.pushTaskId) !== String(task.value?.id) ||
      fresh.runStatus !== RUN_STATUS.UNKNOWN
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
  const payload: Omit<DataforgeDataPushTaskApi.ResolveCommand, 'requestId'> = {
    recordId: reviewRecord.value.id,
    outcome: review.outcome,
    confirmedStopped: true,
    outputReviewed: true,
    reason: review.reason.trim(),
    stoppedEvidenceRef: review.stoppedEvidenceRef.trim(),
    outputEvidenceRef: review.outputEvidenceRef.trim(),
  };
  const receipt = await commands.execute(
    `push-resolve:${payload.recordId}`,
    payload,
    resolveDataPushRun,
  );
  if (!receipt) return;
  message.success(
    '已记录人工处置；任务保持未发布，水位未改变，请单独评估后续执行',
  );
  reviewVisible.value = false;
  currentLog.value = receipt;
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

const task = ref<DataforgeDataPushTaskApi.Task>();
const rows = ref<DataforgeDataPushTaskApi.TaskLog[]>([]);
const loading = ref(false);
const pagination = reactive({ pageNo: 1, pageSize: 10, total: 0 });
const runStatus = ref<number>();

const currentLog = ref<DataforgeDataPushTaskApi.TaskLog>();
const logLoading = ref(false);

let listRequestSeq = 0;
let logRequestSeq = 0;
let refreshTimer: null | ReturnType<typeof setInterval> = null;

const tableColumns = [
  { title: '编号', dataIndex: 'id', key: 'id', width: 70 },
  { title: '触发方式', key: 'runType', width: 90 },
  { title: '运行状态', key: 'runStatus', width: 90 },
  {
    title: '开始时间',
    dataIndex: 'startTime',
    key: 'startTime',
    width: 160,
    customRender: ({ text }: { text: any }) => formatDateTime(text) || '-',
  },
  {
    title: '结束时间',
    dataIndex: 'endTime',
    key: 'endTime',
    width: 160,
    customRender: ({ text }: { text: any }) => formatDateTime(text) || '-',
  },
  { title: '推出行数', dataIndex: 'dataCount', key: 'dataCount', width: 100 },
  { title: '操作', key: 'actions', width: 200, fixed: 'right' as const },
];

const isPolling = computed(
  () =>
    rows.value.some(
      (item) =>
        item.runStatus === RUN_STATUS.RUNNING ||
        item.runStatus === RUN_STATUS.WAITING,
    ) ||
    currentLog.value?.runStatus === RUN_STATUS.RUNNING ||
    currentLog.value?.runStatus === RUN_STATUS.WAITING,
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
    const result = await getDataPushTaskLogPage({
      pageNo: pagination.pageNo,
      pageSize: pagination.pageSize,
      pushTaskId: task.value.id,
      runStatus: runStatus.value,
    });
    if (seq !== listRequestSeq) {
      return;
    }
    rows.value = result.list ?? [];
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
    const log = await getDataPushTaskLog(id);
    if (seq !== logRequestSeq) {
      return;
    }
    currentLog.value = log;
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

function handleShowLog(row: DataforgeDataPushTaskApi.TaskLog) {
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
      currentLog.value.runStatus === RUN_STATUS.RUNNING
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
  rows.value = [];
  task.value = undefined;
  runStatus.value = undefined;
  currentLog.value = undefined;
  pagination.total = 0;
  loading.value = false;
  logLoading.value = false;
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
      reset();
      return;
    }
    reset();
    opened = true;
    const data = modalApi.getData() as
      | DataforgeDataPushTaskApi.Task
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
        <Button size="small" @click="loadData"
          ><template #icon><IconifyIcon icon="lucide:refresh-cw" /></template
          >刷新</Button
        >
      </div>
      <Table
        :scroll="{ x: 'max-content' }"
        :columns="tableColumns"
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
              <template #icon
                ><IconifyIcon icon="lucide:scroll-text"
              /></template>
              日志
            </Button>
            <Button
              v-if="canReview && record.runStatus === RUN_STATUS.UNKNOWN"
              size="small"
              type="link"
              @click="handleReview(record)"
            >
              <template #icon
                ><IconifyIcon icon="lucide:clipboard-check"
              /></template>
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
              v-if="currentLog.runStatus === RUN_STATUS.RUNNING"
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
        <LogViewer
          :content="currentLog.realTimeLog"
          :error="currentLog.errorInfo"
        />
        <div v-if="currentLog.resolutionRequestId" class="mt-3 text-sm">
          <p>
            人工处置：{{
              currentLog.resolutionOutcome
            }}（非发送成功，水位未改变）
          </p>
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
    v-model:open="reviewVisible"
    title="未知推送人工核查"
    :footer="null"
    :closable="!commands.busy.value"
    :mask-closable="false"
    :keyboard="!commands.busy.value"
  >
    <Alert
      type="warning"
      show-icon
      message="仅有证据证明旧工作者及远端请求已终止，且全部输出未生效或已完整补偿，才可处置。租约到期、超时或点击停止都不是证明。已生效/部分生效/仍未知必须保留 UNKNOWN。处置不会发送数据、推进水位或自动重启任务。"
    />
    <div v-if="reviewLoading" class="my-3">正在读取最新运行凭证...</div>
    <div v-if="reviewRecord" class="my-3">
      <p>
        记录 #{{ reviewRecord.id }} · 执行号
        {{ reviewRecord.executeNo || '旧协议未提供' }}
      </p>
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
          已逐批核对全部输出及补偿范围，确认旧水位下重新执行安全
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
          <template #icon><IconifyIcon icon="lucide:shield-check" /></template>
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
