<script lang="ts" setup>
import type { DataAssetApi } from '#/api/asset/dataasset';
import type { MarketApi } from '#/api/asset/market';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Button, InputNumber, Table, Tooltip } from 'antdv-next';

import { previewMarketAsset } from '#/api/asset/market';
import { createLatestWins } from '#/utils/resource-id';

defineOptions({ name: 'MarketPreviewDrawer' });
const props = defineProps<{
  previewLoader?: typeof previewMarketAsset;
}>();

const emit = defineEmits<{ failed: [] }>();

const assetId = ref<MarketApi.Id>();
const title = ref('授权预览');
const loading = ref(false);
const loaded = ref(false);
const previewError = ref('');
const limit = ref(20);
const preview = ref<DataAssetApi.Preview>({});
const previewGuard = createLatestWins();
const consumptionKey = ref('');
const access = useAccessStore();
const user = useUserStore();

const tableColumns = computed(() =>
  (preview.value.columns ?? []).map((column) => ({
    key: column.name,
    dataIndex: column.name,
    title: column.label || column.name,
    ellipsis: true,
    width: 180,
  })),
);

const [Modal, modalApi] = useVbenModal({
  showConfirmButton: false,
  cancelText: '关闭',
  onOpenChange(isOpen: boolean) {
    reset();
    if (!isOpen) {
      return;
    }
    const data = modalApi.getData() as {
      assetId?: MarketApi.Id;
      requestId?: MarketApi.Id;
      title?: string;
    };
    assetId.value = data.requestId ?? data.assetId;
    title.value = data.title || '授权预览';
    void load();
  },
});

function reset() {
  previewGuard.invalidate();
  assetId.value = undefined;
  title.value = '授权预览';
  loaded.value = false;
  loading.value = false;
  preview.value = {};
  previewError.value = '';
  consumptionKey.value = '';
}
watch(
  () =>
    JSON.stringify([
      access.accessToken,
      access.refreshToken,
      access.loginExpired,
      access.accessCodes,
      user.userInfo?.userId,
      user.userRoles,
    ]),
  () => {
    reset();
    modalApi.setState({ isOpen: false });
  },
  { flush: 'sync' },
);
onBeforeUnmount(reset);

async function load() {
  if (assetId.value === undefined || assetId.value === null) {
    return;
  }
  const ticket = previewGuard.next();
  consumptionKey.value = crypto.randomUUID();
  loading.value = true;
  previewError.value = '';
  try {
    const data =
      (await (props.previewLoader ?? previewMarketAsset)(
        assetId.value,
        limit.value,
        consumptionKey.value,
      )) ?? {};
    if (!ticket.isCurrent()) return;
    preview.value = data;
    loaded.value = true;
  } catch (error: unknown) {
    if (!ticket.isCurrent()) return;
    previewError.value =
      error instanceof Error ? error.message : '预览失败，授权可能已收回或过期';
    preview.value = {};
    emit('failed');
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}

function formatCell(value: unknown) {
  if (value === null || value === undefined) {
    return '';
  }
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

function cellText(record: object, key: string) {
  return formatCell((record as Record<string, unknown>)[key]);
}

function rowKey(_record: object, index?: number) {
  return String(index ?? 0);
}
</script>

<template>
  <Modal :title="title" class="w-[960px]">
    <div class="mx-4 mb-3 flex items-center gap-2">
      <Button :loading="loading" type="primary" @click="load">
        <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
        刷新
      </Button>
      <span class="text-sm">行数</span>
      <InputNumber v-model:value="limit" :max="100" :min="1" class="w-24" />
      <span class="text-muted-foreground text-xs">
        限额申请按成功次数或返回行计量；每次刷新都是新查询
      </span>
    </div>
    <div v-if="consumptionKey" class="text-muted-foreground mx-4 mb-2 text-xs">
      消费请求键：{{ consumptionKey }}。网络失败时可使用此请求键联系管理员核对。
    </div>
    <template v-if="previewError"></template>
    <Alert
      v-else-if="preview.truncated"
      class="mx-4 mb-2"
      message="结果已按行数上限截断"
      show-icon
      type="warning"
    />
    <Table
      class="mx-4"
      :columns="tableColumns"
      :data-source="preview.rows ?? []"
      :loading="loading"
      :pagination="false"
      :row-key="rowKey"
      :scroll="{ x: Math.max(tableColumns.length * 180, 640), y: 480 }"
      bordered
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <Tooltip :title="cellText(record, String(column.key))">
          <span class="font-mono text-xs">
            {{ cellText(record, String(column.key)) }}
          </span>
        </Tooltip>
      </template>
      <template #emptyText>
        <div class="text-muted-foreground py-10">
          {{ loaded ? '暂无记录' : '加载中' }}
        </div>
      </template>
    </Table>
  </Modal>
</template>
