<script lang="ts" setup>
import type { DataforgeEtlApi } from '#/api/dataforge/etl';
import type { EtlTargetBatch } from '#/api/dataforge/etl/target-batch';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import { Alert, Button, Input, Modal, Table } from 'antdv-next';

import { getEtlRecordPage } from '#/api/dataforge/etl';
import { getEtlTargetBatches } from '#/api/dataforge/etl/target-batch';
import { parseResourceId } from '#/utils/resource-id';

defineProps<{ disabled?: boolean }>();
const emit = defineEmits<{ select: [batch: EtlTargetBatch] }>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const canRead = computed(() => hasAccessByCodes(['dataforge:etl:query']));
const open = ref(false);
const name = ref('');
const records = ref<DataforgeEtlApi.EtlRecord[]>([]);
const batches = ref<EtlTargetBatch[]>([]);
const selectedRecord = ref<DataforgeEtlApi.Id>();
const pageNo = ref(1);
const total = ref(0);
const loading = ref(false);
const batchLoading = ref(false);
const error = ref('');
const batchError = ref('');
let recordSeq = 0;
let batchSeq = 0;
const recordColumns = [
  { title: '运行编号', dataIndex: 'id', key: 'id', width: 180 },
  { title: '流程名称', dataIndex: 'name', key: 'name', width: 240 },
  { title: '完成时间', key: 'time', width: 180 },
  { title: '选择运行', key: 'actions', width: 110 },
];
const batchColumns = [
  { title: '目标批次', dataIndex: 'id', key: 'id', width: 180 },
  { title: '输出节点', dataIndex: 'nodeNo', key: 'nodeNo', width: 150 },
  { title: '提交 / 质量状态', key: 'status', width: 220 },
  { title: '选择', key: 'actions', width: 120 },
];
function run(record: object) {
  return record as DataforgeEtlApi.EtlRecord;
}
function batch(record: object) {
  return record as EtlTargetBatch;
}
function eligible(item: EtlTargetBatch) {
  return (
    item.commitStatus === 'COMMITTED' &&
    ['EMPTY', 'PASSED'].includes(item.qualityStatus) &&
    !!parseResourceId(item.id)
  );
}
function clearBatches() {
  batchSeq++;
  batches.value = [];
  selectedRecord.value = undefined;
  batchLoading.value = false;
  batchError.value = '';
}
function reset() {
  recordSeq++;
  open.value = false;
  records.value = [];
  loading.value = false;
  total.value = 0;
  pageNo.value = 1;
  error.value = '';
  name.value = '';
  clearBatches();
}
async function loadRecords(page = 1) {
  if (!open.value || !canRead.value) return;
  clearBatches();
  const seq = ++recordSeq;
  records.value = [];
  total.value = 0;
  pageNo.value = page;
  loading.value = true;
  error.value = '';
  try {
    const result = await getEtlRecordPage({
      pageNo: page,
      pageSize: 10,
      name: name.value.trim() || undefined,
      runStatus: 3,
      archived: false,
    });
    if (seq !== recordSeq || !open.value) return;
    records.value = result.list;
    total.value = result.total;
  } catch {
    if (seq === recordSeq)
      error.value =
        '执行记录读取失败，请重试；也可关闭窗口后手工填写批次编号。';
  } finally {
    if (seq === recordSeq) loading.value = false;
  }
}
function show() {
  if (!canRead.value) return;
  reset();
  open.value = true;
  void loadRecords();
}
async function selectRecord(record: DataforgeEtlApi.EtlRecord) {
  const id = parseResourceId(record.id);
  if (!id || !open.value || !canRead.value) return;
  clearBatches();
  selectedRecord.value = id;
  const seq = ++batchSeq;
  batchLoading.value = true;
  try {
    const result = await getEtlTargetBatches(id);
    if (seq !== batchSeq || !open.value) return;
    if (result.some((item) => String(item.recordId) !== id))
      throw new Error('BATCH_IDENTITY');
    batches.value = result;
  } catch {
    if (seq === batchSeq)
      batchError.value = '该运行的目标批次读取失败，请重新选择运行。';
  } finally {
    if (seq === batchSeq) batchLoading.value = false;
  }
}
function choose(item: EtlTargetBatch) {
  if (
    !canRead.value ||
    !open.value ||
    batchLoading.value ||
    !eligible(item) ||
    String(item.recordId) !== String(selectedRecord.value)
  )
    return;
  emit('select', item);
  reset();
}
watch(
  () => [
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
    canRead.value,
  ],
  reset,
  { flush: 'sync' },
);
onBeforeUnmount(reset);
</script>

<template>
  <Button v-if="canRead" :disabled="disabled" @click="show">
    选择目标批次
  </Button>
  <Modal
    :open="open"
    title="选择 ETL 目标批次"
    width="1000px"
    :footer="null"
    destroy-on-hidden
    @cancel="reset"
  >
    <Alert
      type="info"
      class="mb-3"
      message="先选择成功且未归档的运行，再选择已提交且质量通过的目标。这里仅展示状态摘要；选择不等于获得准入，绑定或发布时仍会核验整个运行和当前权限。"
    />
    <div class="mb-3 flex gap-2">
      <Input
        v-model:value="name"
        aria-label="流程名称筛选"
        placeholder="按流程名称搜索"
        @press-enter="loadRecords(1)"
      />
      <Button :loading="loading" @click="loadRecords(1)">搜索运行</Button>
    </div>
    <Alert v-if="error" type="error" :message="error" />
    <Table
      :columns="recordColumns"
      :data-source="records"
      :loading="loading"
      row-key="id"
      size="small"
      :pagination="{
        current: pageNo,
        pageSize: 10,
        total,
        showSizeChanger: false,
        onChange: loadRecords,
      }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'time'">
          {{ formatDateTime(run(record).endTime ?? run(record).createTime) }}
        </template>
        <Button
          v-else-if="column.key === 'actions'"
          size="small"
          @click="selectRecord(run(record))"
        >
          查看目标
        </Button>
      </template>
    </Table>
    <template v-if="selectedRecord">
      <h4 class="my-3">运行 #{{ selectedRecord }} 的目标批次</h4>
      <Alert v-if="batchError" type="error" :message="batchError" />
      <Table
        :columns="batchColumns"
        :data-source="batches"
        :loading="batchLoading"
        row-key="id"
        :pagination="false"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            {{ batch(record).commitStatus }} /
            {{ batch(record).qualityStatus }}
          </template>
          <Button
            v-else-if="column.key === 'actions'"
            size="small"
            :disabled="batchLoading || !eligible(batch(record))"
            @click="choose(batch(record))"
          >
            选择此批次
          </Button>
        </template>
      </Table>
    </template>
  </Modal>
</template>
