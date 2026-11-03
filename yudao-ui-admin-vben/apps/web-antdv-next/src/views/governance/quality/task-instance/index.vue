<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { QualityTaskInstanceApi } from '#/api/governance/quality/instance';

import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import { Button, Descriptions, DescriptionsItem, Modal, Tag } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  getTaskInstance,
  getTaskInstancePage,
} from '#/api/governance/quality/instance';
import { parseResourceId } from '#/utils/resource-id';

defineOptions({ name: 'GovernanceQualityTaskInstance' });

const STATUS_OPTIONS = [
  { label: '排队中', value: 'QUEUED', color: 'default' },
  { label: '运行中', value: 'RUNNING', color: 'processing' },
  { label: '成功', value: 'SUCCESS', color: 'success' },
  { label: '失败', value: 'FAILED', color: 'error' },
  { label: '结果待确认', value: 'UNKNOWN', color: 'warning' },
];
const RUN_TYPE_OPTIONS = [
  { label: '手动', value: 'MANUAL' },
  { label: '调度', value: 'SCHEDULE' },
  { label: 'ETL 批次质检', value: 'ETL_TARGET_BATCH' },
  { label: '研发关联质检', value: 'DATAFORGE' },
];
/** 批次绑定只记录受理；检测结论以实例状态为准 */
const ETL_BATCH_STATE_LABELS: Record<string, string> = { QUEUED: '已受理' };

function statusOption(value?: string) {
  return STATUS_OPTIONS.find((item) => item.value === value);
}
function runTypeLabel(value?: string) {
  return (
    RUN_TYPE_OPTIONS.find((item) => item.value === value)?.label ?? value ?? '-'
  );
}
function displayOrDash(value?: null | number | string) {
  return value === null || value === undefined || value === '' ? '-' : value;
}
function yesNo(value?: boolean) {
  if (value === undefined || value === null) return '-';
  return value ? '是' : '否';
}

const { hasAccessByCodes } = useAccess();
/** 跳转入口按菜单按钮权限显示；ETL 运行记录接口仍做最终校验 */
const canQueryEtl = computed(() => hasAccessByCodes(['dataforge:etl:query']));
const accessStore = useAccessStore();
const route = useRoute();
const router = useRouter();

const detailVisible = ref(false);
const detail = ref<QualityTaskInstanceApi.Instance>();
let detailSeq = 0;

function closeDetail() {
  ++detailSeq;
  detailVisible.value = false;
  detail.value = undefined;
}

async function openDetail(row: Pick<QualityTaskInstanceApi.Instance, 'id'>) {
  if (row.id === undefined || row.id === null) return;
  const seq = ++detailSeq;
  try {
    const result = await getTaskInstance(row.id);
    if (seq !== detailSeq) return;
    detail.value = result;
    detailVisible.value = true;
  } catch {
    // 请求层已统一提示错误，这里只丢弃本次结果
  }
}

function openEtlRecord(etlBatch?: null | QualityTaskInstanceApi.EtlBatch) {
  const recordId = parseResourceId(etlBatch?.recordId);
  if (!canQueryEtl.value || !recordId) return;
  closeDetail();
  void router.push({ name: 'DataforgeEtlRecord', query: { recordId } });
}

watch(
  () => route.query.instanceId,
  (value) => {
    const id = parseResourceId(value);
    if (id) void openDetail({ id });
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  ++detailSeq;
});

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: [
      {
        fieldName: 'instanceName',
        label: '实例名称',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入实例名称' },
      },
      {
        fieldName: 'runType',
        label: '运行类型',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: RUN_TYPE_OPTIONS,
          placeholder: '请选择运行类型',
        },
      },
      {
        fieldName: 'instanceStatus',
        label: '状态',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: STATUS_OPTIONS.map(({ label, value }) => ({ label, value })),
          placeholder: '请选择状态',
        },
      },
    ],
  },
  gridOptions: {
    showOverflow: false,
    columns: [
      { field: 'id', title: '编号', width: 70 },
      {
        field: 'instanceName',
        title: '实例名称',
        minWidth: 220,
        align: 'left',
      },
      { field: 'taskName', title: '任务名称', width: 150 },
      {
        field: 'runType',
        title: '运行类型',
        width: 110,
        formatter: ({ cellValue }) => runTypeLabel(cellValue),
      },
      {
        field: 'instanceStatus',
        title: '状态',
        width: 100,
        slots: { default: 'instanceStatus' },
      },
      {
        field: 'etlBatch',
        title: 'ETL 来源',
        minWidth: 190,
        slots: { default: 'etlSource' },
      },
      {
        field: 'startTime',
        title: '开始时间',
        width: 165,
        formatter: 'formatDateTime',
      },
      { field: 'durationMs', title: '耗时(ms)', width: 90 },
      { field: 'totalCount', title: '检测总数', width: 90 },
      { field: 'failCount', title: '失败数', width: 80 },
      { field: 'passRate', title: '通过率(%)', width: 90 },
      { field: 'score', title: '评分', width: 80 },
      {
        field: 'execUser',
        title: '执行用户',
        width: 100,
        formatter: ({ cellValue }) => cellValue || '-',
      },
      {
        title: '操作',
        width: 180,
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
          return await getTaskInstancePage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<QualityTaskInstanceApi.Instance>,
});

watch(
  () => [accessStore.accessToken, accessStore.refreshToken],
  () => {
    closeDetail();
    gridApi.query();
  },
);
</script>

<template>
  <Page auto-content-height>
    <Grid table-title="任务实例监控">
      <template #instanceStatus="{ row }">
        <Tag :color="statusOption(row.instanceStatus)?.color ?? 'default'">
          {{
            statusOption(row.instanceStatus)?.label ??
            displayOrDash(row.instanceStatus)
          }}
        </Tag>
      </template>
      <template #etlSource="{ row }">
        <span v-if="row.etlBatch">
          运行 #{{ row.etlBatch.recordId }} · 批次 #{{
            row.etlBatch.targetBatchId
          }}
        </span>
        <span v-else class="text-muted-foreground">-</span>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '详情',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              onClick: openDetail.bind(null, row),
            },
            {
              label: 'ETL 运行',
              icon: 'lucide:workflow',
              type: 'link',
              auth: ['dataforge:etl:query'],
              ifShow: !!row.etlBatch,
              onClick: () => openEtlRecord(row.etlBatch),
            },
          ]"
        />
      </template>
    </Grid>

    <Modal
      v-model:open="detailVisible"
      :footer="null"
      :width="960"
      title="实例详情"
      @cancel="closeDetail"
    >
      <Descriptions :column="2" bordered size="small">
        <DescriptionsItem :span="2" label="实例名称">
          {{ detail?.instanceName }}
        </DescriptionsItem>
        <DescriptionsItem label="任务名称">
          {{ detail?.taskName ?? '-' }}
        </DescriptionsItem>
        <DescriptionsItem label="运行类型">
          {{ runTypeLabel(detail?.runType) }}
        </DescriptionsItem>
        <DescriptionsItem label="状态">
          {{
            statusOption(detail?.instanceStatus)?.label ??
            displayOrDash(detail?.instanceStatus)
          }}
        </DescriptionsItem>
        <DescriptionsItem label="运行次数">
          {{ detail?.runCount ?? '-' }}
        </DescriptionsItem>
        <DescriptionsItem label="开始时间">
          {{ formatDateTime(detail?.startTime) || '-' }}
        </DescriptionsItem>
        <DescriptionsItem label="结束时间">
          {{ formatDateTime(detail?.endTime) || '-' }}
        </DescriptionsItem>
        <DescriptionsItem label="运行耗时">
          {{ detail?.durationMs ?? '-' }} ms
        </DescriptionsItem>
        <DescriptionsItem label="检测总数">
          {{ displayOrDash(detail?.totalCount) }}
        </DescriptionsItem>
        <DescriptionsItem label="失败数">
          {{ displayOrDash(detail?.failCount) }}
        </DescriptionsItem>
        <DescriptionsItem label="通过率">
          {{
            detail?.passRate === null || detail?.passRate === undefined
              ? '-'
              : `${detail.passRate}%`
          }}
        </DescriptionsItem>
        <DescriptionsItem label="质量评分">
          {{ displayOrDash(detail?.score) }}
        </DescriptionsItem>
        <DescriptionsItem label="执行用户">
          {{ detail?.execUser ?? '-' }}
        </DescriptionsItem>
        <DescriptionsItem label="主机">
          {{ detail?.host ?? '-' }}
        </DescriptionsItem>
        <DescriptionsItem v-if="detail?.errorFlag" :span="2" label="错误标识">
          {{ detail?.errorFlag }}
        </DescriptionsItem>
      </Descriptions>
      <template v-if="detail?.etlBatch">
        <div class="mb-2 mt-4 flex items-center justify-between">
          <strong>ETL 来源</strong>
          <Button
            v-if="canQueryEtl"
            size="small"
            type="link"
            @click="openEtlRecord(detail?.etlBatch)"
          >
            <template #icon><IconifyIcon icon="lucide:workflow" /></template>
            查看 ETL 运行
          </Button>
        </div>
        <p class="text-muted-foreground mb-2 text-xs">
          检测范围限定为该次 ETL
          目标批次中准入的合格行及允许的警告行，不扫描整张目标表；检测结论以上方实例状态为准。
        </p>
        <Descriptions :column="2" bordered size="small">
          <DescriptionsItem label="运行编号">
            {{ detail.etlBatch.recordId }}
          </DescriptionsItem>
          <DescriptionsItem label="目标批次">
            {{ detail.etlBatch.targetBatchId }}
          </DescriptionsItem>
          <DescriptionsItem label="目标节点">
            {{ displayOrDash(detail.etlBatch.sinkNodeId) }}
          </DescriptionsItem>
          <DescriptionsItem label="质量节点 / 批次">
            {{ displayOrDash(detail.etlBatch.qualityNodeId) }} /
            {{ displayOrDash(detail.etlBatch.qualityBatchId) }}
          </DescriptionsItem>
          <DescriptionsItem label="内联报告">
            {{ displayOrDash(detail.etlBatch.inlineReportId) }}
          </DescriptionsItem>
          <DescriptionsItem label="受理状态">
            {{
              ETL_BATCH_STATE_LABELS[detail.etlBatch.state ?? ''] ??
              displayOrDash(detail.etlBatch.state)
            }}
          </DescriptionsItem>
          <DescriptionsItem label="落库行数">
            {{ displayOrDash(detail.etlBatch.rowCount) }}
          </DescriptionsItem>
          <DescriptionsItem label="准入行数">
            {{ displayOrDash(detail.etlBatch.admittedRowCount) }}
          </DescriptionsItem>
          <DescriptionsItem label="允许警告行准入">
            {{ yesNo(detail.etlBatch.allowWarnings) }}
          </DescriptionsItem>
          <DescriptionsItem label="来源运行标识">
            {{ displayOrDash(detail.etlBatch.runId) }}
          </DescriptionsItem>
          <DescriptionsItem
            v-if="detail.etlBatch.replayOfBatchId"
            :span="2"
            label="回放来源"
          >
            批次 #{{ detail.etlBatch.replayOfBatchId }} · 运行 #{{
              displayOrDash(detail.etlBatch.replayOfRecordId)
            }}
            · 整改提交 #{{
              displayOrDash(detail.etlBatch.workOrderSubmissionId)
            }}
          </DescriptionsItem>
        </Descriptions>
      </template>
    </Modal>
  </Page>
</template>
