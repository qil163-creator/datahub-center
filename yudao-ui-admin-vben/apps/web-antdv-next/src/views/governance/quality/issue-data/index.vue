<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { QualityIssueDataApi } from '#/api/governance/quality/issue';

import { ref } from 'vue';

import { Page } from '@vben/common-ui';
import { downloadFileFromBlobPart } from '@vben/utils';

import { message, Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  deleteIssueData,
  exportIssueData,
  getIssueDataPage,
  updateIssueStatus,
} from '#/api/governance/quality/issue';
import { $t } from '#/locales';

import WorkOrderForm from '../work-order/modules/form.vue';
import ProblemData from './modules/problem-data.vue';

import '../report/report.css';

defineOptions({ name: 'GovernanceQualityIssueData' });

const problemRef = ref<InstanceType<typeof ProblemData>>();
const workOrderFormRef = ref<InstanceType<typeof WorkOrderForm>>();

function handleRefresh() {
  gridApi.query();
}

async function handleExport() {
  const formValues = await gridApi.formApi.getValues();
  const data = await exportIssueData({ ...formValues });
  downloadFileFromBlobPart({ fileName: '问题数据清单.xls', source: data });
}

async function openDetail(row: QualityIssueDataApi.IssueData) {
  if (row.auditLogId === undefined || row.auditLogId === null) return;
  await problemRef.value?.open({
    id: row.auditLogId,
    ruleId: row.ruleId,
    ruleName: row.ruleName || '',
    ruleType: '',
    qualityDim: '',
    checkObjectId: row.objectId,
    tableName: row.tableName,
    checkField: row.checkField,
    fieldLabels: [],
    totalCount: row.issueCount ?? 0,
    failCount: row.issueCount ?? 0,
    problemRatio: null,
    resultType: 'FAIL',
    issueIds: row.id === undefined ? [] : [row.id],
  });
}

function handleCreateWorkOrder(row: QualityIssueDataApi.IssueData) {
  workOrderFormRef.value?.open({
    taskId: row.taskId,
    issueIds: row.id ? [row.id] : [],
    orderName: `整改-${row.tableName ?? '问题'}${row.checkField ? `-${row.checkField}` : ''}`,
  });
}

async function handleProcess(row: QualityIssueDataApi.IssueData) {
  await updateIssueStatus(row.id!, 'PROCESSED');
  message.success('已标记为已处理');
  handleRefresh();
}

async function handleDelete(row: QualityIssueDataApi.IssueData) {
  await deleteIssueData(row.id!);
  handleRefresh();
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: [
      {
        fieldName: 'tableName',
        label: '检测表',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入检测表' },
      },
      {
        fieldName: 'statScope',
        label: '统计口径',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: [
            { label: '全部合计', value: 'ALL' },
            { label: '按机构', value: 'ORG' },
          ],
          placeholder: '请选择统计口径',
        },
      },
      {
        fieldName: 'issueStatus',
        label: '处理状态',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: [
            { label: '未处理', value: 'UNPROCESSED' },
            { label: '已处理', value: 'PROCESSED' },
          ],
          placeholder: '请选择处理状态',
        },
      },
    ],
  },
  gridOptions: {
    showOverflow: false,
    columns: [
      { field: 'id', title: '编号', width: 70 },
      { field: 'tableName', title: '检测表', minWidth: 140, align: 'left' },
      { field: 'checkField', title: '检测字段', width: 130 },
      { field: 'ruleName', title: '规则名称', width: 150 },
      { field: 'taskName', title: '任务名称', width: 140 },
      {
        field: 'statScope',
        title: '统计口径',
        width: 90,
        formatter: ({ cellValue }) =>
          cellValue === 'ORG' ? '按机构' : '全部合计',
      },
      { field: 'issueCount', title: '问题行数', width: 90 },
      {
        field: 'issueStatus',
        title: '处理状态',
        width: 90,
        slots: { default: 'issueStatus' },
      },
      {
        field: 'checkTime',
        title: '检出时间',
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
          return await getIssueDataPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
      },
    },
    emptyText:
      '暂无问题数据。请确认质量规则已开启「采集问题明细」，并重新执行质量任务后再查看。',
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<QualityIssueDataApi.IssueData>,
});
</script>

<template>
  <Page auto-content-height content-class="quality-report-page">
    <div class="flex h-full min-h-0 flex-col gap-3">
      <div class="min-h-0 flex-1 overflow-hidden">
        <Grid class="quality-report-grid" table-title="问题数据">
          <template #toolbar-tools>
            <TableAction
              :actions="[
                {
                  label: '导出清单',
                  icon: ACTION_ICON.DOWNLOAD,
                  type: 'default',
                  onClick: handleExport,
                },
              ]"
            />
          </template>
          <template #issueStatus="{ row }">
            <Tag
              :color="row.issueStatus === 'PROCESSED' ? 'success' : 'warning'"
            >
              {{ row.issueStatus === 'PROCESSED' ? '已处理' : '未处理' }}
            </Tag>
          </template>
          <template #actions="{ row }">
            <TableAction
              :actions="[
                {
                  label: '查看明细',
                  type: 'link',
                  icon: ACTION_ICON.VIEW,
                  auth: ['governance:quality-issue-data:query'],
                  disabled:
                    row.auditLogId === undefined || row.auditLogId === null,
                  onClick: openDetail.bind(null, row),
                },
              ]"
              :drop-down-actions="[
                {
                  label: '创建工单',
                  type: 'link',
                  icon: ACTION_ICON.ADD,
                  auth: ['governance:quality-work-order:create'],
                  ifShow: row.issueStatus !== 'PROCESSED',
                  onClick: handleCreateWorkOrder.bind(null, row),
                },
                {
                  label: '标记已处理',
                  type: 'link',
                  icon: 'lucide:wrench',
                  auth: ['governance:quality-issue-data:update'],
                  ifShow: row.issueStatus !== 'PROCESSED',
                  onClick: handleProcess.bind(null, row),
                },
                {
                  label: $t('common.delete'),
                  type: 'link',
                  danger: true,
                  icon: ACTION_ICON.DELETE,
                  auth: ['governance:quality-issue-data:delete'],
                  popConfirm: {
                    title: '确认删除该问题数据记录吗？',
                    confirm: handleDelete.bind(null, row),
                  },
                },
              ]"
            />
          </template>
        </Grid>
      </div>
    </div>

    <WorkOrderForm ref="workOrderFormRef" @saved="handleRefresh" />

    <ProblemData ref="problemRef" />
  </Page>
</template>
