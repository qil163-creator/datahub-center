<script lang="ts" setup>
import type { EChartsOption } from '@vben/plugins/echarts';

import type { MetricCardItem } from '../../dashboard/shared/data';
import type { RouteTarget } from '../../dashboard/shared/route-resolver';

import type { DashboardApi } from '#/api/dashboard';

import { computed, onMounted, ref } from 'vue';

import { Card, Progress } from 'antdv-next';

import {
  getDashboardGovernanceSummary,
  getDashboardQualityTrend,
  getDashboardSafetyOverview,
  getDashboardWorkOrderStatistics,
} from '#/api/dashboard';

import ChartCard from '../../dashboard/shared/chart-card.vue';
import {
  CHART_COLORS,
  getAccessTrendOptions,
  getDonutOptions,
  getMetaChangeTrendOptions,
  getQualityTrendOptions,
  getRadarOptions,
} from '../../dashboard/shared/chart-options';
import { QUALITY_DIMENSIONS } from '../../dashboard/shared/data';
import MetricCards from '../../dashboard/shared/metric-cards.vue';
import {
  DASHBOARD_TARGETS,
  useDashboardNav,
} from '../../dashboard/shared/route-resolver';
import {
  formatCount,
  formatPercent,
  settle,
} from '../../dashboard/shared/utils';

/**
 * 治理总览：面向数据治理负责人的“治理工作做得怎么样”。
 * 覆盖元数据、数据标准、数据质量、数据安全、主数据五个治理域：
 * 核心规模指标 → 治理健康度（覆盖率 / 通过率类指标）→ 各域趋势与分布。
 * 数据来自治理域既有看板接口，无权限或服务未启动时对应卡片降级展示。
 */
defineOptions({ name: 'GovernanceOverview' });

const TREND_DAYS = 30;

const { navTo } = useDashboardNav();

const loading = ref(true);
const governance = ref<DashboardApi.GovernanceSummary>();
const safety = ref<DashboardApi.SafetySummary>();
const qualityTrend = ref<DashboardApi.QualityTrendPoint[]>();
const workOrderStats = ref<DashboardApi.WorkOrderStatistics>();

// ==================== 核心指标 ====================

const metricItems = computed<MetricCardItem[]>(() => {
  const summary = governance.value;
  const meta = summary?.meta;
  const standard = summary?.standard;
  return [
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
      key: 'standard',
      title: '标准数据元',
      icon: 'carbon:white-paper',
      color: CHART_COLORS.amber,
      value: standard?.totalCount,
      unit: '个',
      hint: `已发布 ${formatCount(standard?.publishedCount)} · 已落标 ${formatCount(standard?.mappedElementCount)}`,
      target: DASHBOARD_TARGETS.standardElement,
    },
    {
      key: 'qualityRule',
      title: '质量规则',
      icon: 'carbon:rule',
      color: CHART_COLORS.blue,
      value: summary?.qualityRuleCount,
      unit: '条',
      hint: `启用 ${formatCount(summary?.qualityRuleEnabledCount)} · 检核任务 ${formatCount(summary?.qualityTaskCount)}`,
      target: DASHBOARD_TARGETS.qualityRule,
    },
    {
      key: 'sensitive',
      title: '敏感字段',
      icon: 'carbon:security',
      color: CHART_COLORS.red,
      value: safety.value?.sensitiveColumnCount,
      unit: '个',
      hint: `待确认打标 ${formatCount(safety.value?.pendingTagCount)} · 白名单 ${formatCount(safety.value?.activeWhitelistCount)}`,
    },
    {
      key: 'master',
      title: '主数据模型',
      icon: 'carbon:data-share',
      color: CHART_COLORS.teal,
      value: summary?.masterModelCount,
      unit: '个',
      hint: `已发布 ${formatCount(summary?.masterModelReleasedCount)} · 派发任务 ${formatCount(summary?.masterDistributeCount)}`,
      target: DASHBOARD_TARGETS.masterModel,
    },
    {
      key: 'workOrder',
      title: '待处理质量工单',
      icon: 'carbon:task-complete',
      color: '#f97316',
      value: summary?.pendingWorkOrderCount,
      unit: '单',
      hint: `未处理告警 ${formatCount(summary?.qualityAlert?.unprocessedCount)} · 已处理 ${formatCount(summary?.qualityAlert?.processedCount)}`,
      target: DASHBOARD_TARGETS.qualityWorkOrder,
    },
  ];
});

// ==================== 治理健康度 ====================

interface HealthItem {
  key: string;
  label: string;
  /** 0~100；undefined 表示不可用 */
  percent?: null | number;
  description: string;
  target?: RouteTarget;
}

function healthColor(percent?: null | number) {
  if (percent === undefined || percent === null) {
    return CHART_COLORS.slate;
  }
  if (percent >= 90) {
    return CHART_COLORS.green;
  }
  return percent >= 70 ? CHART_COLORS.amber : CHART_COLORS.red;
}

const healthItems = computed<HealthItem[]>(() => {
  const summary = governance.value;
  const meta = summary?.meta;
  return [
    {
      key: 'comment',
      label: '表注释覆盖率',
      percent: meta?.commentCoverage,
      description: '有注释的表 / 全部表',
      target: DASHBOARD_TARGETS.metaObject,
    },
    {
      key: 'owner',
      label: '业务认责覆盖率',
      percent: meta?.ownerCoverage,
      description: '已指定业务负责人的表 / 全部表',
      target: DASHBOARD_TARGETS.metaObject,
    },
    {
      key: 'collect',
      label: '元数据采集成功率',
      percent: meta?.collectSuccessRate,
      description: `近 30 天执行 ${formatCount(meta?.collectInstanceCount)} 次`,
      target: DASHBOARD_TARGETS.metaCollectTask,
    },
    {
      key: 'mapping',
      label: '标准落标率',
      percent: summary?.standard?.mappingRate,
      description: `已落标数据元 ${formatCount(summary?.standard?.mappedElementCount)} 个`,
      target: DASHBOARD_TARGETS.standardElement,
    },
    {
      key: 'quality',
      label: '质量检核通过率',
      percent: summary?.qualityReport?.avgPassRate,
      description: `累计检核 ${formatCount(summary?.qualityReport?.totalCount)} 次`,
      target: DASHBOARD_TARGETS.qualityReport,
    },
    {
      key: 'static',
      label: '静态脱敏成功率',
      percent: safety.value?.staticRunSuccessRate,
      description: `近 30 天执行 ${formatCount(safety.value?.staticRunCount30d)} 次`,
    },
  ];
});

// ==================== 图表 ====================

/** 近 30 天元数据变更趋势 */
const metaTrendOptions = computed<EChartsOption | undefined>(() => {
  const trend = governance.value?.meta?.changeTrend;
  return trend && trend.length > 0
    ? getMetaChangeTrendOptions(trend)
    : undefined;
});

/** 近 30 天质量检核趋势 */
const qualityTrendOptions = computed<EChartsOption | undefined>(() => {
  const trend = qualityTrend.value;
  return trend && trend.length > 0 ? getQualityTrendOptions(trend) : undefined;
});

/** 质量六维通过率雷达 */
const qualityRadarOptions = computed<EChartsOption | undefined>(() => {
  const dimensions = governance.value?.qualityDimensions;
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
  const summary = workOrderStats.value?.statusSummary;
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
  const levels = safety.value?.levelDistribution?.filter(
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

/** 近 30 天敏感数据访问趋势 */
const accessTrendOptions = computed<EChartsOption | undefined>(() => {
  const trend = safety.value?.accessTrend;
  return trend && trend.length > 0 ? getAccessTrendOptions(trend) : undefined;
});

const metaTrendExtra = computed(() => {
  const meta = governance.value?.meta;
  return meta
    ? `库 ${formatCount(meta.dbCount)} · 表 ${formatCount(meta.tableCount)} · 待确认删除 ${formatCount(meta.pendingDeleteTableCount)}`
    : '';
});

const qualityTrendExtra = computed(() => {
  const report = governance.value?.qualityReport;
  return report
    ? `累计通过 ${formatCount(report.passCount)} · 未通过 ${formatCount(report.failCount)}`
    : '';
});

const qualityRadarExtra = computed(() => {
  const alert = governance.value?.qualityAlert;
  if (!alert) {
    return '';
  }
  return `告警：危险 ${formatCount(alert.dangerCount)} · 严重 ${formatCount(alert.seriousCount)} · 一般 ${formatCount(alert.generalCount)}`;
});

const workOrderExtra = computed(() => {
  const total = workOrderStats.value?.statusSummary
    ? Object.values(workOrderStats.value.statusSummary).reduce(
        (sum, count) => sum + count,
        0,
      )
    : undefined;
  return `近 ${TREND_DAYS} 天工单 ${formatCount(total)} 单`;
});

const safetyLevelExtra = computed(() => {
  const dashboard = safety.value;
  return dashboard
    ? `已确认打标 ${formatCount(dashboard.confirmedTagCount)} · 识别规则 ${formatCount(dashboard.identifyRuleCount)}`
    : '';
});

const accessTrendExtra = computed(() => {
  const dashboard = safety.value;
  return dashboard
    ? `访问 ${formatCount(dashboard.accessCount30d)} 次 · 明文豁免 ${formatCount(dashboard.exemptCount30d)} 次`
    : '';
});

// ==================== 加载 ====================

async function loadData() {
  loading.value = true;
  try {
    const [governanceRes, safetyRes, qualityTrendRes, workOrderRes] =
      await Promise.all([
        settle(getDashboardGovernanceSummary()),
        settle(getDashboardSafetyOverview()),
        settle(getDashboardQualityTrend(TREND_DAYS)),
        settle(getDashboardWorkOrderStatistics(TREND_DAYS)),
      ]);
    governance.value = governanceRes;
    safety.value = safetyRes;
    qualityTrend.value = qualityTrendRes;
    workOrderStats.value = workOrderRes;
  } finally {
    loading.value = false;
  }
}

onMounted(loadData);
</script>

<template>
  <div class="flex flex-col gap-5 p-5">
    <MetricCards :items="metricItems" :loading="loading" @navigate="navTo" />

    <Card title="治理健康度">
      <template #extra>
        <span class="text-muted-foreground text-xs">
          覆盖率 / 通过率类指标，点击可查看明细
        </span>
      </template>
      <div
        class="grid grid-cols-2 gap-x-8 gap-y-5 md:grid-cols-3 xl:grid-cols-6"
      >
        <div
          v-for="item in healthItems"
          :key="item.key"
          :class="{ 'cursor-pointer': !!item.target }"
          @click="item.target && navTo(item.target)"
        >
          <div class="text-muted-foreground truncate text-sm">
            {{ item.label }}
          </div>
          <div class="mt-2 text-xl font-semibold tabular-nums">
            {{ formatPercent(item.percent) }}
          </div>
          <Progress
            v-if="item.percent != null"
            :percent="item.percent"
            :show-info="false"
            :stroke-color="healthColor(item.percent)"
            class="mt-1"
            size="small"
          />
          <div class="text-muted-foreground mt-1 truncate text-xs">
            {{ item.description }}
          </div>
        </div>
      </div>
    </Card>

    <div class="grid grid-cols-1 gap-5 md:grid-cols-2 xl:grid-cols-3">
      <ChartCard
        :extra="metaTrendExtra"
        :loading="loading"
        :options="metaTrendOptions"
        empty-text="近 30 天无元数据变更"
        title="元数据变更趋势（近 30 天）"
      />
      <ChartCard
        :extra="qualityTrendExtra"
        :loading="loading"
        :options="qualityTrendOptions"
        empty-text="近 30 天无质量检核记录"
        title="质量检核趋势（近 30 天）"
      />
      <ChartCard
        :extra="qualityRadarExtra"
        :loading="loading"
        :options="qualityRadarOptions"
        empty-text="暂无质量检核结果"
        title="质量六维通过率"
      />
      <ChartCard
        :extra="safetyLevelExtra"
        :loading="loading"
        :options="safetyLevelOptions"
        empty-text="暂无已确认的敏感数据分级"
        title="敏感数据分级分布"
      />
      <ChartCard
        :extra="accessTrendExtra"
        :loading="loading"
        :options="accessTrendOptions"
        empty-text="近 30 天无敏感数据访问"
        title="敏感数据访问趋势（近 30 天）"
      />
      <ChartCard
        :extra="workOrderExtra"
        :loading="loading"
        :options="workOrderStatusOptions"
        empty-text="暂无质量工单"
        title="质量工单状态分布"
      />
    </div>
  </div>
</template>
