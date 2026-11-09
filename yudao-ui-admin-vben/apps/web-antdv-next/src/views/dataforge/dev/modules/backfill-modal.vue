<script lang="ts" setup>
import type { Dayjs } from 'dayjs';

import type {
  DataforgeDevApi,
  DataforgeDevBackfillApi,
} from '#/api/dataforge/dev';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useAccessStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  DateRangePicker,
  Drawer,
  Input,
  message,
  Modal,
  Progress,
  RadioGroup,
  Table,
  Tag,
  Tooltip,
} from 'antdv-next';
import dayjs from 'dayjs';

import {
  cancelDevBackfill,
  createDevBackfill,
  getDevBackfill,
  getDevBackfillPage,
} from '#/api/dataforge/dev';

import { useRunCommands } from '../../execution/use-run-commands';
import {
  BACKFILL_ITEM_STATUS,
  BACKFILL_MAX_DAYS,
  BACKFILL_STATUS,
  backfillDays,
  backfillProgress,
} from './backfill-model';

const emit = defineEmits<{
  openRecord: [taskId: DataforgeDevApi.Id, recordId: DataforgeDevApi.Id];
}>();

const { hasAccessByCodes } = useAccess();
const accessStore = useAccessStore();
const commands = useRunCommands();

const visible = ref(false);
const task = ref<DataforgeDevApi.Task>();
const loading = ref(false);
const batches = ref<DataforgeDevBackfillApi.Backfill[]>([]);
const total = ref(0);
const pageNo = ref(1);
const expanded = ref<Record<string, DataforgeDevBackfillApi.Item[]>>({});
const form = reactive<{
  dateOrder: 'ASC' | 'DESC';
  failurePolicy: 'CONTINUE' | 'STOP';
  range?: [Dayjs, Dayjs];
  remark: string;
}>({ dateOrder: 'ASC', failurePolicy: 'STOP', remark: '' });
let loadSeq = 0;
let timer: ReturnType<typeof setInterval> | undefined;

const canRun = computed(() => hasAccessByCodes(['dataforge:dev-task:run']));
const days = computed(() =>
  form.range ? backfillDays(form.range[0], form.range[1]) : 0,
);
const hasRunning = computed(() =>
  batches.value.some((item) => item.status === 'RUNNING'),
);
const submitBlocked = computed(
  () =>
    !canRun.value ||
    !task.value ||
    task.value.status !== 1 ||
    !task.value.currentVersionId ||
    !form.range ||
    days.value < 1 ||
    days.value > BACKFILL_MAX_DAYS ||
    hasRunning.value,
);

const columns = [
  { title: '批次', dataIndex: 'id', key: 'id', width: 70 },
  { title: '业务日期区间', key: 'range', width: 200 },
  { title: '策略', key: 'policy', width: 130 },
  { title: '状态', key: 'status', width: 90 },
  { title: '进度', key: 'progress', width: 190 },
  { title: '发起时间', key: 'createTime', width: 160 },
  { title: '操作', key: 'actions', width: 80, fixed: 'right' as const },
];
const itemColumns = [
  { title: '#', dataIndex: 'seq', key: 'seq', width: 56 },
  {
    title: '业务日期',
    dataIndex: 'businessDate',
    key: 'businessDate',
    width: 110,
  },
  { title: '状态', key: 'status', width: 90 },
  { title: '运行实例', key: 'record', width: 100 },
  { title: '说明', key: 'lastError', minWidth: 200 },
];

function disabledDate(current: Dayjs) {
  return current.isAfter(dayjs().endOf('day'));
}

function stopTimer() {
  if (timer) clearInterval(timer);
  timer = undefined;
}

function close() {
  ++loadSeq;
  stopTimer();
  commands.invalidate();
  visible.value = false;
  task.value = undefined;
  batches.value = [];
  expanded.value = {};
  total.value = 0;
  pageNo.value = 1;
  form.range = undefined;
  form.remark = '';
  form.dateOrder = 'ASC';
  form.failurePolicy = 'STOP';
}

onBeforeUnmount(close);
watch(() => [accessStore.accessToken, accessStore.refreshToken], close, {
  flush: 'sync',
});

async function open(row: DataforgeDevApi.Task) {
  close();
  task.value = row;
  visible.value = true;
  await load();
  // 进行中批次每 10 秒刷新一次进度；抽屉关闭即停止。
  timer = setInterval(() => {
    if (hasRunning.value) void load(true);
  }, 10_000);
}

async function load(silent = false) {
  const taskId = task.value?.id;
  if (taskId === undefined || !visible.value) return;
  const seq = ++loadSeq;
  if (!silent) loading.value = true;
  try {
    const page = await getDevBackfillPage({
      pageNo: pageNo.value,
      pageSize: 10,
      taskId,
    });
    if (seq !== loadSeq || !visible.value) return;
    batches.value = page.list;
    total.value = page.total;
    for (const key of Object.keys(expanded.value)) void loadItems(key);
  } finally {
    if (seq === loadSeq) loading.value = false;
  }
}

async function loadItems(id: DataforgeDevApi.Id) {
  const detail = await getDevBackfill(id);
  if (!visible.value) return;
  expanded.value = { ...expanded.value, [String(id)]: detail.items ?? [] };
}

function onExpand(open: boolean, record: DataforgeDevBackfillApi.Backfill) {
  const key = String(record.id);
  if (open) {
    void loadItems(record.id);
  } else {
    expanded.value = Object.fromEntries(
      Object.entries(expanded.value).filter(([id]) => id !== key),
    );
  }
}

async function submit() {
  if (submitBlocked.value || !form.range || !task.value) return;
  const payload: DataforgeDevBackfillApi.CreateReq = {
    taskId: task.value.id!,
    startDate: form.range[0].format('YYYY-MM-DD'),
    endDate: form.range[1].format('YYYY-MM-DD'),
    dateOrder: form.dateOrder,
    failurePolicy: form.failurePolicy,
    remark: form.remark.trim() || undefined,
  };
  const id = await commands.execute(
    `dev-backfill:${payload.taskId}`,
    payload,
    createDevBackfill,
  );
  if (id === undefined || !visible.value) return;
  message.success(`补数据已发起，批次 #${id}，共 ${days.value} 个业务日期`);
  form.range = undefined;
  form.remark = '';
  pageNo.value = 1;
  await load();
}

function confirmCancel(record: DataforgeDevBackfillApi.Backfill) {
  Modal.confirm({
    title: '取消补数据',
    content:
      '未开始的业务日期将直接取消；已提交的运行会发出停止请求，待调度引擎确认后批次结束。确认取消吗？',
    onOk: async () => {
      await cancelDevBackfill(record.id);
      message.success('已请求取消');
      await load();
    },
  });
}

defineExpose({ open, close });
</script>

<template>
  <Drawer
    v-model:open="visible"
    :width="920"
    destroy-on-hidden
    :title="`补数据 · ${task?.name ?? ''}`"
    @close="close"
  >
    <Alert
      class="mb-3"
      type="info"
      show-icon
      message="按业务日期逐日重跑任务当前保存版本"
      description="同一任务同一时间只跑一个日期；每个日期仍校验上游同业务日期依赖和质量检查，未满足时会等待。任务保存了新版本、被下线或执行身份失去权限时，批次终止。"
    />
    <div class="backfill-form">
      <label>
        <span>业务日期</span>
        <DateRangePicker
          v-model:value="form.range"
          :disabled-date="disabledDate"
          :allow-clear="true"
        />
        <span v-if="form.range" class="hint">
          共 {{ days }} 天<template v-if="days > BACKFILL_MAX_DAYS"
            >，超过单批次上限 {{ BACKFILL_MAX_DAYS }} 天</template
          >
        </span>
      </label>
      <label>
        <span>执行顺序</span>
        <RadioGroup
          v-model:value="form.dateOrder"
          :options="[
            { label: '从早到晚', value: 'ASC' },
            { label: '从晚到早', value: 'DESC' },
          ]"
        />
      </label>
      <label>
        <span>失败策略</span>
        <RadioGroup
          v-model:value="form.failurePolicy"
          :options="[
            { label: '失败即停止', value: 'STOP' },
            { label: '继续后续日期', value: 'CONTINUE' },
          ]"
        />
      </label>
      <label>
        <span>备注</span>
        <Input
          v-model:value="form.remark"
          :maxlength="255"
          placeholder="例如：上游口径修正后重算 10 月"
        />
      </label>
      <div class="actions">
        <Tooltip
          :title="
            hasRunning
              ? '已有进行中的批次'
              : task && (task.status !== 1 || !task.currentVersionId)
                ? '任务需已上线且存在保存版本'
                : undefined
          "
        >
          <Button
            type="primary"
            :disabled="submitBlocked"
            :loading="commands.busy.value"
            @click="submit"
          >
            发起补数据
          </Button>
        </Tooltip>
      </div>
    </div>

    <Table
      :scroll="{ x: 'max-content' }"
      :columns="columns"
      :data-source="batches"
      :loading="loading"
      :pagination="{
        current: pageNo,
        pageSize: 10,
        total,
        onChange: (page: number) => {
          pageNo = page;
          void load();
        },
      }"
      :expandable="{ onExpand }"
      row-key="id"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'range'">
          {{ record.startDate }} ~ {{ record.endDate }}
          <div class="hint">{{ record.remark }}</div>
        </template>
        <template v-else-if="column.key === 'policy'">
          {{ record.dateOrder === 'DESC' ? '从晚到早' : '从早到晚' }} ·
          {{ record.failurePolicy === 'CONTINUE' ? '继续' : '失败即停' }}
        </template>
        <template v-else-if="column.key === 'status'">
          <Tag :color="BACKFILL_STATUS[record.status]?.color">
            {{
              record.status === 'RUNNING' && record.cancelRequested
                ? '取消中'
                : (BACKFILL_STATUS[record.status]?.label ?? record.status)
            }}
          </Tag>
        </template>
        <template v-else-if="column.key === 'progress'">
          <Progress
            :percent="
              backfillProgress(record as DataforgeDevBackfillApi.Backfill)
            "
            size="small"
            :status="record.failedCount > 0 ? 'exception' : undefined"
          />
          <div class="hint">
            成功 {{ record.successCount }} / 失败 {{ record.failedCount }} /
            取消 {{ record.cancelledCount }} / 共 {{ record.totalCount }}
          </div>
          <div v-if="record.lastError" class="error">
            {{ record.lastError }}
          </div>
        </template>
        <template v-else-if="column.key === 'createTime'">
          {{ formatDateTime(record.createTime) || '-' }}
        </template>
        <template v-else-if="column.key === 'actions'">
          <Button
            v-if="record.status === 'RUNNING' && !record.cancelRequested"
            type="link"
            size="small"
            danger
            :disabled="!canRun"
            @click="confirmCancel(record as DataforgeDevBackfillApi.Backfill)"
          >
            取消
          </Button>
        </template>
      </template>
      <template #expandedRowRender="{ record }">
        <Table
          :columns="itemColumns"
          :data-source="expanded[String(record.id)] ?? []"
          :pagination="false"
          row-key="id"
          size="small"
        >
          <template #bodyCell="{ column, record: item }">
            <template v-if="column.key === 'status'">
              <Tag :color="BACKFILL_ITEM_STATUS[item.status]?.color">
                {{ BACKFILL_ITEM_STATUS[item.status]?.label ?? item.status }}
              </Tag>
            </template>
            <template v-else-if="column.key === 'record'">
              <Button
                v-if="item.recordId"
                type="link"
                size="small"
                @click="emit('openRecord', record.taskId, item.recordId)"
              >
                #{{ item.recordId }}
              </Button>
              <span v-else>-</span>
            </template>
            <template v-else-if="column.key === 'lastError'">
              {{ item.lastError || '-' }}
            </template>
          </template>
        </Table>
      </template>
    </Table>
  </Drawer>
</template>

<style scoped>
.backfill-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 24px;
  margin-bottom: 16px;
}

.backfill-form label {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.backfill-form label > span:first-child {
  width: 64px;
  color: var(--ant-color-text-secondary, rgb(0 0 0 / 65%));
}

.backfill-form .actions {
  display: flex;
  grid-column: 1 / -1;
  justify-content: flex-end;
}

.hint {
  font-size: 12px;
  color: var(--ant-color-text-tertiary, rgb(0 0 0 / 45%));
}

.error {
  font-size: 12px;
  color: var(--ant-color-error, #ff4d4f);
}
</style>
