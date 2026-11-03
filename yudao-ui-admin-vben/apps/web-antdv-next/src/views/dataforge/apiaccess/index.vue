<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { DataforgeAccessApi } from '#/api/dataforge/access';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { Page, useVbenDrawer } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';

import { message, Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  cancelAccess,
  deleteAccess,
  getAccessPage,
  releaseAccess,
  runAccess,
} from '#/api/dataforge/access';
import { $t } from '#/locales';
import { getRunState } from '#/views/dataforge/execution/run-status';
import { useRunCommands } from '#/views/dataforge/execution/use-run-commands';

import {
  optionColor,
  optionLabel,
  RELEASE_STATUS_OPTIONS,
  RUN_STATUS_OPTIONS,
  TASK_TYPE_OPTIONS,
} from '../access/data';
import Form from '../access/modules/form.vue';
import Task from '../access/modules/task.vue';

defineOptions({ name: 'DataforgeApiAccess' });

const access = useAccessStore();
const user = useUserStore();
const identity = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
  ]),
);
const commands = useRunCommands();
let alive = true;
onBeforeUnmount(() => {
  alive = false;
});
const isCurrent = (started: string) => alive && started === identity.value;

const formVisible = ref(false);
const formData = ref<{
  engineType: number;
  id?: DataforgeAccessApi.DataAccess['id'];
}>();

function closeForm() {
  formVisible.value = false;
  formData.value = undefined;
}

watch(identity, closeForm);

const [TaskDrawer, taskDrawerApi] = useVbenDrawer({
  connectedComponent: Task,
  destroyOnClose: true,
});

function handleRefresh() {
  gridApi.query();
}

/** 新建接口采集任务：引擎类型锁定为 API 接入 */
function handleCreate() {
  formData.value = { engineType: 3 };
  formVisible.value = true;
}

function handleEdit(row: DataforgeAccessApi.DataAccess) {
  formData.value = { engineType: 3, id: row.id };
  formVisible.value = true;
}

function handleFormSuccess() {
  closeForm();
  handleRefresh();
}

async function handleRelease(row: DataforgeAccessApi.DataAccess) {
  const started = identity.value;
  const hideLoading = message.loading({ content: '正在发布...', duration: 0 });
  try {
    await releaseAccess(row.id!);
    if (!isCurrent(started)) return;
    message.success('发布成功');
    handleRefresh();
  } finally {
    hideLoading();
  }
}

async function handleCancelRelease(row: DataforgeAccessApi.DataAccess) {
  const started = identity.value;
  const hideLoading = message.loading({
    content: '正在取消发布...',
    duration: 0,
  });
  try {
    await cancelAccess(row.id!);
    if (!isCurrent(started)) return;
    message.success('已取消发布');
    handleRefresh();
  } finally {
    hideLoading();
  }
}

async function handleRun(row: DataforgeAccessApi.DataAccess) {
  if (row.id === undefined) return;
  const receipt = await commands.execute(
    `run:${row.id}`,
    { taskId: row.id },
    runAccess,
  );
  if (!receipt) return;
  message.success(
    `${receipt.replayed ? '已确认原请求' : '运行已受理'}，记录 #${receipt.recordId}：${getRunState('ACCESS', receipt).label}`,
  );
  handleRefresh();
}

function handleTaskRecords(row: DataforgeAccessApi.DataAccess) {
  taskDrawerApi.setData(row).open();
}

async function handleDelete(row: DataforgeAccessApi.DataAccess) {
  const started = identity.value;
  const hideLoading = message.loading({
    content: $t('ui.actionMessage.deleting', [row.taskName]),
    duration: 0,
  });
  try {
    await deleteAccess(row.id!);
    if (!isCurrent(started)) return;
    message.success($t('ui.actionMessage.deleteSuccess', [row.taskName]));
    handleRefresh();
  } finally {
    hideLoading();
  }
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: [
      {
        fieldName: 'taskName',
        label: '任务名称',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入任务名称' },
      },
      {
        fieldName: 'status',
        label: '发布状态',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: RELEASE_STATUS_OPTIONS.map(({ label, value }) => ({
            label,
            value,
          })),
          placeholder: '请选择发布状态',
        },
      },
      {
        fieldName: 'runStatus',
        label: '运行状态',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: RUN_STATUS_OPTIONS.map(({ label, value }) => ({
            label,
            value,
          })),
          placeholder: '请选择运行状态',
        },
      },
    ],
  },
  gridOptions: {
    showOverflow: false,
    showHeaderOverflow: 'tooltip',
    columns: [
      { field: 'id', title: '编号', width: 80 },
      { field: 'taskName', title: '任务名称', minWidth: 160 },
      {
        field: 'taskType',
        title: '调度类型',
        width: 100,
        slots: { default: 'taskType' },
      },
      {
        field: 'status',
        title: '发布状态',
        width: 90,
        slots: { default: 'status' },
      },
      {
        field: 'runStatus',
        title: '最近执行状态',
        width: 120,
        slots: { default: 'runStatus' },
      },
      {
        field: 'targetDatabaseName',
        title: '目的数据源',
        minWidth: 120,
        formatter: ({ cellValue }) => cellValue || '-',
      },
      {
        field: 'startTime',
        title: '上次执行时间',
        width: 160,
        formatter: 'formatDateTime',
      },
      {
        field: 'nextRunTime',
        title: '下次执行时间',
        width: 160,
        formatter: 'formatDateTime',
      },
      {
        field: 'releaseUserName',
        title: '发布人',
        width: 90,
        formatter: ({ cellValue }) => cellValue || '-',
      },
      {
        field: 'releaseTime',
        title: '最新发布时间',
        width: 160,
        formatter: 'formatDateTime',
      },
      {
        title: '操作',
        width: 240,
        minWidth: 240,
        fixed: 'right',
        showOverflow: false,
        className: 'access-action-column',
        slots: { default: 'actions' },
      },
    ],
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          const started = identity.value;
          const result = await getAccessPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            engineType: 3,
            ...formValues,
          });
          return isCurrent(started) ? result : { list: [], total: 0 };
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<DataforgeAccessApi.DataAccess>,
});
</script>

<template>
  <Page auto-content-height>
    <Form
      v-if="formVisible"
      :key="`form:${identity}`"
      embedded
      :initial-data="formData"
      @close="closeForm"
      @success="handleFormSuccess"
    />
    <TaskDrawer :key="`task:${identity}`" />
    <div v-show="!formVisible" class="flex h-full min-h-0 flex-col">
      <Grid :key="identity" table-title="接口采集任务">
        <template #toolbar-tools>
          <TableAction
            :actions="[
              {
                label: $t('common.create'),
                type: 'primary',
                icon: ACTION_ICON.ADD,
                auth: ['dataforge:access:create'],
                onClick: handleCreate,
              },
            ]"
          />
        </template>
        <template #taskType="{ row }">
          <Tag :color="optionColor(TASK_TYPE_OPTIONS, row.taskType)">
            {{ optionLabel(TASK_TYPE_OPTIONS, row.taskType) }}
          </Tag>
        </template>
        <template #status="{ row }">
          <Tag :color="optionColor(RELEASE_STATUS_OPTIONS, row.status)">
            {{ optionLabel(RELEASE_STATUS_OPTIONS, row.status) }}
          </Tag>
        </template>
        <template #runStatus="{ row }">
          <Tag v-if="row.runStatus" :color="getRunState('ACCESS', row).color">
            {{ getRunState('ACCESS', row).label }}
          </Tag>
          <span v-else>未执行</span>
        </template>
        <template #actions="{ row }">
          <TableAction
            :actions="[
              {
                label: $t('common.edit'),
                type: 'link',
                icon: ACTION_ICON.EDIT,
                auth: ['dataforge:access:update'],
                onClick: handleEdit.bind(null, row),
              },
              {
                label: row.status === 1 ? '取消发布' : '发布',
                type: 'link',
                icon: row.status === 1 ? 'lucide:square' : 'lucide:send',
                auth: ['dataforge:access:release'],
                popConfirm: {
                  title:
                    row.status === 1
                      ? `确认取消发布「${row.taskName}」吗？`
                      : `确认发布「${row.taskName}」吗？${
                          row.taskType === 2
                            ? '一次性任务发布后将立即执行。'
                            : ''
                        }`,
                  confirm:
                    row.status === 1
                      ? handleCancelRelease.bind(null, row)
                      : handleRelease.bind(null, row),
                },
              },
            ]"
            :drop-down-actions="[
              {
                label: '执行一次',
                type: 'link',
                icon: 'lucide:play',
                disabled: commands.busy.value || row.engineType === 2,
                auth: ['dataforge:access:run'],
                popConfirm: {
                  title: `确认提交「${row.taskName}」执行请求吗？`,
                  confirm: handleRun.bind(null, row),
                },
              },
              {
                label: '执行记录',
                type: 'link',
                icon: 'lucide:history',
                auth: ['dataforge:access:query'],
                onClick: handleTaskRecords.bind(null, row),
              },
              {
                label: $t('common.delete'),
                type: 'link',
                danger: true,
                icon: ACTION_ICON.DELETE,
                auth: ['dataforge:access:delete'],
                popConfirm: {
                  title: $t('ui.actionMessage.deleteConfirm', [row.taskName]),
                  confirm: handleDelete.bind(null, row),
                },
              },
            ]"
          />
        </template>
      </Grid>
    </div>
  </Page>
</template>

<style scoped>
:deep(.access-action-column),
:deep(.access-action-column .vxe-cell) {
  overflow: visible !important;
}
</style>
