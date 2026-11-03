<script setup lang="ts">
import type { VbenFormSchema } from '#/adapter/form';
import type { GovernanceMasterManageApi } from '#/api/governance/mastermanage';

import { computed, onMounted, reactive, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';
import { formatDateTime } from '@vben/utils';

import { Alert, Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  getMasterApprovalPage,
  getMasterChangeLogPage,
} from '#/api/governance/mastermanage';

import Detail from '../masterapproval/modules/detail.vue';
import ChangeDetail from '../masterchangelog/modules/detail.vue';
import {
  APPROVAL_STATUS_OPTIONS,
  approvalStatus,
  OPERATION_OPTIONS,
  operationLabel,
} from './enums';
import { useMasterScope } from './use-master-scope';

const props = defineProps<{ mode: 'approval' | 'change' }>();
const approval = computed(() => props.mode === 'approval');
type HistoryFilter = {
  operation?: GovernanceMasterManageApi.Operation;
  status?: GovernanceMasterManageApi.ApprovalStatus;
};
type HistoryRow = Partial<GovernanceMasterManageApi.Approval> &
  Partial<GovernanceMasterManageApi.ChangeLog> & {
    id: GovernanceMasterManageApi.Id;
  };
const query = reactive<HistoryFilter>({});
const pagination = reactive({ pageNo: 1, pageSize: 20, total: 0 });
const failed = ref(false);
const [DetailDrawer, detailApi] = useVbenDrawer({
  connectedComponent: Detail,
  destroyOnClose: true,
});
const [ChangeDetailDrawer, changeDetailApi] = useVbenDrawer({
  connectedComponent: ChangeDetail,
  destroyOnClose: true,
});
const scope = useMasterScope(() => {
  detailApi.setState({ isOpen: false });
  changeDetailApi.setState({ isOpen: false });
  gridApi.setGridOptions({ data: [], pagerConfig: { total: 0 } });
  void search();
});
function formSchema(): VbenFormSchema[] {
  return [
    ...(approval.value
      ? [
          {
            fieldName: 'status',
            label: '审批状态',
            component: 'Select' as const,
            componentProps: {
              allowClear: true,
              options: APPROVAL_STATUS_OPTIONS,
              placeholder: '请选择审批状态',
            },
          },
        ]
      : []),
    {
      fieldName: 'operation',
      label: approval.value ? '审批类型' : '变更类型',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: OPERATION_OPTIONS,
        placeholder: approval.value ? '请选择审批类型' : '请选择变更类型',
      },
    },
  ];
}
const [Grid, gridApi] = useVbenVxeGrid<HistoryRow, HistoryFilter>({
  formOptions: {
    schema: formSchema(),
    handleSubmit: async () => {
      const values = await gridApi.formApi.getValues();
      query.operation = values.operation;
      query.status = values.status;
      await search();
    },
    handleReset: async () => {
      await gridApi.formApi.reset();
      query.operation = undefined;
      query.status = undefined;
      await search();
    },
  },
  gridOptions: {
    showOverflow: false,
    columns:
      props.mode === 'approval'
        ? [
            { field: 'dataId', title: '数据ID', minWidth: 150 },
            { field: 'modelName', title: '所属模型', minWidth: 160 },
            {
              field: 'operation',
              title: '审批类型',
              width: 120,
              slots: { default: 'operation' },
            },
            {
              field: 'status',
              title: '审批状态',
              width: 140,
              slots: { default: 'status' },
            },
            { field: 'applicantName', title: '申请人', minWidth: 130 },
            {
              field: 'createTime',
              title: '申请时间',
              minWidth: 170,
              slots: { default: 'time' },
            },
            { field: 'reviewerName', title: '审批人', minWidth: 130 },
            {
              field: 'reviewTime',
              title: '审批时间',
              minWidth: 170,
              slots: { default: 'reviewTime' },
            },
            {
              title: '操作',
              width: 100,
              fixed: 'right',
              showOverflow: false,
              slots: { default: 'actions' },
            },
          ]
        : [
            { field: 'dataId', title: '数据ID', minWidth: 180 },
            { field: 'modelName', title: '所属模型', minWidth: 160 },
            {
              field: 'changeType',
              title: '变更类型',
              width: 120,
              slots: { default: 'operation' },
            },
            { field: 'propertyName', title: '属性名称', minWidth: 170 },
            { field: 'beforeValue', title: '变更前', minWidth: 220 },
            { field: 'afterValue', title: '变更后', minWidth: 220 },
            { field: 'changedByName', title: '变更人', minWidth: 130 },
            {
              field: 'changedAt',
              title: '变更时间',
              minWidth: 170,
              slots: { default: 'time' },
            },
            {
              title: '操作',
              width: 100,
              fixed: 'right',
              showOverflow: false,
              slots: { default: 'actions' },
            },
          ],
    rowConfig: { keyField: 'id', isHover: true },
    height: 'auto',
    keepSource: true,
    pagerConfig: { currentPage: 1, pageSize: 20, total: 0 },
    toolbarConfig: {
      refresh: true,
      refreshOptions: { queryMethod: load },
      search: true,
    },
  },
  gridEvents: {
    pageChange: ({
      currentPage,
      pageSize,
    }: {
      currentPage: number;
      pageSize: number;
    }) => {
      pagination.pageNo = currentPage;
      pagination.pageSize = pageSize;
      void load();
    },
  },
});
async function load() {
  const current = scope.begin();
  const page = { pageNo: pagination.pageNo, pageSize: pagination.pageSize };
  failed.value = false;
  gridApi.setGridOptions({ loading: true });
  try {
    const result = approval.value
      ? await getMasterApprovalPage({ ...page, ...query })
      : await getMasterChangeLogPage({ ...page, changeType: query.operation });
    if (!current()) return;
    pagination.total = result.total;
    gridApi.setGridOptions({
      data: result.list,
      pagerConfig: {
        currentPage: page.pageNo,
        pageSize: page.pageSize,
        total: result.total,
      },
    });
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) {
      gridApi.setGridOptions({ loading: false });
    }
  }
}
function search() {
  pagination.pageNo = 1;
  return load();
}
onMounted(load);
</script>

<template>
  <div class="flex h-full min-h-0 flex-col">
    <DetailDrawer @success="load" />
    <ChangeDetailDrawer />
    <Alert
      class="mb-3"
      type="info"
      show-icon
      :message="
        approval
          ? '当模型开启了审批功能后，数据的新增、编辑、删除操作将进入此审批队列。审批通过后数据才会正式生效，驳回则撤销本次变更。'
          : '变更日志自动记录主数据的每一次变更（新增、编辑、删除），便于追溯数据的历史变化。此页面为只读，无需手动操作。'
      "
    />
    <Alert
      v-if="failed"
      class="mb-3"
      type="error"
      message="记录加载失败，请重新查询。"
      show-icon
    />
    <Grid
      class="min-h-0 flex-1"
      :table-title="approval ? '主数据审批' : '变更日志'"
    >
      <template #operation="{ row }">
        {{ operationLabel(row.operation ?? row.changeType ?? '') || '-' }}
      </template>
      <template #status="{ row }">
        <Tag :color="approvalStatus(row.status ?? '').color">
          {{ approvalStatus(row.status ?? '').label || '-' }}
        </Tag>
      </template>
      <template #time="{ row }">
        {{ formatDateTime(row.createTime ?? row.changedAt) || '-' }}
      </template>
      <template #reviewTime="{ row }">
        {{ formatDateTime(row.reviewTime) || '-' }}
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: approval && row.status === 'PENDING' ? '审批' : '详情',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              auth: [
                approval
                  ? 'governance:master-approval:query'
                  : 'governance:master-change-log:query',
              ],
              onClick: () =>
                approval
                  ? detailApi.setData({ id: row.id }).open()
                  : changeDetailApi
                      .setData(row as GovernanceMasterManageApi.ChangeLog)
                      .open(),
            },
          ]"
        />
      </template>
    </Grid>
  </div>
</template>
