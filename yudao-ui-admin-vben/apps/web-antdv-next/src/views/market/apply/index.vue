<script lang="ts" setup>
import type { DepartmentAssetsApi } from '#/api/asset/market/department-assets';

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

import { Page } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Button, CheckboxGroup, Dropdown, Tag } from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import {
  getDepartmentAssetCategories,
  getDepartmentAssetsPage,
} from '#/api/asset/market/department-assets';
import TableViewport from '#/components/table-viewport/index.vue';
import {
  createLatestWins,
  parseResourceId,
  resourceIdKey,
} from '#/utils/resource-id';
import ReferencePagination from '#/views/governance/standardcommon/standard-pagination.vue';

import { marketCheckTime } from '../check/data';
import {
  DEPARTMENT_ASSET_COLUMNS,
  DEPARTMENT_ASSET_DELIVERIES,
  DEPARTMENT_ASSET_DETAIL_ROUTE,
  DEPARTMENT_ASSET_PERMISSION,
  departmentAssetIcon,
  departmentAssetPeriod,
  departmentDeliveryLabel,
} from './data';

import './reference.css';

defineOptions({ name: 'MarketApply' });
const detailIcon = '/assets/qdata-tag/detail.svg';
const router = useRouter();
const access = useAccessStore();
const user = useUserStore();
const identity = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    access.loginExpired,
    access.accessCodes,
    user.userInfo,
    user.userRoles,
  ]),
);
const canQuery = computed(
  () =>
    !!access.accessToken &&
    !access.loginExpired &&
    !!parseResourceId(user.userInfo?.userId) &&
    access.accessCodes.includes(DEPARTMENT_ASSET_PERMISSION),
);
const rows = ref<DepartmentAssetsApi.Asset[]>([]);
const categories = ref<DepartmentAssetsApi.Category[]>([]);
const loading = ref(false);
const categoryLoading = ref(false);
const errorText = ref('');
const categoryError = ref('');
const showFilters = ref(true);
const columnSettingsOpen = ref(false);
const filters = reactive<{
  deliveryType?: number;
  keyword: string;
  marketCategoryId?: number | string;
}>({ keyword: '' });
const query = reactive<
  DepartmentAssetsApi.PageReq & { pageNo: number; pageSize: number }
>({
  pageNo: 1,
  pageSize: 10,
  sortField: 'applyTime',
  sortOrder: 'desc',
});
const total = ref(0);
const visibleKeys = ref(DEPARTMENT_ASSET_COLUMNS.map((column) => column.key));
const columns = computed(() =>
  DEPARTMENT_ASSET_COLUMNS.filter((column) =>
    visibleKeys.value.includes(column.key),
  ),
);
const categoryOptions = computed(() =>
  categories.value.map((item) => ({ value: item.id, label: item.name })),
);
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
        maxlength: 200,
        placeholder: '请输入数据集市名称',
      },
    },
    {
      fieldName: 'deliveryType',
      label: '交付形态',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: DEPARTMENT_ASSET_DELIVERIES,
        placeholder: '请选择交付形态',
      },
    },
    {
      fieldName: 'marketCategoryId',
      label: '所属集市',
      component: 'Select',
      componentProps: () => ({
        allowClear: true,
        options: categoryOptions.value,
        loading: categoryLoading.value,
        placeholder: '请选择所属集市',
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
const listGuard = createLatestWins();
const categoryGuard = createLatestWins();
let active = false;
let alive = true;

async function load() {
  if (!alive || !active || !canQuery.value) return;
  const ticket = listGuard.next();
  loading.value = true;
  errorText.value = '';
  try {
    const page = await getDepartmentAssetsPage({ ...query });
    if (ticket.isCurrent()) {
      rows.value = page.list || [];
      total.value = page.total || 0;
    }
  } catch {
    if (ticket.isCurrent()) {
      rows.value = [];
      total.value = 0;
      errorText.value = '申请资产读取失败，请重试。';
    }
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}
async function loadCategories() {
  if (!alive || !active || !canQuery.value) return;
  const ticket = categoryGuard.next();
  categoryLoading.value = true;
  categoryError.value = '';
  try {
    const items = await getDepartmentAssetCategories();
    if (ticket.isCurrent()) categories.value = items;
  } catch {
    if (ticket.isCurrent()) {
      categories.value = [];
      categoryError.value = '集市类目读取失败，可重试或使用其他条件。';
    }
  } finally {
    if (ticket.isCurrent()) categoryLoading.value = false;
  }
}
function search() {
  Object.assign(query, {
    pageNo: 1,
    keyword: filters.keyword.trim() || undefined,
    deliveryType: filters.deliveryType,
    marketCategoryId: filters.marketCategoryId,
  });
  void load();
}
function resetFilters() {
  if (filterFormApi.isMounted) void filterFormApi.reset();
  filters.keyword = '';
  filters.deliveryType = undefined;
  filters.marketCategoryId = undefined;
  search();
}
function openDetail(row: DepartmentAssetsApi.Asset) {
  if (
    !active ||
    !canQuery.value ||
    !row.canView ||
    !parseResourceId(row.id) ||
    !rows.value.some((item) => item.id === row.id)
  )
    return;
  void router.push({
    name: DEPARTMENT_ASSET_DETAIL_ROUTE,
    params: { id: row.id },
  });
}
function tableChange(
  _page: unknown,
  _filter: unknown,
  sorter:
    | { columnKey?: number | string; order?: null | string }
    | { columnKey?: number | string; order?: null | string }[],
) {
  const value = Array.isArray(sorter) ? sorter[0] : sorter;
  if (value?.columnKey !== 'applyId' && value?.columnKey !== 'applyTime')
    return;
  query.sortField = value.columnKey;
  query.sortOrder = value.order === 'ascend' ? 'asc' : 'desc';
  query.pageNo = 1;
  void load();
}
function clear() {
  listGuard.invalidate();
  categoryGuard.invalidate();
  rows.value = [];
  categories.value = [];
  total.value = 0;
  loading.value = false;
  categoryLoading.value = false;
  errorText.value = '';
  categoryError.value = '';
  columnSettingsOpen.value = false;
}
function refresh() {
  void load();
  void loadCategories();
}
function activate() {
  if (active) return;
  active = true;
  refresh();
}
watch(
  identity,
  () => {
    clear();
    resetFilters();
    void loadCategories();
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
  alive = false;
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
    content-class="flex min-h-0 flex-col overflow-hidden"
  >
    <div class="source-assets source-assets-list">
      <Alert
        v-if="!canQuery"
        type="warning"
        message="当前身份无权查看同部门申请资产。"
        show-icon
      />
      <template v-else>
        <FilterForm
          @vue:mounted="syncFilterValues"
          v-show="showFilters"
          class="rounded-lg bg-background p-4"
        >
          <template #reset-before>
            <Button @click="resetFilters">
              <span
                class="source-assets-icon"
                :style="{ maskImage: `url(${departmentAssetIcon('reset')})` }"
                aria-hidden="true"
              ></span>重置
            </Button>
            <Button type="primary" @click="filterFormApi.validateAndSubmit()">
              <span
                class="source-assets-icon"
                :style="{ maskImage: `url(${departmentAssetIcon('query')})` }"
                aria-hidden="true"
              ></span>查询
            </Button>
          </template>
        </FilterForm>
        <section class="source-assets-results">
          <div class="source-assets-tools">
            <h2 class="mr-auto text-base font-medium">我的资产列表</h2>
            <Button
              aria-label="显示或隐藏查询"
              @click="showFilters = !showFilters"
            >
              <span
                class="source-assets-icon"
                :style="{
                  maskImage: `url(${departmentAssetIcon('search')})`,
                }"
                aria-hidden="true"
              ></span>
</Button><Button aria-label="刷新我的资产" @click="refresh">
              <span
                class="source-assets-icon"
                :style="{
                  maskImage: `url(${departmentAssetIcon('refresh')})`,
                }"
                aria-hidden="true"
              ></span>
</Button><Dropdown v-model:open="columnSettingsOpen" :trigger="['click']">
              <Button aria-label="选择显示列">
                <span
                  class="source-assets-icon"
                  :style="{
                    maskImage: `url(${departmentAssetIcon('columns')})`,
                  }"
                  aria-hidden="true"
                ></span>
</Button><template #popupRender>
                <div class="source-assets-column-settings">
                  <CheckboxGroup
                    v-model:value="visibleKeys"
                    :options="
                      DEPARTMENT_ASSET_COLUMNS.map((column) => ({
                        value: column.key,
                        label: column.title,
                      }))
                    "
                  />
                </div>
              </template>
            </Dropdown>
          </div>
          <Alert v-if="categoryError" type="warning" :message="categoryError">
            <template #action>
              <Button size="small" @click="loadCategories"> 重试 </Button>
            </template>
          </Alert>
          <Alert v-if="errorText" type="error" :message="errorText">
            <template #action>
              <Button size="small" @click="load">重试</Button>
            </template>
          </Alert>
          <TableViewport
            :columns="columns"
            :data-source="rows"
            :loading="loading"
            :row-key="(row: DepartmentAssetsApi.Asset) => resourceIdKey(row.id)"
            :pagination="false"
            :scroll="{ x: 1505 }"
            table-layout="auto"
            :custom-row="
              (row: DepartmentAssetsApi.Asset) => ({
                onDblclick: () => openDetail(row),
              })
            "
            @change="tableChange"
          >
            <template #bodyCell="{ column, record }">
              <span v-if="column.key === 'applyId'">{{
                record.displayId
              }}</span>
              <button
                v-else-if="column.key === 'marketName'"
                class="source-asset-name"
                @click="openDetail(record)"
              >
                {{ record.marketName || '-' }}
              </button>
              <Tag v-else-if="column.key === 'marketCategoryName'">
                {{ record.marketCategoryName || '-' }}
              </Tag>
              <template v-else-if="column.key === 'deliveryTypes'">
                <Tag
                  v-for="value in record.deliveryTypes"
                  :key="value"
                  :class="`source-delivery-${value}`"
                >
                  {{ departmentDeliveryLabel(value) }}
                </Tag>
              </template>
              <span v-else-if="column.key === 'applyTime'">{{
                marketCheckTime(record.applyTime)
              }}</span>
              <Tag v-else-if="column.key === 'period'">
                {{ departmentAssetPeriod(record) }}
              </Tag>
              <Tag
                v-else-if="column.key === 'status'"
                :class="`source-status-${record.statusTone || 'default'}`"
              >
                {{ record.statusLabel || (record.status ?? '-') }}
              </Tag>
              <Tag v-else-if="column.key === 'frequency'">
                {{ record.frequency || '-' }}
              </Tag>
              <Button
                v-else-if="column.key === 'actions'"
                type="link"
                :disabled="!record.canView"
                @click="openDetail(record)"
              >
                <span
                  class="source-assets-icon"
                  :style="{ maskImage: `url(${detailIcon})` }"
                  aria-hidden="true"
                ></span>详情
              </Button>
            </template>
          </TableViewport>
          <footer class="source-assets-pagination">
            <ReferencePagination
              v-model:page="query.pageNo"
              v-model:page-size="query.pageSize"
              :total="total"
              @change="load"
            />
          </footer>
        </section>
      </template>
    </div>
  </Page>
</template>
