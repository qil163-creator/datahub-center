<script setup lang="ts">
import type { BatchFileApi as Api } from '#/api/dataforge/batchfile';

import { onBeforeUnmount, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { formatDate } from '@vben/utils';

import {
  Alert,
  Button,
  Checkbox,
  message,
  Modal,
  Spin,
  TextArea,
} from 'antdv-next';

import {
  executeBatchFile,
  getBatchFileRun,
  getBatchFileRuns,
  resolveBatchFileRun,
} from '#/api/dataforge/batchfile';
import { createCommandTracker } from '#/api/dataforge/execution/command';
import { useObjectRequest } from '#/hooks/use-object-request';

import { activeRun, idOf, statusOf } from '../model';
import { useBatchConfirm } from '../use-confirm';
import { useBatchScope } from '../use-scope';
import EmptyState from './empty-state.vue';
import GridTools from './grid-tools.vue';
import Pagination from './pagination.vue';
import ReferenceIcon from './reference-icon.vue';

const props = defineProps<{ task: Api.Detail }>();
const emit = defineEmits<{ refresh: [] }>();
const { hasAccessByCodes } = useAccess();
const canExecute = () => hasAccessByCodes(['dataforge:batch-file:execute']);
const rows = ref<Api.Run[]>([]);
const loading = ref(false);
const failed = ref(false);
const busy = ref(false);
const page = ref(1);
const pageSize = ref(10);
const total = ref(0);
const selectedId = ref<Api.Id>();
const selected = ref<Api.Run>();
const open = ref(false);
const detailLoading = ref(false);
const detailFailed = ref(false);
const reason = ref('');
const confirmedStopped = ref(false);
const outputReviewed = ref(false);
const columns = [
  { key: 'id', title: '编号' },
  { key: 'method', title: '执行方式' },
  { key: 'startTime', title: '开始时间' },
  { key: 'endTime', title: '结束时间' },
  { key: 'fileCount', title: '文件总数' },
  { key: 'successCount', title: '成功' },
  { key: 'failedCount', title: '失败' },
  { key: 'status', title: '执行结果' },
  { key: 'actions', title: '操作' },
];
const visible = ref(columns.map((column) => column.key));
const { active, scope } = useBatchScope(() => props.task.id);
const confirm = useBatchConfirm(scope);
const request = useObjectRequest(() => scope.value);
const actionRequest = useObjectRequest(() => scope.value);
const detailRequest = useObjectRequest(() =>
  JSON.stringify([scope.value, open.value, selectedId.value]),
);
const commands = createCommandTracker(() => scope.value);
let timer: ReturnType<typeof setTimeout> | undefined;
function clearTimer() {
  if (timer) clearTimeout(timer);
  timer = undefined;
}
async function load() {
  clearTimer();
  if (!active.value || !hasAccessByCodes(['dataforge:batch-file:query']))
    return;
  const current = request.begin();
  loading.value = true;
  failed.value = false;
  try {
    const result = await getBatchFileRuns({
      taskId: props.task.id,
      pageNo: page.value,
      pageSize: pageSize.value,
    });
    if (current()) {
      rows.value = result.list;
      total.value = result.total;
      if (rows.value.some((run) => ['QUEUED', 'RUNNING'].includes(run.status)))
        timer = setTimeout(() => {
          emit('refresh');
          void load();
        }, 5000);
    }
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
async function detail(id: Api.Id) {
  selectedId.value = id;
  selected.value = undefined;
  open.value = true;
  detailFailed.value = false;
  reason.value = '';
  confirmedStopped.value = false;
  outputReviewed.value = false;
  const current = detailRequest.begin();
  detailLoading.value = true;
  try {
    const result = await getBatchFileRun(id);
    if (current()) selected.value = result;
  } catch {
    if (current()) detailFailed.value = true;
  } finally {
    if (current()) detailLoading.value = false;
  }
}
async function execute() {
  if (
    busy.value ||
    !canExecute() ||
    !props.task.online ||
    activeRun(props.task.lastStatus, props.task.lastRunResolvedTime)
  )
    return;
  const current = actionRequest.begin();
  busy.value = true;
  try {
    const result = await commands.execute(
      `execute:${idOf(props.task.id)}`,
      { id: props.task.id },
      executeBatchFile,
    );
    if (current() && result) {
      message.success(result.replayed ? '已确认原执行请求' : '执行请求已受理');
      page.value = 1;
      emit('refresh');
      await load();
    }
  } finally {
    if (current()) busy.value = false;
  }
}
function confirmExecute() {
  if (
    busy.value ||
    !canExecute() ||
    !props.task.online ||
    activeRun(props.task.lastStatus, props.task.lastRunResolvedTime)
  )
    return;
  const openedScope = scope.value;
  confirm({
    okText: '确定',
    cancelText: '取消',
    title: '执行文件入库',
    content:
      '本次执行将向目标表追加全部文件的数据。此前成功或部分成功写入的行不会自动跳过，再次执行可能产生重复数据。执行人需具备批量文件执行和目标数据写入权限；目标存在禁止写入策略时不可入库。',
    onOk: () => (scope.value === openedScope ? execute() : undefined),
  });
}
async function resolve() {
  if (
    !selected.value ||
    selected.value.resolvedTime ||
    busy.value ||
    !confirmedStopped.value ||
    !outputReviewed.value ||
    !reason.value.trim() ||
    !canExecute()
  )
    return;
  const current = detailRequest.begin();
  const actionCurrent = actionRequest.begin();
  busy.value = true;
  try {
    await resolveBatchFileRun({
      id: selected.value.id,
      reason: reason.value.trim(),
      confirmedStopped: true,
      outputReviewed: true,
    });
    if (current()) {
      message.success('核查结论已登记');
      open.value = false;
      emit('refresh');
      await load();
    }
  } finally {
    if (actionCurrent()) busy.value = false;
  }
}
watch(
  scope,
  () => {
    clearTimer();
    commands.invalidate();
    rows.value = [];
    total.value = 0;
    open.value = false;
    selected.value = undefined;
    loading.value = false;
    busy.value = false;
    page.value = 1;
    void load();
  },
  { immediate: true },
);
watch(
  () => props.task.lastRunId,
  () => {
    void load();
  },
);
onBeforeUnmount(() => {
  clearTimer();
  commands.invalidate();
});
</script>
<template>
  <div class="bf-run-list">
    <Alert
      v-if="failed"
      class="bf-error"
      type="error"
      title="运行实例加载失败，请重试"
      show-icon
      >
<template #action><Button @click="load"><template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>重试</Button></template>
</Alert>
    <GridTools
      v-model:visible="visible"
      :columns="columns"
      hide-search
      @refresh="load"
      >
<Button
        v-if="canExecute()"
        type="primary"
        :loading="busy"
        :disabled="
          !task.online || activeRun(task.lastStatus, task.lastRunResolvedTime)
        "
        @click="confirmExecute"
        >
<ReferenceIcon name="play" />执行一次
</Button>
</GridTools>
    <Spin :spinning="loading" class="bf-run-table">
<div class="bf-table-wrap">
        <table class="bf-table bf-fixed-actions-table">
          <thead>
            <tr>
              <th
                v-for="column in columns.filter((entry) =>
                  visible.includes(entry.key),
                )"
                :key="column.key"
                :class="{ 'bf-sticky-actions': column.key === 'actions' }"
              >
                {{ column.title }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="run in rows" :key="idOf(run.id)">
              <td v-if="visible.includes('id')">{{ run.id }}</td>
              <td v-if="visible.includes('method')">手动执行</td>
              <td v-if="visible.includes('startTime')">
                {{
                  run.startTime
                    ? formatDate(run.startTime, 'YYYY-MM-DD HH:mm:ss')
                    : '-'
                }}
              </td>
              <td v-if="visible.includes('endTime')">
                {{
                  run.endTime
                    ? formatDate(run.endTime, 'YYYY-MM-DD HH:mm:ss')
                    : '-'
                }}
              </td>
              <td v-if="visible.includes('fileCount')">{{ run.fileCount }}</td>
              <td v-if="visible.includes('successCount')">
                {{ run.successCount }}
              </td>
              <td v-if="visible.includes('failedCount')">
                {{ run.failedCount }}
              </td>
              <td v-if="visible.includes('status')">
                <span class="bf-tag" :class="statusOf(run.status).tone">{{
                  statusOf(run.status).label
                }}</span>
              </td>
              <td v-if="visible.includes('actions')" class="bf-sticky-actions">
                <button class="bf-link" @click="detail(run.id)">
                  <ReferenceIcon name="eye" />详情 / 日志
                </button>
              </td>
            </tr>
          </tbody>
        </table>
        <EmptyState v-if="!rows.length && !loading" />
</div>
</Spin>
    <Pagination
      v-if="total"
      v-model:page="page"
      v-model:page-size="pageSize"
      :total="total"
      @change="load"
    />
    <Modal
      v-model:open="open"
      title="运行实例详情"
      width="1100px"
      :footer="null"
      >
<div class="qdata-batch-file bf-modal-body">
        <Alert
          v-if="detailFailed"
          class="bf-error"
          type="error"
          title="运行详情加载失败，请重试"
          show-icon
          >
<template #action>
<Button @click="selectedId !== undefined && detail(selectedId)">
<template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
重试
</Button>
</template>
</Alert>
        <Spin :spinning="detailLoading">
<template v-if="selected">
<div class="bf-actions mb-4">
              <span>编号：{{ selected.id }}</span><span class="bf-tag" :class="statusOf(selected.status).tone">{{
                statusOf(selected.status).label
              }}</span><span>写入行数：{{ selected.writtenRows }}</span><Button @click="detail(selected.id)"><template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>刷新</Button>
            </div>
            <Alert
              v-if="selected.errorMessage"
              class="bf-error"
              type="error"
              :title="selected.errorMessage"
              show-icon
            />
            <div class="bf-table-wrap">
              <table class="bf-table">
                <thead>
                  <tr>
                    <th>文件名称</th>
                    <th>目标表</th>
                    <th>写入顺序</th>
                    <th>写入行数</th>
                    <th>执行状态</th>
                    <th>执行信息</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="item in selected.items" :key="idOf(item.fileId)">
                    <td>{{ item.fileName }}</td>
                    <td>{{ item.targetTable }}</td>
                    <td>{{ item.writeOrder }}</td>
                    <td>{{ item.writtenRows }}</td>
                    <td>
                      <span
                        class="bf-tag"
                        :class="statusOf(item.status).tone"
                        >{{ statusOf(item.status).label }}</span>
                    </td>
                    <td>{{ item.errorMessage || '-' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <Alert
              v-if="selected.resolvedTime"
              class="my-4"
              type="info"
              :title="`已登记核查结论：${selected.resolutionReason || '-'}；核查时间：${formatDate(selected.resolvedTime, 'YYYY-MM-DD HH:mm:ss')}`"
              show-icon
            />
            <template
              v-if="selected.status === 'UNKNOWN' && !selected.resolvedTime"
              >
<Alert
                class="my-4"
                type="warning"
                title="外部写入结果待核查，当前任务暂停后续执行。请确认执行已停止并核对目标表数据后登记结论。"
                show-icon
              />
              <div v-if="canExecute()" class="bf-form">
                <label class="bf-form-row bf-full-row"><span class="bf-form-label">核查说明</span>
                  <div class="bf-form-control">
                    <TextArea
                      v-model:value="reason"
                      :maxlength="500"
                      placeholder="填写已核实的执行状态、目标数据与处理结论"
                      aria-label="核查说明"
                    /></div></label><Checkbox v-model:checked="confirmedStopped">
已确认执行停止
</Checkbox><Checkbox v-model:checked="outputReviewed">
已核对目标数据
</Checkbox><Button
                  type="primary"
                  :loading="busy"
                  :disabled="
                    !confirmedStopped || !outputReviewed || !reason.trim()
                  "
                  @click="resolve"
                  >
<template #icon><IconifyIcon icon="lucide:clipboard-check" /></template>
登记核查结论
</Button>
              </div>
</template>
          </template>
</Spin>
      </div>
</Modal>
  </div>
</template>

<style scoped>
.bf-run-list {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.bf-run-table,
.bf-run-table :deep(.ant-spin-container) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.bf-run-table :deep(.bf-table-wrap) {
  display: flex;
  flex: 1;
  flex-direction: column;
}

.bf-run-table :deep(.bf-empty) {
  flex: 1;
  min-height: 160px;
}

.bf-fixed-actions-table {
  min-width: 1200px;
}

.bf-sticky-actions {
  position: sticky;
  right: 0;
  z-index: 1;
  width: var(--batch-action-width, 160px);
  min-width: var(--batch-action-width, 160px);
  white-space: nowrap;
  background: hsl(var(--card));
  box-shadow: -4px 0 6px -5px hsl(var(--foreground) / 20%);
}

th.bf-sticky-actions {
  z-index: 2;
  background: hsl(var(--accent));
}
</style>
