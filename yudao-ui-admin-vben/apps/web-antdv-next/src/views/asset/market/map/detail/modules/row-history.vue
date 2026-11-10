<script setup lang="ts">
import type { Dayjs } from 'dayjs';

import type { ImportedAssetApi } from '#/api/asset/dataasset/imported';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import { Button, Input, Table } from 'antdv-next';

import { RangePicker } from '#/adapter/component/date-picker';
import { getImportedAssetRowLogPage } from '#/api/asset/dataasset/imported';
import { createLatestWins, parseResourceId } from '#/utils/resource-id';

defineOptions({ name: 'AssetMapImportedRowHistory' });
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const owner = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    access.loginExpired,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]),
);
const target = ref<{ id: ImportedAssetApi.Id; rowId?: string }>();
const rows = ref<ImportedAssetApi.RowLog[]>([]);
const loading = ref(false);
const failed = ref(false);
const total = ref(0);
const pageNo = ref(1);
const pageSize = ref(10);
const dateRange = ref<[Dayjs, Dayjs]>();
const creatorName = ref('');
const guard = createLatestWins();
let opened = false;
function reset() {
  guard.invalidate();
  target.value = undefined;
  rows.value = [];
  loading.value = false;
  failed.value = false;
  total.value = 0;
  pageNo.value = 1;
  dateRange.value = undefined;
  creatorName.value = '';
}
async function load() {
  if (
    !opened ||
    !target.value ||
    !access.accessToken ||
    access.loginExpired ||
    !parseResourceId(user.userInfo?.userId) ||
    !hasAccessByCodes(['asset:data-asset:update'])
  )
    return;
  const ticket = guard.next();
  loading.value = true;
  failed.value = false;
  try {
    const data = await getImportedAssetRowLogPage({
      ...target.value,
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      createTime: dateRange.value
        ? [dateRange.value[0].valueOf(), dateRange.value[1].valueOf()]
        : undefined,
      creatorName: creatorName.value.trim() || undefined,
    });
    if (!ticket.isCurrent()) return;
    rows.value = data.list;
    total.value = data.total;
  } catch {
    if (ticket.isCurrent()) {
      rows.value = [];
      failed.value = true;
    }
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}
function query() {
  pageNo.value = 1;
  void load();
}
function clearFilters() {
  dateRange.value = undefined;
  creatorName.value = '';
  query();
}
const [Modal, modalApi] = useVbenModal({
  title: '修改记录',
  fullscreenButton: false,
  showConfirmButton: false,
  cancelText: '关闭',
  onOpenChange(isOpen) {
    opened = isOpen;
    reset();
    if (!isOpen) return;
    const data = modalApi.getData() as {
      id: ImportedAssetApi.Id;
      rowId?: string;
    };
    if (!parseResourceId(data?.id)) return;
    target.value = { id: data.id, rowId: data.rowId };
    void load();
  },
});
const tableColumns = [
  { key: 'id', dataIndex: 'id', title: '编号', width: 130 },
  { key: 'rowId', dataIndex: 'rowId', title: '记录编号', width: 150 },
  { key: 'changes', title: '修改内容', width: 500 },
  { key: 'actorName', dataIndex: 'actorName', title: '修改人', width: 120 },
  { key: 'createTime', title: '修改时间', width: 180 },
];
watch(
  owner,
  () => {
    opened = false;
    reset();
    modalApi.setState({ isOpen: false });
  },
  { flush: 'sync' },
);
onBeforeUnmount(() => {
  opened = false;
  reset();
});
</script>

<template>
  <Modal
    :centered="false"
    confirm-text="确定"
    cancel-text="取消"
    class="!top-[60px] !w-[1200px] !max-w-[calc(100vw-32px)] !rounded-[var(--radius)]"
  >
    <div class="row-history__filters">
      <label>时间
        <RangePicker
          v-model:value="dateRange"
          show-time
          :placeholder="['开始日期', '结束日期']"
      /></label>
      <label>创建人
        <Input
          v-model:value="creatorName"
          placeholder="请输入创建人"
          allow-clear
          @press-enter="query"
      /></label>
      <Button @click="query">查询</Button><Button @click="clearFilters">重置</Button>
    </div>

    <Table
      :columns="tableColumns"
      :data-source="rows"
      :loading="loading"
      :row-key="(row) => String(row.id)"
      :scroll="{ x: 1080 }"
      :pagination="{
        current: pageNo,
        pageSize,
        total,
        showSizeChanger: true,
        showTotal: (count: number) => `共 ${count} 条`,
      }"
      size="small"
      @change="
        (pagination) => {
          pageNo = pagination.current ?? 1;
          pageSize = pagination.pageSize ?? 10;
          load();
        }
      "
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'createTime'">
          {{ formatDateTime(record.createTime) || '-' }}
        </template>

        <template v-if="column.key === 'changes'">
          <div
            v-for="change in (record as ImportedAssetApi.RowLog).changes"
            :key="change.columnName"
          >
            {{ change.columnLabel || change.columnName }}：{{
              change.oldValue === null ? 'NULL' : change.oldValue
            }}
            → {{ change.newValue === null ? 'NULL' : change.newValue }}
          </div>
        </template>
      </template>
    </Table>
  </Modal>
</template>

<style scoped>
.row-history__filters {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 24px;
  align-items: center;
  margin: 8px 0 20px;
}

.row-history__filters label {
  display: flex;
  gap: 10px;
  align-items: center;
}

.row-history__filters :deep(.ant-input-affix-wrapper) {
  width: 180px;
}
</style>

<style scoped src="../../../../table-font.css"></style>
