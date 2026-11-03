<script lang="ts" setup>
import type { DataAssetApi } from '#/api/asset/dataasset';

import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page, useVbenModal } from '@vben/common-ui';
import { useTabs } from '@vben/hooks';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Card,
  Descriptions,
  DescriptionsItem,
  Dropdown,
  Menu,
  message,
  Modal,
  Tabs,
  Tag,
} from 'antdv-next';

import {
  ASSET_STATUS_OPTIONS,
  getDataAssetDetail,
  publishDataAsset,
  refreshDataAsset,
  unpublishDataAsset,
} from '#/api/asset/dataasset';
import { createLatestWins, parseResourceId } from '#/utils/resource-id';
import ApplyForm from '#/views/market/modules/apply-form.vue';

import { TABLE_KIND_META } from '../data';
import CatalogTab from './modules/catalog-tab.vue';
import ColumnsTab from './modules/columns-tab.vue';
import EtlBatchTab from './modules/etl-batch-tab.vue';
import LineageTab from './modules/lineage-tab.vue';
import OverviewTab from './modules/overview-tab.vue';
import PreviewTab from './modules/preview-tab.vue';
import QualityTab from './modules/quality-tab.vue';

defineOptions({ name: 'AssetMarketMapDetail' });

const route = useRoute();
const router = useRouter();
const tabs = useTabs();

const accessStore = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const canQuery = computed(
  () =>
    !!accessStore.accessToken &&
    !accessStore.loginExpired &&
    !!parseResourceId(user.userInfo?.userId),
);
const identity = computed(() =>
  JSON.stringify([
    accessStore.accessToken,
    accessStore.refreshToken,
    accessStore.loginExpired,
    accessStore.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
    route.params.id,
  ]),
);
const childEpoch = ref(0);
let alive = false;
const assetId = ref('');
const loading = ref(false);
const detailGuard = createLatestWins();
const detail = ref<DataAssetApi.Detail>({});
const asset = computed<DataAssetApi.DataAsset>(() => detail.value.asset ?? {});
const activeTab = ref('columns');
/** 已访问过的 Tab 保持挂载，避免切换时丢失预览条件与业务状态 */
const visited = reactive<Record<string, boolean>>({ columns: true });

const [ApplyModal, applyModalApi] = useVbenModal({
  connectedComponent: ApplyForm,
  destroyOnClose: true,
});

const tabItems = computed(() =>
  [
    { key: 'columns', label: '资产字段' },
    {
      key: 'preview',
      label: '资产预览',
      permission: 'asset:data-asset:preview',
    },
    { key: 'quality', label: '资产质量' },
    { key: 'lineage', label: '资产血缘' },
    { key: 'overview', label: '资产概览' },
    ...(activeTab.value === 'etl-batch'
      ? [
          {
            key: 'etl-batch',
            label: 'ETL 批次',
            permission: 'asset:data-asset:query',
          },
        ]
      : []),
    ...(activeTab.value === 'catalog'
      ? [
          {
            key: 'catalog',
            label: '目录审核',
            permission: 'asset:data-asset:query',
          },
        ]
      : []),
  ].filter((tab) => !tab.permission || hasAccessByCodes([tab.permission])),
);

function canAccessTab(key: string) {
  if (['catalog', 'etl-batch'].includes(key))
    return canQuery.value && hasAccessByCodes(['asset:data-asset:query']);
  return canQuery.value && tabItems.value.some((tab) => tab.key === key);
}

const statusMeta = computed(
  () =>
    ASSET_STATUS_OPTIONS.find((item) => item.value === asset.value.status) ??
    ASSET_STATUS_OPTIONS[0]!,
);

const tableKind = computed(
  () =>
    TABLE_KIND_META[
      String(
        detail.value.importedSnapshot?.fileInfo?.tableType ||
          asset.value.registrationConfig?.tableType ||
          asset.value.layer ||
          asset.value.tableType ||
          '',
      ).toUpperCase()
    ] ?? {
      label: asset.value.tableType || '数据表',
      color: 'blue',
    },
);
const timeEffectLabel = computed(() => {
  const value = String(
    detail.value.importedSnapshot?.fileInfo?.timeEffect ||
      asset.value.registrationConfig?.timeEffect ||
      '',
  );
  const labels: Record<string, string> = {
    '1': '天（T+1）',
    '2': '小时（T+h）',
    '3': '分钟（T+h）',
  };
  return labels[value] || asset.value.updateCycle || '-';
});

const hierarchy = computed(() =>
  [
    asset.value.registrationConfig?.dataDomainName || asset.value.categoryName,
    asset.value.subjectDomainName,
  ]
    .filter(Boolean)
    .join(' / '),
);
const assetTypeNames: Record<number, string> = {
  1: '数据库表',
  2: '外部API',
  3: '非结构化数据',
};
const assetTypeLabel = computed(
  () => assetTypeNames[asset.value.assetType ?? 1] ?? '-',
);
const showManagement = computed(() =>
  hasAccessByCodes([
    'asset:data-asset:query',
    'asset:data-asset:update',
    'asset:data-asset:publish',
    'asset:market:apply',
  ]),
);

async function loadDetail() {
  const id = assetId.value;
  if (!alive || !canQuery.value || !id) return;
  const ticket = detailGuard.next();
  loading.value = true;
  try {
    const data = await getDataAssetDetail(id);
    if (!ticket.isCurrent()) return;
    detail.value = data;
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}

function handleTabChange(key: any) {
  if (!alive || !canAccessTab(String(key))) return;
  activeTab.value = String(key);
  visited[String(key)] = true;
}

function handleBack() {
  tabs.closeCurrentTab();
  router.push({ path: String(route.meta.activePath || '/asset/map') });
}

function handleTogglePublish() {
  if (
    !alive ||
    !canQuery.value ||
    !assetId.value ||
    !hasAccessByCodes(['asset:data-asset:publish'])
  )
    return;
  const owner = identity.value;
  const id = assetId.value;
  const publishing = asset.value.status !== 1;
  Modal.confirm({
    title: publishing ? '发布资产' : '撤销发布',
    content: publishing
      ? `发布后资产“${asset.value.assetName}”将对全域可见，确认发布？`
      : `撤销发布后资产“${asset.value.assetName}”将不再对外提供，确认撤销？`,
    onOk: async () => {
      if (
        !alive ||
        !canQuery.value ||
        identity.value !== owner ||
        !hasAccessByCodes(['asset:data-asset:publish'])
      )
        return;
      await (publishing ? publishDataAsset(id) : unpublishDataAsset(id));
      if (!alive || identity.value !== owner) return;
      message.success(publishing ? '发布成功' : '撤销发布成功');
      loadDetail();
    },
  });
}

async function handleRefreshMetadata() {
  if (
    !alive ||
    !canQuery.value ||
    !assetId.value ||
    !hasAccessByCodes(['asset:data-asset:update'])
  )
    return;
  const owner = identity.value;
  await refreshDataAsset(assetId.value);
  if (!alive || identity.value !== owner) return;
  message.success('已按最新元数据定版刷新资产快照');
  loadDetail();
}

function handleApply() {
  if (
    !alive ||
    !canQuery.value ||
    asset.value.status !== 1 ||
    !parseResourceId(asset.value.id) ||
    !hasAccessByCodes(['asset:market:apply'])
  )
    return;
  applyModalApi
    .setData({
      assetId: asset.value.id,
      assetName: asset.value.assetName,
    })
    .open();
}

function bindRouteId() {
  const id = parseResourceId(route.params.id);
  if (!id) {
    message.warning('参数错误，资产编号不能为空');
    handleBack();
    return false;
  }
  assetId.value = id;
  return true;
}

function clearDetail() {
  detailGuard.invalidate();
  detail.value = {};
  assetId.value = '';
  loading.value = false;
  childEpoch.value++;
  activeTab.value = 'columns';
  for (const key of Object.keys(visited)) visited[key] = false;
  visited.columns = true;
  applyModalApi.setState({ isOpen: false });
}

watch(
  identity,
  () => {
    clearDetail();
    if (alive && canQuery.value && bindRouteId()) {
      loadDetail();
    }
  },
  { flush: 'sync' },
);

onMounted(() => {
  alive = true;
  if (canQuery.value && bindRouteId()) {
    loadDetail();
  }
});

onUnmounted(() => {
  alive = false;
  clearDetail();
});
</script>

<template>
  <Page
    auto-content-height
    content-class="overflow-hidden"
    class="asset-map-detail"
  >
    <ApplyModal :key="`apply-${childEpoch}`" />
    <Alert
      v-if="!canQuery"
      message="登录身份已失效，请重新登录后查看资产详情。"
      type="warning"
      show-icon
    />
    <div
      v-if="canQuery"
      :key="childEpoch"
      class="flex h-full min-h-0 flex-col gap-3 overflow-hidden"
    >
      <Card
        :bordered="false"
        :loading="loading"
        :styles="{ body: { padding: '16px 20px' } }"
        class="detail-header shrink-0"
      >
        <div class="detail-header__title-row">
          <div class="detail-header__title">
            <span
              class="detail-header__number"
              :title="String(asset.sourceRecordId || asset.id || '')"
              >{{ asset.sourceRecordId || asset.id }}</span><span>{{ asset.assetName || '数据资产详情' }}</span><Tag :color="statusMeta.color">{{ statusMeta.label }}</Tag>
          </div>
          <div class="detail-header__actions">
            <Dropdown v-if="showManagement">
              <Button size="small">管理</Button>
              <template #popupRender>
                <Menu>
                  <Menu.Item
                    v-if="hasAccessByCodes(['asset:data-asset:query'])"
                    @click="handleTabChange('catalog')"
                  >
                    目录审核
                  </Menu.Item>
                  <Menu.Item
                    v-if="hasAccessByCodes(['asset:data-asset:query'])"
                    @click="handleTabChange('etl-batch')"
                  >
                    ETL 批次
                  </Menu.Item>
                  <Menu.Item
                    v-if="
                      hasAccessByCodes(['asset:data-asset:update']) &&
                      !detail.importedSnapshot
                    "
                    @click="handleRefreshMetadata"
                  >
                    刷新元数据
                  </Menu.Item>
                  <Menu.Item
                    v-if="hasAccessByCodes(['asset:data-asset:publish'])"
                    @click="handleTogglePublish"
                  >
                    {{ asset.status === 1 ? '撤销发布' : '发布' }}
                  </Menu.Item>
                  <Menu.Item
                    v-if="
                      asset.status === 1 &&
                      hasAccessByCodes(['asset:market:apply'])
                    "
                    @click="handleApply"
                  >
                    申请使用
                  </Menu.Item>
                </Menu>
              </template>
            </Dropdown>
            <Button size="small" @click="handleBack">
              <template #icon><IconifyIcon icon="lucide:undo-2" /></template>返回
            </Button>
          </div>
        </div>
        <Descriptions :column="3" bordered size="small" :colon="false">
          <DescriptionsItem
            :label="asset.assetType === 3 ? '文件名称' : '表名称'"
          >
            {{ asset.fileName || asset.tableName || '-' }}
          </DescriptionsItem>
          <DescriptionsItem label="资产类型">
            <Tag color="blue">{{ assetTypeLabel }}</Tag>
          </DescriptionsItem>
          <DescriptionsItem label="归属信息">
            {{ hierarchy || '-' }}
          </DescriptionsItem>
          <DescriptionsItem :span="3" label="描述">
            {{ asset.description || '-' }}
          </DescriptionsItem>
          <DescriptionsItem
            :label="asset.assetType === 3 ? '文件类型' : '表类型'"
          >
            <Tag :color="tableKind.color">
              {{
                asset.assetType === 3 ? asset.fileType || '-' : tableKind.label
              }}
            </Tag>
          </DescriptionsItem>
          <DescriptionsItem label="数据时效">
            <Tag v-if="timeEffectLabel !== '-'" color="green">
              {{ timeEffectLabel }}
</Tag><span v-else>-</span>
          </DescriptionsItem>
          <DescriptionsItem label="更新时间">
            {{ formatDateTime(asset.updateTime) || '-' }}
          </DescriptionsItem>
        </Descriptions>
      </Card>

      <Card
        :bordered="false"
        :styles="{
          body: {
            display: 'flex',
            flexDirection: 'column',
            height: '100%',
            minHeight: 0,
            overflow: 'hidden',
            padding: '8px 20px 16px',
          },
        }"
        class="detail-tabs min-h-0 flex-1 overflow-hidden"
      >
        <Tabs
          :active-key="activeTab"
          :items="tabItems"
          class="detail-tabs__nav"
          @change="handleTabChange"
        />
        <div class="detail-tab-body">
          <ColumnsTab
            v-show="activeTab === 'columns'"
            :asset-id="assetId"
            :columns="detail.columns ?? []"
            :loading="loading"
            @refresh="loadDetail"
          />
          <PreviewTab
            v-if="visited.preview && canAccessTab('preview')"
            v-show="activeTab === 'preview'"
            :asset-id="assetId"
            :columns="detail.columns ?? []"
            :tech-available="asset.techAvailable"
            :imported-snapshot="detail.importedSnapshot"
          />
          <QualityTab
            v-if="visited.quality"
            v-show="activeTab === 'quality'"
            :asset-id="assetId"
            :columns="detail.columns ?? []"
            :tasks="detail.qualityTasks ?? []"
            :imported-snapshot="detail.importedSnapshot"
            :asset="asset"
            @refresh="loadDetail"
          />
          <LineageTab
            v-if="visited.lineage"
            v-show="activeTab === 'lineage'"
            :snapshot="detail.importedSnapshot"
            @refresh="loadDetail"
          />
          <EtlBatchTab
            v-if="visited['etl-batch'] && asset.id && canAccessTab('etl-batch')"
            v-show="activeTab === 'etl-batch'"
            :asset="asset"
            @refresh="loadDetail"
          />
          <CatalogTab
            v-if="visited.catalog && asset.id && canAccessTab('catalog')"
            v-show="activeTab === 'catalog'"
            :asset="asset"
            @refresh="loadDetail"
          />
          <OverviewTab
            v-if="visited.overview"
            v-show="activeTab === 'overview'"
            :asset="asset"
            :imported-snapshot="detail.importedSnapshot"
          />
        </div>
      </Card>
    </div>
  </Page>
</template>

<style scoped>
.asset-map-detail {
  background: hsl(var(--muted));
}

.detail-header {
  background: hsl(var(--background));
  border-radius: var(--radius);
}

.detail-header__title-row {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.detail-header__title {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 16px;
  font-weight: 600;
}

.detail-header__actions {
  display: flex;
  gap: 8px;
}

.detail-tabs {
  background: hsl(var(--background));
  border-radius: var(--radius);
}

@media (width <= 760px) {
  .detail-header__title-row {
    flex-wrap: wrap;
  }

  .detail-header :deep(.ant-descriptions-item-label) {
    width: 90px;
  }
}

.detail-header__number {
  display: grid;
  place-items: center;
  min-width: 20px;
  max-width: 60px;
  height: 20px;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 14px;
  color: hsl(var(--primary-foreground));
  white-space: nowrap;
  background: hsl(var(--primary));
}

.detail-header :deep(.ant-descriptions-item-label) {
  width: 150px;
}

.detail-tabs :deep(.ant-card-body) {
  height: 100%;
}

.detail-tabs__nav {
  flex-shrink: 0;
}

.detail-tabs__nav :deep(.ant-tabs-nav) {
  margin-bottom: 8px;
}

.detail-tabs__nav :deep(.ant-tabs-content-holder) {
  display: none;
}

.detail-tab-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
</style>
