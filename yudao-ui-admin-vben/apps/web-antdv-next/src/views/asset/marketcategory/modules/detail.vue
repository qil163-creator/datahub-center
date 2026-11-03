<script lang="ts" setup>
import type { AssetMarketCategoryApi } from '#/api/asset/marketcategory';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, onBeforeUnmount, onDeactivated, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenDrawer } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import { Alert } from 'antdv-next';

import {
  getMarketCategory,
  getMarketCategoryList,
} from '#/api/asset/marketcategory';
import {
  createLatestWins,
  parseResourceId,
  sameResourceId,
} from '#/utils/resource-id';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';
defineOptions({ name: 'AssetMarketCategoryDetail' });
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const canQuery = computed(
  () =>
    !!parseResourceId(user.userInfo?.userId) &&
    hasAccessByCodes(['asset:market-category:query']),
);
const scope = computed(() =>
  JSON.stringify([
    user.userInfo?.userId,
    user.userRoles,
    access.accessCodes,
    access.accessToken,
    access.refreshToken,
  ]),
);
const request = createLatestWins();
const detail = ref<AssetMarketCategoryApi.MarketCategory>();
const parentName = ref('');
const loadError = ref('');
const fields = computed<DetailFieldItem[]>(() =>
  detail.value
    ? [
        { key: 'id', label: '编号', value: detail.value.id },
        { key: 'name', label: '集市类目名称', value: detail.value.name },
        { key: 'parentName', label: '上级类目', value: parentName.value },
        {
          key: 'status',
          label: '状态',
          value: formatStatus(detail.value.status),
        },
        { key: 'sort', label: '排序', value: detail.value.sort },
        {
          key: 'description',
          label: '描述',
          value: detail.value.description,
          type: 'textarea',
          span: 2,
        },
        {
          key: 'creator',
          label: '创建人',
          value: detail.value.creatorName || detail.value.creator,
        },
        {
          key: 'createTime',
          label: '创建时间',
          value: formatDateTime(detail.value.createTime),
        },
      ]
    : [],
);
function formatStatus(status?: number) {
  if (status === 0) return '启用';
  return status === 1 ? '停用' : '—';
}
function reset() {
  request.invalidate();
  detail.value = undefined;
  parentName.value = '';
  loadError.value = '';
}
const [Drawer, drawerApi] = useVbenDrawer({
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(open: boolean) {
    reset();
    drawerApi.setState({ loading: false });
    if (!open) return;
    const data = drawerApi.getData() as
      | undefined
      | { id?: AssetMarketCategoryApi.Id };
    const id = parseResourceId(data?.id);
    if (!canQuery.value || !id) {
      loadError.value = '类目编号无效或缺少集市类目查询权限。';
      return;
    }
    const ticket = request.next();
    drawerApi.setState({ loading: true });
    try {
      const [value, parents] = await Promise.all([
        getMarketCategory(id),
        getMarketCategoryList(),
      ]);
      if (!ticket.isCurrent()) return;
      if (!sameResourceId(value.id, id)) throw new Error('CATEGORY_IDENTITY');
      detail.value = value;
      parentName.value =
        String(value.parentId) === '0'
          ? '顶级节点'
          : parents.find(
              (parent) => String(parent.id) === String(value.parentId),
            )?.name || `类目 ${value.parentId}`;
    } catch {
      if (ticket.isCurrent())
        loadError.value = '类目详情读取失败，请关闭后重试。';
    } finally {
      if (ticket.isCurrent()) drawerApi.setState({ loading: false });
    }
  },
});
function close() {
  reset();
  drawerApi.setState({ loading: false });
  void drawerApi.close();
}
watch(scope, close, { flush: 'sync' });
onDeactivated(close);
onBeforeUnmount(reset);
</script>
<template>
  <Drawer
    :title="`集市类目详情${detail?.name ? ` - ${detail.name}` : ''}`"
    class="w-[960px] max-w-full"
    data-testid="market-category-detail-drawer"
  >
    <Alert
      v-if="loadError"
      :message="loadError"
      type="error"
      show-icon
      class="mx-4 mb-4"
    />
    <DetailFieldForm v-if="detail && canQuery" :fields="fields" class="mx-4" />
  </Drawer>
</template>
