<script lang="ts" setup>
import type { MarketApi } from '#/api/asset/market';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  ref,
  watch,
} from 'vue';

import { useAccess } from '@vben/access';
import { useVbenDrawer, useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Button, Spin } from 'antdv-next';

import { ASSET_TYPE_OPTIONS } from '#/api/asset/dataasset';
import { checkMarketAccess, getMarketResourceDetail } from '#/api/asset/market';
import {
  createLatestWins,
  parseResourceId,
  sameResourceId,
} from '#/utils/resource-id';
import { optionLabel, SHARE_ATTRIBUTE_OPTIONS } from '#/views/market/data';
import ApplyForm from '#/views/market/modules/apply-form.vue';
import CommentPanel from '#/views/market/modules/comment-panel.vue';
import PreviewDrawer from '#/views/market/modules/preview-drawer.vue';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import { deliveryLabels } from '../data';

defineOptions({ name: 'AssetMarketResourceDetail' });
withDefaults(defineProps<{ embedded?: boolean }>(), { embedded: false });
const emit = defineEmits<{ applied: [] }>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const canQuery = computed(
  () =>
    !!access.accessToken &&
    !access.loginExpired &&
    !!parseResourceId(user.userInfo?.userId),
);
const canApply = computed(() => hasAccessByCodes(['asset:market:apply']));
const scope = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    access.loginExpired,
    user.userInfo?.userId,
    access.accessCodes,
    user.userRoles,
  ]),
);
const detailGuard = createLatestWins();
let alive = false;
const current = ref<MarketApi.Resource>();
const detail = ref<MarketApi.ConsumerDetail>();
const detailError = ref('');
const detailLoading = ref(false);
const previewAllowed = ref(false);
const previewError = ref('');
const accessLoading = ref(false);
const childEpoch = ref(0);

function technicalStatus(value?: number) {
  if (value === 1) return '已挂接（不代表已授权）';
  return value === 0 ? '未挂接，不可预览/开通' : '待确认';
}

const detailFields = computed<DetailFieldItem[]>(() => {
  const row = current.value;
  if (!row) {
    return [];
  }
  return [
    { key: 'assetCode', label: '资产编码', value: row.assetCode },
    {
      key: 'tableName',
      label: '物理表 / URN',
      value: row.tableName || row.resourceUrn,
    },
    {
      key: 'assetType',
      label: '资产类型',
      value: optionLabel(ASSET_TYPE_OPTIONS, row.assetType),
    },
    {
      key: 'marketCategoryName',
      label: '所属集市类目',
      value: row.marketCategoryName,
    },
    {
      key: 'frequency',
      label: '更新频率',
      value: row.frequency || row.updateCycle,
    },
    {
      key: 'deliveryTypes',
      label: '交付形态',
      value: deliveryLabels(row.deliveryTypes).join(' / ') || '--',
    },
    { key: 'layer', label: '数仓分层', value: row.layer },
    {
      key: 'shareAttribute',
      label: '共享属性',
      value: optionLabel(
        SHARE_ATTRIBUTE_OPTIONS,
        row.shareAttribute ?? row.shareAttr ?? 0,
      ),
    },
    { key: 'shareCondition', label: '共享条件', value: row.shareCondition },
    {
      key: 'updateRule',
      label: '更新规则',
      value: row.updateRule || row.updateCycle,
    },
    { key: 'dutyBasis', label: '职责依据', value: row.dutyBasis },
    {
      key: 'techAvailable',
      label: '技术状态',
      value: technicalStatus(row.techAvailable),
    },
    {
      key: 'subjectDomainName',
      label: '主题域',
      value: row.subjectDomainName,
    },
    { key: 'healthScore', label: '健康分', value: row.healthScore },
    { key: 'fieldCount', label: '字段数', value: row.fieldCount },
    { key: 'contactName', label: '联系人', value: row.contactName },
    {
      key: 'description',
      label: '说明',
      value: row.marketDescription || row.businessPurpose || row.description,
      span: 2,
      type: 'textarea',
    },
  ];
});

const [ApplyModal, applyModalApi] = useVbenModal({
  connectedComponent: ApplyForm,
  destroyOnClose: true,
});
const [PreviewModal, previewModalApi] = useVbenModal({
  connectedComponent: PreviewDrawer,
  destroyOnClose: true,
});
const [DetailDrawer, detailDrawerApi] = useVbenDrawer({
  class: 'w-[960px] max-w-full',
  placement: 'right',
  showConfirmButton: false,
  cancelText: '关闭',
  onOpenChange(visible: boolean) {
    if (!visible) resetDetail();
  },
});

function resetDetail() {
  detailGuard.invalidate();
  current.value = undefined;
  detail.value = undefined;
  detailError.value = '';
  detailLoading.value = false;
  previewAllowed.value = false;
  previewError.value = '';
  accessLoading.value = false;
  applyModalApi.setState({ isOpen: false });
  previewModalApi.setState({ isOpen: false });
  childEpoch.value += 1;
}

function close() {
  resetDetail();
  detailDrawerApi.setState({ isOpen: false });
}

async function open(assetId: MarketApi.Id) {
  const id = parseResourceId(assetId);
  if (!alive || !canQuery.value || !id) return;
  resetDetail();
  const ticket = detailGuard.next();
  detailLoading.value = true;
  detailDrawerApi.setState({ title: '资产详情' }).open();
  try {
    const data = await getMarketResourceDetail(id);
    if (!ticket.isCurrent()) return;
    if (!sameResourceId(data?.asset?.id, id))
      throw new Error('RESOURCE_IDENTITY');
    // 消费详情重新读取服务端资产信息，不合并列表中的旧授权状态。
    detail.value = data;
    current.value = data.asset;
    detailDrawerApi.setState({
      title:
        current.value?.marketName || current.value?.assetName || '资产详情',
    });
  } catch {
    if (ticket.isCurrent())
      detailError.value = '详情读取失败或资产身份不一致，请关闭后重新打开。';
  } finally {
    if (ticket.isCurrent()) detailLoading.value = false;
  }
  if (!ticket.isCurrent() || current.value?.assetType !== 1) return;
  // 公开目录详情与受保护的数据预览分别核验。
  accessLoading.value = true;
  try {
    const effective = await checkMarketAccess(id);
    if (!ticket.isCurrent()) return;
    previewAllowed.value = effective === true;
    if (typeof effective !== 'boolean')
      previewError.value = '预览授权未确认，当前不能预览；目录详情仍可查看。';
  } catch {
    if (ticket.isCurrent())
      previewError.value = '预览授权未确认，当前不能预览；目录详情仍可查看。';
  } finally {
    if (ticket.isCurrent()) accessLoading.value = false;
  }
}

function applyAsset() {
  if (!alive || !canQuery.value || !canApply.value) return;
  const target = current.value;
  if (!target || !parseResourceId(target.id) || target.publicationNotice)
    return;
  applyModalApi
    .setData({
      assetId: target.id,
      assetName: target.marketName || target.assetName,
    })
    .open();
}

function openPreview() {
  if (
    !alive ||
    !canQuery.value ||
    detailLoading.value ||
    !previewAllowed.value ||
    current.value?.assetType !== 1 ||
    !parseResourceId(current.value.id)
  )
    return;
  previewModalApi
    .setData({
      assetId: current.value.id,
      title: current.value.marketName || current.value.assetName,
    })
    .open();
}

function onApplied() {
  if (alive && canQuery.value && current.value) emit('applied');
}

watch(scope, close, { flush: 'sync' });
watch([canQuery, canApply], close, { flush: 'sync' });
onMounted(() => {
  alive = true;
});
onActivated(() => {
  alive = true;
});
onDeactivated(() => {
  alive = false;
  close();
});
onBeforeUnmount(() => {
  alive = false;
  close();
});
defineExpose({ open, close });
</script>

<template>
  <ApplyModal :key="`apply-${childEpoch}`" @success="onApplied" />
  <PreviewModal :key="`preview-${childEpoch}`" />
  <component
    :is="embedded ? 'section' : DetailDrawer"
    :class="{ 'market-detail-page': embedded }"
  >
    <h1 v-if="embedded && current" class="market-detail-page__title">
      {{ current.marketName || current.assetName || '资产详情' }}
    </h1>
    <Spin v-if="detailLoading" spinning />
    <Alert
      v-if="previewError"
      :message="previewError"
      type="warning"
      show-icon
    />
    <Alert
      v-else-if="accessLoading"
      message="正在核验预览授权，目录详情可先查看。"
      type="info"
      show-icon
    />
    <Alert v-if="detailError" :message="detailError" type="error" show-icon />
    <div v-if="current" class="mx-4 flex flex-col gap-4">
      <Alert
        v-if="current.publicationNotice"
        :message="current.publicationNotice"
        type="warning"
        show-icon
      />
      <div class="flex justify-end gap-2">
        <Button
          v-if="canQuery && previewAllowed && current.assetType === 1"
          @click="openPreview"
        >
          <template #icon><IconifyIcon icon="lucide:table-2" /></template>
          授权预览（访问时复核）
        </Button>
        <Button
          v-if="canApply"
          :disabled="!!current.publicationNotice"
          type="primary"
          @click="applyAsset"
        >
          <template #icon>
<IconifyIcon icon="lucide:clipboard-list" />
</template>
          申请共享
        </Button>
      </div>
      <DetailFieldForm :fields="detailFields" label-width="130px" />
      <div v-if="detail?.columns?.length" class="text-sm">
        <div class="mb-1 font-medium">数据项</div>
        <div
          v-for="column in detail.columns"
          :key="String(column.id ?? column.columnName)"
          class="text-muted-foreground text-sm"
        >
          {{ column.businessName || column.columnName }}
          （{{ column.columnName }} / {{ column.dataType || '-' }}）
          {{ column.businessDefinition || column.sourceComment || '' }}
        </div>
      </div>
      <div v-if="hasAccessByCodes(['asset:market:query'])">
        <div class="mb-2 flex items-center gap-2 font-medium">
          <IconifyIcon icon="lucide:star" />
          评价
        </div>
        <CommentPanel v-if="current.id" :asset-id="current.id" />
      </div>
    </div>
  </component>
</template>

<style scoped>
.market-detail-page {
  min-height: 100%;
  padding: 24px;
  background: hsl(var(--background));
  border-radius: 4px;
}

.market-detail-page__title {
  margin: 0 16px 24px;
  font-size: 22px;
  font-weight: 600;
}

@media (width <= 640px) {
  .market-detail-page {
    padding: 16px 4px;
  }
}
</style>
