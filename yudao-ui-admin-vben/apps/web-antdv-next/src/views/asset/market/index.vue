<script lang="ts" setup>
import type { MarketApi } from '#/api/asset/market';
import type { AssetMarketCategoryApi } from '#/api/asset/marketcategory';

import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  reactive,
  ref,
  watch,
} from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page, useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { handleTree } from '@vben/utils';

import {
  Alert,
  Button,
  Checkbox,
  CheckboxGroup,
  Empty,
  Input,
  RadioGroup,
  Select,
  Spin,
  Tag,
  Tree,
} from 'antdv-next';

import {
  getMarketMartPage,
  getMarketMartStatistics,
  updateMarketMartFavorite,
} from '#/api/asset/market';
import { getMarketCategorySimpleList } from '#/api/asset/marketcategory';
import ListPagination from '#/components/list-pagination/index.vue';
import TreeToolbar from '#/components/tree-toolbar/index.vue';
import {
  createLatestWins,
  parseResourceId,
  resourceIdKey,
  sameResourceId,
} from '#/utils/resource-id';
import { formatMarketTime } from '#/views/asset/market/market-time';

import { DELIVERY_TYPE_OPTIONS, deliveryLabels, marketCount } from './data';
import ApplyForm from './modules/mart-apply-form.vue';

defineOptions({ name: 'AssetMarketMart' });
const router = useRouter();
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
const canFavorite = computed(() => hasAccessByCodes(['asset:market:favorite']));
const identity = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    access.loginExpired,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]),
);
let active = false;
let pageEpoch = 0;
const listGuard = createLatestWins();
const statisticsGuard = createLatestWins();
const categoryGuard = createLatestWins();
const rows = ref<MarketApi.Resource[]>([]);
const statistics = ref<MarketApi.MartStatistics>();
const categories = ref<AssetMarketCategoryApi.MarketCategory[]>([]);
const loading = ref(false);
const listError = ref('');
const statisticsError = ref('');
const categoryError = ref('');
const categoryLoading = ref(false);
const childEpoch = ref(0);
const favoriteBusy = reactive(new Map<string, symbol>());
const keyword = ref('');
const typeDropdown = ref<HTMLDetailsElement>();
function closeTypeDropdown(event: PointerEvent) {
  if (
    event.target instanceof Node &&
    !typeDropdown.value?.contains(event.target)
  )
    typeDropdown.value?.removeAttribute('open');
}
const categoryKeyword = ref('');
const query = reactive<MarketApi.MartPageReq>({
  pageNo: 1,
  pageSize: 10,
  sort: 'POPULAR',
  assetTypes: [],
  authorizedOnly: false,
  favoriteOnly: false,
});
const total = ref(0);
const selectedTopic = ref('RECOMMENDED');
const recommendations = [
  { key: 'RECOMMENDED', name: '重点推荐', count: 12 },
  { key: 'FREQUENT', name: '高频接入', count: 16 },
  { key: 'NEW', name: '新上线资产', count: 8 },
];
function selectTopic(key: string) {
  selectedTopic.value = key;
  search();
}
function primaryType(asset: MarketApi.Resource) {
  return asset.primaryDeliveryType ?? asset.assetType;
}
function assetNameLabel(asset: MarketApi.Resource) {
  if (primaryType(asset) === 3) return '文件名称：';
  if (primaryType(asset) === 2) return '服务标识：';
  return '表名称：';
}
function assetObjectName(asset: MarketApi.Resource) {
  if (primaryType(asset) === 3) return asset.fileName || asset.assetName || '-';
  if (primaryType(asset) === 2)
    return asset.serviceIdentifier || asset.assetCode || '-';
  return asset.tableName || '-';
}
function assetDeliveryLabels(asset: MarketApi.Resource) {
  return (asset.deliveryTypes ?? []).map((value) => ({
    value,
    label: deliveryLabels([value])[0],
  }));
}
const selectedCategoryKeys = computed(() =>
  query.marketCategoryId === undefined ? [] : [String(query.marketCategoryId)],
);
interface CategoryNode {
  id: string;
  parentId: string;
  name: string;
  title: string;
  key: string;
  children?: CategoryNode[];
}
const categoryTree = computed(() => {
  const values = categories.value.map((item) => ({
    ...item,
    id: String(item.id),
    parentId: String(item.parentId ?? 0),
    title: item.name,
    key: String(item.id),
  }));
  const tree = handleTree(values) as CategoryNode[];
  const text = categoryKeyword.value.trim().toLocaleLowerCase();
  const filter = (nodes: CategoryNode[]): CategoryNode[] =>
    nodes
      .map((node) => ({ ...node, children: filter(node.children ?? []) }))
      .filter(
        (node) =>
          !text ||
          node.name.toLocaleLowerCase().includes(text) ||
          node.children.length > 0,
      ) as CategoryNode[];
  return filter(tree);
});
const categoryExpandedKeys = ref<string[]>([]);
function categoryBranchKeys(nodes: CategoryNode[]): string[] {
  return nodes.flatMap((node) => node.children?.length
    ? [node.key, ...categoryBranchKeys(node.children)] : []);
}
const isCategoryExpanded = computed(() => {
  const keys = categoryBranchKeys(categoryTree.value);
  return keys.length > 0 && keys.every((key) => categoryExpandedKeys.value.includes(key));
});
function toggleCategoryExpanded() {
  categoryExpandedKeys.value = isCategoryExpanded.value ? [] : categoryBranchKeys(categoryTree.value);
}
watch(categoryTree, (nodes) => {
  categoryExpandedKeys.value = categoryBranchKeys(nodes);
}, { immediate: true });
const [ApplyModal, applyApi] = useVbenModal({
  connectedComponent: ApplyForm,
  destroyOnClose: true,
});
const summary = computed(() => [
  {
    key: 'total',
    label: '数据资产总数',
    value: statistics.value?.totalAssetCount,
    image: 'stat-total',
    color: 'blue',
  },
  {
    key: 'authorized',
    label: '已授权',
    value: statistics.value?.authorizedAssetCount,
    image: 'stat-authorized',
    color: 'green',
  },
  {
    key: 'favorite',
    label: '收藏',
    value: statistics.value?.favoriteAssetCount,
    image: 'stat-favorite',
    color: 'gold',
  },
  {
    key: 'applying',
    label: '申请中',
    value: statistics.value?.applyingAssetCount,
    image: 'stat-applying',
    color: 'purple',
  },
]);

async function loadAssets() {
  if (!active || !canQuery.value) return;
  const ticket = listGuard.next();
  pageEpoch++;
  favoriteBusy.clear();
  rows.value = [];
  total.value = 0;
  listError.value = '';
  loading.value = true;
  try {
    const result = await getMarketMartPage({
      ...query,
      keyword: keyword.value.trim() || undefined,
      assetTypes: query.assetTypes?.length ? [...query.assetTypes] : undefined,
    });
    if (!ticket.isCurrent()) return;
    rows.value = result.list ?? [];
    total.value = result.total ?? 0;
  } catch {
    if (ticket.isCurrent()) listError.value = '集市资产读取失败，请重试。';
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}
async function loadStatistics() {
  if (!active || !canQuery.value) return;
  const ticket = statisticsGuard.next();
  statisticsError.value = '';
  try {
    const value = await getMarketMartStatistics();
    if (ticket.isCurrent()) statistics.value = value;
  } catch {
    if (ticket.isCurrent()) {
      statistics.value = undefined;
      statisticsError.value = '统计读取失败，请刷新重试。';
    }
  }
}
async function loadCategories() {
  if (!active || !canQuery.value) return;
  const ticket = categoryGuard.next();
  categoryLoading.value = true;
  categoryError.value = '';
  try {
    const value = await getMarketCategorySimpleList();
    if (ticket.isCurrent()) categories.value = value;
  } catch {
    if (ticket.isCurrent()) {
      categories.value = [];
      categoryError.value = '集市类目读取失败。';
    }
  } finally {
    if (ticket.isCurrent()) categoryLoading.value = false;
  }
}
function search() {
  query.pageNo = 1;
  void loadAssets();
}
function selectCategory(keys: (number | string)[]) {
  query.marketCategoryId = keys[0];
  search();
}
function openDetail(asset: MarketApi.Resource) {
  if (active && canQuery.value && parseResourceId(asset.id))
    void router.push(`/asset/market/detail/${parseResourceId(asset.id)}`);
}
function applyAsset(asset: MarketApi.Resource) {
  if (
    !active ||
    !canQuery.value ||
    !canApply.value ||
    !parseResourceId(asset.id) ||
    asset.publicationNotice ||
    asset.myApplyStatus === 1
  )
    return;
  applyApi
    .setData({
      assetId: asset.id,
      assetName: asset.marketName || asset.assetName,
    })
    .open();
}
async function toggleFavorite(asset: MarketApi.Resource) {
  const id = parseResourceId(asset.id);
  const epoch = pageEpoch;
  const owner = identity.value;
  if (
    !active ||
    !canQuery.value ||
    !canFavorite.value ||
    !id ||
    favoriteBusy.has(id) ||
    !rows.value.some((row) => sameResourceId(row.id, id))
  )
    return;
  const token = Symbol('favorite');
  favoriteBusy.set(id, token);
  try {
    await updateMarketMartFavorite({
      assetId: asset.id!,
      favorite: asset.favorite !== true,
    });
    if (active && epoch === pageEpoch && owner === identity.value)
      await Promise.all([loadAssets(), loadStatistics()]);
  } finally {
    if (owner === identity.value && favoriteBusy.get(id) === token)
      favoriteBusy.delete(id);
  }
}
function reset() {
  listGuard.invalidate();
  statisticsGuard.invalidate();
  categoryGuard.invalidate();
  categoryLoading.value = false;
  pageEpoch++;
  rows.value = [];
  statistics.value = undefined;
  categories.value = [];
  favoriteBusy.clear();
  total.value = 0;
  loading.value = false;
  listError.value = '';
  statisticsError.value = '';
  categoryError.value = '';
  applyApi.setState({ isOpen: false });
  childEpoch.value++;
}
function refresh() {
  void loadAssets();
  void loadStatistics();
  void loadCategories();
}
function activate() {
  active = true;
  refresh();
}
watch(
  identity,
  () => {
    reset();
    query.pageNo = 1;
    query.marketCategoryId = undefined;
    keyword.value = '';
    categoryKeyword.value = '';
    refresh();
  },
  { flush: 'sync' },
);
onMounted(() => {
  document.addEventListener('pointerdown', closeTypeDropdown);
  activate();
});
onActivated(() => {
  if (!active) activate();
});
onDeactivated(() => {
  active = false;
  reset();
});
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', closeTypeDropdown);
  active = false;
  reset();
});
function assetImage(name: string) {
  return `/assets/qdata-market/${name}.png`;
}
</script>

<template>
  <Page
    auto-content-height
    content-class="flex min-h-0 flex-col overflow-hidden"
  >
    <ApplyModal :key="childEpoch" @success="refresh" />
    <div class="asset-mart" data-testid="asset-market">
      <header class="mart-banner">
        <div class="mart-banner__intro">
          <h1>数据资产集市</h1>
          <p>
            统一汇聚数据表、API
            接口、文件资源等标准化资产形态，提供快速发现、权限申领、授权接入、收藏足迹与审批闭环。
          </p>
          <div class="mart-banner__features">
            <div
              v-for="feature in [
                {
                  image: 'feature-rich',
                  title: '丰富资产',
                  text: '多源汇聚，持续更新',
                },
                {
                  image: 'feature-compliant',
                  title: '安全合规',
                  text: '分级管理，合规可控',
                },
                {
                  image: 'feature-open',
                  title: '高效开放',
                  text: '标准接口，便捷接入',
                },
              ]"
              :key="feature.image"
              class="mart-feature"
            >
              <img
                :src="`/assets/qdata-market/${feature.image}.png`"
                alt=""
                aria-hidden="true"
              />
              <div>
                <strong>{{ feature.title }}</strong><small>{{ feature.text }}</small>
              </div>
            </div>
          </div>
        </div>
        <div class="mart-banner__search">
          <div class="mart-banner__search-control">
            <details ref="typeDropdown" class="mart-banner__type">
              <summary>全部类型<span class="mart-chevron"></span></summary>
              <div class="mart-type-menu">
                <Checkbox
                  :checked="!query.assetTypes?.length"
                  @change="
                    query.assetTypes = [];
                    search();
                  "
                >
                  全部类型
                </Checkbox>
                <CheckboxGroup
                  v-model:value="query.assetTypes"
                  :options="DELIVERY_TYPE_OPTIONS"
                  @change="search"
                />
              </div>
            </details>
            <Input
              v-model:value="keyword"
              placeholder="请输入搜索内容"
              aria-label="搜索集市资产"
              class="mart-banner__keyword"
              @press-enter="search"
            />
            <Button type="primary" :disabled="!canQuery" @click="search">
              <template #icon><IconifyIcon icon="lucide:search" /></template>搜索
            </Button>
          </div>
          <div class="mart-hot-search">
            <span>大家都在搜：</span><button
              v-for="text in ['水位预警', '防汛物资', '大坝安全']"
              :key="text"
              type="button"
              @click="
                keyword = text;
                search();
              "
            >
              {{ text }}
            </button>
          </div>
        </div>
      </header>
      <Alert
        v-if="!canQuery"
        type="warning"
        show-icon
        message="登录身份已失效，请重新登录后查看数据集市。"
      />
      <div v-if="canQuery" class="mart-layout">
        <aside class="mart-sidebar subtle-tree-scrollbar">
          <div class="mart-filter mart-filter--search">
            <Input
              v-model:value="keyword"
              allow-clear
              placeholder="请输入要搜索的内容"
              aria-label="侧栏搜索资产"
              @press-enter="search"
            >
              <template #prefix><IconifyIcon icon="lucide:search" /></template>
            </Input>
          </div>
          <section class="mart-filter">
            <h2>类型筛选</h2>
            <CheckboxGroup
              v-model:value="query.assetTypes"
              :options="DELIVERY_TYPE_OPTIONS"
              @change="search"
            />
          </section>
          <section class="mart-filter">
            <h2>我的权限</h2>
            <RadioGroup
              v-model:value="query.authorizedOnly"
              :options="[
                { label: '全部可见', value: false },
                { label: '仅看已获授权', value: true },
              ]"
              @change="search"
            />
          </section>
          <section class="mart-filter mart-filter--topics">
            <h2>推荐专题</h2>
            <button
              v-for="topic in recommendations"
              :key="topic.key"
              type="button"
              class="mart-topic"
              :class="{ 'mart-topic--active': selectedTopic === topic.key }"
              @click="selectTopic(topic.key)"
            >
              <span>{{ topic.name }}</span><span>{{ topic.count }}</span>
            </button>
          </section>
          <section class="mart-filter mart-filter--tree subtle-tree-scrollbar">
            <div class="mb-2 flex items-center gap-2">
              <h2 class="min-w-0 flex-1">数据集市导航</h2>
              <TreeToolbar
                :expanded="isCategoryExpanded"
                :loading="categoryLoading"
                @toggle-expand="toggleCategoryExpanded"
                @refresh="loadCategories"
              />
            </div>
            <Alert
              v-if="categoryError"
              :message="categoryError"
              type="warning"
            />
            <Tree
              :tree-data="categoryTree"
              :selected-keys="selectedCategoryKeys"
              block-node
              v-model:expanded-keys="categoryExpandedKeys"
              @select="selectCategory"
            >
              <template #titleRender="node">
                <span class="mart-tree-label"><img
                    :src="`/assets/qdata-market/icon-${node.children?.length ? 'folder' : 'document'}.png`"
                    alt=""
                    aria-hidden="true"
                  />{{ node.title }}</span>
              </template>
            </Tree>
            <Button
              v-if="query.marketCategoryId !== undefined"
              type="link"
              @click="
                query.marketCategoryId = undefined;
                search();
              "
            >
              全部类目
            </Button>
          </section>
        </aside>
        <main class="mart-main">
          <section class="mart-summary" aria-label="集市资产统计">
            <div
              v-for="metric in summary"
              :key="metric.key"
              class="mart-summary__metric"
            >
              <img
                :src="`/assets/qdata-market/${metric.image}.png`"
                alt=""
                aria-hidden="true"
              />
              <div>
                <span>{{ metric.label }}</span><strong>{{ marketCount(metric.value) }}</strong>
              </div>
            </div>
            <div class="mart-summary__sort">
              <label>排序方式</label><Select
                v-model:value="query.sort"
                :options="[
                  { label: '订阅热度', value: 'POPULAR' },
                  { label: '最新发布', value: 'LATEST' },
                  { label: '收藏最多', value: 'FAVORITE' },
                ]"
                @change="search"
              />
            </div>
          </section>
          <Alert
            v-if="statisticsError"
            :message="statisticsError"
            type="warning"
            show-icon
          />
          <Alert v-if="listError" :message="listError" type="error" show-icon />
          <Spin :spinning="loading" class="mart-results">
            <article
              v-for="asset in rows"
              :key="resourceIdKey(asset.id)"
              class="mart-card"
            >
              <header class="mart-card__header">
                <button
                  type="button"
                  class="mart-card__title"
                  @click="openDetail(asset)"
                >
                  <img
                    :src="assetImage('icon-asset')"
                    alt=""
                    aria-hidden="true"
                  />{{ asset.marketName || asset.assetName || '未命名资产' }}
                </button>
                <span class="mart-card__score"><img
                    :src="assetImage('icon-score')"
                    alt=""
                    aria-hidden="true"
                  />{{ asset.displayScore ?? asset.healthScore ?? '-' }}</span>
                <button
                  type="button"
                  class="mart-card__favorite-button"
                  :class="{ 'mart-card__favorite': asset.favorite }"
                  :disabled="
                    !canFavorite || favoriteBusy.has(resourceIdKey(asset.id))
                  "
                  :aria-label="`${asset.favorite ? '取消收藏' : '收藏'} ${asset.marketName || asset.assetName}`"
                  @click="toggleFavorite(asset)"
                >
                  <img
                    :src="assetImage('icon-star')"
                    alt=""
                    aria-hidden="true"
                  />{{ asset.favorite ? '已收藏' : '收藏' }}
                </button>
              </header>
              <div class="mart-card__facts">
                <span class="mart-card__object"><label>{{ assetNameLabel(asset) }}</label><span :title="assetObjectName(asset)">{{
                    assetObjectName(asset)
                  }}</span></span>
                <span v-if="primaryType(asset) === 3"><label>文件类型：</label>{{ asset.fileType || '-' }}</span>
                <span><label>更新频率：</label><Tag class="mart-tag mart-tag--1">{{
                    asset.frequency || asset.updateCycle || '-'
                  }}</Tag></span>
                <span v-if="primaryType(asset) !== 3"><label>交付形态：</label><Tag
                    v-for="delivery in assetDeliveryLabels(asset)"
                    :key="delivery.value"
                    class="mart-tag"
                    :class="`mart-tag--${delivery.value}`"
                    >{{ delivery.label }}</Tag><span v-if="!asset.deliveryTypes?.length">-</span></span>
                <span><label>发布时间：</label>{{ formatMarketTime(asset.publishTime) }}</span>
              </div>
              <p class="mart-card__description">
                <label>资产简介：</label>{{
                  asset.marketDescription ||
                  asset.businessPurpose ||
                  asset.description ||
                  '-'
                }}
              </p>
              <footer class="mart-card__footer">
                <div class="mart-card__tags">
                  <label>数据标签：</label><Tag
                    v-for="tag in asset.tags"
                    :key="String(tag.id)"
                    class="mart-tag mart-tag--1"
                  >
                    {{ tag.name }}
</Tag><span v-if="!asset.tags?.length">-</span>
                </div>
                <Button size="small" type="primary" @click="openDetail(asset)">
                  <template #icon>
                    <img
                      class="mart-button-icon"
                      :src="assetImage('icon-eye')"
                      alt=""
                      aria-hidden="true"
                    />
</template>详情
                </Button>
                <Button
                  v-if="canApply"
                  size="small"
                  :disabled="
                    !!asset.publicationNotice || asset.myApplyStatus === 1
                  "
                  @click="applyAsset(asset)"
                >
                  <template #icon>
                    <img
                      class="mart-button-icon"
                      :src="assetImage('icon-send')"
                      alt=""
                      aria-hidden="true"
                    />
</template>申请权限
                </Button>
              </footer>
              <p v-if="asset.publicationNotice" class="mart-card__notice">
                {{ asset.publicationNotice }}
              </p>
            </article>
            <Empty
              v-if="!loading && !rows.length && !listError"
              description="暂无符合条件的集市资产"
            />
          </Spin>
          <div class="mart-pagination">
            <ListPagination
              v-model:current="query.pageNo"
              v-model:page-size="query.pageSize"
              :total="total"
              @change="loadAssets"
            />
          </div>
        </main>
      </div>
    </div>
  </Page>
</template>

<style scoped>
.asset-mart {
  --mart-primary: hsl(var(--primary));
  --mart-border: #ebeef5;
  --mart-label: #909399;

  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  padding: 0;
  font-family: Arial, 'Microsoft YaHei', sans-serif;
  font-size: 14px;
  color: #303133;
}

.mart-banner {
  position: relative;
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  height: 200px;
  padding: 28px 30px;
  margin-bottom: 16px;
  color: #fff;
  background: #3459f4 url('/assets/qdata-market/banner-original.png') center /
    100% 100% no-repeat;
  border-radius: var(--radius);
}

.mart-banner__intro,
.mart-banner__search {
  position: relative;
  min-width: 0;
}

.mart-banner h1 {
  margin: 0 0 12px;
  font-size: 24px;
  font-weight: 500;
  line-height: 32px;
}

.mart-banner__intro > p {
  margin: 0;
  font-size: 16px;
  line-height: 26px;
}

.mart-banner__features {
  display: flex;
  gap: 48px;
  margin-top: 24px;
}

.mart-feature {
  display: flex;
  gap: 12px;
  align-items: center;
}

.mart-feature > img {
  width: 43px;
  height: 43px;
  object-fit: contain;
}

.mart-feature strong {
  display: block;
  font-size: 14px;
  font-weight: 500;
}

.mart-feature small {
  display: block;
  margin-top: 6px;
  font-size: 12px;
}

.mart-banner__search {
  flex: 0 0 434px;
  margin-right: 204px;
  margin-left: 32px;
}

.mart-banner__search-control {
  display: flex;
  align-items: center;
  height: 42px;
  padding: 6px;
  color: #606266;
  background: #fff;
  border-radius: var(--radius);
}

.mart-banner__type {
  position: relative;
  flex: 0 0 120px;
  font-size: 14px;
}

.mart-banner__type summary {
  display: flex;
  gap: 7px;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  list-style: none;
  border-right: 1px solid var(--mart-border);
}

.mart-banner__type summary::-webkit-details-marker {
  display: none;
}

.mart-chevron {
  width: 6px;
  height: 6px;
  border-right: 1px solid #c0c4cc;
  border-bottom: 1px solid #c0c4cc;
  transform: rotate(45deg) translateY(-2px);
}

.mart-banner__keyword {
  flex: 1;
  min-width: 0;
  border: 0;
  box-shadow: none;
}

.mart-type-menu {
  position: absolute;
  top: 34px;
  left: -6px;
  z-index: 20;
  display: flex;
  flex-direction: column;
  gap: 20px;
  width: 180px;
  padding: 18px;
  background: #fff;
  border-radius: var(--radius);
  box-shadow: 0 2px 12px #00000020;
}

.mart-type-menu > button {
  padding: 0;
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
}

.mart-type-menu :deep(.ant-checkbox-group) {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.mart-hot-search {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-top: 12px;
  font-size: 12px;
  white-space: nowrap;
}

.mart-hot-search button {
  padding: 3px 12px;
  color: #fff;
  cursor: pointer;
  background: #ffffff2e;
  border: 0;
}

.mart-layout {
  display: grid;
  flex: 1;
  grid-template-columns: 314px minmax(0, 1fr);
  gap: 16px;
  align-items: stretch;
  min-height: 0;
  overflow: hidden;
}

.mart-sidebar {
  min-width: 0;
  min-height: 0;
  overflow: auto;
  background: #fff;
  border-radius: var(--radius);
}

.mart-filter {
  display: flex;
  flex-direction: column;
  gap: 22px;
  padding: 24px 20px;
  border-bottom: 1px solid var(--mart-border);
}

.mart-filter--search {
  height: 80px;
  padding: 20px;
}

.mart-filter--search :deep(.ant-input-affix-wrapper) {
  min-height: 40px;
  border-radius: var(--radius);
}

.mart-filter--search :deep(.ant-input-prefix) {
  margin-right: 8px;
  color: #c0c4cc;
}

.mart-filter h2 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  line-height: 20px;
}

.mart-filter :deep(.ant-checkbox-group),
.mart-filter :deep(.ant-radio-group) {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.mart-filter :deep(.ant-checkbox-wrapper),
.mart-filter :deep(.ant-radio-wrapper) {
  font-size: 14px;
}

.mart-filter :deep(.ant-checkbox-inner),
.mart-filter :deep(.ant-radio-inner) {
  width: 14px;
  height: 14px;
  border-color: var(--mart-border);
}

.mart-filter :deep(.ant-radio-checked .ant-radio-inner) {
  background-color: var(--mart-primary);
  border-color: var(--mart-primary);
}

.mart-filter--topics {
  gap: 10px;
  padding-bottom: 20px;
}

.mart-filter--topics h2 {
  margin-bottom: 10px;
}

.mart-topic {
  display: flex;
  justify-content: space-between;
  padding: 7px 20px;
  font-size: 14px;
  color: #606266;
  text-align: left;
  cursor: pointer;
  background: #fafafa;
  border: 1px solid var(--mart-border);
  border-radius: var(--radius);
}

.mart-topic--active {
  color: var(--mart-primary);
  background: hsl(var(--primary) / 10%);
  border-color: hsl(var(--primary) / 20%);
}

.mart-filter--tree {
  gap: 10px;
  padding-top: 22px;
}

.mart-tree-label {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  font-size: 14px;
  line-height: 26px;
}

.mart-tree-label img {
  width: 18px;
  height: 18px;
  object-fit: contain;
}

.mart-filter--tree :deep(.ant-tree-treenode) {
  padding: 0;
}

.mart-filter--tree :deep(.ant-tree-switcher) {
  width: 24px;
  height: 26px;
  line-height: 26px;
  color: #909399;
}

.mart-main {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
  min-height: 0;
}

.mart-summary {
  display: flex;
  flex: none;
  align-items: center;
  height: 80px;
  padding: 16px 12px 16px 28px;
  background: #fff;
  border-radius: var(--radius);
}

.mart-summary__metric {
  display: flex;
  flex: 1;
  gap: 14px;
  align-items: center;
  min-width: 0;
  height: 44px;
  padding-right: 20px;
  border-right: 1px solid var(--mart-border);
}

.mart-summary__metric + .mart-summary__metric {
  padding-left: 40px;
}

.mart-summary__metric:nth-child(4) {
  border: 0;
}

.mart-summary__metric > img {
  width: 42px;
  height: 44px;
  object-fit: contain;
}

.mart-summary__metric:first-child > img {
  width: 62px;
}

.mart-summary__metric span {
  display: block;
  margin-bottom: 2px;
  font-size: 12px;
  line-height: 18px;
  color: var(--mart-label);
}

.mart-summary__metric strong {
  font-size: 24px;
  font-weight: 400;
  line-height: 26px;
}

.mart-summary__sort {
  display: flex;
  flex: none;
  gap: 8px;
  align-items: center;
  margin-left: 24px;
  font-size: 12px;
  color: var(--mart-label);
}

.mart-summary__sort :deep(.ant-select) {
  width: 120px;
}

.mart-summary__sort :deep(.ant-select-selector) {
  border-radius: var(--radius);
}

.mart-results {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: auto;
}

.mart-card {
  min-height: 168px;
  padding: 20px 18px 18px;
  margin-bottom: 16px;
  background: #fff;
  border-radius: var(--radius);
}

.mart-card:last-child {
  margin-bottom: 0;
}

.mart-card__header {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 14px;
}

.mart-card__title {
  display: inline-flex;
  flex: 1;
  gap: 14px;
  align-items: center;
  min-width: 0;
  padding: 0;
  font-size: 14px;
  color: var(--mart-primary);
  text-align: left;
  overflow-wrap: anywhere;
  cursor: pointer;
  background: none;
  border: 0;
}

.mart-card__title > img {
  flex: none;
  width: 16px;
  height: 16px;
  object-fit: contain;
}

.mart-card__score {
  display: flex;
  flex: none;
  gap: 10px;
  align-items: center;
  font-size: 12px;
  color: #e6a23c;
}

.mart-card__score img {
  width: 12px;
  height: 18px;
  object-fit: contain;
}

.mart-card__favorite-button {
  display: flex;
  flex: none;
  gap: 10px;
  align-items: center;
  padding: 0;
  font-size: 12px;
  color: #909399;
  cursor: pointer;
  background: none;
  border: 0;
}

.mart-card__favorite-button img {
  width: 17px;
  height: 19px;
  object-fit: contain;
}

.mart-card__favorite-button:disabled {
  cursor: default;
  opacity: 0.55;
}

.mart-card__favorite {
  color: #e6a23c;
}

.mart-card__favorite img {
  filter: sepia(1) saturate(4);
}

.mart-card__facts {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 40px;
  align-items: center;
  font-size: 14px;
  line-height: 24px;
}

.mart-card__facts > span {
  display: inline-flex;
  align-items: center;
}

.mart-card__object {
  width: 305px;
}

.mart-card__object > span {
  min-width: 0;
  overflow-wrap: anywhere;
  white-space: normal;
}

.mart-card label {
  flex: none;
  color: var(--mart-label);
}

.mart-card__description {
  margin: 10px 0;
  font-size: 14px;
  line-height: 24px;
  overflow-wrap: anywhere;
}

.mart-card__footer {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  font-size: 14px;
}

.mart-card__tags {
  display: flex;
  flex: 1;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  min-width: 0;
}

.mart-tag {
  padding: 0 9px;
  margin: 0;
  font-size: 14px;
  line-height: 24px;
  border: 0;
  border-radius: calc(var(--radius) - 2px);
}

.mart-tag--1 {
  color: var(--mart-primary);
  background: hsl(var(--primary) / 10%);
}

.mart-tag--2 {
  color: #9650bd;
  background: #f1edf7;
}

.mart-tag--3 {
  color: #e6a23c;
  background: #fdf8eb;
}

.mart-button-icon {
  width: 14px;
  height: 14px;
  object-fit: contain;
}

.mart-card__notice {
  margin: 10px 0 0;
  font-size: 12px;
  color: #d97706;
}

.mart-pagination {
  flex: none;
  padding: 0 20px;
  background: #fff;
}

@media (width <= 1700px) {
  .mart-summary__metric + .mart-summary__metric {
    padding-left: 20px;
  }

  .mart-card__facts {
    gap: 10px 22px;
  }

  .mart-card__object {
    width: 280px;
  }
}

@media (width <= 1200px) {
  .mart-layout {
    grid-template-columns: 240px minmax(0, 1fr);
  }

  .mart-summary {
    flex-wrap: wrap;
    gap: 12px;
    height: auto;
    min-height: 80px;
    padding: 14px;
  }

  .mart-summary__metric {
    flex-basis: 18%;
    gap: 8px;
    padding-right: 8px;
  }

  .mart-summary__metric + .mart-summary__metric {
    padding-left: 0;
  }

  .mart-summary__sort {
    justify-content: flex-end;
    width: 100%;
    margin: 0;
  }
}

@media (width <= 900px) {
  .mart-banner {
    flex-direction: column;
    gap: 24px;
    align-items: stretch;
    padding: 16px;
  }

  .mart-layout {
    grid-template-columns: 210px minmax(0, 1fr);
  }

  .mart-filter {
    padding-right: 14px;
    padding-left: 14px;
  }
}

@media (width <= 640px) {
  .mart-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .mart-banner {
    padding: 20px 16px;
  }

  .mart-banner__search-control {
    flex-wrap: wrap;
    gap: 8px;
    height: auto;
  }

  .mart-banner__type {
    flex-basis: 90px;
  }

  .mart-hot-search {
    flex-wrap: wrap;
    gap: 6px;
    white-space: normal;
  }

  .mart-filter--tree {
    max-height: 320px;
    overflow: auto;
  }

  .mart-summary__metric {
    flex-basis: 40%;
    border: 0;
  }

  .mart-card {
    padding: 16px;
  }

  .mart-card__facts {
    flex-direction: column;
    gap: 7px;
    align-items: flex-start;
  }

  .mart-card__object {
    max-width: 100%;
  }

  .mart-card__title {
    font-size: 14px;
  }

  .mart-card__header {
    gap: 8px;
  }

  .mart-card__tags {
    flex-basis: 100%;
    margin-bottom: 6px;
  }

  .mart-pagination {
    justify-content: center;
    padding: 14px 6px;
  }
}

@media (width <= 1700px) {
  .mart-banner__search {
    margin-right: 0;
  }

  .mart-banner__intro > p {
    max-width: 760px;
    font-size: 14px;
  }

  .mart-banner__features {
    gap: 30px;
  }
}

@media (width <= 1200px) {
  .mart-banner__search {
    flex-basis: 370px;
    margin-left: 20px;
  }

  .mart-banner__features {
    gap: 14px;
  }
}

@media (width <= 900px) {
  .mart-banner {
    height: auto;
    min-height: 200px;
    padding: 24px;
  }

  .mart-banner__search {
    flex: auto;
    max-width: 480px;
    margin: 0;
  }
}

@media (width <= 640px) {
  .mart-banner {
    padding: 20px 16px;
  }

  .mart-banner__features {
    flex-wrap: wrap;
  }

  .mart-banner__intro > p {
    font-size: 13px;
  }

  .mart-feature > img {
    width: 36px;
    height: 36px;
  }

  .mart-feature small {
    font-size: 12px;
  }
}
</style>
