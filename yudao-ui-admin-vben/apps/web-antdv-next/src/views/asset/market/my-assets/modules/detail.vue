<script lang="ts" setup>
import type { MyAssetsApi } from '#/api/asset/my-assets';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import {
  Button,
  Empty,
  Spin,
  Tag,
  Timeline,
  TimelineItem,
} from 'antdv-next';

import { getMyAsset } from '#/api/asset/my-assets';
import {
  createLatestWins,
  parseResourceId,
  sameResourceId,
} from '#/utils/resource-id';
import { PROCESS_ACTION_OPTIONS } from '#/views/asset/market/data';
import { formatMarketTime } from '#/views/asset/market/market-time';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import { deliveryLabels } from '../../data';
import { accessState, assetTitle, canOpenDelivery } from '../data';

defineOptions({ name: 'MyAssetDetail' });
const emit = defineEmits<{
  closed: [id: string];
  document: [row: MyAssetsApi.Detail];
  preview: [row: MyAssetsApi.Detail];
}>();
const access = useAccessStore();
const user = useUserStore();
const detail = ref<MyAssetsApi.Detail>();
const currentId = ref<string>();
const loading = ref(false);
const loadError = ref('');
const guard = createLatestWins();
let alive = true;
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
const fields = computed<DetailFieldItem[]>(() => {
  const row = detail.value;
  if (!row) return [];
  return [
    { key: 'applyNo', label: '申请单号', value: row.applyNo },
    { key: 'assetCode', label: '资产编码', value: row.assetCode },
    {
      key: 'marketCategoryName',
      label: '集市类目',
      value: row.marketCategoryName,
    },
    {
      key: 'deliveryType',
      label: '交付形态',
      value: deliveryLabels(
        row.deliveryType === undefined || row.deliveryType === null
          ? []
          : [row.deliveryType],
      ).join(' / '),
    },
    {
      key: 'createTime',
      label: '申请时间',
      value: formatMarketTime(row.createTime),
    },
    { key: 'assetVersion', label: '批准资产版本', value: row.assetVersion },
    {
      key: 'validity',
      label: '有效期',
      value: `${formatMarketTime(row.applyStartTime)} 至 ${row.applyEndTime === undefined || row.applyEndTime === null ? '长期有效' : formatMarketTime(row.applyEndTime)}`,
      span: 2,
    },
    { key: 'updateCycle', label: '数据更新周期', value: row.updateCycle },
    {
      key: 'evaluatedAt',
      label: '状态核验时间',
      value: formatMarketTime(row.evaluatedAt),
    },
    {
      key: 'purpose',
      label: '使用用途',
      value: row.purpose,
      span: 2,
      type: 'textarea',
    },
    { key: 'dutyMatter', label: '履职事项', value: row.dutyMatter, span: 2 },
    { key: 'legalBasis', label: '使用依据', value: row.legalBasis, span: 2 },
    {
      key: 'approvedColumns',
      label: '批准字段范围',
      value: row.approvedColumns?.join('、') || '未指定字段',
      span: 2,
      type: 'textarea',
    },
    {
      key: 'description',
      label: '资产说明',
      value: row.description,
      span: 2,
      type: 'textarea',
    },
  ];
});
const [Drawer, drawerApi] = useVbenDrawer({
  class: 'w-[960px] max-w-full',
  title: '我的资产详情',
  showConfirmButton: false,
  cancelText: '关闭',
  onOpenChange(open) {
    if (!open) {
      const id = currentId.value;
      reset();
      if (id) emit('closed', id);
    }
  },
});
function reset() {
  guard.invalidate();
  detail.value = undefined;
  currentId.value = undefined;
  loading.value = false;
  loadError.value = '';
}
function close() {
  const id = currentId.value;
  reset();
  drawerApi.setState({ isOpen: false });
  if (id) emit('closed', id);
}
function isCurrent(row: MyAssetsApi.Detail) {
  return (
    alive &&
    detail.value === row &&
    sameResourceId(currentId.value, row.applyId)
  );
}
function useDelivery(kind: 'document' | 'preview') {
  const row = detail.value;
  if (!row || !isCurrent(row) || !canOpenDelivery(row, kind)) return;
  if (kind === 'preview') emit('preview', row);
  else emit('document', row);
}
async function load() {
  const id = currentId.value;
  if (!alive || !id) return;
  const ticket = guard.next();
  detail.value = undefined;
  loadError.value = '';
  loading.value = true;
  try {
    const value = await getMyAsset(id);
    if (!ticket.isCurrent()) return;
    if (!sameResourceId(value.applyId, id))
      throw new Error('IDENTITY_MISMATCH');
    detail.value = value;
    drawerApi.setState({ title: assetTitle(value) });
  } catch (error: unknown) {
    if (ticket.isCurrent()) {
      const failure = error as null | {
        data?: { msg?: unknown };
        message?: unknown;
        msg?: unknown;
        response?: { data?: { msg?: unknown } };
      };
      const text =
        failure?.msg ??
        failure?.response?.data?.msg ??
        failure?.data?.msg ??
        failure?.message;
      loadError.value =
        typeof text === 'string' && text.trim() && text !== 'IDENTITY_MISMATCH'
          ? text
          : '资产详情读取失败，授权可能已变更，请重试。';
    }
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}
function open(id: MyAssetsApi.Id) {
  const key = parseResourceId(id);
  if (!alive || !key) return;
  reset();
  currentId.value = key;
  drawerApi.setState({ title: '我的资产详情' }).open();
  void load();
}
watch(owner, close, { flush: 'sync' });
onBeforeUnmount(() => {
  alive = false;
  reset();
});
defineExpose({ open, close, isCurrent });
</script>

<template>
  <Drawer>
    <Spin v-if="loading" spinning />

    <template v-if="detail">
      <div class="mb-4 flex flex-wrap items-center gap-2">
        <Tag :color="accessState(detail.accessStatus).color">
          {{ accessState(detail.accessStatus).label }}
        </Tag>
        <span class="text-muted-foreground text-sm">{{
          detail.disabledReason || '打开交付入口时将再次核验授权与服务状态。'
        }}</span>
      </div>
      <div class="mb-4 flex flex-wrap gap-2">
        <Button
          v-if="detail.deliveryType === 1"
          type="primary"
          :disabled="!canOpenDelivery(detail, 'preview')"
          @click="useDelivery('preview')"
        >
          <template #icon><IconifyIcon icon="lucide:table-2" /></template>
          数据预览
        </Button>
        <Button
          v-else-if="detail.deliveryType === 2"
          type="primary"
          :disabled="!canOpenDelivery(detail, 'document')"
          @click="useDelivery('document')"
        >
          <template #icon><IconifyIcon icon="lucide:book-open" /></template>
          API 文档
        </Button>
        <Button v-else-if="detail.deliveryType === 3" disabled>
          <template #icon><IconifyIcon icon="lucide:download" /></template>
          文件获取暂未接通
        </Button>
      </div>
      <DetailFieldForm :fields="fields" label-width="130px" />
      <h3 class="mb-4 mt-6 font-medium">申请记录</h3>
      <Timeline v-if="detail.processes?.length">
        <TimelineItem
          v-for="(process, index) in detail.processes"
          :key="String(process.id ?? index)"
        >
          <div class="flex flex-wrap gap-2 text-sm">
            <strong>{{
              PROCESS_ACTION_OPTIONS.find(
                (item) => item.value === process.action,
              )?.label ||
              process.action ||
              '状态变更'
            }}</strong>
            <span>{{
              process.operatorName ||
              (process.operatorUserId === undefined ||
              process.operatorUserId === null
                ? '系统处理'
                : `用户 ${process.operatorUserId}`)
            }}</span>
            <span class="text-muted-foreground">{{
              formatMarketTime(process.createTime)
            }}</span>
          </div>
          <p v-if="process.note" class="mt-1 whitespace-pre-wrap text-sm">
            {{ process.note }}
          </p>
        </TimelineItem>
      </Timeline>
      <Empty v-else description="暂无申请记录" />
    </template>
  </Drawer>
</template>
