<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { DataforgeFillApi } from '#/api/dataforge/fill';

import { ref } from 'vue';

import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { formatDateTime } from '@vben/utils';

import {
  Button,
  Drawer,
  Input,
  message,
  Modal,
  Table,
  Tag,
  Tooltip,
} from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  cancelCorrection,
  createCorrection,
  getCorrectionPage,
  getMergeBatchPage,
  retryMergeBatch,
} from '#/api/dataforge/fill';

import {
  MERGE_STATUS_TEXT,
  useMergeGridColumns,
  useMergeGridFormSchema,
} from '../data';
import { CORRECTION_STATUS_TEXT } from '../shared';

defineOptions({ name: 'DataforgeFillMerge' });

function handleRefresh() {
  gridApi.query();
}

function handleRetry(batch: DataforgeFillApi.MergeBatch) {
  Modal.confirm({
    title: '重试合并确认',
    content: `将批次 ${batch.id} 置回待执行并由合并任务自动拾取（不需要重新审批）。`,
    onOk: async () => {
      await retryMergeBatch(batch.id);
      message.success('已置回待执行');
      handleRefresh();
    },
  });
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: useMergeGridFormSchema(),
  },
  gridOptions: {
    showOverflow: false,
    showHeaderOverflow: 'tooltip',
    columns: useMergeGridColumns(),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          return await getMergeBatchPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<DataforgeFillApi.MergeBatch>,
});

// ---------------------------------------------------------------- 数据更正
const correctionModalOpen = ref(false);
const correctionSaving = ref(false);
const correctionSubmissionId = ref<number>();
const correctionReason = ref('');

function openCorrectionModal(batch: DataforgeFillApi.MergeBatch) {
  correctionSubmissionId.value = batch.submissionId;
  correctionReason.value = '';
  correctionModalOpen.value = true;
}

async function handleCreateCorrection() {
  if (!correctionSubmissionId.value) return;
  if (!correctionReason.value.trim()) {
    message.warning('请填写更正原因');
    return;
  }
  correctionSaving.value = true;
  try {
    await createCorrection({
      reason: correctionReason.value.trim(),
      submissionId: correctionSubmissionId.value,
    });
    message.success(
      '已发起更正：实例转入整改中，填报人在【我的填报】修改后重新提交审批',
    );
    correctionModalOpen.value = false;
    await loadCorrections();
  } finally {
    correctionSaving.value = false;
  }
}

const correctionsOpen = ref(false);
const correctionsLoading = ref(false);
const corrections = ref<DataforgeFillApi.Correction[]>([]);

async function loadCorrections() {
  correctionsLoading.value = true;
  try {
    const resp = await getCorrectionPage({ pageNo: 1, pageSize: 50 });
    corrections.value = resp.list;
  } finally {
    correctionsLoading.value = false;
  }
}

function openCorrections() {
  correctionsOpen.value = true;
  void loadCorrections();
}

function handleCancelCorrection(correction: DataforgeFillApi.Correction) {
  Modal.confirm({
    title: `取消更正 #${correction.id}`,
    content: '实例将从整改中恢复为已合并状态（仅尚未重新提交时可取消）。',
    onOk: async () => {
      await cancelCorrection(correction.id);
      message.success('已取消更正');
      await loadCorrections();
    },
  });
}
</script>

<template>
  <Page auto-content-height>
    <Grid table-title="合并记录">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              icon: 'lucide:history',
              label: '更正记录',
              type: 'default',
              onClick: openCorrections,
            },
          ]"
        />
      </template>
      <template #status="{ row }">
        <Tag :color="MERGE_STATUS_TEXT[row.status]?.color">
          {{ MERGE_STATUS_TEXT[row.status]?.text ?? row.status }}
        </Tag>
      </template>
      <template #count="{ row }">
        {{ row.successCount }} / {{ row.totalCount }}
      </template>
      <template #errorMessage="{ row }">
        <Tooltip v-if="row.errorMessage" :title="row.errorMessage">
          <span class="line-clamp-1 max-w-[320px] text-red-500">
            {{ row.errorMessage }}
          </span>
        </Tooltip>
        <span v-else>-</span>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '重试',
              type: 'link',
              icon: ACTION_ICON.REFRESH,
              ifShow: row.status === 3,
              auth: ['dataforge:fill-merge:retry'],
              onClick: handleRetry.bind(null, row),
            },
            {
              label: '发起更正',
              type: 'link',
              icon: ACTION_ICON.EDIT,
              ifShow: row.status === 2,
              auth: ['dataforge:fill-correction:create'],
              onClick: openCorrectionModal.bind(null, row),
            },
          ]"
        />
      </template>
    </Grid>

    <Modal
      v-model:open="correctionModalOpen"
      :confirm-loading="correctionSaving"
      title="发起数据更正"
      width="800px"
      @ok="handleCreateCorrection"
    >
      <div class="flex flex-col gap-2 py-2">
        <p class="text-muted-foreground text-xs">
          仅最新一次已合并的提交快照可更正。发起后该组织实例重新开放整改（原快照、
          审批历史与合并记录全部保留），填报人修改后重新提交将走新的审批流程，
          通过后按模板合并策略幂等覆盖正式数据。APPEND 策略的模板不支持更正。
        </p>
        <Input
          v-model:value="correctionReason"
          :maxlength="200"
          placeholder="更正原因（必填），如：统计口径错误需重报"
        />
      </div>
    </Modal>

    <Drawer v-model:open="correctionsOpen" :width="720" title="数据更正记录">
      <div class="mb-2 flex justify-end">
        <Button
          :loading="correctionsLoading"
          size="small"
          @click="loadCorrections"
        >
          <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
          刷新
        </Button>
      </div>
      <Table
        :scroll="{ x: 'max-content' }"
        :columns="[
          { dataIndex: 'id', key: 'id', title: '编号', width: 70 },
          { dataIndex: 'taskName', key: 'taskName', title: '任务' },
          { dataIndex: 'orgName', key: 'orgName', title: '组织', width: 140 },
          { dataIndex: 'reason', key: 'reason', title: '原因', width: 160 },
          { key: 'status', title: '状态', width: 90 },
          {
            dataIndex: 'createTime',
            key: 'createTime',
            title: '发起时间',
            width: 165,
          },
          {
            key: 'actions',
            title: '操作',
            width: 100,
            fixed: 'right' as const,
          },
        ]"
        :data-source="corrections"
        :loading="correctionsLoading"
        :pagination="false"
        row-key="id"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <Tag :color="CORRECTION_STATUS_TEXT[record.status]?.color">
              {{ CORRECTION_STATUS_TEXT[record.status]?.text ?? record.status }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'createTime'">
            {{ formatDateTime(record.createTime) || '-' }}
          </template>
          <template v-else-if="column.key === 'actions'">
            <Button
              v-if="record.status === 0"
              v-access:code="'dataforge:fill-correction:create'"
              danger
              size="small"
              type="link"
              @click="handleCancelCorrection(record)"
            >
              <template #icon><IconifyIcon icon="lucide:x" /></template>
              取消
            </Button>
          </template>
        </template>
      </Table>
    </Drawer>
  </Page>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
