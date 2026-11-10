<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page, useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Button, message, Modal, Switch, Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  cancelEtlPipeline,
  copyEtlPipeline,
  deleteEtlPipeline,
  getEtlPipelinePage,
  releaseEtlPipeline,
  runEtlCommand,
  updateEtlPipelineSchedule,
} from '#/api/dataforge/etl';
import { $t } from '#/locales';
import { useRunCommands } from '#/views/dataforge/execution/use-run-commands';

import {
  optionColor,
  optionLabel,
  RUN_STATUS_OPTIONS,
  usePipelineGridColumns,
  usePipelineGridFormSchema,
} from '../data';
import Designer from './designer.vue';
import Detail from './detail.vue';
import InfoForm from './info-form.vue';
import RunParametersDialog from './run-parameters-dialog.vue';

defineOptions({ name: 'DataforgeEtlPipeline' });

const router = useRouter();
const { hasAccessByCodes } = useAccess();
const accessStore = useAccessStore();
const userStore = useUserStore();
const runParametersRef = ref<InstanceType<typeof RunParametersDialog>>();
const pendingRunInputs = new Map<
  string,
  Omit<DataforgeEtlApi.EtlRunCommand, 'requestId'>
>();
const unconfirmedRuns = ref<string[]>([]);
let runInputSequence = 0;
/** 上线/调度开关按发布权限显示；其余按钮权限由 TableAction.auth 控制，接口仍是最终授权边界。 */
const canRelease = computed(() => hasAccessByCodes(['dataforge:etl:release']));
const runCommands = useRunCommands();
watch(
  () => [
    accessStore.accessToken,
    accessStore.refreshToken,
    userStore.userInfo?.userId,
  ],
  () => {
    ++runInputSequence;
    pendingRunInputs.clear();
    unconfirmedRuns.value = [];
    runParametersRef.value?.close();
    handleRefresh();
  },
);

function resetUnconfirmedRuns() {
  if (runCommands.busy.value) return;
  Modal.confirm({
    title: '已核对本页所有未确认请求？',
    content: `请先检查流程日志中的执行记录。新请求可能再次执行，原执行不会被取消。涉及流程：${unconfirmedRuns.value.join('、')}`,
    onOk: () => {
      runCommands.invalidate();
      pendingRunInputs.clear();
      unconfirmedRuns.value = [];
    },
  });
}

const designerRef = ref<InstanceType<typeof Designer>>();
const infoFormRef = ref<InstanceType<typeof InfoForm>>();

const [DetailModal, detailModalApi] = useVbenModal({
  connectedComponent: Detail,
  destroyOnClose: true,
});

function handleRefresh() {
  gridApi.query();
}

/** 上线使用已发布快照；下线需先关调度。 */
function handleToggleStatus(row: DataforgeEtlApi.Pipeline) {
  if (row.status === 1) {
    if (row.scheduleStatus === 1) {
      message.warning('调度上线中，请先下线调度');
      return;
    }
    Modal.confirm({
      title: '系统提示',
      content: `确认要下线"${row.name}"任务吗？已保存草稿将继续保留。`,
      onOk: async () => {
        await cancelEtlPipeline(row.id!);
        message.success('任务已下线');
        handleRefresh();
      },
    });
    return;
  }
  Modal.confirm({
    title: '系统提示',
    content:
      row.taskType === 1
        ? `确认要上线"${row.name}"任务吗？将冻结当前草稿并受理一次执行。`
        : `确认要上线"${row.name}"任务吗？将冻结当前草稿，可开启调度执行发布版本。`,
    onOk: async () => {
      await releaseEtlPipeline(row.id!, row.revision!);
      message.success(
        row.taskType === 1 ? '已上线，执行请求已受理' : '任务已上线',
      );
      handleRefresh();
    },
  });
}

/** 调度状态开关：仅周期任务，需先上线 */
function handleToggleSchedule(row: DataforgeEtlApi.Pipeline) {
  if (row.scheduleStatus === 1) {
    Modal.confirm({
      title: '系统提示',
      content: `确认要下线"${row.name}"的调度吗？下线后不再按 Cron 自动执行。`,
      onOk: async () => {
        await updateEtlPipelineSchedule(row.id!, false);
        message.success('调度已下线');
        handleRefresh();
      },
    });
    return;
  }
  if (row.status !== 1) {
    message.warning('任务尚未上线，请先上线任务');
    return;
  }
  Modal.confirm({
    title: '系统提示',
    content: `确认要上线"${row.name}"的调度吗？将按 Cron（${row.cron}）自动执行。`,
    onOk: async () => {
      await updateEtlPipelineSchedule(row.id!, true);
      message.success('调度已上线');
      handleRefresh();
    },
  });
}

function publishDraft(row: DataforgeEtlApi.Pipeline) {
  Modal.confirm({
    title: '发布当前草稿',
    content: `确认发布「${row.name}」修订 ${row.revision}？后续实例将使用此快照。`,
    onOk: async () => {
      await releaseEtlPipeline(row.id!, row.revision!);
      message.success('草稿已发布');
      handleRefresh();
    },
  });
}

async function handleRunOnce(row: DataforgeEtlApi.Pipeline) {
  if (
    row.id === undefined ||
    row.status !== 1 ||
    !hasAccessByCodes(['dataforge:etl:run'])
  )
    return;
  if (!hasAccessByCodes(['dataforge:etl:query'])) {
    message.warning(
      '查看运行参数需要 ETL 查询权限，请联系管理员授权；本次未提交运行。',
    );
    return;
  }
  const seq = ++runInputSequence;
  const key = String(row.id);
  let payload = pendingRunInputs.get(key);
  if (!payload) {
    const selection = await runParametersRef.value?.open(row.id);
    if (!selection || seq !== runInputSequence) return;
    payload = { taskId: row.id, ...selection };
    pendingRunInputs.set(key, payload);
  }
  let receipt;
  try {
    receipt = await runCommands.execute(
      `etl:run:${key}`,
      payload,
      runEtlCommand,
    );
  } catch {
    if (seq === runInputSequence && !unconfirmedRuns.value.includes(key))
      unconfirmedRuns.value.push(key);
    return;
  }
  if (receipt) {
    pendingRunInputs.delete(key);
    unconfirmedRuns.value = unconfirmedRuns.value.filter(
      (item) => item !== key,
    );
  }
  if (seq !== runInputSequence) return;
  if (!receipt) return;
  void router.push({
    path: '/develop/etl/record',
    query: {
      recordId: String(receipt.recordId),
      pipelineId: String(row.id),
    },
  });
  message.success('已提交执行，可在「流程日志」中查看进度');
  handleRefresh();
}

function handleRecords(row: DataforgeEtlApi.Pipeline) {
  router.push({
    path: '/develop/etl/record',
    query: { pipelineId: String(row.id) },
  });
}

async function handleCopy(row: DataforgeEtlApi.Pipeline) {
  await copyEtlPipeline(row.id!);
  message.success('流程已克隆');
  handleRefresh();
}

async function handleDelete(row: DataforgeEtlApi.Pipeline) {
  await deleteEtlPipeline(row.id!);
  message.success('流程已删除');
  handleRefresh();
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: usePipelineGridFormSchema(),
  },
  gridOptions: {
    showOverflow: false,
    showHeaderOverflow: 'tooltip',
    columns: usePipelineGridColumns(),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          return await getEtlPipelinePage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<DataforgeEtlApi.Pipeline>,
});
</script>

<template>
  <Page auto-content-height>
    <RunParametersDialog ref="runParametersRef" />
    <Alert
      v-if="unconfirmedRuns.length"
      type="warning"
      message="上次运行受理结果尚未确认，再次运行将原样重试。"
    >
      <template #action>
        <Button
          :disabled="runCommands.busy.value"
          @click="resetUnconfirmedRuns"
        >
          <template #icon><IconifyIcon icon="lucide:settings" /></template>
          核对记录后重新配置
        </Button>
      </template>
    </Alert>
    <Grid table-title="生产流程">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('common.create'),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['dataforge:etl:save'],
              onClick: () => infoFormRef?.open(),
            },
          ]"
        />
      </template>
      <template #status="{ row }">
        <Switch
          v-if="canRelease"
          :checked="row.status === 1"
          checked-children="已上线"
          size="small"
          un-checked-children="未上线"
          @click="handleToggleStatus(row)"
        />
        <Tag v-else>{{ row.status === 1 ? '已发布' : '未发布' }}</Tag>
      </template>
      <template #scheduleStatus="{ row }">
        <Switch
          v-if="row.taskType === 2 && canRelease"
          :checked="row.scheduleStatus === 1"
          checked-children="开"
          size="small"
          un-checked-children="关"
          @click="handleToggleSchedule(row)"
        />
        <span v-else>{{
          row.taskType === 2
            ? row.scheduleStatus === 1
              ? '调度中'
              : '未调度'
            : '-'
        }}</span>
      </template>
      <template #runStatus="{ row }">
        <Tag
          v-if="row.runStatus !== null && row.runStatus !== undefined"
          :color="optionColor(RUN_STATUS_OPTIONS, row.runStatus)"
        >
          {{ optionLabel(RUN_STATUS_OPTIONS, row.runStatus) }}
        </Tag>
        <span v-else>-</span>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '详情',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              onClick: () => detailModalApi.setData({ id: row.id }).open(),
            },
          ]"
          :drop-down-actions="[
            {
              label: '配置任务',
              type: 'link',
              icon: ACTION_ICON.EDIT,
              auth: ['dataforge:etl:save'],
              onClick: () => designerRef?.open(row.id!),
            },
            {
              label: '发布当前草稿',
              type: 'link',
              icon: 'lucide:send',
              auth: ['dataforge:etl:release'],
              onClick: () => publishDraft(row),
            },
            {
              label: '编辑信息',
              auth: ['dataforge:etl:save'],
              type: 'link',
              icon: ACTION_ICON.EDIT,
              onClick: () => infoFormRef?.open(row.id),
            },
            {
              label: '执行一次',
              auth: ['dataforge:etl:run'],
              type: 'link',
              icon: 'lucide:play',
              disabled: row.status !== 1 || runCommands.busy.value,
              popConfirm: {
                title: `确认要执行一次「${row.name}」吗？`,
                confirm: handleRunOnce.bind(null, row),
              },
            },
            {
              label: '运行实例',
              type: 'link',
              icon: 'lucide:history',
              onClick: handleRecords.bind(null, row),
            },
            {
              label: '克隆',
              auth: ['dataforge:etl:save'],
              type: 'link',
              icon: ACTION_ICON.COPY,
              onClick: handleCopy.bind(null, row),
            },
            {
              label: $t('common.delete'),
              auth: ['dataforge:etl:delete'],
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              disabled: row.status === 1,
              popConfirm: {
                title: `确认要删除「${row.name}」流程吗？`,
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>

    <Designer ref="designerRef" @saved="handleRefresh" />
    <InfoForm ref="infoFormRef" @success="handleRefresh" />
    <DetailModal />
  </Page>
</template>

<style scoped>
:deep(.etl-action-column),
:deep(.etl-action-column .vxe-cell) {
  overflow: visible !important;
}
</style>
