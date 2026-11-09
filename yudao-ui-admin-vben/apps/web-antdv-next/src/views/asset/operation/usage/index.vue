<script setup lang="ts">
import type { AssetOperationApi } from '#/api/asset/operation';

import { computed, onMounted, reactive, ref } from 'vue';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { downloadFileFromBlobPart, formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Input,
  message,
  Progress,
  Select,
  Table,
  Tag,
  Tooltip,
} from 'antdv-next';

import {
  exportAssetOperation,
  getAssetOperationPage,
  refreshAssetOperation,
} from '#/api/asset/operation';

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

const columns = [
  { title: '资产', key: 'asset', fixed: 'left' as const, width: 220 },
  { title: '类目', dataIndex: 'categoryName', key: 'category', width: 120 },
  {
    title: '热度',
    key: 'heatScore',
    dataIndex: 'heatScore',
    width: 150,
    sorter: true,
  },
  {
    title: '价值',
    key: 'valueScore',
    dataIndex: 'valueScore',
    width: 150,
    sorter: true,
    defaultSortOrder: 'descend' as const,
  },
  {
    title: '浏览 / 使用 / 申请',
    key: 'usageCount',
    dataIndex: 'usageCount',
    width: 170,
    sorter: true,
  },
  { title: '授权部门 / 收藏', key: 'grants', width: 130 },
  {
    title: '接口调用',
    key: 'apiCallCount',
    dataIndex: 'apiCallCount',
    width: 120,
    sorter: true,
  },
  {
    title: '闲置',
    key: 'idleDays',
    dataIndex: 'idleDays',
    width: 130,
    sorter: true,
  },
  { title: '运营建议', key: 'suggestion', width: 130 },
  { title: '操作', key: 'actions', width: 80, fixed: 'right' as const },
];

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
  filters.keyword = '';
  filters.assetType = undefined;
  filters.valueLevel = undefined;
  filters.suggestion = undefined;
  search();
}

function onTableChange(
  pagination: { current?: number; pageSize?: number },
  _filters: unknown,
  sorter: any,
) {
  pageNo.value = pagination.current ?? 1;
  pageSize.value = pagination.pageSize ?? pageSize.value;
  const single = Array.isArray(sorter) ? sorter[0] : sorter;
  if (single?.order) {
    sort.field = single.columnKey as AssetOperationApi.SortField;
    sort.order = single.order === 'ascend' ? 'asc' : 'desc';
  } else {
    sort.field = 'valueScore';
    sort.order = 'desc';
  }
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
</script>

<template>
  <Page
    auto-content-height
    title="资产使用与价值"
    :description="
      statDate
        ? `统计日期 ${statDate}，近 ${windowDays} 天；每日自动计算，可手动刷新。`
        : '尚未生成运营指标，可点击“刷新指标”立即计算。'
    "
  >
    <div class="operation-toolbar">
      <Input
        v-model:value="filters.keyword"
        allow-clear
        placeholder="资产名称或编码"
        class="w-56"
        @press-enter="search"
      />
      <Select
        v-model:value="filters.assetType"
        allow-clear
        placeholder="资产类型"
        class="w-32"
        :options="typeOptions"
        @change="search"
      />
      <Select
        v-model:value="filters.valueLevel"
        allow-clear
        placeholder="价值等级"
        class="w-32"
        :options="levelOptions"
        @change="search"
      />
      <Select
        v-model:value="filters.suggestion"
        allow-clear
        placeholder="运营建议"
        class="w-36"
        :options="suggestionOptions"
        @change="search"
      />
      <Button type="primary" @click="search">查询</Button>
      <Button @click="reset">重置</Button>
      <div class="flex-1"></div>
      <Button v-if="canExport" :loading="exporting" @click="exportList">
        导出清单
      </Button>
      <Button
        v-if="canRefresh"
        type="primary"
        ghost
        :loading="refreshing"
        @click="refresh"
      >
        刷新指标
      </Button>
    </div>
    <Alert
      v-if="apiStatsMissing"
      class="mb-3"
      type="warning"
      show-icon
      message="本次计算未取得数据服务的接口调用统计，接口调用按 0 计，接口类资产的热度与价值可能偏低。"
    />
    <Table
      :columns="columns"
      :data-source="rows"
      :loading="loading"
      :pagination="{
        current: pageNo,
        pageSize,
        total,
        showSizeChanger: true,
        showTotal: (count: number) => `共 ${count} 个资产`,
      }"
      :scroll="{ x: 'max-content' }"
      row-key="assetId"
      size="middle"
      @change="onTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'asset'">
          <div class="font-medium">{{ record.assetName }}</div>
          <div class="text-muted-foreground text-xs">
            {{ record.assetCode }} ·
            {{ ASSET_TYPES[record.assetType ?? -1] ?? '-' }} ·
            {{ ASSET_STATUSES[record.assetStatus ?? -1] ?? '-' }}
          </div>
        </template>
        <template v-else-if="column.key === 'category'">
          {{ record.categoryName || '未归类' }}
        </template>
        <template v-else-if="column.key === 'heatScore'">
          <Progress
            :percent="Number(record.heatScore)"
            :format="() => formatScore(record.heatScore)"
            size="small"
          />
        </template>
        <template v-else-if="column.key === 'valueScore'">
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
        <template v-else-if="column.key === 'usageCount'">
          <span class="tabular-nums">
            {{ record.browseCount }} / {{ record.usageCount }} /
            {{ record.applyCount }}
          </span>
          <div class="text-muted-foreground text-xs">
            {{ record.browseUserCount }} 人浏览 · {{ record.usageUserCount }}
            人使用
          </div>
        </template>
        <template v-else-if="column.key === 'grants'">
          <span class="tabular-nums">
            {{ record.grantDeptCount }} / {{ record.favoriteCount }}
          </span>
          <div class="text-muted-foreground text-xs">
            有效授权 {{ record.activeGrantCount }}
          </div>
        </template>
        <template v-else-if="column.key === 'apiCallCount'">
          <span class="tabular-nums">{{ record.apiCallCount }}</span>
          <div v-if="record.apiAppCount" class="text-muted-foreground text-xs">
            {{ record.apiAppCount }} 个应用
          </div>
        </template>
        <template v-else-if="column.key === 'idleDays'">
          {{ formatIdle(record.idleDays) }}
          <div class="text-muted-foreground text-xs">
            {{
              record.lastUsedTime
                ? `最近 ${formatDateTime(record.lastUsedTime)}`
                : '从未使用'
            }}
          </div>
        </template>
        <template v-else-if="column.key === 'suggestion'">
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
        <template v-else-if="column.key === 'actions'">
          <Button
            type="link"
            size="small"
            @click="trendDrawer?.open(record as AssetOperationApi.Metric)"
          >
            趋势
          </Button>
        </template>
      </template>
    </Table>
    <TrendDrawer ref="trendDrawer" />
  </Page>
</template>

<style scoped>
.operation-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}
</style>
