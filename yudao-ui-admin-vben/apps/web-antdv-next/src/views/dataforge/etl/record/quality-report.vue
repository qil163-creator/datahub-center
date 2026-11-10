<script lang="ts" setup>
import type { Dayjs } from 'dayjs';

import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import { Alert, Button, DatePicker, Input, Modal, Spin } from 'antdv-next';

import {
  createEtlQualityWorkOrder,
  getEtlQualityReport,
} from '#/api/dataforge/etl';
import WorkOrderResolution from '#/views/governance/quality/work-order/modules/resolution.vue';

const props = defineProps<{ batchId: DataforgeEtlApi.Id }>();
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const canRead = computed(
  () =>
    hasAccessByCodes(['dataforge:etl:query']) &&
    hasAccessByCodes(['governance:quality-audit-log:query']),
);
const canCreate = computed(
  () =>
    canRead.value && hasAccessByCodes(['governance:quality-work-order:create']),
);
const canOpenOrder = computed(() =>
  hasAccessByCodes(['governance:quality-work-order:query']),
);
const report = ref<DataforgeEtlApi.QualityReport>();
const loading = ref(false);
const formVisible = ref(false);
const orderName = ref('');
const description = ref('');
const deadline = ref<Dayjs>();
const creating = ref(false);
const createError = ref('');
const needsConfirmation = ref(false);
const resolution = ref<InstanceType<typeof WorkOrderResolution>>();
let generation = 0;
let reportSeq = 0;

function invalidate() {
  ++generation;
  ++reportSeq;
  report.value = undefined;
  loading.value = false;
  creating.value = false;
  createError.value = '';
  formVisible.value = false;
  needsConfirmation.value = false;
}

async function loadReport() {
  if (!canRead.value) return;
  const current = generation;
  const seq = ++reportSeq;
  loading.value = true;
  try {
    const result = await getEtlQualityReport(props.batchId);
    if (current !== generation || seq !== reportSeq) return;
    report.value = result;
    needsConfirmation.value = false;
    if (result.workOrderId !== undefined && result.workOrderId !== null) {
      formVisible.value = false;
      createError.value = '';
    }
  } catch {
    // 请求客户端已显示错误。
  } finally {
    if (current === generation && seq === reportSeq) loading.value = false;
  }
}

function openCreate() {
  if (
    !canCreate.value ||
    !report.value ||
    (report.value.workOrderId !== undefined &&
      report.value.workOrderId !== null) ||
    needsConfirmation.value
  )
    return;
  orderName.value = `ETL 质量整改 - ${props.batchId}`;
  description.value = '';
  deadline.value = undefined;
  createError.value = '';
  formVisible.value = true;
}

async function createWorkOrder() {
  if (
    !canCreate.value ||
    creating.value ||
    loading.value ||
    !report.value ||
    (report.value.workOrderId !== undefined &&
      report.value.workOrderId !== null) ||
    needsConfirmation.value
  )
    return;
  if (
    !orderName.value.trim() ||
    orderName.value.trim().length > 256 ||
    description.value.length > 1024
  ) {
    createError.value = '请填写 1–256 字工单名称，说明不超过 1024 字。';
    return;
  }
  const current = generation;
  creating.value = true;
  createError.value = '';
  try {
    const id = await createEtlQualityWorkOrder({
      batchId: props.batchId,
      orderName: orderName.value.trim(),
      description: description.value.trim() || undefined,
      deadline: deadline.value?.valueOf(),
    });
    if (current !== generation) return;
    report.value.workOrderId = id;
    formVisible.value = false;
  } catch {
    if (current !== generation) return;
    needsConfirmation.value = true;
    createError.value =
      '创建结果未确认，请先重新查询治理报告；如已创建，将显示已有工单。';
    await loadReport();
  } finally {
    if (current === generation) creating.value = false;
  }
}

function openWorkOrder() {
  const id = report.value?.workOrderId;
  if (canOpenOrder.value && id !== undefined && id !== null)
    resolution.value?.open(id);
}

watch(
  () => [props.batchId, access.accessToken, access.refreshToken, canRead.value],
  () => {
    invalidate();
    void loadReport();
  },
  { immediate: true },
);
onBeforeUnmount(invalidate);
</script>

<template>
  <section
    v-if="canRead"
    class="mb-4 rounded border p-3"
    aria-label="治理报告与工单"
  >
    <div class="mb-2 flex items-center justify-between">
      <strong>治理报告与整改工单</strong>
      <Button
        size="small"
        :loading="loading"
        :disabled="creating"
        @click="loadReport"
      >
        <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
        重新查询报告
      </Button>
    </div>
    <Spin :spinning="loading">
      <div v-if="report" class="space-y-2">
        <p>
          报告 #{{ report.reportId }} · 执行 {{ report.runId }} · 节点
          {{ report.nodeId }} · 批次 {{ report.batchId }}
        </p>
        <p>
          总行数 {{ report.totalCount }} · 合格 {{ report.passCount }} · 警告
          {{ report.warnCount }} · 不合格 {{ report.rejectCount }}
        </p>
        <p
          v-if="report.workOrderId !== undefined && report.workOrderId !== null"
        >
          整改工单 #{{ report.workOrderId }}
          <Button
            v-if="canOpenOrder"
            type="link"
            size="small"
            @click="openWorkOrder"
          >
            <template #icon><IconifyIcon icon="lucide:eye" /></template>
            查看工单详情
          </Button>
        </p>
        <Button
          v-else-if="canCreate"
          size="small"
          :disabled="loading || needsConfirmation"
          @click="openCreate"
        >
          <template #icon><IconifyIcon icon="lucide:plus" /></template>
          创建整改工单
        </Button>
      </div>
    </Spin>
    <p class="mt-2 text-xs text-muted-foreground">
      ETL 内联工单默认由创建人负责，可转派并提交源端整改说明；当前尚未接入 ETL
      复检证据，提交后停留待复检，不能验收关闭。
    </p>
    <Modal
      :open="formVisible"
      title="创建 ETL 整改工单"
      :confirm-loading="creating"
      :ok-button-props="{ disabled: creating || loading || needsConfirmation }"
      :cancel-button-props="{ disabled: creating }"
      :closable="!creating"
      :mask-closable="!creating"
      :keyboard="!creating"
      @ok="createWorkOrder"
      @cancel="formVisible = false"
    >
      <Alert
        v-if="createError"
        class="mb-3"
        type="warning"
        :message="createError"
      />
      <div class="space-y-3">
        <label class="block">工单名称<Input
            v-model:value="orderName"
            :maxlength="256"
            :disabled="creating"
        /></label>
        <label class="block">问题说明<Input.TextArea
            v-model:value="description"
            :maxlength="1024"
            :rows="3"
            :disabled="creating"
        /></label>
        <label class="block">整改截止时间<DatePicker
            v-model:value="deadline"
            show-time
            :disabled="creating"
        /></label>
        <Button v-if="needsConfirmation" :loading="loading" @click="loadReport">
          <template #icon><IconifyIcon icon="lucide:shield-check" /></template>
          确认创建结果
        </Button>
      </div>
    </Modal>
    <WorkOrderResolution ref="resolution" />
  </section>
</template>
