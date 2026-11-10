<script lang="ts" setup>
import type { SupplyAppApi } from '#/api/supply/app';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import { Alert, Button, Drawer, Modal, Table } from 'antdv-next';

import { getEtlGrantAuditPage } from '#/api/supply/app';

const props = defineProps<{ appId: SupplyAppApi.Id; open: boolean }>();
const emit = defineEmits<{ close: [] }>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const canRead = computed(
  () =>
    hasAccessByCodes(['supply:app:query']) &&
    hasAccessByCodes(['supply:app:grant', 'supply:app:update']),
);
const rows = ref<SupplyAppApi.EtlGrantAudit[]>([]);
const selected = ref<SupplyAppApi.EtlGrantAudit>();
const loading = ref(false);
const error = ref('');
const cursor = ref<SupplyAppApi.Id>();
const hasMore = ref(false);
let generation = 0;
const actions: Record<string, string> = {
  CREATE: '首次授权',
  REGRANT: '重新授权',
  ENABLE: '启用授权',
  DISABLE: '禁用授权',
  REVOKE: '永久关闭授权',
  APP_ENABLE: '启用应用',
  APP_DISABLE: '停用应用',
  APP_REVOKE: '撤销应用',
};
const columns = [
  { title: '时间', key: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 140 },
  { title: '授权 / API', key: 'grant', width: 220 },
  { title: '操作者', dataIndex: 'actorId', key: 'actorId', width: 120 },
  { title: '原因', dataIndex: 'reason', key: 'reason', width: 260 },
  { title: '结果', dataIndex: 'result', key: 'result', width: 110 },
  { title: '详情', key: 'detail', width: 150, fixed: 'right' as const },
];
const snapshotFields: {
  key: keyof SupplyAppApi.EtlGrantSnapshot;
  label: string;
}[] = [
  { key: 'permissionUserId', label: '执行权限主体' },
  { key: 'sourceType', label: '授权来源' },
  { key: 'sourceId', label: '来源编号' },
  { key: 'purpose', label: '批次用途摘要' },
  { key: 'approvedContractHash', label: '批准合同摘要' },
  { key: 'apiVersion', label: 'API 版本' },
  { key: 'grantRevision', label: '授权修订' },
  { key: 'aggregateRevision', label: '控制修订' },
  { key: 'grantStatus', label: '授权状态' },
  { key: 'applicationStatus', label: '应用状态' },
  { key: 'aggregateDisabled', label: '整体禁用' },
  { key: 'foreverFlag', label: '永久有效' },
  { key: 'validFrom', label: '有效期起点' },
  { key: 'validTo', label: '有效期终点' },
];
function snapshotValue(
  snapshot: null | SupplyAppApi.EtlGrantSnapshot | undefined,
  key: keyof SupplyAppApi.EtlGrantSnapshot,
) {
  const value = snapshot?.[key];
  if (value === undefined || value === null) return '—';
  if (key === 'validFrom' || key === 'validTo')
    return formatDateTime(value as number);
  if (key === 'foreverFlag') return value === 1 ? '是' : '否';
  if (typeof value === 'boolean') return value ? '是' : '否';
  return String(value);
}
const comparison = computed(() =>
  snapshotFields.map(({ key, label }) => ({
    key,
    label,
    before: snapshotValue(selected.value?.before, key),
    after: snapshotValue(selected.value?.after, key),
  })),
);
function row(record: object) {
  return record as SupplyAppApi.EtlGrantAudit;
}
function reset() {
  generation++;
  rows.value = [];
  selected.value = undefined;
  error.value = '';
  loading.value = false;
  cursor.value = undefined;
  hasMore.value = false;
}
async function load(append = false) {
  if (!props.open || !canRead.value || loading.value) return;
  const beforeId = append ? cursor.value : undefined;
  if (append && (!hasMore.value || beforeId === undefined)) return;
  if (!append) reset();
  const current = ++generation;
  loading.value = true;
  error.value = '';
  try {
    const page = await getEtlGrantAuditPage({
      appId: props.appId,
      beforeId,
      limit: 20,
    });
    if (current !== generation || !props.open) return;
    if (page.list.some((item) => String(item.appId) !== String(props.appId)))
      throw new Error('AUDIT_IDENTITY');
    rows.value = append ? [...rows.value, ...page.list] : page.list;
    cursor.value = page.nextBeforeId;
    hasMore.value = page.hasMore && cursor.value !== undefined;
  } catch {
    if (current === generation) error.value = '授权审计读取失败，请重试。';
  } finally {
    if (current === generation) loading.value = false;
  }
}
watch(
  () => [
    props.appId,
    props.open,
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
    canRead.value,
  ],
  () => {
    reset();
    if (props.open && canRead.value) void load();
  },
  { immediate: true, flush: 'sync' },
);
onBeforeUnmount(reset);
</script>

<template>
  <Drawer
    :open="open && canRead"
    title="ETL 授权审计"
    size="1100px"
    destroy-on-hidden
    @close="emit('close')"
  >
    <Alert
      class="mb-3"
      type="info"
      message="显示升级后实际记录的授权及应用控制历史，不补造旧记录。历史状态不等于当前授权；此处不能恢复权限。"
    />

    <Table
      class="supply-font-table"
      :columns="columns"
      :data-source="rows"
      :loading="loading"
      :pagination="false"
      :row-key="(record: SupplyAppApi.EtlGrantAudit) => String(record.id)"
      :scroll="{ x: 'max-content' }"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'createTime'">
          {{ formatDateTime(row(record).createTime) }}
        </template>
        <template v-else-if="column.key === 'action'">
          {{ actions[row(record).action] ?? row(record).action }}
        </template>
        <template v-else-if="column.key === 'grant'">
          {{ row(record).grantId }} / {{ row(record).apiId }}
        </template>
        <Button
          v-else-if="column.key === 'detail'"
          type="link"
          @click="selected = row(record)"
        >
          <template #icon>
            <IconifyIcon icon="lucide:git-compare-arrows" />
          </template>
          前后对比
        </Button>
      </template>
    </Table>
    <div class="mt-3 flex gap-2">
      <Button :disabled="loading" @click="load(false)">
        <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
        刷新审计
      </Button>
      <Button v-if="hasMore" :loading="loading" @click="load(true)">
        加载更早记录
      </Button>
    </div>
    <Modal
      :open="!!selected"
      title="权限变更前后对比"
      width="900px"
      :footer="null"
      destroy-on-hidden
      @cancel="selected = undefined"
    >
      <template v-if="selected">
        <p class="mb-3 break-all">
          审计 #{{ selected.id }} · 修订 {{ selected.revision }} · 链路
          {{ selected.traceId ?? '—' }}
        </p>
        <Table
          class="supply-font-table"
          :data-source="comparison"
          :pagination="false"
          row-key="key"
          size="small"
          :columns="[
            { title: '字段', dataIndex: 'label' },
            { title: '变更前', dataIndex: 'before' },
            { title: '变更后', dataIndex: 'after' },
          ]"
        />
      </template>
    </Modal>
  </Drawer>
</template>

<style scoped>
.supply-font-table :deep(.ant-table-thead > tr > th),
.supply-font-table :deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
