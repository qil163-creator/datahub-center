<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { QualityWorkOrderApi } from '#/api/governance/quality/workorder';

import { h, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { formatDateTime } from '@vben/utils';

import { message, Modal, Segmented, Select, Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  assignWorkOrder,
  claimWorkOrder,
  deleteWorkOrder,
  getMyTodoWorkOrderList,
  getWorkOrderPage,
  getWorkOrderRawIssues,
  transferWorkOrderCommand,
  withdrawWorkOrder,
} from '#/api/governance/quality/workorder';
import { getSimpleUserList } from '#/api/system/user';
import { $t } from '#/locales';

import WorkOrderExpiryFailures from './modules/expiry-failures.vue';
import WorkOrderForm from './modules/form.vue';
import { CLOSE_LABELS } from './modules/resolution-state';
import WorkOrderResolution from './modules/resolution.vue';
import WorkOrderStatistics from './modules/statistics.vue';
import {
  createWorkOrderRequestGuard,
  losslessId,
} from './use-work-order-request';

defineOptions({ name: 'GovernanceQualityWorkOrder' });

const STATUS_LABELS: Record<string, [string, string]> = {
  PENDING: ['待受理', 'warning'],
  IN_PROGRESS: ['处理中', 'processing'],
  PENDING_RECHECK: ['待复检', 'gold'],
  RECHECKING: ['复检中', 'blue'],
  PENDING_CONFIRMATION: ['待确认', 'purple'],
  PROCESSED: ['已关闭', 'success'],
  TRANSFERRED: ['历史转办', 'default'],
};

const activeTab = ref('all');
const viewOptions = [
  { label: '工单列表', value: 'all' },
  { label: '我的待办', value: 'todo' },
  { label: '工单统计', value: 'stats' },
];
const formRef = ref<InstanceType<typeof WorkOrderForm>>();
const expiryFailuresRef = ref<InstanceType<typeof WorkOrderExpiryFailures>>();
const resolutionRef = ref<InstanceType<typeof WorkOrderResolution>>();

/** 系统用户（转办下拉用） */
const users = ref<{ id: QualityWorkOrderApi.Id; nickname: string }[]>([]);
async function loadUsers() {
  if (users.value.length === 0) {
    users.value = (await getSimpleUserList()) as any;
  }
}

const pageRequestGuard = createWorkOrderRequestGuard();
let lastPageResult: { list: QualityWorkOrderApi.WorkOrder[]; total: number } = {
  list: [],
  total: 0,
};

const workOrderColumns: VxeTableGridOptions<QualityWorkOrderApi.WorkOrder>['columns'] =
  [
    { field: 'id', title: '编号', width: 70 },
    { field: 'orderName', title: '工单名称', minWidth: 180, align: 'left' },
    { field: 'taskName', title: '所属任务', width: 150 },
    { field: 'issueCount', title: '问题条数', width: 90 },
    { field: 'orderCode', title: '工单编号', width: 120 },
    { field: 'ownerUserName', title: '责任人', width: 100 },
    { field: 'responsibilityStatus', title: '认责', width: 90 },
    { field: 'creatorUserName', title: '发起人', width: 100 },
    {
      field: 'deadline',
      title: '整改截止时间',
      width: 165,
      formatter: 'formatDateTime',
    },
    {
      field: 'orderStatus',
      title: '状态',
      width: 90,
      slots: { default: 'orderStatus' },
    },
    {
      field: 'createTime',
      title: '创建时间',
      width: 165,
      formatter: 'formatDateTime',
    },
    {
      field: 'closeType',
      title: '关闭结论',
      minWidth: 180,
      formatter: ({ cellValue }) => CLOSE_LABELS[cellValue] ?? cellValue ?? '-',
    },
    {
      title: '操作',
      width: 300,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];

function handleRefresh() {
  allGridApi.query();
  todoGridApi.query();
}

function commandBase(row: QualityWorkOrderApi.WorkOrder) {
  return {
    id: losslessId(row.id),
    expectedVersion: row.lockVersion ?? 0,
    requestId: globalThis.crypto?.randomUUID?.() ?? `${Date.now()}`,
  };
}

function handleAssign(row: QualityWorkOrderApi.WorkOrder) {
  void loadUsers().then(() => {
    let ownerUserId: QualityWorkOrderApi.Id | undefined;
    Modal.confirm({
      title: `认责指派：${row.orderName}`,
      content: () =>
        h(Select, {
          placeholder: '请选择办理人',
          style: 'width: 100%',
          showSearch: true,
          optionFilterProp: 'label',
          options: users.value.map((user) => ({
            label: user.nickname,
            value: user.id,
          })),
          onChange: (value: QualityWorkOrderApi.Id) => (ownerUserId = value),
        }),
      onOk: async () => {
        if (ownerUserId === null || ownerUserId === undefined) {
          message.warning('请指定办理人');
          throw new Error('owner required');
        }
        await assignWorkOrder({ ...commandBase(row), ownerUserId });
        message.success('已指派');
        handleRefresh();
      },
    });
  });
}

async function handleClaim(row: QualityWorkOrderApi.WorkOrder) {
  await claimWorkOrder(commandBase(row));
  message.success('已认领');
  handleRefresh();
}

function handleSubmit(row: QualityWorkOrderApi.WorkOrder) {
  if (row.id !== undefined && row.id !== null)
    resolutionRef.value?.open(row.id);
}

function handleRecheck(row: QualityWorkOrderApi.WorkOrder) {
  handleSubmit(row);
}

function handleConfirm(row: QualityWorkOrderApi.WorkOrder) {
  handleSubmit(row);
}

async function handleWithdraw(row: QualityWorkOrderApi.WorkOrder) {
  await withdrawWorkOrder({
    ...commandBase(row),
    submissionId: row.currentSubmissionId,
    reason: '撤回当前提交',
  });
  message.success('已撤回当前提交');
  handleRefresh();
}

async function handleTransfer(row: QualityWorkOrderApi.WorkOrder) {
  await loadUsers();
  let newOwnerUserId: QualityWorkOrderApi.Id | undefined;
  Modal.confirm({
    title: `转派工单：${row.orderName}`,
    content: () =>
      h(Select, {
        placeholder: '请选择新责任人',
        style: 'width: 100%',
        showSearch: true,
        optionFilterProp: 'label',
        options: users.value.map((user) => ({
          label: user.nickname,
          value: user.id,
        })),
        onChange: (value: QualityWorkOrderApi.Id) => (newOwnerUserId = value),
      }),
    onOk: async () => {
      if (newOwnerUserId === null || newOwnerUserId === undefined) {
        message.warning('请指定新责任人');
        throw new Error('owner required');
      }
      await transferWorkOrderCommand({
        ...commandBase(row),
        ownerUserId: newOwnerUserId,
        reason: '转派',
      });
      message.success('已在同一工单链转派');
      handleRefresh();
    },
  });
}

async function handleDelete(row: QualityWorkOrderApi.WorkOrder) {
  await deleteWorkOrder(losslessId(row.id));
  handleRefresh();
}

async function handleRawIssues(row: QualityWorkOrderApi.WorkOrder) {
  const raw = await getWorkOrderRawIssues(losslessId(row.id));
  const lines = (raw.issues ?? []).map(
    (item) =>
      `问题 ${losslessId(item.issueId)}：检出时间 ${formatDateTime(item.evidenceTime) || '-'}；${item.liveValueNote ?? ''}`,
  );
  Modal.info({
    title: `原文明细（历史检出 ≠ 实时值）：${row.orderName}`,
    content: lines.length > 0 ? lines.join('\n') : '暂无已授权明细',
  });
}

const [AllGrid, allGridApi] = useVbenVxeGrid({
  formOptions: {
    schema: [
      {
        fieldName: 'orderName',
        label: '工单名称',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入工单名称' },
      },
      {
        fieldName: 'orderStatus',
        label: '工单状态',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: [
            { label: '待受理', value: 'PENDING' },
            { label: '处理中', value: 'IN_PROGRESS' },
            { label: '待复检', value: 'PENDING_RECHECK' },
            { label: '待确认', value: 'PENDING_CONFIRMATION' },
            { label: '已关闭', value: 'PROCESSED' },
            { label: '待认责', value: 'UNASSIGNED' },
          ],
          placeholder: '请选择工单状态',
        },
      },
    ],
  },
  gridOptions: {
    showOverflow: false,
    columns: workOrderColumns,
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          const requestSequence = pageRequestGuard.begin();
          const result = await getWorkOrderPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
          if (!pageRequestGuard.isLatest(requestSequence)) {
            return lastPageResult;
          }
          lastPageResult = result;
          return result;
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<QualityWorkOrderApi.WorkOrder>,
});

const [TodoGrid, todoGridApi] = useVbenVxeGrid({
  gridOptions: {
    showOverflow: false,
    columns: workOrderColumns,
    height: 'auto',
    keepSource: true,
    pagerConfig: { enabled: false },
    proxyConfig: {
      ajax: {
        query: async () => {
          const list = await getMyTodoWorkOrderList();
          return { list, total: list.length };
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: false },
  } as VxeTableGridOptions<QualityWorkOrderApi.WorkOrder>,
});
</script>

<template>
  <Page auto-content-height>
    <template #extra>
      <Segmented v-model:value="activeTab" :options="viewOptions" />
    </template>
    <AllGrid v-if="activeTab === 'all'" table-title="问题工单">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: '例外到期处理失败',
              auth: ['governance:quality-work-order:coordinate'],
              onClick: () => expiryFailuresRef?.open(),
            },
            {
              label: $t('common.create'),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['governance:quality-work-order:create'],
              onClick: () => formRef?.open(),
            },
          ]"
        />
      </template>
      <template #orderStatus="{ row }">
        <Tag :color="STATUS_LABELS[row.orderStatus ?? '']?.[1]">
          {{ STATUS_LABELS[row.orderStatus ?? '']?.[0] ?? '-' }}
        </Tag>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '处理详情',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              auth: ['governance:quality-work-order:query'],
              onClick: handleSubmit.bind(null, row),
            },
            {
              label: '原文明细',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              auth: ['governance:quality-work-order:raw-query'],
              onClick: handleRawIssues.bind(null, row),
            },
          ]"
          :drop-down-actions="[
            {
              label: '认责',
              type: 'link',
              icon: 'lucide:user-check',
              auth: ['governance:quality-work-order:assign'],
              ifShow: (row.allowedActions ?? []).includes('ASSIGN'),
              onClick: handleAssign.bind(null, row),
            },
            {
              label: '认领',
              type: 'link',
              icon: 'lucide:check',
              auth: ['governance:quality-work-order:claim'],
              ifShow: (row.allowedActions ?? []).includes('CLAIM'),
              onClick: handleClaim.bind(null, row),
            },
            {
              label: '提交整改',
              type: 'link',
              icon: 'lucide:wrench',
              auth: ['governance:quality-work-order:process'],
              ifShow: (row.allowedActions ?? []).includes('SUBMIT'),
              onClick: handleSubmit.bind(null, row),
            },
            {
              label: '复检',
              type: 'link',
              icon: ACTION_ICON.REFRESH,
              auth: ['governance:quality-work-order:process'],
              ifShow: (row.allowedActions ?? []).includes('RECHECK'),
              onClick: handleRecheck.bind(null, row),
            },
            {
              label: '验收',
              type: 'link',
              icon: 'lucide:check',
              auth: ['governance:quality-work-order:confirm'],
              ifShow: (row.allowedActions ?? []).includes('CONFIRM'),
              onClick: handleConfirm.bind(null, row),
            },
            {
              label: '撤回',
              type: 'link',
              icon: 'lucide:undo-2',
              auth: ['governance:quality-work-order:process'],
              ifShow: (row.allowedActions ?? []).includes('WITHDRAW'),
              onClick: handleWithdraw.bind(null, row),
            },
            {
              label: '转派',
              type: 'link',
              icon: 'lucide:share-2',
              auth: ['governance:quality-work-order:process'],
              ifShow: (row.allowedActions ?? []).includes('TRANSFER'),
              onClick: handleTransfer.bind(null, row),
            },
            {
              label: $t('common.delete'),
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['governance:quality-work-order:delete'],
              popConfirm: {
                title: `确认删除问题工单「${row.orderName}」吗？`,
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </AllGrid>
    <TodoGrid v-else-if="activeTab === 'todo'" table-title="我的待办">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('common.create'),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['governance:quality-work-order:create'],
              onClick: () => formRef?.open(),
            },
          ]"
        />
      </template>
      <template #orderStatus="{ row }">
        <Tag :color="STATUS_LABELS[row.orderStatus ?? '']?.[1]">
          {{ STATUS_LABELS[row.orderStatus ?? '']?.[0] ?? '-' }}
        </Tag>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '处理详情',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              auth: ['governance:quality-work-order:query'],
              onClick: handleSubmit.bind(null, row),
            },
            {
              label: '原文明细',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              auth: ['governance:quality-work-order:raw-query'],
              onClick: handleRawIssues.bind(null, row),
            },
          ]"
          :drop-down-actions="[
            {
              label: '认责',
              type: 'link',
              icon: 'lucide:user-check',
              auth: ['governance:quality-work-order:assign'],
              ifShow: (row.allowedActions ?? []).includes('ASSIGN'),
              onClick: handleAssign.bind(null, row),
            },
            {
              label: '认领',
              type: 'link',
              icon: 'lucide:check',
              auth: ['governance:quality-work-order:claim'],
              ifShow: (row.allowedActions ?? []).includes('CLAIM'),
              onClick: handleClaim.bind(null, row),
            },
            {
              label: '提交整改',
              type: 'link',
              icon: 'lucide:wrench',
              auth: ['governance:quality-work-order:process'],
              ifShow: (row.allowedActions ?? []).includes('SUBMIT'),
              onClick: handleSubmit.bind(null, row),
            },
            {
              label: '复检',
              type: 'link',
              icon: ACTION_ICON.REFRESH,
              auth: ['governance:quality-work-order:process'],
              ifShow: (row.allowedActions ?? []).includes('RECHECK'),
              onClick: handleRecheck.bind(null, row),
            },
            {
              label: '验收',
              type: 'link',
              icon: 'lucide:check',
              auth: ['governance:quality-work-order:confirm'],
              ifShow: (row.allowedActions ?? []).includes('CONFIRM'),
              onClick: handleConfirm.bind(null, row),
            },
            {
              label: '撤回',
              type: 'link',
              icon: 'lucide:undo-2',
              auth: ['governance:quality-work-order:process'],
              ifShow: (row.allowedActions ?? []).includes('WITHDRAW'),
              onClick: handleWithdraw.bind(null, row),
            },
            {
              label: '转派',
              type: 'link',
              icon: 'lucide:share-2',
              auth: ['governance:quality-work-order:process'],
              ifShow: (row.allowedActions ?? []).includes('TRANSFER'),
              onClick: handleTransfer.bind(null, row),
            },
            {
              label: $t('common.delete'),
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['governance:quality-work-order:delete'],
              popConfirm: {
                title: `确认删除问题工单「${row.orderName}」吗？`,
                confirm: handleDelete.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </TodoGrid>
    <WorkOrderStatistics v-else />
    <WorkOrderExpiryFailures ref="expiryFailuresRef" />

    <WorkOrderForm ref="formRef" @saved="handleRefresh" />
    <WorkOrderResolution ref="resolutionRef" @saved="handleRefresh" />
  </Page>
</template>
