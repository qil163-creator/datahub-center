<script lang="ts" setup>
import type { MarketApi } from '#/api/asset/market';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import {
  Button,
  Empty,
  Input,
  message,
  Select,
  Tag,
  TextArea,
} from 'antdv-next';

import {
  acceptMarketDemand,
  confirmMarketDemandMatch,
  getMarketDemand,
  getMarketResourcePage,
  matchMarketDemand,
  replyMarketDemand,
  suggestMarketDemandAssets,
} from '#/api/asset/market';
import { useObjectRequest } from '#/hooks/use-object-request';
import { resourceIdKey } from '#/utils/resource-id';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import {
  DELIVERY_OPTIONS,
  DEMAND_STATUS_OPTIONS,
  DEMAND_TYPE_OPTIONS,
  formatMarketTime,
  MATCH_STATUS_OPTIONS,
  optionColor,
  optionLabel,
  REPLY_TYPE_OPTIONS,
  URGENCY_OPTIONS,
} from '../demand/data';

defineOptions({ name: 'MarketDemandDetail' });

const emit = defineEmits<{ (e: 'success'): void }>();

const detail = ref<MarketApi.Demand>({});
const suggestions = ref<MarketApi.Resource[]>([]);
const replyType = ref(1);
const replyContent = ref('');
const matchAssetId = ref<MarketApi.Id>();
const matchNote = ref('');
const assets = ref<MarketApi.Resource[]>([]);
const selectedAsset = ref<{ label: string; value: string }>();
const assetKeyword = ref('');
const assetPage = ref(0);
const assetTotal = ref(0);
const assetsLoading = ref(false);
const assetError = ref('');
const mode = ref<'hall' | 'match' | 'mine'>('hall');
const identity = ref('closed');
const access = useAccessStore();
const user = useUserStore();
const selectorOwner = computed(() =>
  JSON.stringify([
    identity.value,
    access.loginExpired,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]),
);
const assetRequest = useObjectRequest(
  () => selectorOwner.value,
  () => {
    assetsLoading.value = false;
    assetError.value = '';
  },
);
let previousDetailOwner = selectorOwner.value;
const detailRequest = useObjectRequest(
  () => selectorOwner.value,
  () => {
    if (previousDetailOwner === selectorOwner.value) {
      drawerApi.setState({ loading: false });
    } else {
      previousDetailOwner = selectorOwner.value;
      resetDetail();
    }
  },
);
const actionRequest = useObjectRequest(() => identity.value);
let currentMutationScope = () => false;
const mutationRequest = useObjectRequest(() => '', resetMutationScope);

/** 当前会话内的并发写操作共享有效周期，关闭详情不取消父列表刷新。 */
function resetMutationScope() {
  currentMutationScope = mutationRequest.begin();
}
resetMutationScope();

const assetOptions = computed(() => {
  const options = new Map(
    assets.value
      .filter((item) => item.id !== null && item.id !== undefined)
      .map((item) => [
        resourceIdKey(item.id),
        {
          label: item.assetName || `资产#${item.id}`,
          value: resourceIdKey(item.id),
        },
      ]),
  );
  const id = resourceIdKey(matchAssetId.value);
  if (id && !options.has(id)) {
    const previous = detail.value.matches?.find(
      (match) => resourceIdKey(match.assetId) === id,
    );
    options.set(
      id,
      selectedAsset.value?.value === id
        ? selectedAsset.value
        : { label: previous?.assetName || `资产#${id}`, value: id },
    );
  }
  return [...options.values()];
});

function resetAssets() {
  assetRequest.begin();
  assets.value = [];
  selectedAsset.value = undefined;
  matchAssetId.value = undefined;
  assetKeyword.value = '';
  assetPage.value = 0;
  assetTotal.value = 0;
  assetsLoading.value = false;
  assetError.value = '';
}

async function loadAssets(append = false) {
  if (identity.value === 'closed' || access.loginExpired) return;
  const current = assetRequest.begin();
  const pageNo = append ? assetPage.value + 1 : 1;
  const keyword = assetKeyword.value.trim();
  assetsLoading.value = true;
  assetError.value = '';
  try {
    const page = await getMarketResourcePage({
      pageNo,
      pageSize: 20,
      keyword: keyword || undefined,
    });
    if (!current()) return;
    const rows = page.list ?? [];
    const values = new Map(
      (append ? assets.value : []).map((asset) => [
        resourceIdKey(asset.id),
        asset,
      ]),
    );
    for (const asset of rows)
      if (asset.id !== null && asset.id !== undefined)
        values.set(resourceIdKey(asset.id), asset);
    assets.value = [...values.values()];
    assetPage.value = pageNo;
    assetTotal.value = page.total ?? (pageNo - 1) * 20 + rows.length;
  } catch {
    if (current()) {
      assetError.value = '资产选项读取失败，请重新搜索或重新打开下拉框。';
      message.warning(assetError.value);
    }
  } finally {
    if (current()) assetsLoading.value = false;
  }
}

async function searchAssets(keyword: string) {
  assetKeyword.value = keyword;
  assetPage.value = 0;
  assetTotal.value = 0;
  assets.value = [];
  await loadAssets();
}

function loadMoreAssets(event: { target?: EventTarget | null }) {
  const element = event.target as HTMLElement | null;
  if (
    !element ||
    assetsLoading.value ||
    assets.value.length >= assetTotal.value ||
    element.scrollHeight - element.scrollTop - element.clientHeight > 20
  )
    return;
  void loadAssets(true);
}

function changeAsset(value: MarketApi.Id | undefined) {
  const id = resourceIdKey(value);
  selectedAsset.value = assetOptions.value.find(
    (option) => option.value === id,
  );
}

function openAssets(open: boolean) {
  if (
    open &&
    !assetsLoading.value &&
    (assetPage.value === 0 || assetError.value)
  ) {
    void loadAssets(assetPage.value > 0);
  }
}

function resetDetail() {
  detail.value = {};
  suggestions.value = [];
  replyType.value = 1;
  replyContent.value = '';
  matchAssetId.value = undefined;
  matchNote.value = '';
  resetAssets();
  mode.value = 'hall';
  drawerApi.setState({ loading: false });
}

const fields = computed<DetailFieldItem[]>(() => {
  const row = detail.value;
  return [
    { key: 'demandNo', label: '需求单号', value: row.demandNo },
    {
      key: 'status',
      label: '状态',
      value: optionLabel(DEMAND_STATUS_OPTIONS, row.status),
    },
    { key: 'title', label: '标题', value: row.title, span: 2 },
    {
      key: 'demandType',
      label: '类型',
      value: optionLabel(DEMAND_TYPE_OPTIONS, row.demandType),
    },
    {
      key: 'urgency',
      label: '紧急',
      value: optionLabel(URGENCY_OPTIONS, row.urgency),
    },
    { key: 'applyUserName', label: '提出人', value: row.applyUserName },
    { key: 'applyDeptName', label: '部门', value: row.applyDeptName },
    {
      key: 'expectedDelivery',
      label: '期望交付',
      value: optionLabel(DELIVERY_OPTIONS, row.expectedDelivery),
    },
    {
      key: 'expectFinishTime',
      label: '期望完成',
      value: formatMarketTime(row.expectFinishTime),
    },
    {
      key: 'description',
      label: '说明',
      value: row.description,
      span: 2,
      type: 'textarea',
    },
    {
      key: 'expectedFields',
      label: '期望字段',
      value: row.expectedFields,
      span: 2,
    },
    {
      key: 'acceptanceStatus',
      label: '验收',
      value:
        row.acceptanceStatus === 1 ? '已验收' : '未验收（已匹配不等于已解决）',
      span: 2,
    },
  ];
});

const [Drawer, drawerApi] = useVbenDrawer({
  title: '需求详情',
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(isOpen) {
    const data = drawerApi.getData() as {
      id?: MarketApi.Id;
      mode?: 'hall' | 'match' | 'mine';
    };
    identity.value = isOpen && data?.id ? String(data.id) : 'closed';
    detailRequest.begin();
    actionRequest.begin();
    resetDetail();
    if (!isOpen) return;
    mode.value = data?.mode ?? 'hall';
    if (data?.id) {
      await load(data.id);
    }
  },
});

async function load(id: MarketApi.Id) {
  const current = detailRequest.begin();
  drawerApi.setState({ loading: true });
  try {
    const row = await getMarketDemand(id);
    if (!current()) return;
    detail.value = row ?? {};
    const recommended = await suggestMarketDemandAssets(id).catch(() => []);
    if (!current()) return;
    suggestions.value = recommended ?? [];
    await loadAssets();
  } finally {
    if (current()) drawerApi.setState({ loading: false });
  }
}

async function submitReply() {
  if (!detail.value.id || !replyContent.value.trim()) {
    message.warning('请填写响应内容');
    return;
  }
  const demandId = detail.value.id;
  const current = actionRequest.begin();
  const canRefresh = currentMutationScope;
  await replyMarketDemand({
    demandId,
    replyType: replyType.value,
    content: replyContent.value,
  });
  if (canRefresh()) emit('success');
  if (!current()) return;
  replyContent.value = '';
  message.success('已响应');
  await load(demandId);
}

async function submitMatch(assetId?: MarketApi.Id) {
  const id = assetId ?? matchAssetId.value;
  if (!detail.value.id || !id) {
    message.warning('请选择资产');
    return;
  }
  const demandId = detail.value.id;
  const current = actionRequest.begin();
  const canRefresh = currentMutationScope;
  await matchMarketDemand({
    demandId,
    assetId: id,
    matchNote: matchNote.value,
  });
  if (canRefresh()) emit('success');
  if (!current()) return;
  message.success('已提交匹配，等待需求方确认');
  matchAssetId.value = undefined;
  matchNote.value = '';
  await load(demandId);
}

async function confirm(match: MarketApi.DemandMatch, status: number) {
  const demandId = detail.value.id;
  if (!demandId) return;
  const current = actionRequest.begin();
  const canRefresh = currentMutationScope;
  await confirmMarketDemandMatch({
    id: match.id!,
    status,
    createApply: status === 1,
  });
  if (canRefresh()) emit('success');
  if (!current()) return;
  message.success(status === 1 ? '已接受并生成共享申请' : '已拒绝该匹配');
  await load(demandId);
}

async function accept() {
  const demandId = detail.value.id;
  if (!demandId) return;
  const current = actionRequest.begin();
  const canRefresh = currentMutationScope;
  await acceptMarketDemand(demandId);
  if (canRefresh()) emit('success');
  if (!current()) return;
  message.success('已验收。已匹配不等于已解决，本操作才计入已解决。');
  await load(demandId);
}
</script>

<template>
  <Drawer class="w-[960px] max-w-full">
    <DetailFieldForm :fields="fields" class="mx-4" label-width="130px" />
    <div
      v-if="
        mode === 'mine' &&
        detail.acceptanceStatus !== 1 &&
        (detail.status === 3 || detail.status === 7)
      "
      class="mx-4 mt-3"
    >
      <Button type="primary" @click="accept">
        <template #icon><IconifyIcon icon="lucide:check" /></template>
        确认验收
      </Button>
    </div>

    <div class="mx-4 mt-4 text-base font-medium">推荐资产</div>
    <div v-if="suggestions.length" class="mx-4 mt-2 flex flex-wrap gap-2">
      <Tag
        v-for="item in suggestions"
        :key="item.id"
        class="cursor-pointer"
        @click="mode === 'match' && submitMatch(item.id)"
      >
        {{ item.assetName }}
      </Tag>
    </div>
    <Empty v-else class="mx-4 mt-2" description="暂无推荐资产" />

    <div
      v-if="mode === 'match'"
      class="governance-modal-form governance-form-item-horizontal mx-4 mt-4"
    >
      <div class="text-base font-medium">匹配已有资产</div>
      <div class="form-item">
        <label class="form-item__label">已发布资产</label>
        <Select
          v-model:value="matchAssetId"
          allow-clear
          class="w-full"
          show-search
          :filter-option="false"
          :loading="assetsLoading"
          :not-found-content="assetError || undefined"
          :options="assetOptions"
          placeholder="选择已发布资产"
          @change="changeAsset"
          @dropdown-visible-change="openAssets"
          @popup-scroll="loadMoreAssets"
          @search="searchAssets"
        />
      </div>
      <div class="form-item">
        <label class="form-item__label">匹配说明</label>
        <Input v-model:value="matchNote" placeholder="匹配说明" />
      </div>
      <Button type="primary" @click="submitMatch()">
        <template #icon><IconifyIcon icon="lucide:link" /></template>
        提交匹配
      </Button>
      <div class="text-base font-medium">响应需求</div>
      <div class="form-item">
        <label class="form-item__label">响应类型</label>
        <Select
          v-model:value="replyType"
          class="w-full"
          :options="REPLY_TYPE_OPTIONS"
        />
      </div>
      <div class="form-item">
        <label class="form-item__label">响应内容</label>
        <TextArea v-model:value="replyContent" :rows="3" />
      </div>
      <Button @click="submitReply">
        <template #icon><IconifyIcon icon="lucide:send" /></template>
        发送响应
      </Button>
    </div>

    <div class="mx-4 mt-4 text-base font-medium">匹配记录</div>
    <div v-if="detail.matches?.length" class="mx-4 mt-2 flex flex-col gap-2">
      <div
        v-for="item in detail.matches"
        :key="item.id"
        class="border-border flex items-center justify-between rounded border p-2"
      >
        <div>
          <div>{{ item.assetName }}</div>
          <div class="text-muted-foreground text-xs">
            {{ item.matchNote || '无说明' }} · {{ item.matcherName }} ·
            {{ formatMarketTime(item.createTime) }}
          </div>
        </div>
        <div class="flex items-center gap-2">
          <Tag :color="optionColor(MATCH_STATUS_OPTIONS, item.status)">
            {{ optionLabel(MATCH_STATUS_OPTIONS, item.status) }}
          </Tag>
          <Button
            v-if="mode === 'mine' && item.status === 0"
            size="small"
            type="primary"
            @click="confirm(item, 1)"
          >
            <template #icon><IconifyIcon icon="lucide:check" /></template>
            接受并申请
          </Button>
          <Button
            v-if="mode === 'mine' && item.status === 0"
            danger
            size="small"
            @click="confirm(item, 2)"
          >
            <template #icon><IconifyIcon icon="lucide:x" /></template>
            拒绝
          </Button>
        </div>
      </div>
    </div>
    <Empty v-else class="mx-4" description="还没有匹配" />

    <div class="mx-4 mt-4 text-base font-medium">响应记录</div>
    <div v-if="detail.replies?.length" class="mx-4 mt-2 flex flex-col gap-2">
      <div v-for="item in detail.replies" :key="item.id" class="text-sm">
        <Tag>{{ optionLabel(REPLY_TYPE_OPTIONS, item.replyType) }}</Tag>
        {{ item.replyUserName }}：{{ item.content }}
        <span class="text-muted-foreground ml-2 text-xs">{{
          formatMarketTime(item.createTime)
        }}</span>
      </div>
    </div>
  </Drawer>
</template>

<style lang="scss" scoped>
@import '../../governance/common/custom-modal-form.css';
</style>
