<script lang="ts" setup>
import type { EChartsOption } from '@vben/plugins/echarts';

import type { NameValue } from '../shared/chart-options';
import type { AnalyticsOverview, MetricCardItem } from '../shared/data';

import { computed, onMounted, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Button, Tooltip } from 'antdv-next';

import {
  CHART_COLORS,
  getDonutOptions,
  getRadarOptions,
  getRankBarOptions,
} from '../shared/chart-options';
import {
  loadAnalyticsOverview,
  loadAnalyticsTrends,
  QUALITY_DIMENSIONS,
} from '../shared/data';
import { DASHBOARD_TARGETS, useDashboardNav } from '../shared/route-resolver';
import { formatCount, formatPercent } from '../shared/utils';
import ChartCard from './modules/chart-card.vue';
import MetricCards from './modules/metric-cards.vue';
import TrendCard from './modules/trend-card.vue';

/**
 * 数据总览：面向平台管理者的“平台运行得怎么样”。
 * 核心指标（数据源 / 元数据 / 模型 / 标准 / 资产 / 服务）→ 运行趋势 → 各域分布与排行。
 * 数据来自各业务域的看板汇总接口，无权限或服务未启动时对应卡片降级。
 */
defineOptions({ name: 'DashboardAnalytics' });

const { navTo } = useDashboardNav();

const loading = ref(true);
const trendLoading = ref(false);
const days = ref(7);
const overview = ref<AnalyticsOverview>({});

/** 核心指标 */
const metricItems = computed<MetricCardItem[]>(() => {
  const { apiStats, asset, dataforge, governance, model } = overview.value;
  const meta = governance?.meta;
  const standard = governance?.standard;
  return [
    {
      key: 'database',
      title: '数据源',
      icon: 'carbon:ibm-cloud-databases',
      color: CHART_COLORS.blue,
      value: dataforge?.databaseCount,
      unit: '个',
      hint: `集成任务 ${formatCount(dataforge?.accessJobCount)} · 开发任务 ${formatCount(dataforge?.devTaskCount)}`,
      target: DASHBOARD_TARGETS.database,
    },
    {
      key: 'meta',
      title: '元数据表',
      icon: 'carbon:catalog',
      color: CHART_COLORS.violet,
      value: meta?.tableCount,
      unit: '张',
      hint: `字段 ${formatCount(meta?.columnCount)} · 已发布 ${formatCount(meta?.publishedTableCount)}`,
      target: DASHBOARD_TARGETS.metaObject,
    },
    {
      key: 'model',
      title: '数据模型',
      icon: 'carbon:model-alt',
      color: CHART_COLORS.teal,
      value: model?.modelCount,
      unit: '个',
      hint: `已发布 ${formatCount(model?.publishedCount)} · 已物化 ${formatCount(model?.materializedCount)}`,
      target: DASHBOARD_TARGETS.logicModel,
    },
    {
      key: 'standard',
      title: '标准数据元',
      icon: 'carbon:white-paper',
      color: CHART_COLORS.amber,
      value: standard?.totalCount,
      unit: '个',
      hint: `已发布 ${formatCount(standard?.publishedCount)} · 落标率 ${formatPercent(standard?.mappingRate)}`,
      target: DASHBOARD_TARGETS.standardElement,
    },
    {
      key: 'asset',
      title: '数据资产',
      icon: 'carbon:carbon-for-ibm-product',
      color: '#f97316',
      value: asset?.total,
      unit: '项',
      hint: `类目 ${formatCount(asset?.categories?.length)} · 主题域 ${formatCount(asset?.subjectDomains?.length)}`,
      target: DASHBOARD_TARGETS.dataAsset,
    },
    {
      key: 'api',
      title: '服务 API',
      icon: 'carbon:api-1',
      color: CHART_COLORS.green,
      value: apiStats?.apiCount,
      unit: '个',
      hint: `在线 ${formatCount(apiStats?.onlineApiCount)} · 近 ${days.value} 天调用 ${formatCount(apiStats?.totalCount)} 次`,
      target: DASHBOARD_TARGETS.dataApi,
    },
  ];
});

function toNameValues(
  list: undefined | { count?: number; label?: string; name?: string }[],
): NameValue[] | undefined {
  if (!list) {
    return undefined;
  }
  const items = list
    .map((item) => ({
      name: item.label ?? item.name ?? '未命名',
      value: item.count ?? 0,
    }))
    .filter((item) => item.value > 0);
  return items.length > 0 ? items : undefined;
}

/** 数据源类型分布 */
const databaseTypeOptions = computed<EChartsOption | undefined>(() => {
  const items = toNameValues(overview.value.dataforge?.databaseTypeDistribution);
  return items ? getDonutOptions(items, '数据源类型') : undefined;
});

/** 资产类目分布 */
const assetCategoryOptions = computed<EChartsOption | undefined>(() => {
  const items = toNameValues(overview.value.asset?.categories);
  return items ? getDonutOptions(items, '资产类目') : undefined;
});

/** API 调用排行 */
const apiRankOptions = computed<EChartsOption | undefined>(() => {
  const list = overview.value.apiStats?.topApis
    ?.filter((item) => (item.value ?? 0) > 0)
    .map((item) => ({ name: item.name ?? '未命名', value: item.value ?? 0 }));
  return list && list.length > 0
    ? getRankBarOptions(list.slice(0, 10), '调用次数')
    : undefined;
});

/** 质量六维通过率雷达 */
const qualityRadarOptions = computed<EChartsOption | undefined>(() => {
  const dimensions = overview.value.governance?.qualityDimensions;
  if (!dimensions || dimensions.length === 0) {
    return undefined;
  }
  const byKey = new Map(dimensions.map((item) => [item.qualityDim, item]));
  return getRadarOptions(
    QUALITY_DIMENSIONS.map((dim) => ({ name: dim.label })),
    QUALITY_DIMENSIONS.map((dim) => byKey.get(dim.key)?.passRate ?? 0),
    '通过率',
    CHART_COLORS.teal,
  );
});

const WORK_ORDER_STATUS: Record<string, { color: string; label: string }> = {
  PENDING: { color: CHART_COLORS.amber, label: '待处理' },
  PROCESSED: { color: CHART_COLORS.green, label: '已处理' },
  TRANSFERRED: { color: CHART_COLORS.slate, label: '已转办' },
};

/** 质量工单状态分布 */
const workOrderStatusOptions = computed<EChartsOption | undefined>(() => {
  const summary = overview.value.workOrderStats?.statusSummary;
  if (!summary) {
    return undefined;
  }
  const entries = Object.entries(summary).filter(([, count]) => count > 0);
  if (entries.length === 0) {
    return undefined;
  }
  return getDonutOptions(
    entries.map(([status, count]) => ({
      name: WORK_ORDER_STATUS[status]?.label ?? status,
      value: count,
    })),
    '工单状态',
    entries.map(
      ([status]) => WORK_ORDER_STATUS[status]?.color ?? CHART_COLORS.blue,
    ),
  );
});

/** 敏感数据分级分布 */
const safetyLevelOptions = computed<EChartsOption | undefined>(() => {
  const levels = overview.value.safety?.levelDistribution?.filter(
    (item) => (item.count ?? 0) > 0,
  );
  if (!levels || levels.length === 0) {
    return undefined;
  }
  const hasCustomColor = levels.every((item) => !!item.color);
  return getDonutOptions(
    levels.map((item) => ({
      name: item.levelName ?? `级别 ${item.levelId}`,
      value: item.count ?? 0,
    })),
    '数据分级',
    hasCustomColor ? levels.map((item) => item.color as string) : undefined,
  );
});

const databaseTypeExtra = computed(() => {
  const dataforge = overview.value.dataforge;
  return dataforge
    ? `数据源 ${formatCount(dataforge.databaseCount)} 个 · 已发布同步任务 ${formatCount(dataforge.accessReleasedCount)}`
    : '';
});

const apiRankExtra = computed(() => {
  const stats = overview.value.apiStats;
  return stats
    ? `近 ${days.value} 天成功率 ${formatPercent(stats.successRate)} · 平均耗时 ${formatCount(stats.avgDurationMs)} ms`
    : '';
});

const qualityRadarExtra = computed(() => {
  const governance = overview.value.governance;
  if (!governance) {
    return '';
  }
  const parts = [
    `累计检核 ${formatCount(governance.qualityReport?.totalCount)} 次`,
    `平均通过率 ${formatPercent(governance.qualityReport?.avgPassRate)}`,
  ];
  if (governance.qualityAlert?.unprocessedCount !== undefined) {
    parts.push(`未处理告警 ${governance.qualityAlert.unprocessedCount}`);
  }
  return parts.join(' · ');
});

const workOrderExtra = computed(() => {
  const { governance, workOrderStats } = overview.value;
  const total = workOrderStats?.statusSummary
    ? Object.values(workOrderStats.statusSummary).reduce((a, b) => a + b, 0)
    : undefined;
  return `质量规则 ${formatCount(governance?.qualityRuleCount)} 条 · 近 ${days.value} 天工单 ${formatCount(total)} 单`;
});

const safetyExtra = computed(() => {
  const safety = overview.value.safety;
  return safety
    ? `敏感字段 ${formatCount(safety.sensitiveColumnCount)} · 近 30 天访问 ${formatCount(safety.accessCount30d)} 次`
    : '';
});

async function loadData() {
  loading.value = true;
  try {
    overview.value = await loadAnalyticsOverview(days.value);
  } finally {
    loading.value = false;
  }
}

/** 切换天数：只刷新趋势类统计 */
watch(days, async () => {
  if (loading.value) {
    return;
  }
  trendLoading.value = true;
  try {
    const trends = await loadAnalyticsTrends(days.value);
    overview.value = { ...overview.value, ...trends };
  } finally {
    trendLoading.value = false;
  }
});

onMounted(loadData);
</script>

<template>
  <div class="flex flex-col gap-5 p-5">
    <div class="flex items-center justify-between">
      <h2 class="text-lg font-semibold">平台数据总览</h2>
      <Tooltip title="刷新全部统计">
        <Button :loading="loading" @click="loadData">
          <template #icon>
            <IconifyIcon icon="lucide:refresh-cw" class="size-4" />
          </template>
          刷新
        </Button>
      </Tooltip>
    </div>

    <MetricCards :items="metricItems" :loading="loading" @navigate="navTo" />

    <TrendCard
      v-model:days="days"
      :api-stats="overview.apiStats"
      :loading="loading || trendLoading"
      :meta="overview.governance?.meta"
      :quality-trend="overview.qualityTrend"
      :safety="overview.safety"
      :work-order-stats="overview.workOrderStats"
    />

    <div class="grid grid-cols-1 gap-5 md:grid-cols-2 xl:grid-cols-3">
      <ChartCard
        :extra="databaseTypeExtra"
        :loading="loading"
        :options="databaseTypeOptions"
        empty-text="暂无数据源"
        title="数据源类型分布"
      />
      <ChartCard
        :loading="loading"
        :options="assetCategoryOptions"
        empty-text="暂无资产类目数据"
        title="资产类目分布"
      />
      <ChartCard
        :extra="apiRankExtra"
        :loading="loading || trendLoading"
        :options="apiRankOptions"
        empty-text="暂无 API 调用记录"
        title="API 调用排行 TOP10"
      />
      <ChartCard
        :extra="qualityRadarExtra"
        :loading="loading"
        :options="qualityRadarOptions"
        empty-text="暂无质量检核结果"
        title="质量六维通过率"
      />
      <ChartCard
        :extra="workOrderExtra"
        :loading="loading || trendLoading"
        :options="workOrderStatusOptions"
        empty-text="暂无质量工单"
        title="质量工单状态分布"
      />
      <ChartCard
        :extra="safetyExtra"
        :loading="loading"
        :options="safetyLevelOptions"
        empty-text="暂无已确认的敏感数据分级"
        title="敏感数据分级分布"
      />
    </div>
  </div>
</template>
