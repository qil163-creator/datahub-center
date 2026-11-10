<script setup lang="ts">
import type { QualityTaskInstanceApi } from '#/api/governance/quality/instance';

import { onDeactivated, reactive, ref, watch } from 'vue';

import { useAccessStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import { ACTION_ICON, TableAction } from '#/adapter/vxe-table';
import {
  getQualityReport,
  getQualityReportPage,
} from '#/api/governance/quality/report';
import { useObjectRequest } from '#/hooks/use-object-request';

import { useMetadataGrid } from '../../../metadata-common/use-metadata-grid';
import WorkOrderForm from '../../work-order/modules/form.vue';
import { REPORT_STATUS_OPTIONS, reportStatus } from '../report-presentation';

const props = defineProps<{ taskId?: number | string }>();
const emit = defineEmits<{ enter: [row: QualityTaskInstanceApi.Instance] }>();
const access = useAccessStore();
const notifyingId = ref<string>();
const workOrderRef = ref<InstanceType<typeof WorkOrderForm>>();
const notificationRequest = useObjectRequest();
const filters = reactive({
  taskName: '',
  instanceStatus: undefined as string | undefined,
});
const applied = reactive({ ...filters });

const { Grid, gridApi, load } =
  useMetadataGrid<QualityTaskInstanceApi.Instance>(
    [
      { field: 'id', title: '编号', width: 82, sortable: true },
      {
        field: 'taskName',
        title: '任务名称',
        minWidth: 340,
        slots: { default: 'name' },
      },
      {
        field: 'score',
        title: '质量评分',
        minWidth: 150,
        sortable: true,
        formatter: ({ cellValue }) => cellValue ?? '-',
      },
      {
        field: 'failCount',
        title: '问题数据',
        minWidth: 160,
        sortable: true,
        formatter: ({ cellValue }) => cellValue ?? '-',
      },
      {
        field: 'instanceStatus',
        title: '执行状态',
        minWidth: 140,
        slots: { default: 'status' },
      },
      {
        field: 'startTime',
        title: '开始时间',
        minWidth: 190,
        sortable: true,
        formatter: ({ cellValue }) =>
          cellValue === null || cellValue === undefined
            ? '-'
            : formatDateTime(cellValue),
      },
      {
        field: 'endTime',
        title: '结束时间',
        minWidth: 190,
        sortable: true,
        formatter: ({ cellValue }) =>
          cellValue === null || cellValue === undefined
            ? '-'
            : formatDateTime(cellValue),
      },
      {
        title: '操作',
        width: 225,
        fixed: 'right',
        showOverflow: false,
        slots: { default: 'actions' },
      },
    ],
    (page) =>
      getQualityReportPage({ ...page, ...applied, taskId: props.taskId }),
    {
      schema: [
        {
          fieldName: 'taskName',
          label: '任务名称',
          component: 'Input',
          componentProps: { allowClear: true, placeholder: '请输入任务名称' },
        },
        {
          fieldName: 'instanceStatus',
          label: '执行状态',
          component: 'Select',
          componentProps: {
            allowClear: true,
            placeholder: '请选择执行状态',
            options: REPORT_STATUS_OPTIONS,
          },
        },
      ],
      handleValuesChange: (values) => Object.assign(filters, values),
      handleSubmit: (values) => {
        Object.assign(filters, values);
        search();
      },
      handleReset: async () => {
        await gridApi.formApi.reset();
        reset();
      },
    },
    () => {
      Object.assign(filters, { taskName: '', instanceStatus: undefined });
      Object.assign(applied, filters);
    },
    'fill',
    true,
  );

gridApi.setGridOptions({
  rowConfig: { keyField: 'id', isHover: true },
});

function search() {
  Object.assign(applied, filters);
  void load(true);
}
function reset() {
  filters.taskName = '';
  filters.instanceStatus = undefined;
  search();
}
function hasIssues(row: QualityTaskInstanceApi.Instance) {
  return (
    row.failCount !== null &&
    row.failCount !== undefined &&
    !/^0+$/u.test(String(row.failCount))
  );
}
async function notify(row: QualityTaskInstanceApi.Instance) {
  if (row.id === undefined || notifyingId.value || !hasIssues(row)) return;
  const current = notificationRequest.begin();
  notifyingId.value = String(row.id);
  try {
    const detail = await getQualityReport(row.id, 7);
    if (!current()) return;
    await workOrderRef.value?.open({
      requireOwner: true,
      taskId: row.taskId,
      issueIds: [
        ...new Map(
          detail.rules
            .flatMap((rule) => rule.issueIds)
            .map((id) => [String(id), id]),
        ).values(),
      ],
      orderName: `质量问题处理 - ${row.taskName || row.instanceName || row.id}`,
    });
  } finally {
    if (current()) notifyingId.value = undefined;
  }
}
watch(
  () => [props.taskId, access.accessToken, access.refreshToken],
  () => {
    notificationRequest.begin();
    notifyingId.value = undefined;
    workOrderRef.value?.close();
    void load(true);
  },
);
onDeactivated(() => {
  notificationRequest.begin();
  notifyingId.value = undefined;
  workOrderRef.value?.close();
});
</script>

<template>
  <div class="quality-report-list">
    <section class="quality-report-list-panel">
      <Grid class="quality-report-grid" table-title="质量报告列表">
        <template #name="{ row }">
          <button
            class="quality-report-link"
            type="button"
            @click="emit('enter', row)"
          >
            {{ row.taskName || row.instanceName || '-' }}
          </button>
        </template>
        <template #status="{ row }">
          <span
            class="quality-report-status"
            :class="`quality-report-status--${String(row.instanceStatus).toLowerCase()}`"
            >{{ reportStatus(row.instanceStatus) }}</span>
        </template>
        <template #actions="{ row }">
          <TableAction
            :actions="[
              {
                label: '详情',
                icon: ACTION_ICON.VIEW,
                type: 'link',
                auth: ['governance:quality-audit-log:query'],
                onClick: () => emit('enter', row),
              },
              {
                label: '通知处理',
                icon: 'lucide:send',
                type: 'link',
                auth: ['governance:quality-work-order:create'],
                disabled: !hasIssues(row) || !!notifyingId,
                loading: notifyingId === String(row.id),
                tooltip: '创建整改工单并选择处理人',
                onClick: () => notify(row),
              },
            ]"
          />
        </template>
      </Grid>
    </section>
    <WorkOrderForm ref="workOrderRef" />
  </div>
</template>
