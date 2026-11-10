<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { QualityRepairOrderApi } from '#/api/governance/quality/repair';

import { ref } from 'vue';

import { Page } from '@vben/common-ui';
import { formatDateTime } from '@vben/utils';

import { message, Modal, Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  approveRepairOrder,
  deleteRepairOrder,
  executeRepairOrder,
  getRepairOrderPage,
  resubmitRepairOrder,
  verifyRepairOrder,
} from '#/api/governance/quality/repair';
import { $t } from '#/locales';

import RepairForm from './modules/form.vue';

defineOptions({ name: 'GovernanceQualityRepairOrder' });

const REPAIR_TYPE_LABELS: Record<string, string> = {
  FILL_DEFAULT: '填充默认值',
  DELETE_RECORD: '删除异常记录',
  FORMAT_FIX: '格式修正',
  DEDUP: '去重清洗',
  STANDARD_MAP: '标准化映射',
  CUSTOM_SQL: '自定义SQL',
};
const APPROVE_LABELS: Record<string, [string, string]> = {
  PENDING: ['待审批', 'default'],
  PASSED: ['已通过', 'success'],
  REJECTED: ['已拒绝', 'error'],
};
const EXEC_LABELS: Record<string, [string, string]> = {
  PENDING: ['待执行', 'default'],
  SUCCESS: ['执行成功', 'success'],
  FAILED: ['执行失败', 'error'],
  RUNNING: ['执行中', 'processing'],
  UNKNOWN: ['结果待核验', 'warning'],
  FAILED_CONFIRMED: ['确认未提交', 'error'],
};
const VERIFY_LABELS: Record<string, [string, string]> = {
  UNVERIFIED: ['未验证', 'default'],
  PASSED: ['验证通过', 'success'],
  FAILED: ['验证未通过', 'error'],
};

const formRef = ref<InstanceType<typeof RepairForm>>();
const resultVisible = ref(false);
const resultOrder = ref<QualityRepairOrderApi.RepairOrder>();

function handleRefresh() {
  gridApi.query();
}

async function handleApprove(
  row: QualityRepairOrderApi.RepairOrder,
  status: string,
) {
  await approveRepairOrder(row.id!, status);
  message.success(status === 'PASSED' ? '审批通过' : '已拒绝');
  handleRefresh();
}

async function handleExecute(row: QualityRepairOrderApi.RepairOrder) {
  await executeRepairOrder(row.id!);
  message.success('执行完成，请查看执行结果');
  handleRefresh();
}

async function handleVerify(
  row: QualityRepairOrderApi.RepairOrder,
  status: string,
) {
  await verifyRepairOrder(row.id!, status);
  message.success('验证状态已更新');
  handleRefresh();
}

function openResult(row: QualityRepairOrderApi.RepairOrder) {
  resultOrder.value = row;
  resultVisible.value = true;
}

async function handleDelete(row: QualityRepairOrderApi.RepairOrder) {
  await deleteRepairOrder(row.id!);
  handleRefresh();
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: [
      {
        fieldName: 'orderTitle',
        label: '工单标题',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入工单标题' },
      },
      {
        fieldName: 'approveStatus',
        label: '审批状态',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: [
            { label: '待审批', value: 'PENDING' },
            { label: '已通过', value: 'PASSED' },
            { label: '已拒绝', value: 'REJECTED' },
          ],
          placeholder: '请选择审批状态',
        },
      },
      {
        fieldName: 'execStatus',
        label: '执行状态',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: [
            { label: '待执行', value: 'PENDING' },
            { label: '执行成功', value: 'SUCCESS' },
            { label: '执行失败', value: 'FAILED' },
            { label: '执行中', value: 'RUNNING' },
            { label: '结果待核验', value: 'UNKNOWN' },
            { label: '确认未提交', value: 'FAILED_CONFIRMED' },
          ],
          placeholder: '请选择执行状态',
        },
      },
    ],
  },
  gridOptions: {
    showOverflow: false,
    columns: [
      { field: 'id', title: '编号', width: 70 },
      { field: 'orderTitle', title: '工单标题', minWidth: 180, align: 'left' },
      {
        field: 'repairType',
        title: '修复类型',
        width: 110,
        formatter: ({ cellValue }) => REPAIR_TYPE_LABELS[cellValue] ?? '-',
      },
      { field: 'tableName', title: '检测表', width: 140 },
      {
        field: 'approveStatus',
        title: '审批状态',
        width: 90,
        slots: { default: 'approveStatus' },
      },
      {
        field: 'execStatus',
        title: '执行状态',
        width: 90,
        slots: { default: 'execStatus' },
      },
      {
        field: 'verifyStatus',
        title: '验证状态',
        width: 100,
        slots: { default: 'verifyStatus' },
      },
      {
        field: 'createTime',
        title: '创建时间',
        width: 165,
        formatter: 'formatDateTime',
      },
      {
        title: '操作',
        width: 280,
        fixed: 'right',
        showOverflow: false,
        slots: { default: 'actions' },
      },
    ],
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          return await getRepairOrderPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<QualityRepairOrderApi.RepairOrder>,
});
</script>

<template>
  <Page auto-content-height>
    <Grid table-title="修复工单">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('common.create'),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['governance:quality-repair-order:create'],
              onClick: () => formRef?.open(),
            },
          ]"
        />
      </template>
      <template #approveStatus="{ row }">
        <Tag :color="APPROVE_LABELS[row.approveStatus ?? '']?.[1]">
          {{ APPROVE_LABELS[row.approveStatus ?? '']?.[0] ?? '-' }}
        </Tag>
      </template>
      <template #execStatus="{ row }">
        <Tag :color="EXEC_LABELS[row.execStatus ?? '']?.[1]">
          {{ EXEC_LABELS[row.execStatus ?? '']?.[0] ?? '-' }}
        </Tag>
      </template>
      <template #verifyStatus="{ row }">
        <Tag :color="VERIFY_LABELS[row.verifyStatus ?? '']?.[1]">
          {{ VERIFY_LABELS[row.verifyStatus ?? '']?.[0] ?? '-' }}
        </Tag>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '查看结果',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              onClick: openResult.bind(null, row),
            },
          ]"
          :drop-down-actions="[
            {
              label: '通过',
              type: 'link',
              icon: 'lucide:check',
              auth: ['governance:quality-repair-order:approve'],
              ifShow: row.approveStatus === 'PENDING',
              onClick: handleApprove.bind(null, row, 'PASSED'),
            },
            {
              label: '拒绝',
              type: 'link',
              danger: true,
              icon: ACTION_ICON.CLOSE,
              auth: ['governance:quality-repair-order:approve'],
              ifShow: row.approveStatus === 'PENDING',
              onClick: handleApprove.bind(null, row, 'REJECTED'),
            },
            {
              label: '执行修复',
              type: 'link',
              icon: 'lucide:play',
              auth: ['governance:quality-repair-order:execute'],
              ifShow:
                row.approveStatus === 'PASSED' &&
                ['PENDING', 'FAILED_CONFIRMED'].includes(row.execStatus ?? ''),
              popConfirm: {
                title: '将真实执行修复SQL并变更源库数据，确认执行？',
                confirm: handleExecute.bind(null, row),
              },
            },
            {
              label: '重新送审',
              icon: 'lucide:send',
              type: 'link',
              auth: ['governance:quality-repair-order:update'],
              ifShow:
                row.approveStatus !== 'PENDING' &&
                ['PENDING', 'FAILED_CONFIRMED'].includes(row.execStatus ?? ''),
              popConfirm: {
                title: '撤销旧批准并重新核验物理目标，确认送审？',
                confirm: async () => {
                  await resubmitRepairOrder(row.id!);
                  handleRefresh();
                },
              },
            },
            {
              label: '验证通过',
              type: 'link',
              icon: 'lucide:check',
              auth: ['governance:quality-repair-order:update'],
              ifShow: row.execStatus === 'SUCCESS',
              onClick: handleVerify.bind(null, row, 'PASSED'),
            },
            {
              label: $t('common.delete'),
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['governance:quality-repair-order:delete'],
              popConfirm: {
                title: `确认删除修复工单「${row.orderTitle}」吗？`,
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </Grid>

    <RepairForm ref="formRef" @saved="handleRefresh" />

    <Modal
      v-model:open="resultVisible"
      :footer="null"
      :width="960"
      title="执行结果"
    >
      <div class="mb-2">
        <span class="font-semibold">修复SQL：</span>
        <pre
          class="max-h-40 overflow-auto whitespace-pre-wrap rounded bg-[hsl(var(--accent))] p-2 text-xs"
          >{{ resultOrder?.repairSql ?? '-' }}</pre>
      </div>
      <div class="mb-2">
        <span class="font-semibold">批准的目标及内容快照：</span>
        <pre
          class="max-h-56 overflow-auto whitespace-pre-wrap break-all rounded bg-[hsl(var(--accent))] p-2 text-xs"
          >{{ resultOrder?.approvedSnapshot ?? '未生成批准快照' }}</pre>
        <p class="text-muted-foreground">
          数据源、物理位置或配置修订变更后须重新送审。结果待核验时禁止重放；执行成功后仍须完成质量复检。
        </p>
      </div>
      <div class="mb-2">
        <span class="font-semibold">执行时间：</span>
        {{ formatDateTime(resultOrder?.execTime) || '-' }}
      </div>
      <div>
        <span class="font-semibold">执行结果：</span>
        <pre class="max-h-40 overflow-auto whitespace-pre-wrap text-xs">{{
          resultOrder?.execResult ?? '尚未执行'
        }}</pre>
      </div>
    </Modal>
  </Page>
</template>
