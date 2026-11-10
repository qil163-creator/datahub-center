<script setup lang="ts">
import type { AssetOperationApi } from '#/api/asset/operation';

import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { downloadFileFromBlobPart, formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  message,
  Progress,
  Tag,
  Tooltip,
} from 'antdv-next';

import { NativeVxeButton, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import { useVbenForm } from '#/adapter/form';
import {
  exportAssetOperation,
  getAssetOperationPage,
  refreshAssetOperation,
} from '#/api/asset/operation';
import ListPagination from '#/components/list-pagination/index.vue';

import {
  ASSET_STATUSES,
  ASSET_TYPES,
  formatIdle,
  formatScore,
  SUGGESTIONS,
  VALUE_LEVELS,
} from '../shared';
import TrendDrawer from './modules/trend-drawer.vue';

defineOptions({ name: 'AssetOperationUsage' });

const { hasAccessByCodes } = useAccess();
const canRefresh = computed(() =>
  hasAccessByCodes(['asset:operation:refresh']),
);
const canExport = computed(() => hasAccessByCodes(['asset:operation:export']));

const filters = reactive<{
  assetType?: number;
  keyword: string;
  suggestion?: AssetOperationApi.Suggestion;
  valueLevel?: AssetOperationApi.ValueLevel;
}>({ keyword: '' });
const sort = reactive<{
  field: AssetOperationApi.SortField;
  order: 'asc' | 'desc';
}>({ field: 'valueScore', order: 'desc' });
const rows = ref<AssetOperationApi.Metric[]>([]);
const total = ref(0);
const pageNo = ref(1);
const pageSize = ref(20);
const loading = ref(false);
const refreshing = ref(false);
const exporting = ref(false);
const showFilters = ref(true);
const trendDrawer = ref<InstanceType<typeof TrendDrawer>>();
let seq = 0;

const statDate = computed(() => rows.value[0]?.statDate);
const windowDays = computed(() => rows.value[0]?.windowDays ?? 30);
const apiStatsMissing = computed(() =>
  rows.value.some((row) => row.apiStatsAvailable === false),
);

const levelOptions = Object.entries(VALUE_LEVELS).map(([value, item]) => ({
  label: item.label,
  value,
}));
const suggestionOptions = Object.entries(SUGGESTIONS).map(([value, item]) => ({
  label: item.label,
  value,
}));
const typeOptions = Object.entries(ASSET_TYPES).map(([value, label]) => ({
  label,
  value: Number(value),
}));

const [FilterForm, filterFormApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' }, labelWidth: 120 },
  layout: 'horizontal',
  wrapperClass: 'grid-cols-1 md:grid-cols-2 lg:grid-cols-3',
  showCollapseButton: true,
  submitOnEnter: true,
  resetButtonOptions: { show: false },
  submitButtonOptions: { show: false },
  schema: [
    { fieldName: 'keyword', label: '资产名称或编码', component: 'Input', componentProps: { allowClear: true, placeholder: '请输入资产名称或编码' } },
    { fieldName: 'assetType', label: '资产类型', component: 'Select', componentProps: { allowClear: true, options: typeOptions, placeholder: '请选择资产类型' } },
    { fieldName: 'valueLevel', label: '价值等级', component: 'Select', componentProps: { allowClear: true, options: levelOptions, placeholder: '请选择价值等级' } },
    { fieldName: 'suggestion', label: '运营建议', component: 'Select', componentProps: { allowClear: true, options: suggestionOptions, placeholder: '请选择运营建议' } },
  ],
  handleValuesChange(values) {
    filters.keyword = values.keyword ?? '';
    filters.assetType = values.assetType;
    filters.valueLevel = values.valueLevel;
    filters.suggestion = values.suggestion;
  },
  handleSubmit: search,
  handleReset: reset,
});

const [Grid, gridApi] = useVbenVxeGrid<AssetOperationApi.Metric>({
  gridOptions: {
    columns: [
      { title: '编号', field: 'sequence', fixed: 'left' as const, width: 70 },
      { title: '资产', field: 'asset', align: 'left' as const, fixed: 'left' as const, minWidth: 220 },
      { title: '类目', field: 'category', minWidth: 120 },
      { title: '热度', field: 'heatScore', minWidth: 150, sortable: true },
      { title: '价值', field: 'valueScore', minWidth: 150, sortable: true },
      { title: '浏览 / 使用 / 申请', field: 'usageCount', minWidth: 170, sortable: true },
      { title: '授权部门 / 收藏', field: 'grants', minWidth: 130 },
      { title: '接口调用', field: 'apiCallCount', minWidth: 120, sortable: true },
      { title: '闲置', field: 'idleDays', minWidth: 130, sortable: true },
      { title: '运营建议', field: 'suggestion', minWidth: 130 },
      { title: '操作', field: 'actions', width: 100, fixed: 'right' as const, showOverflow: false },
    ].map((column) => ({ ...column, slots: { default: 'body' } })),
    data: [],
    height: 'auto',
    pagerConfig: { enabled: false },
    proxyConfig: { enabled: false },
    rowConfig: { keyField: 'assetId', isHover: true },
    scrollbarConfig: { height: 6, width: 6 },
    showOverflow: false,
    sortConfig: { remote: true, defaultSort: { field: 'valueScore', order: 'desc' } },
    toolbarConfig: { refresh: true, refreshOptions: { queryMethod: () => load() }, custom: true, zoom: false },
  },
  gridEvents: {
    sortChange: ({ field, order }: { field: string; order: null | string }) => onSortChange(field, order),
  },
});
watch(loading, (value) => gridApi.setLoading(value), { immediate: true });

async function load() {
  const current = ++seq;
  loading.value = true;
  try {
    const page = await getAssetOperationPage({
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      keyword: filters.keyword.trim() || undefined,
      assetType: filters.assetType,
      valueLevel: filters.valueLevel,
      suggestion: filters.suggestion,
      sortField: sort.field,
      sortOrder: sort.order,
    });
    if (current !== seq) return;
    rows.value = page.list;
    total.value = page.total;
  } finally {
    if (current === seq) loading.value = false;
  }
}

function search() {
  pageNo.value = 1;
  void load();
}

function reset() {
  if (filterFormApi.isMounted) void filterFormApi.reset();
  filters.keyword = '';
  filters.assetType = undefined;
  filters.valueLevel = undefined;
  filters.suggestion = undefined;
  search();
}

function onSortChange(field: string, order: null | string) {
  const sortableFields: AssetOperationApi.SortField[] = [
    'heatScore', 'valueScore', 'usageCount', 'apiCallCount', 'idleDays',
  ];
  if ((order === 'asc' || order === 'desc') && sortableFields.includes(field as AssetOperationApi.SortField)) {
    sort.field = field as AssetOperationApi.SortField;
    sort.order = order;
  } else {
    sort.field = 'valueScore';
    sort.order = 'desc';
  }
  void load();
}

function onPageChange(page: number, size: number) {
  pageNo.value = page;
  pageSize.value = size;
  void load();
}

async function refresh() {
  if (!canRefresh.value || refreshing.value) return;
  refreshing.value = true;
  try {
    const count = await refreshAssetOperation();
    message.success(`运营指标已重新计算，共 ${count} 个资产`);
    pageNo.value = 1;
    await load();
  } finally {
    refreshing.value = false;
  }
}

async function exportList() {
  if (!canExport.value || exporting.value) return;
  exporting.value = true;
  try {
    const data = await exportAssetOperation({
      keyword: filters.keyword.trim() || undefined,
      assetType: filters.assetType,
      valueLevel: filters.valueLevel,
      suggestion: filters.suggestion,
      sortField: sort.field,
      sortOrder: sort.order,
    });
    downloadFileFromBlobPart({ fileName: '资产盘点清单.xls', source: data });
  } finally {
    exporting.value = false;
  }
}

onMounted(load);
onBeforeUnmount(() => { seq++; });

function syncFilterValues() {
  if (filterFormApi.isMounted) void filterFormApi.setValues({ ...filters });
}
</script>

<template>
  <Page auto-content-height content-class="flex min-h-0 flex-col gap-4 overflow-hidden">
    <FilterForm
      v-show="showFilters"
      class="shrink-0 rounded-lg bg-background p-4"
      @vue:mounted="syncFilterValues"
    >
      <template #reset-before>
        <Button @click="reset">
          <template #icon><IconifyIcon icon="lucide:rotate-ccw" /></template>重置
        </Button>
        <Button type="primary" @click="filterFormApi.validateAndSubmit()">
          <template #icon><IconifyIcon icon="lucide:search" /></template>查询
        </Button>
      </template>
    </FilterForm>
    <section class="operation-results">
      <div class="operation-description">
        {{ statDate ? `统计日期 ${statDate}，近 ${windowDays} 天；每日自动计算，可手动刷新。` : '运营指标每日自动计算，也可手动刷新。' }}
      </div>
    <Alert
      v-if="apiStatsMissing"
      class="mb-3"
      type="warning"
      show-icon
      message="本次计算未取得数据服务的接口调用统计，接口调用按 0 计，接口类资产的热度与价值可能偏低。"
    />
      <Grid class="operation-grid subtle-grid-scrollbar" table-title="资产使用与价值列表" :table-data="rows">
        <template #toolbar-tools>
          <Button v-if="canExport" :loading="exporting" @click="exportList">
            <template #icon><IconifyIcon icon="lucide:download" /></template>导出清单
          </Button>
          <Button v-if="canRefresh" type="primary" :loading="refreshing" @click="refresh">
            <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>刷新指标
          </Button>
          <NativeVxeButton class="ml-2" aria-label="显示或隐藏筛选" title="搜索" icon="vxe-icon-search" circle :status="showFilters ? 'primary' : undefined" :aria-pressed="showFilters" @click="showFilters = !showFilters" />
        </template>
      <template #body="{ column, row: record, rowIndex }">
        <template v-if="column.field === 'sequence'">
          {{ (pageNo - 1) * pageSize + rowIndex + 1 }}
        </template>
        <template v-else-if="column.field === 'asset'">
          <div class="font-medium">{{ record.assetName }}</div>
          <div class="text-muted-foreground text-xs">
            {{ record.assetCode }} ·
            {{ ASSET_TYPES[record.assetType ?? -1] ?? '-' }} ·
            {{ ASSET_STATUSES[record.assetStatus ?? -1] ?? '-' }}
          </div>
        </template>
        <template v-else-if="column.field === 'category'">
          {{ record.categoryName || '未归类' }}
        </template>
        <template v-else-if="column.field === 'heatScore'">
          <Progress
            :percent="Number(record.heatScore)"
            :format="() => formatScore(record.heatScore)"
            size="small"
          />
        </template>
        <template v-else-if="column.field === 'valueScore'">
          <span class="mr-2 tabular-nums">{{
            formatScore(record.valueScore)
          }}</span>
          <Tooltip
            :title="
              VALUE_LEVELS[record.valueLevel as AssetOperationApi.ValueLevel]
                ?.hint
            "
          >
            <Tag
              :color="
                VALUE_LEVELS[record.valueLevel as AssetOperationApi.ValueLevel]
                  ?.color
              "
            >
              {{
                VALUE_LEVELS[record.valueLevel as AssetOperationApi.ValueLevel]
                  ?.label ?? record.valueLevel
              }}
            </Tag>
          </Tooltip>
        </template>
        <template v-else-if="column.field === 'usageCount'">
          <span class="tabular-nums">
            {{ record.browseCount }} / {{ record.usageCount }} /
            {{ record.applyCount }}
          </span>
          <div class="text-muted-foreground text-xs">
            {{ record.browseUserCount }} 人浏览 · {{ record.usageUserCount }}
            人使用
          </div>
        </template>
        <template v-else-if="column.field === 'grants'">
          <span class="tabular-nums">
            {{ record.grantDeptCount }} / {{ record.favoriteCount }}
          </span>
          <div class="text-muted-foreground text-xs">
            有效授权 {{ record.activeGrantCount }}
          </div>
        </template>
        <template v-else-if="column.field === 'apiCallCount'">
          <span class="tabular-nums">{{ record.apiCallCount }}</span>
          <div v-if="record.apiAppCount" class="text-muted-foreground text-xs">
            {{ record.apiAppCount }} 个应用
          </div>
        </template>
        <template v-else-if="column.field === 'idleDays'">
          {{ formatIdle(record.idleDays) }}
          <div class="text-muted-foreground text-xs">
            {{
              record.lastUsedTime
                ? `最近 ${formatDateTime(record.lastUsedTime)}`
                : '从未使用'
            }}
          </div>
        </template>
        <template v-else-if="column.field === 'suggestion'">
          <Tag
            :color="
              SUGGESTIONS[record.suggestion as AssetOperationApi.Suggestion]
                ?.color
            "
          >
            {{
              SUGGESTIONS[record.suggestion as AssetOperationApi.Suggestion]
                ?.label ?? record.suggestion
            }}
          </Tag>
          <div
            v-if="record.feedbackCount"
            class="text-xs text-[var(--ant-color-warning)]"
          >
            {{ record.feedbackCount }} 条问题反馈
          </div>
        </template>
        <template v-else-if="column.field === 'actions'">
          <TableAction
            :actions="[{ label: '趋势', icon: 'lucide:chart-no-axes-combined', onClick: () => trendDrawer?.open(record as AssetOperationApi.Metric) }]"
          />
        </template>
      </template>
        <template #pager>
          <ListPagination :current="pageNo" :page-size="pageSize" :total="total" aria-label="资产使用与价值分页" @change="onPageChange" />
        </template>
      </Grid>
    </section>
    <TrendDrawer ref="trendDrawer" />
  </Page>
</template>

<style scoped>
.operation-results {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  padding: 12px 14px 20px;
  overflow: hidden;
  background: hsl(var(--background));
  border-radius: var(--radius);
}

.operation-description {
  padding: 0 0 8px;
  font-size: 13px;
  color: hsl(var(--muted-foreground));
}

.operation-grid {
  flex: 1;
  min-height: 0;
}
</style>
