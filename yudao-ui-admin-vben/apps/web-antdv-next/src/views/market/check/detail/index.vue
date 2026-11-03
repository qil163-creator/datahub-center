<script lang="ts" setup>
import type { MarketCheckApi } from '#/api/asset/market/check';

import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  ref,
  watch,
} from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { Page, useVbenDrawer } from '@vben/common-ui';
import { useTabs } from '@vben/hooks';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Button, Spin } from 'antdv-next';

import { getMarketCheckDetail } from '#/api/asset/market/check';
import {
  createLatestWins,
  parseResourceId,
  sameResourceId,
} from '#/utils/resource-id';
import ApplyDetail from '#/views/market/modules/apply-detail.vue';

import ApiDocument from './modules/api-document.vue';
import FieldsTab from './modules/fields-tab.vue';
import PreviewTab from './modules/preview-tab.vue';
import ResourceSummary from './modules/resource-summary.vue';

defineOptions({ name: 'MarketCheckDetail' });
const route = useRoute();
const router = useRouter();
const tabNavigation = useTabs();
const access = useAccessStore();
const user = useUserStore();
const detail = ref<MarketCheckApi.Detail>();
const loading = ref(false);
const loadError = ref('');
const childEpoch = ref(0);
const guard = createLatestWins();
const activeTab = ref('fields');
const visited = ref<Record<string, boolean>>({ fields: true });
const id = computed(() => parseResourceId(route.params.id));
const origin = computed<MarketCheckApi.Origin | undefined>(() => {
  const value = route.query.origin || 'NATIVE';
  return value === 'NATIVE' || value === 'QDATA' ? value : undefined;
});
const canQuery = computed(
  () =>
    !!id.value &&
    !!origin.value &&
    !!access.accessToken &&
    !access.loginExpired &&
    access.accessCodes.includes('asset:market:check') &&
    !!parseResourceId(user.userInfo?.userId),
);
const owner = computed(() =>
  JSON.stringify([
    route.params.id,
    route.query.origin,
    access.accessToken,
    access.refreshToken,
    access.loginExpired,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]),
);
let active = false;
const [ApplyDetailDrawer, applyDetailApi] = useVbenDrawer({
  connectedComponent: ApplyDetail,
  destroyOnClose: true,
});
const tabs = [
  { key: 'fields', label: '资产字段' },
  { key: 'preview', label: '数据预览' },
  { key: 'api', label: 'API接口文档' },
];
function reset() {
  guard.invalidate();
  detail.value = undefined;
  loading.value = false;
  loadError.value = '';
  activeTab.value = 'fields';
  visited.value = { fields: true };
  childEpoch.value++;
  applyDetailApi.setState({ isOpen: false });
}
async function load() {
  reset();
  if (!active || !canQuery.value || !id.value || !origin.value) return;
  const requestedId = id.value;
  const requestedOrigin = origin.value;
  const ticket = guard.next();
  loading.value = true;
  try {
    const result = await getMarketCheckDetail(requestedId, requestedOrigin);
    if (!ticket.isCurrent()) return;
    if (
      !sameResourceId(result.ticket.id, requestedId) ||
      result.ticket.origin !== requestedOrigin ||
      !result.ticket.canView
    )
      throw new Error('工单身份或可见范围已变更');
    detail.value = result;
  } catch {
    if (ticket.isCurrent()) loadError.value = '工单详情读取失败，请重试。';
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}
function selectTab(key: string) {
  if (!tabs.some((tab) => tab.key === key)) return;
  activeTab.value = key;
  visited.value[key] = true;
}
function back() {
  tabNavigation.closeCurrentTab();
  void router.push(String(route.meta.activePath || '/asset/check'));
}
function viewApplication() {
  if (detail.value?.ticket.origin !== 'NATIVE') return;
  applyDetailApi.setData({ id: detail.value.ticket.id }).open();
}
function activate() {
  active = true;
  void load();
}
watch(
  owner,
  () => {
    void load();
  },
  { flush: 'sync' },
);
onMounted(activate);
onActivated(() => {
  if (!active) activate();
});
onDeactivated(() => {
  active = false;
  reset();
});
onBeforeUnmount(() => {
  active = false;
  reset();
});
</script>

<template>
  <Page auto-content-height content-class="check-detail-page">
    <ApplyDetailDrawer />
    <Alert
      v-if="!canQuery"
      message="工单编号无效或当前身份无权查看。"
      type="warning"
      show-icon
    />
    <Alert v-if="loadError" :message="loadError" type="error" show-icon>
      <template #action>
        <Button size="small" @click="load">重试</Button>
      </template>
    </Alert>
    <div v-if="loading" class="check-detail__loading"><Spin spinning /></div>
    <template v-if="detail">
      <ResourceSummary
        :resource="detail.resource"
        :status-label="detail.ticket.statusLabel"
        :status-tone="detail.ticket.statusTone"
        @back="back"
      />
      <section :key="childEpoch" class="check-detail__content">
        <nav class="check-detail__tabs" aria-label="审批资产内容">
          <button
            v-for="tab in tabs"
            :key="tab.key"
            type="button"
            :class="{ 'is-active': activeTab === tab.key }"
            @click="selectTab(tab.key)"
          >
            {{ tab.label }}
          </button>
        </nav>
        <div
          v-if="detail.ticket.origin === 'NATIVE'"
          class="check-detail__application"
        >
          <Button size="small" @click="viewApplication">
            申请信息与审批轨迹
          </Button>
        </div>
        <FieldsTab
          v-if="visited.fields"
          v-show="activeTab === 'fields'"
          :columns="detail.columns ?? []"
          :active="activeTab === 'fields'"
          :loading="loading"
          @refresh="load"
        />
        <PreviewTab
          v-if="visited.preview"
          v-show="activeTab === 'preview'"
          :preview="detail.canPreview ? detail.preview : undefined"
        />
        <ApiDocument
          v-if="visited.api"
          v-show="activeTab === 'api'"
          :document="detail.apiDocument"
          :can-invoke="detail.canInvoke"
        />
      </section>
    </template>
  </Page>
</template>

<style scoped>
:deep(.check-detail-page) {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
  min-height: 100%;
}

.check-detail__content {
  flex: 1 0 auto;
  min-width: 0;
  min-height: 700px;
  padding: 14px;
  background: hsl(var(--background));
  border-radius: var(--radius);
}

.check-detail__loading {
  display: grid;
  place-items: center;
  min-height: 180px;
  background: hsl(var(--background));
}

.check-detail__application {
  display: flex;
  justify-content: flex-end;
  padding-top: 12px;
}

.check-detail__tabs {
  display: flex;
  gap: 36px;
  height: 36px;
  border-bottom: 2px solid hsl(var(--border));
}

.check-detail__tabs button {
  padding: 0 0 12px;
  margin-bottom: -2px;
  font-size: 14px;
  color: #303743;
  cursor: pointer;
  background: none;
  border: 0;
  border-bottom: 2px solid transparent;
}

.check-detail__tabs button.is-active {
  color: hsl(var(--primary));
  border-bottom-color: hsl(var(--primary));
}

.check-detail__content :deep(.ant-table-pagination) {
  margin-top: 22px;
}

@media (width <= 640px) {
  .check-detail__tabs {
    gap: 24px;
  }

  .check-detail__content {
    padding: 12px;
  }
}
</style>
