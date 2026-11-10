<script lang="ts" setup>
import type { AssetMarketCategoryApi } from '#/api/asset/marketcategory';
import type { MyAssetsApi } from '#/api/asset/my-assets';

import {
  computed,
  nextTick,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  reactive,
  ref,
  watch,
} from 'vue';
import { useRoute } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page, useVbenModal } from '@vben/common-ui';
import { useIsMobile } from '@vben/hooks';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Button, Empty, Tag, Tooltip } from 'antdv-next';

import { useVbenVxeGrid } from '#/adapter/business-vxe-table';
import { useVbenForm } from '#/adapter/form';
import { getMarketCategorySimpleList } from '#/api/asset/marketcategory';
import { getMyAssetsPage, previewMyAsset } from '#/api/asset/my-assets';
import ListPagination from '#/components/list-pagination/index.vue';
import {
  createLatestWins,
  parseResourceId,
  sameResourceId,
} from '#/utils/resource-id';
import {
  MY_ASSETS_ROUTE_NAME,
  parseAuthorizedApplyId,
} from '#/views/asset/market/authorized-assets-navigation';
import { formatMarketTime } from '#/views/asset/market/market-time';
import PreviewDrawer from '#/views/asset/market/modules/preview-drawer.vue';
import { marketCategoryTree } from '#/views/asset/marketcategory/category-tree';

import { DELIVERY_TYPE_OPTIONS, deliveryLabels } from '../data';
import { accessState, assetTitle, canOpenDelivery } from './data';
import ApiDocument from './modules/api-document.vue';
import AssetDetail from './modules/detail.vue';
import MyApplications from './modules/my-applications.vue';

defineOptions({ name: 'AssetMarketMyAssets' });
const route = useRoute();
const activeTab = ref<'applications' | 'assets'>('assets');
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const { isMobile } = useIsMobile();
const canQuery = computed(
  () =>
    !!access.accessToken &&
    !access.loginExpired &&
    !!parseResourceId(user.userInfo?.userId) &&
    hasAccessByCodes(['asset:market:my-assets:query']),
);
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
const listGuard = createLatestWins();
const categoryGuard = createLatestWins();
const linkedAssetGuard = createLatestWins();
const linkedAssetError = ref('');
const selectedDetailId = ref<string>();
const rows = ref<MyAssetsApi.Asset[]>([]);
const total = ref(0);
const loading = ref(false);
const listError = ref('');
const categoryError = ref('');
const categories = ref<AssetMarketCategoryApi.MarketCategory[]>([]);
const categoryLoading = ref(false);
const childEpoch = ref(0);
const detail = ref<InstanceType<typeof AssetDetail>>();
const apiDocument = ref<InstanceType<typeof ApiDocument>>();
const filters = reactive({
  keyword: '',
  deliveryType: undefined as number | undefined,
  marketCategoryId: undefined as MyAssetsApi.Id | undefined,
});
const query = reactive<MyAssetsApi.PageReq>({ pageNo: 1, pageSize: 10 });
const [FilterForm, filterFormApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' }, labelWidth: 110 },
  layout: 'horizontal',
  wrapperClass: 'grid-cols-1 md:grid-cols-2 lg:grid-cols-3',
  showCollapseButton: true,
  submitOnEnter: true,
  resetButtonOptions: { show: false },
  submitButtonOptions: { show: false },
  schema: [
    {
      fieldName: 'keyword',
      label: '数据集市名称',
      component: 'Input',
      componentProps: {
        allowClear: true,
        maxlength: 100,
        placeholder: '请输入数据集市名称',
      },
    },
    {
      fieldName: 'deliveryType',
      label: '交付形态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: DELIVERY_TYPE_OPTIONS,
        placeholder: '请选择交付形态',
      },
    },
    {
      fieldName: 'marketCategoryId',
      label: '所属集市',
      component: 'TreeSelect',
      componentProps: () => ({
        allowClear: true,
        showSearch: true,
        fieldNames: { label: 'name', value: 'id', children: 'children' },
        treeNodeFilterProp: 'name',
        treeData: categoryOptions.value,
        loading: categoryLoading.value,
        placeholder: '请选择集市类目',
      }),
    },
  ],
  handleValuesChange(values) {
    filters.keyword = values.keyword ?? '';
    filters.deliveryType = values.deliveryType;
    filters.marketCategoryId = values.marketCategoryId;
  },
  handleSubmit: search,
  handleReset: resetFilters,
});
const categoryOptions = computed(() =>
  marketCategoryTree(
    categories.value.map((row) => ({
      ...row,
      id: String(row.id),
      parentId: String(row.parentId),
    })),
  ),
);
const columns = computed(() => [
  { title: '编号', field: 'applyNo', width: isMobile.value ? 112 : 180 },
  { title: '数据集市名称', field: 'name', width: 240 },
  { title: '集市类目', field: 'marketCategoryName', width: 150 },
  { title: '交付形态', field: 'deliveryType', width: 120 },
  { title: '申请时间', field: 'createTime', width: 175 },
  { title: '有效期', field: 'validity', width: 190 },
  { title: '状态', field: 'accessStatus', width: 180 },
  { title: '数据更新周期', field: 'updateCycle', width: 140 },
  {
    title: '操作', field: 'actions', fixed: 'right' as const, width: 180,
    showOverflow: false,
  },
].map((column) => ({ ...column, slots: { default: 'body' } })));
const [Grid, gridApi] = useVbenVxeGrid<MyAssetsApi.Asset>({
  gridOptions: {
    columns: columns.value,
    data: [],
    height: 'auto',
    pagerConfig: { enabled: false },
    proxyConfig: { enabled: false },
    rowConfig: { keyField: 'applyId', isHover: true },
    scrollbarConfig: { height: 6 },
    showOverflow: false,
    toolbarConfig: {
      custom: false,
      refresh: true,
      refreshOptions: { queryMethod: () => loadAssets() },
    },
  },
});
watch(columns, (value) => gridApi.setGridOptions({ columns: value }));
watch(loading, (value) => gridApi.setLoading(value), { immediate: true });
const [PreviewModal, previewModalApi] = useVbenModal({
  connectedComponent: PreviewDrawer,
  destroyOnClose: true,
});
function cellText(row: MyAssetsApi.Asset, key: unknown) {
  if (
    key === 'applyNo' ||
    key === 'marketCategoryName' ||
    key === 'updateCycle'
  )
    return row[key] || '--';
  return '--';
}
function currentRow(row: MyAssetsApi.Asset) {
  return (
    active &&
    canQuery.value &&
    route.name === MY_ASSETS_ROUTE_NAME &&
    !!parseResourceId(row.applyId) &&
    rows.value.some((item) => sameResourceId(item.applyId, row.applyId))
  );
}
function openDetail(row: MyAssetsApi.Asset) {
  if (currentRow(row)) {
    linkedAssetGuard.invalidate();
    linkedAssetError.value = '';
    openDetailById(row.applyId);
  }
}
function openDetailById(value: MyAssetsApi.Id) {
  const id = parseAuthorizedApplyId(value);
  if (!active || !canQuery.value || route.name !== MY_ASSETS_ROUTE_NAME || !id)
    return;
  selectedDetailId.value = id;
  detail.value?.open(id);
}
function detailClosed(id: string) {
  if (sameResourceId(selectedDetailId.value, id))
    selectedDetailId.value = undefined;
}
function currentDetail(row: MyAssetsApi.Detail) {
  return (
    active &&
    canQuery.value &&
    route.name === MY_ASSETS_ROUTE_NAME &&
    sameResourceId(row.applyId, selectedDetailId.value) &&
    detail.value?.isCurrent(row) === true
  );
}
function openDetailPreview(row: MyAssetsApi.Detail) {
  if (!currentDetail(row) || !canOpenDelivery(row, 'preview')) return;
  previewModalApi
    .setData({ requestId: row.applyId, title: `${assetTitle(row)} · 数据预览` })
    .open();
}
function openDetailApiDocument(row: MyAssetsApi.Detail) {
  if (currentDetail(row) && canOpenDelivery(row, 'document'))
    apiDocument.value?.open(row.applyId);
}
function closeDelivery() {
  selectedDetailId.value = undefined;
  detail.value?.close();
  apiDocument.value?.close();
  previewModalApi.setState({ isOpen: false });
}
async function syncLinkedAsset() {
  if (
    active &&
    canQuery.value &&
    route.name === MY_ASSETS_ROUTE_NAME &&
    route.query.applyId !== undefined
  )
    activeTab.value = 'assets';
  const ticket = linkedAssetGuard.next();
  linkedAssetError.value = '';
  closeDelivery();
  if (
    !active ||
    !canQuery.value ||
    route.name !== MY_ASSETS_ROUTE_NAME ||
    route.query.applyId === undefined
  )
    return;
  const id = parseAuthorizedApplyId(route.query.applyId);
  if (!id) {
    linkedAssetError.value =
      '授权记录编号无效，请检查链接或从我的资产重新打开。';
    return;
  }
  const owner = identity.value;
  const epoch = childEpoch.value;
  await nextTick();
  if (
    ticket.isCurrent() &&
    active &&
    canQuery.value &&
    route.name === MY_ASSETS_ROUTE_NAME &&
    owner === identity.value &&
    epoch === childEpoch.value &&
    id === parseAuthorizedApplyId(route.query.applyId)
  )
    openDetailById(id);
}
function openPreview(row: MyAssetsApi.Asset) {
  if (!currentRow(row) || !canOpenDelivery(row, 'preview')) return;
  previewModalApi
    .setData({ requestId: row.applyId, title: `${assetTitle(row)} · 数据预览` })
    .open();
}
function openApiDocument(row: MyAssetsApi.Asset) {
  if (currentRow(row) && canOpenDelivery(row, 'document'))
    apiDocument.value?.open(row.applyId);
}
async function loadAssets() {
  if (!active || !canQuery.value) return;
  const ticket = listGuard.next();
  rows.value = [];
  total.value = 0;
  loading.value = true;
  listError.value = '';
  try {
    const value = await getMyAssetsPage({ ...query });
    if (!ticket.isCurrent()) return;
    rows.value = value.list ?? [];
    total.value = value.total ?? 0;
  } catch {
    if (ticket.isCurrent()) listError.value = '我的资产读取失败，请重试。';
  } finally {
    if (ticket.isCurrent()) loading.value = false;
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
      categoryError.value = '集市类目读取失败，可重试或使用其他条件查询。';
    }
  } finally {
    if (ticket.isCurrent()) categoryLoading.value = false;
  }
}
function search() {
  query.pageNo = 1;
  query.keyword = filters.keyword.trim() || undefined;
  query.deliveryType = filters.deliveryType;
  query.marketCategoryId = filters.marketCategoryId;
  void loadAssets();
}
function resetFilters() {
  if (filterFormApi.isMounted) void filterFormApi.reset();
  filters.keyword = '';
  filters.deliveryType = undefined;
  filters.marketCategoryId = undefined;
  search();
}
function changePage(page: number, size: number) {
  query.pageNo = size === query.pageSize ? page : 1;
  query.pageSize = size;
  void loadAssets();
}
function clear() {
  listGuard.invalidate();
  categoryGuard.invalidate();
  linkedAssetGuard.invalidate();
  linkedAssetError.value = '';
  rows.value = [];
  total.value = 0;
  categories.value = [];
  listError.value = '';
  categoryError.value = '';
  loading.value = false;
  categoryLoading.value = false;
  closeDelivery();
  childEpoch.value++;
}
function activate() {
  if (active) return;
  active = true;
  void loadAssets();
  void loadCategories();
  void syncLinkedAsset();
}
watch(
  () => JSON.stringify([route.name, route.query.applyId]),
  () => {
    void syncLinkedAsset();
  },
  { flush: 'sync' },
);
watch(
  activeTab,
  () => {
    linkedAssetGuard.invalidate();
    closeDelivery();
  },
  { flush: 'sync' },
);
watch(
  identity,
  () => {
    clear();
    if (filterFormApi.isMounted) void filterFormApi.reset();
    query.pageNo = 1;
    filters.keyword = '';
    filters.deliveryType = undefined;
    filters.marketCategoryId = undefined;
    query.keyword = undefined;
    query.deliveryType = undefined;
    query.marketCategoryId = undefined;
    if (active) {
      void loadAssets();
      void loadCategories();
      void syncLinkedAsset();
    }
  },
  { flush: 'sync' },
);
onMounted(activate);
onActivated(activate);
onDeactivated(() => {
  active = false;
  clear();
});
onBeforeUnmount(() => {
  active = false;
  clear();
});

function syncFilterValues() {
  if (filterFormApi.isMounted)
    void filterFormApi.setValues({
      keyword: filters.keyword,
      deliveryType: filters.deliveryType,
      marketCategoryId: filters.marketCategoryId,
    });
}
</script>

<template>
  <Page
    auto-content-height
    content-class="flex min-h-0 flex-col gap-4 overflow-hidden"
  >
    <AssetDetail
      :key="`detail-${childEpoch}`"
      ref="detail"
      @closed="detailClosed"
      @preview="openDetailPreview"
      @document="openDetailApiDocument"
    />
    <ApiDocument
      :key="`document-${childEpoch}`"
      ref="apiDocument"
      @unavailable="loadAssets"
    />
    <PreviewModal
      :key="`preview-${childEpoch}`"
      :preview-loader="previewMyAsset"
      @failed="loadAssets"
    />
    <Alert
      v-if="!canQuery"
      type="warning"
      message="当前身份无权查看我的资产，请重新登录或联系管理员。"
      show-icon
    />
    <template v-else>
      <nav class="my-assets-tabs" aria-label="资产与申请记录">
        <button
          :class="{ 'is-active': activeTab === 'assets' }"
          @click="activeTab = 'assets'"
        >
          我的资产
        </button>
        <button
          :class="{ 'is-active': activeTab === 'applications' }"
          @click="activeTab = 'applications'"
        >
          申请记录
        </button>
      </nav>
      <MyApplications v-if="activeTab === 'applications'" />
      <template v-else>
<FilterForm
          @vue:mounted="syncFilterValues"
          class="rounded-lg bg-background p-4"
        >
          <template #reset-before>
            <Button @click="resetFilters">重置</Button>
            <Button
              type="primary"
              :loading="loading"
              @click="filterFormApi.validateAndSubmit()"
            >
              <template #icon><IconifyIcon icon="lucide:search" /></template>查询
            </Button>
          </template>
        </FilterForm>

        <section class="my-assets-results">
          <Grid
            class="my-assets-grid subtle-grid-scrollbar"
            table-title="我的资产"
            :table-data="rows"
          >
            <template #body="{ column, row: record }">
              <Button
                v-if="column.field === 'name'"
                type="link"
                class="my-assets-name"
                @click="openDetail(record)"
              >
                {{ assetTitle(record) }}
              </Button>
              <Tag v-else-if="column.field === 'deliveryType'" color="blue">
                {{
                  deliveryLabels(
                    record.deliveryType === undefined ||
                      record.deliveryType === null
                      ? []
                      : [record.deliveryType],
                  ).join(' / ') || '--'
                }}
              </Tag>
              <span v-else-if="column.field === 'createTime'">{{
                formatMarketTime(record.createTime)
              }}</span>
              <div
                v-else-if="column.field === 'validity'"
                class="my-assets-validity"
              >
                <span>{{ formatMarketTime(record.applyStartTime) }}</span><span class="text-muted-foreground">至
                  {{
                    record.applyEndTime === undefined ||
                    record.applyEndTime === null
                      ? '长期有效'
                      : formatMarketTime(record.applyEndTime)
                  }}</span>
              </div>
              <div
                v-else-if="column.field === 'accessStatus'"
                class="my-assets-status"
              >
                <Tag :color="accessState(record.accessStatus).color">
                  {{ accessState(record.accessStatus).label }}
</Tag><span v-if="record.disabledReason">{{
                  record.disabledReason
                }}</span>
              </div>
              <div
                v-else-if="column.field === 'actions'"
                class="my-assets-actions"
              >
                <Button type="link" size="small" @click="openDetail(record)">
                  <template #icon><IconifyIcon icon="lucide:eye" /></template>
                  详情
                </Button>
                <Tooltip
                  v-if="record.deliveryType === 1"
                  :title="
                    record.disabledReason ||
                    (!record.canPreview
                      ? '当前未接通数据预览'
                      : '打开时再次核验授权')
                  "
                >
                  <span><Button
                      type="link"
                      size="small"
                      :disabled="!canOpenDelivery(record, 'preview')"
                      @click="openPreview(record)"
                    >
                      <template #icon><IconifyIcon icon="lucide:table-2" /></template>
                      数据预览
                    </Button></span>
                </Tooltip>
                <Tooltip
                  v-else-if="record.deliveryType === 2"
                  :title="
                    record.disabledReason ||
                    (!record.canViewApiDocument
                      ? '当前未接通 API 文档'
                      : '打开时再次核验批准字段与接口版本')
                  "
                >
                  <span><Button
                      type="link"
                      size="small"
                      :disabled="!canOpenDelivery(record, 'document')"
                      @click="openApiDocument(record)"
                    >
                      <template #icon><IconifyIcon icon="lucide:book-open" /></template>
                      API 文档
                    </Button></span>
                </Tooltip>
                <Tooltip
                  v-else-if="record.deliveryType === 3"
                  title="文件交付暂未接通，请联系资产提供方"
                >
                  <span><Button type="link" size="small" disabled>
                      <template #icon><IconifyIcon icon="lucide:download" /></template>
                      文件获取
                    </Button></span>
                </Tooltip>
              </div>
              <span v-else>{{ cellText(record, column.field) }}</span>
            </template>
            <template #empty>
              <Empty
                v-if="!loading && !listError"
                description="暂无符合条件的已批准资产服务"
              /><span v-else-if="loading">正在读取资产服务</span>
            </template>
          </Grid>
          <ListPagination
            :current="query.pageNo"
            :page-size="query.pageSize"
            :total="total"
            class="my-assets-pagination"
            @change="changePage"
          />
        </section>
      </template>
    </template>
  </Page>
</template>

<style scoped>
.my-assets-tabs {
  display: flex;
  gap: 24px;
  padding: 0 20px;
  background: hsl(var(--background));
  border-radius: var(--radius);
}

.my-assets-tabs button {
  padding: 14px 2px 12px;
  color: hsl(var(--muted-foreground));
  border-bottom: 2px solid transparent;
}

.my-assets-tabs button.is-active {
  color: hsl(var(--primary));
  border-bottom-color: hsl(var(--primary));
}

.my-assets-filters__actions {
  display: flex;
  gap: 8px;
}

.my-assets-results {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: hsl(var(--background));
  border-radius: var(--radius);
}

.my-assets-grid {
  flex: 1;
  min-height: 0;
}

.my-assets-name {
  max-width: 100%;
  height: auto;
  padding-inline: 0;
  text-align: left;
  overflow-wrap: anywhere;
  white-space: normal;
}

.my-assets-validity,
.my-assets-status {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.my-assets-status {
  font-size: 12px;
}

.my-assets-status > span {
  color: hsl(var(--muted-foreground));
}

.my-assets-actions {
  display: flex;
  flex-wrap: nowrap;
  gap: 2px;
  white-space: nowrap;
}

.my-assets-pagination {
  display: flex;
  flex: none;
  margin: 0 8px 8px;
}

</style>
