<script lang="ts" setup>
import type { DataAssetApi } from '#/api/asset/dataasset';
import type { AssetEtlBatchBinding } from '#/api/asset/dataasset/etl-batch';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import { Alert, Button, Input, message, Table, Tag } from 'antdv-next';

import {
  bindAssetEtlBatch,
  getAssetEtlBatchBinding,
  getAssetEtlBatchRows,
} from '#/api/asset/dataasset/etl-batch';
import EtlTargetBatchPicker from '#/components/etl-target-batch-picker/index.vue';

import EtlBatchHistory from './etl-batch-history.vue';

defineOptions({ name: 'AssetEtlBatchTab' });

const props = defineProps<{ asset: DataAssetApi.DataAsset }>();
const emit = defineEmits<{ refresh: [] }>();
const access = useAccessStore();
const loading = ref(false);
const bindingBusy = ref(false);
const rowLoading = ref(false);
const binding = ref<AssetEtlBatchBinding | null>(null);
const selectedBatchId = ref('');
const reason = ref('');
const rows = ref<(Record<string, unknown> & { __previewKey: string })[]>([]);
const afterRow = ref<DataAssetApi.Id>('0');
const hasMore = ref(false);
let epoch = 0;

const columns = computed(() =>
  (binding.value?.batch.fields ?? []).map((field) => ({
    dataIndex: field,
    key: field,
    title: field,
    width: 180,
    ellipsis: true,
  })),
);

async function loadBinding() {
  const assetId = props.asset.id;
  if (!assetId) return;
  const current = ++epoch;
  loading.value = true;
  rows.value = [];
  afterRow.value = '0';
  hasMore.value = false;
  try {
    const result = await getAssetEtlBatchBinding(assetId);
    if (current !== epoch) return;
    binding.value = result;
    selectedBatchId.value = '';
    reason.value = '';
  } finally {
    if (current === epoch) loading.value = false;
  }
}

async function loadRows(append = false) {
  const assetId = props.asset.id;
  if (!assetId || !binding.value) return;
  const current = epoch;
  const expectedRevision = binding.value.revision;
  rowLoading.value = true;
  try {
    const result = await getAssetEtlBatchRows(
      assetId,
      expectedRevision,
      append ? afterRow.value : '0',
      100,
    );
    if (current !== epoch) return;
    const nextRows = (result.rows ?? []).map((row, index) => ({
      ...row,
      __previewKey: `${String(result.nextAfterRow)}:${index}`,
    }));
    rows.value = append ? [...rows.value, ...nextRows] : nextRows;
    afterRow.value = result.nextAfterRow;
    hasMore.value = Boolean(result.hasMore);
  } finally {
    if (current === epoch) rowLoading.value = false;
  }
}

async function submitBinding() {
  const assetId = props.asset.id;
  const batchId = selectedBatchId.value.trim();
  const trimmedReason = reason.value.trim();
  if (!assetId || !/^\d+$/.test(batchId)) {
    message.warning('请输入 ETL 批次 ID（十进制数字）');
    return;
  }
  if (!trimmedReason || trimmedReason.length > 1000) {
    message.warning('请填写不超过 1000 字的关联原因');
    return;
  }
  if (bindingBusy.value) return;
  bindingBusy.value = true;
  try {
    await bindAssetEtlBatch({
      assetId,
      batchId,
      expectedRevision: binding.value?.revision,
      reason: trimmedReason,
    });
    message.success('ETL 批次已关联到资产');
    emit('refresh');
    await loadBinding();
  } finally {
    bindingBusy.value = false;
  }
}

watch(
  () => [props.asset.id, access.accessToken, access.refreshToken],
  () => {
    epoch++;
    binding.value = null;
    rows.value = [];
    selectedBatchId.value = '';
    reason.value = '';
    void loadBinding();
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  epoch++;
});
</script>

<template>
  <div class="flex flex-col gap-4">
    <Alert
      show-icon
      type="info"
      message="关联一个已完成且通过准入的 ETL 目标批次。服务端会再次核对资产数据源、模式、表名、质量状态和当前用户读取权限。"
    />
    <div class="flex flex-wrap items-end gap-3">
      <div class="min-w-52 flex-1">
        <div class="mb-1 text-sm">ETL 批次 ID</div>
        <Input
          v-model:value="selectedBatchId"
          placeholder="输入执行记录中的目标批次 ID"
        />
        <EtlTargetBatchPicker
          :disabled="bindingBusy"
          @select="(item) => (selectedBatchId = String(item.id))"
        />
      </div>
      <div class="min-w-64 flex-[2]">
        <div class="mb-1 text-sm">关联原因</div>
        <Input.TextArea v-model:value="reason" :maxlength="1000" :rows="1" />
      </div>
      <Button
        v-access:code="'asset:data-asset:update'"
        type="primary"
        :loading="bindingBusy"
        @click="submitBinding"
      >
        <template #icon><IconifyIcon icon="lucide:link" /></template>
        关联批次
      </Button>
      <Button :loading="loading" @click="loadBinding">
        <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
        刷新
      </Button>
    </div>

    <div v-if="binding" class="flex flex-col gap-3">
      <div class="grid gap-2 rounded border p-3 sm:grid-cols-2 lg:grid-cols-4">
        <div>当前修订：{{ binding.revision }}</div>
        <div>批次 ID：{{ binding.batchId }}</div>
        <div>执行记录：{{ binding.recordId }}</div>
        <div>节点：{{ binding.batch.nodeNo }}</div>
        <div>
          目标：{{ binding.batch.databaseId }} /
          {{ binding.batch.schemaName }}.{{ binding.batch.tableName }}
        </div>
        <div>
          提交状态：<Tag>{{ binding.batch.commitStatus }}</Tag>
        </div>
        <div>
          质量状态：<Tag
            :color="
              binding.batch.qualityStatus === 'PASSED' ? 'green' : 'orange'
            "
          >
            {{ binding.batch.qualityStatus }}
          </Tag>
        </div>
        <div>可用：{{ binding.batch.available ? '是' : '否' }}</div>
        <div>
          输入 / 输出：{{ binding.batch.inputCount ?? '-' }} /
          {{ binding.batch.outputCount ?? '-' }}
        </div>
        <div>
          通过 / 警告 / 拒绝：{{ binding.batch.passCount ?? '-' }} /
          {{ binding.batch.warnCount ?? '-' }} /
          {{ binding.batch.rejectCount ?? '-' }}
        </div>
        <div class="break-all sm:col-span-2">
          Payload hash：{{ binding.payloadHash }}
        </div>
        <div class="sm:col-span-2">关联原因：{{ binding.reason }}</div>
      </div>
      <div class="flex items-center gap-2">
        <Button
          v-access:code="'asset:data-asset:query'"
          :loading="rowLoading"
          @click="loadRows(false)"
        >
          <template #icon><IconifyIcon icon="lucide:table-2" /></template>
          预览准入数据
        </Button>
        <Button v-if="hasMore" :loading="rowLoading" @click="loadRows(true)">
          加载更多
        </Button>
        <span class="text-xs text-gray-500"
          >最多每次加载 100 行，源对象权限会在每次读取时重新检查。</span
        >
      </div>
      <Table
        :columns="columns"
        :data-source="rows"
        :loading="rowLoading"
        :pagination="false"
        :row-key="(row: any) => row.__previewKey"
        :scroll="{ x: 'max-content' }"
        size="small"
      />
    </div>
    <Alert
      v-else-if="!loading"
      type="warning"
      message="尚未关联 ETL 目标批次。"
    />
    <EtlBatchHistory
      v-if="asset.id"
      :asset-id="asset.id"
      :current-revision="binding?.revision"
    />
  </div>
</template>

<style scoped src="../../../../table-font.css"></style>
