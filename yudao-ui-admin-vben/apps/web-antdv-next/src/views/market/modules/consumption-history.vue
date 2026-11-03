<script setup lang="ts">
import type {
  ConsumptionBalance,
  ConsumptionReceipt,
} from '#/api/asset/consumption';

import { onBeforeUnmount, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Button, Input, Table, Tag } from 'antdv-next';

import {
  getConsumptionBalances,
  getConsumptionHistory,
  getConsumptionReceipt,
} from '#/api/asset/consumption';
import { createLatestWins } from '#/utils/resource-id';

const loading = ref(false);
const receipts = ref<ConsumptionReceipt[]>([]);
const balances = ref<ConsumptionBalance[]>([]);
const guard = createLatestWins();
const lookupGuard = createLatestWins();
const access = useAccessStore();
const user = useUserStore();
const lookupAssetId = ref('');
const lookupKey = ref('');
const lookupLoading = ref(false);
const lookupResult = ref<ConsumptionReceipt | null>();
function reset() {
  guard.invalidate();
  lookupGuard.invalidate();
  receipts.value = [];
  balances.value = [];
  loading.value = false;
  lookupAssetId.value = '';
  lookupKey.value = '';
  lookupLoading.value = false;
  lookupResult.value = undefined;
}
const states = {
  CANCELLED: '失败已释放',
  COMMITTED: '已确认消费',
  EXPIRED: '过期已释放',
  RESERVED: '执行中预占',
};
const [Modal, modalApi] = useVbenModal({
  title: '我的消费额度与回执',
  showConfirmButton: false,
  onOpenChange(open) {
    reset();
    if (open) load();
  },
});
watch(
  () => [access.accessToken, access.refreshToken, user.userInfo?.userId],
  () => {
    reset();
    void modalApi.close();
  },
  { flush: 'sync' },
);
onBeforeUnmount(reset);
watch(
  [lookupAssetId, lookupKey],
  () => {
    lookupGuard.invalidate();
    lookupResult.value = undefined;
    lookupLoading.value = false;
  },
  { flush: 'sync' },
);
async function lookup() {
  if (
    !/^[1-9]\d*$/.test(lookupAssetId.value.trim()) ||
    !/^[A-Za-z0-9][\w.:-]{7,127}$/.test(lookupKey.value.trim())
  )
    return;
  const ticket = lookupGuard.next();
  lookupLoading.value = true;
  lookupResult.value = undefined;
  try {
    const result = await getConsumptionReceipt(
      lookupAssetId.value.trim(),
      lookupKey.value.trim(),
    );
    if (ticket.isCurrent()) lookupResult.value = result ?? null;
  } finally {
    if (ticket.isCurrent()) lookupLoading.value = false;
  }
}
async function load() {
  const ticket = guard.next();
  loading.value = true;
  try {
    const [history, current] = await Promise.all([
      getConsumptionHistory(),
      getConsumptionBalances(),
    ]);
    if (ticket.isCurrent()) {
      receipts.value = history;
      balances.value = current;
    }
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}
const balanceColumns = [
  { title: '批准来源', dataIndex: 'sourceKey', key: 'sourceKey' },
  { title: '计量方式', dataIndex: 'metric', key: 'metric' },
  { title: '批准总额度', dataIndex: 'quotaLimit', key: 'quotaLimit' },
  { title: '已使用', dataIndex: 'usedUnits', key: 'usedUnits' },
  { title: '执行中预占', dataIndex: 'reservedUnits', key: 'reservedUnits' },
  { title: '可用余额', dataIndex: 'remainingUnits', key: 'remainingUnits' },
];
const receiptColumns = [
  {
    title: '请求键',
    dataIndex: 'requestKey',
    key: 'requestKey',
    ellipsis: true,
  },
  { title: '消费入口', dataIndex: 'consumerKey', key: 'consumerKey' },
  { title: '状态', dataIndex: 'state', key: 'state' },
  { title: '实际返回行', dataIndex: 'actualRows', key: 'actualRows' },
];
</script>
<template>
  <Modal class="w-[1100px]">
    <div class="flex flex-col gap-4 p-4">
      <Alert
        message="门户与 API 共用批准来源余额。网络中断请先核对请求键；已确认回执不会再次执行查询，重新查询将产生新的消费。显示最近100条回执及已发生消费的来源。"
        type="info"
        show-icon
      />
      <Button :loading="loading" @click="load">
        <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
        刷新余额与回执
      </Button>
      <div class="flex gap-2">
        <Input
          v-model:value="lookupAssetId"
          aria-label="核查资产编号"
          placeholder="资产编号（保留原始完整编号）"
        />
        <Input
          v-model:value="lookupKey"
          aria-label="核查请求键"
          placeholder="网络中断时记录的请求键"
        />
        <Button
          :loading="lookupLoading"
          :disabled="
            !/^[1-9]\d*$/.test(lookupAssetId.trim()) ||
            !/^[A-Za-z0-9][\w.:-]{7,127}$/.test(lookupKey.trim())
          "
          @click="lookup"
        >
          <template #icon><IconifyIcon icon="lucide:file-search" /></template>
          核查原请求
        </Button>
      </div>
      <Alert
        v-if="lookupResult === null"
        message="未找到当前账户的原请求回执；请核对资产编号和请求键。核查不会执行数据查询。"
        type="info"
      />
      <Alert
        v-else-if="lookupResult"
        :message="`原请求：${states[lookupResult.state]}，实际返回 ${lookupResult.actualRows ?? '尚未确认'} 行。核查不会重新查询或扣费。`"
        type="info"
      />
      <Table
        :columns="balanceColumns"
        :data-source="balances"
        :loading="loading"
        :row-key="
          (row: ConsumptionBalance) => `${row.sourceSystem}:${row.sourceKey}`
        "
        :pagination="false"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'metric'">
            {{ record.metric === 'CALLS' ? '次数' : '行数' }}
          </template>
        </template>
      </Table>
      <Table
        :columns="receiptColumns"
        :data-source="receipts"
        :loading="loading"
        :row-key="
          (row: ConsumptionReceipt) => `${row.consumerKey}:${row.requestKey}`
        "
        :pagination="{ pageSize: 10 }"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <Tag
            v-if="column.key === 'state'"
            :color="record.state === 'COMMITTED' ? 'success' : 'default'"
          >
            {{ states[record.state as ConsumptionReceipt['state']] }}
          </Tag>
        </template>
      </Table>
    </div>
  </Modal>
</template>
