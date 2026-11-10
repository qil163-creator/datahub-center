<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { DataforgeAccessApi } from '#/api/dataforge/access';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { Page, useVbenDrawer } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';

import { message, Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
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
  ENGINE_TYPE_OPTIONS,
  optionColor,
  optionLabel,
  RELEASE_STATUS_OPTIONS,
  TASK_TYPE_OPTIONS,
  useGridColumns,
  useGridFormSchema,
} from './data';
import Form from './modules/form.vue';
import Task from './modules/task.vue';

defineOptions({ name: 'DataforgeAccess' });

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
const formData = ref<{ id?: DataforgeAccessApi.DataAccess['id'] }>();

function closeForm() {
  formVisible.value = false;
  formData.value = undefined;
}

watch(identity, closeForm);

const [TaskDrawer, taskDrawerApi] = useVbenDrawer({
  connectedComponent: Task,
  destroyOnClose: true,
});

/** 刷新表格 */
function handleRefresh() {
  gridApi.query();
}

/** 新建同步任务 */
function handleCreate() {
  formData.value = undefined;
  formVisible.value = true;
}

/** 编辑同步任务 */
function handleEdit(row: DataforgeAccessApi.DataAccess) {
  formData.value = { id: row.id };
  formVisible.value = true;
}

function handleFormSuccess() {
  closeForm();
  handleRefresh();
}

/** 发布任务 */
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

/** 取消发布 */
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

/** 手动执行一次 */
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

/** 查看执行记录 */
function handleTaskRecords(row: DataforgeAccessApi.DataAccess) {
  taskDrawerApi.setData(row).open();
}

/** 删除同步任务 */
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
    schema: useGridFormSchema(),
  },
  gridOptions: {
    showOverflow: false,
    showHeaderOverflow: 'tooltip',
    columns: useGridColumns(),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          const started = identity.value;
          // API 接入任务在独立的「接口采集」页面维护，这里只展示离线/实时
          const result = await getAccessPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            engineTypes: [1, 2],
            ...formValues,
          });
          return isCurrent(started) ? result : { list: [], total: 0 };
        },
      },
    },
    rowConfig: {
      keyField: 'id',
      isHover: true,
    },
    toolbarConfig: {
      refresh: true,
      search: true,
    },
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
      <Grid :key="identity" table-title="数据同步任务">
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
        <template #engineType="{ row }">
          <Tag :color="optionColor(ENGINE_TYPE_OPTIONS, row.engineType)">
            {{ optionLabel(ENGINE_TYPE_OPTIONS, row.engineType) }}
          </Tag>
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
          <span v-else>-</span>
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
            ]"
            :drop-down-actions="[
              {
                label: row.status === 1 ? '取消发布' : '发布',
                type: 'link',
                icon: row.status === 1 ? 'lucide:square' : 'lucide:send',
                auth: ['dataforge:access:release'],
                popConfirm: {
                  title:
                    row.status === 1
                      ? `确认取消发布「${row.taskName}」吗？${
                          row.taskType === 1
                            ? '将请求停止 SeaTunnel 集群上的实时作业，请在执行记录中确认最终状态。'
                            : ''
                        }`
                      : `确认发布「${row.taskName}」吗？${
                          row.taskType === 2
                            ? '一次性任务发布后将立即执行。'
                            : row.taskType === 1
                              ? '将提交 SeaTunnel 实时作业，请在执行记录中查看受理和运行结果。'
                              : ''
                        }`,
                  confirm:
                    row.status === 1
                      ? handleCancelRelease.bind(null, row)
                      : handleRelease.bind(null, row),
                },
              },
              {
                label: '执行',
                type: 'link',
                icon: 'lucide:play',
                disabled: commands.busy.value,
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
