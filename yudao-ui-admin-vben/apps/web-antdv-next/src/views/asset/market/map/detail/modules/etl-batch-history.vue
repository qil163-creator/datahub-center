<script lang="ts" setup>
import type { DataAssetApi } from '#/api/asset/dataasset';
import type { AssetEtlBatchBindingHistory } from '#/api/asset/dataasset/etl-batch';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import { Alert, Button, Table } from 'antdv-next';

import { getAssetEtlBatchHistory } from '#/api/asset/dataasset/etl-batch';

const props = defineProps<{
  assetId: DataAssetApi.Id;
  currentRevision?: number;
}>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const canRead = computed(() => hasAccessByCodes(['asset:data-asset:query']));
const expanded = ref(false);
const rows = ref<AssetEtlBatchBindingHistory[]>([]);
const loading = ref(false);
const error = ref('');
const hasMore = ref(false);
const cursor = ref<number>();
let generation = 0;
const columns = [
  { title: '修订', dataIndex: 'revision', key: 'revision', width: 70 },
  { title: '批次 / 执行记录', key: 'batch', width: 220 },
  { title: '目标', key: 'target', width: 220 },
  { title: '操作者', dataIndex: 'actorId', key: 'actorId', width: 100 },
  { title: '关联时间', key: 'createTime', width: 180 },
  { title: '关联原因', dataIndex: 'reason', key: 'reason', width: 240 },
];
function row(record: object) {
  return record as AssetEtlBatchBindingHistory;
}
function invalidate() {
  generation++;
  rows.value = [];
  loading.value = false;
  error.value = '';
  hasMore.value = false;
  cursor.value = undefined;
}
async function load(append = false) {
  if (!expanded.value || !canRead.value || loading.value) return;
  const current = ++generation;
  const beforeRevision = append ? cursor.value : undefined;
  if (append && (!hasMore.value || beforeRevision === undefined)) return;
  if (!append) {
    rows.value = [];
    cursor.value = undefined;
    hasMore.value = false;
  }
  loading.value = true;
  error.value = '';
  try {
    const page = await getAssetEtlBatchHistory({
      assetId: props.assetId,
      beforeRevision,
      limit: 20,
    });
    if (current !== generation || !expanded.value) return;
    if (
      page.list.some((item) => String(item.assetId) !== String(props.assetId))
    )
      throw new Error('HISTORY_IDENTITY');
    rows.value = append ? [...rows.value, ...page.list] : page.list;
    cursor.value = page.nextBeforeRevision;
    hasMore.value = page.hasMore && cursor.value !== undefined;
  } catch {
    if (current === generation)
      error.value =
        '关联历史读取失败，请重试；历史记录不代表当前批次准入状态。';
  } finally {
    if (current === generation) loading.value = false;
  }
}
function toggle() {
  expanded.value = !expanded.value;
  invalidate();
  if (expanded.value) void load();
}
watch(
  () => [
    props.assetId,
    props.currentRevision,
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
    canRead.value,
  ],
  () => {
    expanded.value = false;
    invalidate();
  },
  { flush: 'sync' },
);
onBeforeUnmount(invalidate);
</script>

<template>
  <section v-if="canRead" aria-label="ETL 关联修订历史" class="space-y-3">
    <Button :aria-expanded="expanded" @click="toggle">
      <template #icon><IconifyIcon icon="lucide:history" /></template>
      {{ expanded ? '收起关联历史' : '查看关联修订历史' }}
    </Button>
    <template v-if="expanded">
      <Alert
        type="info"
        message="以下为不可变关联历史，仅供追溯；历史修订不代表当前准入，也不会恢复或读取旧批次数据。"
      />

      <Table
        :columns="columns"
        :data-source="rows"
        :loading="loading"
        :pagination="false"
        :row-key="(record: AssetEtlBatchBindingHistory) => String(record.id)"
        :scroll="{ x: 'max-content' }"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'batch'">
            {{ row(record).batchId }} / {{ row(record).recordId }}
          </template>
          <template v-else-if="column.key === 'target'">
            {{ row(record).databaseId }} / {{ row(record).schemaName }}.{{
              row(record).tableName
            }}
          </template>
          <template v-else-if="column.key === 'createTime'">
            {{ formatDateTime(row(record).createTime) }}
          </template>
        </template>
      </Table>
      <div class="flex gap-2">
        <Button :disabled="loading" @click="load(false)">
          <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
          刷新历史
        </Button>
        <Button v-if="hasMore" :loading="loading" @click="load(true)">
          加载更早修订
        </Button>
      </div>
    </template>
  </section>
</template>

<style scoped src="../../../../table-font.css"></style>
