<script lang="ts" setup>
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import {
  Alert,
  Button,
  Drawer,
  Empty,
  Modal,
  Select,
  Spin,
  Table,
  Tag,
} from 'antdv-next';

import {
  getEtlQualityBatches,
  getEtlQualityIssue,
  getEtlQualityIssues,
  retryEtlQualityRegistration,
} from '#/api/dataforge/etl';

import QualityReport from './quality-report.vue';

const accessStore = useAccessStore();
const { hasAccessByCodes } = useAccess();
const canRetry = computed(
  () =>
    hasAccessByCodes(['dataforge:etl:run']) &&
    hasAccessByCodes(['dataforge:etl:query']),
);
const visible = ref(false);
const recordId = ref<DataforgeEtlApi.Id>();
const batches = ref<DataforgeEtlApi.QualityBatch[]>([]);
const batchId = ref<string>();
const loading = ref(false);
const loadError = ref('');
const issues = ref<DataforgeEtlApi.QualityIssue[]>([]);
const issuesLoading = ref(false);
const pageNo = ref(1);
const pageSize = 20;
const total = ref(0);
const detailVisible = ref(false);
const detail = ref<DataforgeEtlApi.QualityIssueDetail>();
const detailLoading = ref(false);
const detailError = ref('');
const retrying = ref(false);
const retryError = ref('');
let retrySeq = 0;
let batchSeq = 0;
let issuesSeq = 0;
let detailSeq = 0;
const currentBatch = computed(() =>
  batches.value.find((batch) => String(batch.id) === batchId.value),
);
const batchOptions = computed(() =>
  batches.value.map((batch) => ({
    value: String(batch.id),
    label: `${batch.nodeNo} · 批次 ${batch.id}`,
  })),
);
const registrationLabels: Record<string, string> = {
  COLLECTING: '检测中',
  INCOMPLETE: '检测未完成',
  PENDING: '待登记',
  REGISTERING: '登记中',
  RETRY: '等待重试',
  REGISTERED: '已登记',
  FAILED: '待人工处理',
};
const issueColumns = [
  { title: '行号', dataIndex: 'rowNumber', key: 'rowNumber', width: 90 },
  {
    title: '质量状态',
    dataIndex: 'qualityStatus',
    key: 'qualityStatus',
    width: 100,
  },
  { title: '违规原因', dataIndex: 'violationsJson', key: 'violationsJson' },
  { title: '操作', key: 'actions', width: 140, fixed: 'right' as const },
];

function closeDetail() {
  ++detailSeq;
  detailVisible.value = false;
  detail.value = undefined;
  detailError.value = '';
  detailLoading.value = false;
}
function close() {
  ++batchSeq;
  ++issuesSeq;
  ++retrySeq;
  retrying.value = false;
  retryError.value = '';
  closeDetail();
  visible.value = false;
  batches.value = [];
  batchId.value = undefined;
  issues.value = [];
  loading.value = false;
  issuesLoading.value = false;
}
async function open(id: DataforgeEtlApi.Id) {
  close();
  recordId.value = id;
  visible.value = true;
  await loadBatches();
}
defineExpose({ open });

async function loadBatches() {
  if (recordId.value === undefined) return;
  const seq = ++batchSeq;
  loading.value = true;
  loadError.value = '';
  try {
    const response = await getEtlQualityBatches(recordId.value);
    if (seq !== batchSeq || !visible.value) return;
    batches.value = response;
    let nextId = response[0] ? String(response[0].id) : undefined;
    if (response.some((batch) => String(batch.id) === batchId.value))
      nextId = batchId.value;
    if (batchId.value === nextId && nextId) await loadIssues(1);
    else batchId.value = nextId;
  } catch {
    if (seq === batchSeq) loadError.value = '质量批次加载失败，请重试。';
  } finally {
    if (seq === batchSeq) loading.value = false;
  }
}

async function loadIssues(page = 1) {
  const seq = ++issuesSeq;
  issues.value = [];
  total.value = 0;
  pageNo.value = page;
  issuesLoading.value = false;
  closeDetail();
  if (!batchId.value) return;
  issuesLoading.value = true;
  try {
    const response = await getEtlQualityIssues({
      batchId: batchId.value,
      pageNo: page,
      pageSize,
    });
    if (seq !== issuesSeq || !visible.value) return;
    issues.value = response.list;
    total.value = response.total;
  } catch {
    // 请求客户端已显示错误。
  } finally {
    if (seq === issuesSeq) issuesLoading.value = false;
  }
}

async function showDetail(id: DataforgeEtlApi.Id) {
  closeDetail();
  const seq = detailSeq;
  detailVisible.value = true;
  detailLoading.value = true;
  try {
    const response = await getEtlQualityIssue(id);
    if (seq === detailSeq && visible.value && detailVisible.value)
      detail.value = response;
  } catch {
    if (seq === detailSeq)
      detailError.value =
        '该行来源无法安全复核、已变化或未获授权，内容不可展示；关闭弹窗后可继续查看违规原因和统计。';
  } finally {
    if (seq === detailSeq) detailLoading.value = false;
  }
}

async function retryRegistration() {
  const batch = currentBatch.value;
  if (
    !canRetry.value ||
    retrying.value ||
    batch?.registrationStatus !== 'FAILED'
  )
    return;
  const seq = ++retrySeq;
  retrying.value = true;
  retryError.value = '';
  try {
    await retryEtlQualityRegistration(batch.id);
    if (seq !== retrySeq || !visible.value) return;
    batch.registrationStatus = 'PENDING';
    await loadBatches();
  } catch {
    if (seq === retrySeq && visible.value)
      retryError.value = '重试登记请求未确认，请刷新批次状态后重试。';
  } finally {
    if (seq === retrySeq) retrying.value = false;
  }
}

function prettyJson(value?: string) {
  // 证据按原文展示，避免 JSON.parse 将大整数或精确小数转换为浮点数。
  return value || '（无内容）';
}
watch(batchId, () => {
  ++retrySeq;
  retrying.value = false;
  retryError.value = '';
  void loadIssues();
});
watch(() => [accessStore.accessToken, accessStore.refreshToken], close);
onBeforeUnmount(close);
</script>

<template>
  <Drawer
    :open="visible"
    title="ETL 行级质量证据"
    size="960px"
    destroy-on-hidden
    @close="close"
  >
    <div class="mb-3 flex items-center justify-between gap-2">
      <span>执行记录 #{{ recordId }}</span>
      <Button :loading="loading" @click="loadBatches"
        ><template #icon><IconifyIcon icon="lucide:refresh-cw" /></template
        >刷新</Button
      >
    </div>
    <Spin :spinning="loading">
      <Empty
        v-if="!loading && !loadError && !batches.length"
        description="该次执行尚无行级质量批次"
      />
      <template v-if="batches.length">
        <Select
          v-model:value="batchId"
          class="mb-3 w-full"
          :options="batchOptions"
        />
        <div v-if="currentBatch" class="quality-summary">
          <span>输入 {{ currentBatch.inputCount ?? '—' }}</span>
          <span>合格 {{ currentBatch.passCount ?? '—' }}</span>
          <span>警告 {{ currentBatch.warnCount ?? '—' }}</span>
          <span>不合格 {{ currentBatch.rejectCount ?? '—' }}</span>
          <span>违规次数 {{ currentBatch.violationCount ?? '—' }}</span>
          <Tag
            :color="
              currentBatch.registrationStatus === 'REGISTERED'
                ? 'success'
                : 'warning'
            "
          >
            {{
              registrationLabels[currentBatch.registrationStatus] ??
              currentBatch.registrationStatus
            }}
          </Tag>
          <span v-if="currentBatch.governanceReportId"
            >治理报告 #{{ currentBatch.governanceReportId }}</span
          >
          <span v-else>治理报告：尚未登记确认</span>
          <Button
            v-if="currentBatch.registrationStatus === 'FAILED' && canRetry"
            size="small"
            :loading="retrying"
            :disabled="retrying"
            @click="retryRegistration"
          >
            <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
            重试治理登记
          </Button>
          <p
            v-if="currentBatch.planHash"
            class="w-full break-all text-xs text-muted-foreground"
          >
            执行计划 {{ currentBatch.planHash }}
          </p>
        </div>
        <Alert
          v-if="retryError"
          type="error"
          :message="retryError"
          class="mb-3"
        />
        <p
          v-if="currentBatch?.registrationStatus === 'INCOMPLETE'"
          class="mb-3 text-xs text-muted-foreground"
        >
          该质量节点未完成检测，现有证据仅供排查，不代表最终质量结果，不能重试治理登记。
        </p>
        <p
          v-if="currentBatch?.registrationStatus === 'FAILED'"
          class="mb-3 text-xs text-muted-foreground"
        >
          重试仅重新提交治理报告登记，不重跑 ETL，也不重新写入业务目标。
        </p>
        <p class="mb-3 text-xs text-muted-foreground">
          违规次数按规则命中统计，同一行可命中多条规则。此处为节点行级质量证据，写入后的批次验收请使用质量验收入口。
        </p>
        <QualityReport
          v-if="currentBatch?.registrationStatus === 'REGISTERED'"
          :key="String(currentBatch.id)"
          :batch-id="currentBatch.id"
        />
        <Table
          :scroll="{ x: 'max-content' }"
          :data-source="issues"
          :columns="issueColumns"
          :loading="issuesLoading"
          :row-key="(row: DataforgeEtlApi.QualityIssue) => String(row.id)"
          :pagination="{
            current: pageNo,
            pageSize,
            total,
            showSizeChanger: false,
            onChange: loadIssues,
          }"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <pre
              v-if="column.key === 'violationsJson'"
              class="quality-reasons"
              >{{ prettyJson(record.violationsJson) }}</pre>
            <Button
              v-else-if="column.key === 'actions'"
              type="link"
              size="small"
              @click="showDetail(record.id)"
            >
              <template #icon><IconifyIcon icon="lucide:eye" /></template>
              查看行详情
            </Button>
          </template>
        </Table>
      </template>
    </Spin>
    <Modal
      :open="detailVisible"
      title="问题行详情"
      :footer="null"
      width="760px"
      destroy-on-hidden
      @cancel="closeDetail"
    >
      <Spin :spinning="detailLoading">
        <Alert v-if="detailError" type="error" :message="detailError" />
        <template v-if="detail">
          <Alert
            v-if="detail.sourceVerified"
            type="info"
            class="mb-3"
            message="原来源已重新验证；按当前权限与脱敏展示复核后的来源行，不直接展示保存的原始内容。"
          />
          <p class="mb-2">
            批次 #{{ detail.batchId }} · 行 {{ detail.rowNumber }} ·
            {{ detail.qualityStatus }}
          </p>
          <h4>违规原因</h4>
          <pre class="quality-json">{{
            prettyJson(detail.violationsJson)
          }}</pre>
          <h4>{{ detail.sourceVerified ? '经权限处理的来源行' : '行数据' }}</h4>
          <pre class="quality-json">{{ prettyJson(detail.rowJson) }}</pre>
        </template>
      </Spin>
    </Modal>
  </Drawer>
</template>

<style scoped>
.quality-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 18px;
  padding: 12px;
  margin-bottom: 12px;
  background: hsl(var(--muted) / 40%);
  border-radius: 6px;
}

.quality-reasons {
  max-width: 480px;
  max-height: 150px;
  margin: 0;
  overflow: auto;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.quality-json {
  max-height: 320px;
  padding: 10px;
  margin: 8px 0 16px;
  overflow: auto;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  background: hsl(var(--muted));
  border-radius: 6px;
}
</style>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
