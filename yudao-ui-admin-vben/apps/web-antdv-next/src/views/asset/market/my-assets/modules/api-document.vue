<script lang="ts" setup>
import type { MyAssetsApi } from '#/api/asset/my-assets';

import { computed, h, onBeforeUnmount, ref, watch } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';
import {} from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Empty, Spin, Table, Tag } from 'antdv-next';

import { getMyAssetApiDocument } from '#/api/asset/my-assets';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import {
  createLatestWins,
  parseResourceId,
  sameResourceId,
} from '#/utils/resource-id';

defineOptions({ name: 'MyAssetApiDocument' });
const emit = defineEmits<{ unavailable: [] }>();
const access = useAccessStore();
const user = useUserStore();
const document = ref<MyAssetsApi.ApiDocument>();
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
const requestColumns = [
  { title: '参数名称', dataIndex: 'name', key: 'name', width: 180 },
  { title: '类型', dataIndex: 'type', key: 'type', width: 120 },
  { title: '必填', dataIndex: 'required', key: 'required', width: 80 },
  {
    title: '说明',
    dataIndex: 'description',
    key: 'description',
    customRender: ({ text }: { text?: unknown }) =>
      h(TextEllipsisCell, {
        content: text === undefined || text === null ? undefined : String(text),
        line: 2,
        center: true,
      }),
  },
];
const responseColumns = requestColumns.filter(
  (column) => column.key !== 'required',
);
const [Drawer, drawerApi] = useVbenDrawer({
  title: 'API 文档',
  class: 'w-[960px] max-w-full',
  showConfirmButton: false,
  cancelText: '关闭',
  onOpenChange(open) {
    if (!open) reset();
  },
});
function reset() {
  guard.invalidate();
  document.value = undefined;
  currentId.value = undefined;
  loading.value = false;
  loadError.value = '';
}
function fieldText(
  row: { description?: string; name: string; type?: null | string },
  key: unknown,
) {
  if (key === 'name' || key === 'type' || key === 'description')
    return row[key] || '--';
  return '--';
}
function close() {
  reset();
  drawerApi.setState({ isOpen: false });
}
async function load() {
  const id = currentId.value;
  if (!alive || !id) return;
  const ticket = guard.next();
  document.value = undefined;
  loading.value = true;
  loadError.value = '';
  try {
    const value = await getMyAssetApiDocument(id);
    if (!ticket.isCurrent()) return;
    if (!sameResourceId(value.applyId, id))
      throw new Error('IDENTITY_MISMATCH');
    document.value = value;
  } catch (error: unknown) {
    if (ticket.isCurrent())
      loadError.value =
        error instanceof Error && error.message !== 'IDENTITY_MISMATCH'
          ? error.message
          : 'API 文档读取失败，授权或接口版本可能已变更，请重试。';
    if (ticket.isCurrent()) emit('unavailable');
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}
function open(id: MyAssetsApi.Id) {
  const key = parseResourceId(id);
  if (!alive || !key) return;
  reset();
  currentId.value = key;
  drawerApi.open();
  void load();
}
watch(owner, close, { flush: 'sync' });
onBeforeUnmount(() => {
  alive = false;
  reset();
});
defineExpose({ open, close });
</script>

<template>
  <Drawer>
    <Spin v-if="loading" spinning />

    <template v-if="document">
      <h2 class="mb-4 text-base font-semibold">
        {{ document.name || 'API 接口' }}
      </h2>
      <Alert
        type="info"
        message="本文档仅包含当前申请批准的字段范围。实际调用仍需有效的应用授权。"
        show-icon
        class="mb-4"
      />
      <div class="mb-6 flex flex-wrap items-center gap-2 rounded border p-3">
        <Tag color="blue">{{ document.method || '--' }}</Tag><code class="break-all">{{ document.path || '--' }}</code><span class="text-muted-foreground ml-auto text-xs">版本 {{ document.version || '--' }}</span>
      </div>
      <h3 class="mb-3 font-medium">请求参数</h3>
      <Table
        :columns="requestColumns"
        :data-source="document.requestParams ?? []"
        row-key="name"
        :pagination="false"
        :scroll="{ x: 600 }"
        size="small"
        bordered
      >
        <template #bodyCell="{ column, record }">
          <span v-if="column.key === 'required'">{{
            record.required ? '是' : '否'
          }}</span><span v-else>{{ fieldText(record, column.dataIndex) }}</span>
        </template>
        <template #emptyText><Empty description="无请求参数" /></template>
      </Table>
      <h3 class="mb-3 mt-6 font-medium">响应字段</h3>
      <Table
        :columns="responseColumns"
        :data-source="document.responseFields ?? []"
        row-key="name"
        :pagination="false"
        :scroll="{ x: 600 }"
        size="small"
        bordered
      >
        <template #bodyCell="{ column, record }">
          <span>{{ fieldText(record, column.dataIndex) }}</span>
        </template>
        <template #emptyText>
          <Empty description="当前范围内无响应字段" />
        </template>
      </Table>
    </template>
  </Drawer>
</template>

<style scoped src="../../../table-font.css"></style>
