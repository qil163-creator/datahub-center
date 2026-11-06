<script lang="ts" setup>
import type { MarketApi } from '#/api/asset/market';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, onBeforeUnmount, onDeactivated, ref, watch } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Button, Empty, Tag, Timeline, TimelineItem } from 'antdv-next';

import { ASSET_TYPE_OPTIONS } from '#/api/asset/dataasset';
import { getMarketApply } from '#/api/asset/market';
import { createLatestWins } from '#/utils/resource-id';
import { useAuthorizedAssetsNavigation } from '#/views/asset/market/authorized-assets-navigation';
import { PROCESS_ACTION_OPTIONS } from '#/views/asset/market/data';
import {
  APPLY_STATUS_OPTIONS,
  DELIVERY_STATUS_OPTIONS,
  optionLabel,
} from '#/views/asset/market/market-labels';
import { formatMarketTime } from '#/views/asset/market/market-time';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import { applyAccessLabel, deliveryLabel } from '../grant-status';

defineOptions({ name: 'MarketApplyDetail' });
const authorizedAssets = useAuthorizedAssetsNavigation();

const detail = ref<MarketApi.Apply>({});
const access = useAccessStore();
const user = useUserStore();
const detailGuard = createLatestWins();
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

function resetDetail() {
  detailGuard.invalidate();
  detail.value = {};
  drawerApi.setState({ loading: false });
}

watch(
  owner,
  () => {
    resetDetail();
    drawerApi.setState({ isOpen: false });
  },
  { flush: 'sync' },
);
onBeforeUnmount(resetDetail);
onDeactivated(() => {
  resetDetail();
  drawerApi.setState({ isOpen: false });
});
async function viewAuthorizedAsset() {
  if (await authorizedAssets.open(detail.value)) {
    resetDetail();
    drawerApi.setState({ isOpen: false });
  }
}
function quotaLabel(row: MarketApi.Apply) {
  if (row.applyUseType === 1) return `${row.applyUseTimes} 次成功调用`;
  if (row.applyUseType === 2) return `${row.applyUseTimes} 行`;
  return '不限制';
}

/** 审批决定与交付结果分开：批准 + 结果未知绝不能显示成已开通。 */
function formatAuthStatus(row: MarketApi.Apply) {
  return applyAccessLabel(
    row.authStatus === 1,
    row.deliveryResolution,
    row.usable,
  );
}

function formatIfAuth(row: MarketApi.Apply) {
  if (row.ifAuth !== 1) {
    return '未开通';
  }
  return row.effective ? '有效' : '已失效';
}

const fields = computed<DetailFieldItem[]>(() => {
  const row = detail.value;
  return [
    { key: 'applyNo', label: '申请单号', value: row.applyNo },
    {
      key: 'status',
      label: '状态',
      value: optionLabel(APPLY_STATUS_OPTIONS, row.status),
    },
    { key: 'title', label: '标题', value: row.title, span: 2 },
    { key: 'assetName', label: '资产', value: row.assetName },
    {
      key: 'assetType',
      label: '类型',
      value: optionLabel(ASSET_TYPE_OPTIONS, row.assetType),
    },
    { key: 'applyUserName', label: '申请人', value: row.applyUserName },
    { key: 'applyDeptName', label: '部门', value: row.applyDeptName },
    { key: 'quota', label: '累计消费额度', value: quotaLabel(row) },
    {
      key: 'dutyMatter',
      label: '履职事项',
      value: row.dutyMatter,
      span: 2,
    },
    {
      key: 'legalBasis',
      label: '使用依据',
      value: row.legalBasis,
      span: 2,
    },
    {
      key: 'purpose',
      label: '用途',
      value: row.purpose,
      span: 2,
      type: 'textarea',
    },
    {
      key: 'fieldScope',
      label: '字段范围',
      value: row.fieldScope,
      span: 2,
    },
    {
      key: 'approvalResult',
      label: '审批结果',
      value: optionLabel(
        APPLY_STATUS_OPTIONS,
        row.approvalResult ?? row.status,
      ),
    },
    {
      key: 'techProvisionStatus',
      label: '投递状态',
      value: optionLabel(
        DELIVERY_STATUS_OPTIONS,
        row.techProvisionStatus ?? row.deliveryStatus,
      ),
    },
    {
      // 远端是否确认生效，由交付操作账本给出；超时/失联保持"结果待确认"。
      key: 'deliveryResolution',
      label: '交付结果',
      value: deliveryLabel(row.deliveryResolution),
    },
    {
      key: 'authStatus',
      label: '有效授权',
      value: formatAuthStatus(row),
    },
    {
      key: 'renewalOfApplyId',
      label: '续期自申请',
      value: row.renewalOfApplyId ? String(row.renewalOfApplyId) : '-',
    },
    {
      key: 'replyDeadline',
      label: '答复时限',
      value: formatMarketTime(row.replyDeadline),
    },
    {
      key: 'deliveryDeadline',
      label: '交付时限',
      value: formatMarketTime(row.deliveryDeadline),
    },
    {
      key: 'firstUsedTime',
      label: '首次使用',
      value: formatMarketTime(row.firstUsedTime),
    },
    { key: 'sourceGrantId', label: '来源授权', value: row.sourceGrantId },
    {
      key: 'supplementNote',
      label: '补正要求',
      value: row.supplementNote,
      span: 2,
      type: 'textarea',
    },
    {
      key: 'applyNote',
      label: '说明',
      value: row.applyNote,
      span: 2,
      type: 'textarea',
    },
    {
      key: 'validPeriod',
      label: '有效期',
      value: `${formatMarketTime(row.applyStartTime)} ~ ${formatMarketTime(row.applyEndTime)}`,
      span: 2,
    },
    {
      key: 'ifAuth',
      label: '授权',
      value: formatIfAuth(row),
    },
    {
      key: 'defaultCheckerName',
      label: '默认审批人',
      value: row.defaultCheckerName,
    },
    { key: 'tableName', label: '库表', value: row.tableName },
    {
      key: 'checkNote',
      label: '审批说明',
      value: row.checkNote,
      span: 2,
      type: 'textarea',
    },
  ];
});

const [Drawer, drawerApi] = useVbenDrawer({
  title: '申请详情',
  class: 'w-[960px] max-w-full',
  placement: 'right',
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(isOpen) {
    resetDetail();
    if (!isOpen) return;
    const data = drawerApi.getData() as { id?: MarketApi.Id };
    if (!data?.id) return;
    const ticket = detailGuard.next();
    const identity = owner.value;
    const isCurrent = () => ticket.isCurrent() && owner.value === identity;
    drawerApi.setState({ loading: true });
    try {
      const result = await getMarketApply(data.id);
      if (isCurrent()) detail.value = result ?? {};
    } finally {
      if (isCurrent()) drawerApi.setState({ loading: false });
    }
  },
});
</script>

<template>
  <Drawer>
    <DetailFieldForm :fields="fields" class="mx-4" label-width="130px" />
    <div v-if="authorizedAssets.canOpen(detail)" class="mx-4 mt-3">
      <Button type="primary" @click="viewAuthorizedAsset">
        查看已授权资产
      </Button>
      <div class="text-muted-foreground mt-1 text-xs">
        在我的资产查看当前授权状态、数据预览或 API 文档。
      </div>
    </div>
    <div v-if="detail.paused === 1" class="mx-4 mt-2 text-xs text-orange-600">
      授权已暂停，运行时不可用。
    </div>
    <div class="mx-4 mt-4 text-base font-medium">审批轨迹</div>
    <Timeline v-if="detail.processes?.length" class="mx-4 mt-3">
      <TimelineItem v-for="item in detail.processes" :key="item.id">
        <div class="flex items-center gap-2">
          <Tag>{{ optionLabel(PROCESS_ACTION_OPTIONS, item.action) }}</Tag>
          <span>{{ item.operatorName || '-' }}</span>
          <span class="text-muted-foreground">{{
            formatMarketTime(item.createTime)
          }}</span>
        </div>
        <div v-if="item.note" class="text-muted-foreground mt-1 text-xs">
          {{ item.note }}
        </div>
      </TimelineItem>
    </Timeline>
    <Empty v-else class="mx-4" description="暂无轨迹" />
  </Drawer>
</template>
