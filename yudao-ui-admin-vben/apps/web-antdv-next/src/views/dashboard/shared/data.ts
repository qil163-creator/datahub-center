import type { RouteTarget } from './route-resolver';

import type { DataAssetApi } from '#/api/asset/dataasset';
import type { BpmTaskApi } from '#/api/bpm/task';
import type { DashboardApi } from '#/api/dashboard';
import type { DataforgeAccessApi } from '#/api/dataforge/access';
import type { DataforgeDevApi } from '#/api/dataforge/dev';
import type { ModelHomeApi } from '#/api/governance/model-home';
import type { QualityWorkOrderApi } from '#/api/governance/quality/workorder';
import type { SupplyApiLogApi } from '#/api/supply/apilog';
import type { SystemNoticeApi } from '#/api/system/notice';
import type { SystemUserProfileApi } from '#/api/system/user/profile';

import { formatDateTime } from '@vben/utils';

import {
  getDashboardAccessTaskPage,
  getDashboardApiLogStatistics,
  getDashboardAssetFacets,
  getDashboardDataforgeSummary,
  getDashboardDevRecordPage,
  getDashboardGovernanceSummary,
  getDashboardModelOverview,
  getDashboardMyWorkOrderList,
  getDashboardNoticePage,
  getDashboardQualityTrend,
  getDashboardSafetyOverview,
  getDashboardTodoTaskPage,
  getDashboardUnreadNotifyCount,
  getDashboardUserProfile,
  getDashboardWorkOrderStatistics,
} from '#/api/dashboard';

import { DASHBOARD_TARGETS } from './route-resolver';
import { formatDuration, formatPercent, settle } from './utils';

/**
 * 首页看板的数据组装层：把各业务域的看板接口结果拼成页面需要的视图模型。
 * 每个域一个请求，全部并行发出，局部失败可降级（失败字段为 undefined，页面展示 “--” 或空态）。
 */

// ==================== 视图模型 ====================

/** 数据链路阶段（数据源 → 集成 → 元数据 → 建模 → 资产 → 服务） */
export interface ChainStage {
  key: string;
  title: string;
  icon: string;
  color: string;
  /** 主数值；undefined 表示该域不可用 / 无权限 */
  value?: number;
  /** 补充说明，如 “已发布 12” */
  hint: string;
  target: RouteTarget;
}

export type HealthLevel = 'bad' | 'good' | 'none' | 'warn';

/** 运行健康指标 */
export interface HealthMetric {
  key: string;
  title: string;
  /** 已格式化的展示值 */
  value: string;
  level: HealthLevel;
  description: string;
  target?: RouteTarget;
}

export type TodoKind = 'approval' | 'collectFail' | 'workOrder';

/** 待办条目 */
export interface TodoItem {
  key: string;
  kind: TodoKind;
  title: string;
  description: string;
  time?: string;
  target: RouteTarget;
}

/** 待办汇总 */
export interface TodoSummary {
  items: TodoItem[];
  /** 待办审批总数（BPM）；undefined 表示不可用 */
  approvalCount?: number;
  /** 待处理质量工单数 */
  workOrderCount?: number;
  /** 未读站内信 */
  unreadCount?: number;
}

/** 最近运行条目 */
export interface RunItem {
  key: string;
  source: 'access' | 'dev';
  sourceLabel: string;
  name: string;
  statusLabel: string;
  /** antd Tag 颜色 */
  statusColor: string;
  trigger: string;
  startTime?: number | string;
  duration: string;
  target: RouteTarget;
}

/** 工作台页面数据 */
export interface WorkspaceData {
  profile?: SystemUserProfileApi.UserProfileRespVO;
  stages: ChainStage[];
  health: HealthMetric[];
  todo: TodoSummary;
  runs: {
    /** 两个来源都不可用时为 false，用于区分 “无权限” 与 “暂无记录” */
    available: boolean;
    items: RunItem[];
  };
  /** undefined 表示公告接口不可用 */
  notices?: SystemNoticeApi.Notice[];
}

/** 数据总览页：核心指标卡片 */
export interface MetricCardItem {
  key: string;
  title: string;
  icon: string;
  color: string;
  /** undefined 表示该域不可用 / 无权限 */
  value?: number;
  unit?: string;
  hint: string;
  target?: RouteTarget;
}

/** 数据总览页：各域聚合数据（趋势类按天数单独加载） */
export interface AnalyticsOverview {
  /** 数据集成 / 数据研发 */
  dataforge?: DashboardApi.DataforgeSummary;
  /** 数据治理（元数据 / 标准 / 质量） */
  governance?: DashboardApi.GovernanceSummary;
  model?: ModelHomeApi.ModelOverview;
  asset?: DataAssetApi.Facets;
  apiStats?: SupplyApiLogApi.Statistics;
  qualityTrend?: DashboardApi.QualityTrendPoint[];
  workOrderStats?: DashboardApi.WorkOrderStatistics;
  safety?: DashboardApi.SafetySummary;
}

/** 质量六维（顺序即雷达图指标顺序） */
export const QUALITY_DIMENSIONS: { key: string; label: string }[] = [
  { key: 'COMPLETENESS', label: '完整性' },
  { key: 'UNIQUENESS', label: '唯一性' },
  { key: 'NORMALIZATION', label: '规范性' },
  { key: 'ACCURACY', label: '准确性' },
  { key: 'TIMELINESS', label: '及时性' },
  { key: 'CONSISTENCY', label: '一致性' },
];

// ==================== 视图模型构建 ====================

interface DomainData {
  apiStats?: SupplyApiLogApi.Statistics;
  asset?: DataAssetApi.Facets;
  dataforge?: DashboardApi.DataforgeSummary;
  governance?: DashboardApi.GovernanceSummary;
  model?: ModelHomeApi.ModelOverview;
  workOrderStats?: DashboardApi.WorkOrderStatistics;
}

function buildChainStages(input: DomainData): ChainStage[] {
  const { apiStats, asset, dataforge, model } = input;
  const meta = input.governance?.meta;
  return [
    {
      key: 'database',
      title: '数据源',
      icon: 'carbon:ibm-cloud-databases',
      color: '#2f7df6',
      value: dataforge?.databaseCount,
      hint:
        dataforge?.databaseTypeDistribution === undefined
          ? '已接入的数据库 / 数据源'
          : `${dataforge.databaseTypeDistribution.length} 种数据库类型`,
      target: DASHBOARD_TARGETS.database,
    },
    {
      key: 'access',
      title: '集成任务',
      icon: 'carbon:data-share',
      color: '#0ea5e9',
      value: dataforge?.accessJobCount,
      hint:
        dataforge?.accessReleasedCount === undefined
          ? '数据同步 / 采集 / 推送任务'
          : `已发布同步 ${dataforge.accessReleasedCount} · 开发 ${dataforge.devTaskCount ?? 0}`,
      target: DASHBOARD_TARGETS.access,
    },
    {
      key: 'meta',
      title: '元数据表',
      icon: 'carbon:catalog',
      color: '#7c3aed',
      value: meta?.tableCount,
      hint:
        meta?.publishedTableCount === undefined
          ? '采集入库的表级元数据'
          : `已发布 ${meta.publishedTableCount} 张 · 字段 ${meta.columnCount ?? 0} 个`,
      target: DASHBOARD_TARGETS.metaObject,
    },
    {
      key: 'model',
      title: '数据模型',
      icon: 'carbon:model-alt',
      color: '#14b8a6',
      value: model?.modelCount,
      hint:
        model?.publishedCount === undefined
          ? '逻辑 / 物理模型'
          : `已发布 ${model.publishedCount} · 已物化 ${model.materializedCount ?? 0}`,
      target: DASHBOARD_TARGETS.logicModel,
    },
    {
      key: 'asset',
      title: '数据资产',
      icon: 'carbon:carbon-for-ibm-product',
      color: '#f59e0b',
      value: asset?.total,
      hint:
        asset?.categories === undefined
          ? '已登记的数据资产'
          : `覆盖 ${asset.categories.length} 个资产类目`,
      target: DASHBOARD_TARGETS.dataAsset,
    },
    {
      key: 'api',
      title: '服务 API',
      icon: 'carbon:api-1',
      color: '#18a058',
      value: apiStats?.apiCount,
      hint:
        apiStats?.onlineApiCount === undefined
          ? '对外提供的数据服务'
          : `在线 ${apiStats.onlineApiCount} 个 · 近 7 日调用 ${apiStats.totalCount ?? 0} 次`,
      target: DASHBOARD_TARGETS.dataApi,
    },
  ];
}

/** 后端百分比字段在无样本时可能返回 null（如采集成功率），与 undefined 同样视为不可用 */
function rateLevel(
  rate: null | number | undefined,
  good: number,
  warn: number,
): HealthLevel {
  if (rate === undefined || rate === null) {
    return 'none';
  }
  if (rate >= good) {
    return 'good';
  }
  return rate >= warn ? 'warn' : 'bad';
}

function countLevel(count: number | undefined): HealthLevel {
  if (count === undefined) {
    return 'none';
  }
  return count > 0 ? 'warn' : 'good';
}

/** 合并开发任务与数据同步的今日执行统计 */
function mergeRuns(
  ...summaries: (DashboardApi.RunSummary | undefined)[]
): DashboardApi.RunSummary | undefined {
  const present = summaries.filter((item) => item !== undefined);
  if (present.length === 0) {
    return undefined;
  }
  const sum = (pick: (item: DashboardApi.RunSummary) => number | undefined) =>
    present.reduce((total, item) => total + (pick(item) ?? 0), 0);
  return {
    totalCount: sum((item) => item.totalCount),
    successCount: sum((item) => item.successCount),
    failedCount: sum((item) => item.failedCount),
    runningCount: sum((item) => item.runningCount),
  };
}

function buildHealthMetrics(input: DomainData): HealthMetric[] {
  const { apiStats, dataforge, governance, workOrderStats } = input;
  const meta = governance?.meta;
  const standard = governance?.standard;
  const qualityReport = governance?.qualityReport;
  const todayRun = mergeRuns(
    dataforge?.todayDevRun,
    dataforge?.todayAccessRun,
  );
  const weekCreated = workOrderStats?.trend?.reduce(
    (sum, point) => sum + point.createdCount,
    0,
  );
  const weekProcessed = workOrderStats?.trend?.reduce(
    (sum, point) => sum + point.processedCount,
    0,
  );

  return [
    {
      key: 'run',
      title: '今日任务运行',
      value: todayRun === undefined ? '--' : `${todayRun.totalCount ?? 0} 次`,
      level: countLevel(todayRun?.failedCount),
      description:
        todayRun === undefined
          ? '任务执行统计不可用'
          : `成功 ${todayRun.successCount ?? 0} · 失败 ${todayRun.failedCount ?? 0} · 运行中 ${todayRun.runningCount ?? 0}（开发任务 + 数据同步）`,
      target: DASHBOARD_TARGETS.devInstance,
    },
    {
      key: 'apiToday',
      title: '今日 API 调用',
      value:
        apiStats?.todayCount === undefined ? '--' : `${apiStats.todayCount} 次`,
      level: rateLevel(apiStats?.successRate, 99, 95),
      description:
        apiStats?.successRate === undefined
          ? '数据服务调用统计不可用'
          : `近 7 日成功率 ${formatPercent(apiStats.successRate)} · 平均耗时 ${apiStats.avgDurationMs ?? 0} ms`,
      target: DASHBOARD_TARGETS.apiLog,
    },
    {
      key: 'collect',
      title: '元数据采集成功率',
      value: formatPercent(meta?.collectSuccessRate),
      level: rateLevel(meta?.collectSuccessRate, 95, 80),
      description:
        meta?.collectInstanceCount === undefined
          ? '元数据采集统计不可用'
          : `近 30 日执行 ${meta.collectInstanceCount} 次 · 注释覆盖率 ${formatPercent(meta.commentCoverage)}`,
      target: DASHBOARD_TARGETS.metaCollectTask,
    },
    {
      key: 'quality',
      title: '质量检核通过率',
      value: formatPercent(qualityReport?.avgPassRate),
      level: rateLevel(qualityReport?.avgPassRate, 95, 80),
      description:
        qualityReport?.totalCount === undefined
          ? '质量检核统计不可用'
          : `累计检核 ${qualityReport.totalCount} 次 · 未通过 ${qualityReport.failCount ?? 0}${
              governance?.qualityAlert?.unprocessedCount === undefined
                ? ''
                : ` · 未处理告警 ${governance.qualityAlert.unprocessedCount}`
            }`,
      target: DASHBOARD_TARGETS.qualityReport,
    },
    {
      key: 'standard',
      title: '标准落标率',
      value: formatPercent(standard?.mappingRate),
      level: rateLevel(standard?.mappingRate, 80, 50),
      description:
        standard?.totalCount === undefined
          ? '数据标准统计不可用'
          : `数据元 ${standard.totalCount} 个 · 已发布 ${standard.publishedCount} · 已落标 ${standard.mappedElementCount}`,
      target: DASHBOARD_TARGETS.standardElement,
    },
    {
      key: 'workOrder',
      title: '待处理质量工单',
      value:
        governance?.pendingWorkOrderCount === undefined
          ? '--'
          : `${governance.pendingWorkOrderCount} 单`,
      level: countLevel(governance?.pendingWorkOrderCount),
      description:
        weekCreated === undefined
          ? '质量工单统计不可用'
          : `近 7 天新增 ${weekCreated} · 处理完成 ${weekProcessed ?? 0}`,
      target: DASHBOARD_TARGETS.qualityWorkOrder,
    },
  ];
}

function buildTodoSummary(input: {
  governance?: DashboardApi.GovernanceSummary;
  todoPage?: { list: BpmTaskApi.Task[]; total: number };
  unreadCount?: number;
  workOrders?: QualityWorkOrderApi.WorkOrder[];
}): TodoSummary {
  const { governance, todoPage, unreadCount, workOrders } = input;
  const items: TodoItem[] = [];

  todoPage?.list?.forEach((task) => {
    items.push({
      key: `approval-${task.id}`,
      kind: 'approval',
      title: task.processInstance?.name ?? task.name ?? '流程审批',
      description: `${task.name ?? '待审批'} · 发起人 ${task.processInstance?.startUser?.nickname ?? '-'}`,
      time: task.createTime ? formatDateTime(task.createTime) : undefined,
      target: DASHBOARD_TARGETS.bpmTodo,
    });
  });

  workOrders?.slice(0, 5).forEach((order) => {
    items.push({
      key: `workorder-${order.id}`,
      kind: 'workOrder',
      title: order.orderName ?? '质量问题工单',
      description: `${order.taskName ?? '-'} · 问题 ${order.issueCount ?? 0} 条${
        order.deadline ? ` · 截止 ${order.deadline}` : ''
      }`,
      time: order.createTime ? formatDateTime(order.createTime) : undefined,
      target: DASHBOARD_TARGETS.qualityWorkOrder,
    });
  });

  governance?.meta?.recentFails?.slice(0, 3).forEach((fail) => {
    items.push({
      key: `collect-${fail.instanceId}`,
      kind: 'collectFail',
      title: `采集失败：${fail.taskName ?? `任务 ${fail.taskId}`}`,
      description: `${fail.stage ?? '-'} · ${fail.errorType ?? '未知错误'}`,
      time: fail.startTime ? formatDateTime(fail.startTime) : undefined,
      target: DASHBOARD_TARGETS.metaCollectTask,
    });
  });

  return {
    items,
    approvalCount: todoPage?.total,
    workOrderCount: workOrders?.length,
    unreadCount,
  };
}

const DEV_STATUS: Record<string, [string, string]> = {
  RUNNING: ['运行中', 'processing'],
  SUCCESS: ['成功', 'success'],
  FAILED: ['失败', 'error'],
  STOPPED: ['已停止', 'default'],
};

const ACCESS_STATUS: Record<number, [string, string]> = {
  1: ['等待中', 'default'],
  2: ['运行中', 'processing'],
  3: ['正常结束', 'success'],
  4: ['异常结束', 'error'],
};

function toDevRunItem(record: DataforgeDevApi.Record): RunItem {
  const status: [string, string] = DEV_STATUS[record.status ?? ''] ?? [
    String(record.status ?? '-'),
    'default',
  ];
  const [statusLabel, statusColor] = status;
  return {
    key: `dev-${record.id}`,
    source: 'dev',
    sourceLabel: '开发任务',
    name: record.taskName ?? `任务 ${record.taskId ?? record.id}`,
    statusLabel,
    statusColor,
    trigger: record.triggerType === 'SCHEDULE' ? '定时调度' : '手动执行',
    startTime: record.startTime,
    duration: formatDuration(record.durationMs),
    target: DASHBOARD_TARGETS.devInstance,
  };
}

function toAccessRunItem(task: DataforgeAccessApi.AccessTask): RunItem {
  const status: [string, string] = ACCESS_STATUS[task.runStatus ?? 0] ?? [
    '-',
    'default',
  ];
  const [statusLabel, statusColor] = status;
  const start = task.startTime ? new Date(task.startTime).getTime() : undefined;
  const end = task.endTime ? new Date(task.endTime).getTime() : undefined;
  return {
    key: `access-${task.id}`,
    source: 'access',
    sourceLabel: '数据同步',
    name: task.taskName ?? `同步任务 ${task.dataAccessId ?? task.id}`,
    statusLabel,
    statusColor,
    trigger: task.runType === 1 ? '自动调度' : '手动执行',
    startTime: task.startTime,
    duration: start && end && end >= start ? formatDuration(end - start) : '-',
    target: DASHBOARD_TARGETS.accessTask,
  };
}

function buildRecentRuns(
  devPage: undefined | { list: DataforgeDevApi.Record[] },
  accessPage: undefined | { list: DataforgeAccessApi.AccessTask[] },
  limit: number,
): WorkspaceData['runs'] {
  const items = [
    ...(devPage?.list ?? []).map((item) => toDevRunItem(item)),
    ...(accessPage?.list ?? []).map((item) => toAccessRunItem(item)),
  ]
    .toSorted((a, b) => {
      const at = a.startTime ? new Date(a.startTime).getTime() : 0;
      const bt = b.startTime ? new Date(b.startTime).getTime() : 0;
      return bt - at;
    })
    .slice(0, limit);
  return { available: !!devPage || !!accessPage, items };
}

// ==================== 加载函数 ====================

/** 工作台：按域并行拉取（每个域一个汇总接口 + 个人相关接口），再拼装各卡片 */
export async function loadWorkspaceData(): Promise<WorkspaceData> {
  const RUN_LIMIT = 8;
  const [
    profile,
    dataforge,
    governance,
    model,
    asset,
    apiStats,
    workOrderStats,
    todoPage,
    workOrders,
    unreadCount,
    devPage,
    accessPage,
    noticePage,
  ] = await Promise.all([
    settle(getDashboardUserProfile()),
    settle(getDashboardDataforgeSummary()),
    settle(getDashboardGovernanceSummary()),
    settle(getDashboardModelOverview()),
    settle(getDashboardAssetFacets()),
    settle(getDashboardApiLogStatistics(7)),
    settle(getDashboardWorkOrderStatistics(7)),
    settle(getDashboardTodoTaskPage(5)),
    settle(getDashboardMyWorkOrderList()),
    settle(getDashboardUnreadNotifyCount()),
    settle(getDashboardDevRecordPage(RUN_LIMIT)),
    settle(getDashboardAccessTaskPage(RUN_LIMIT)),
    settle(getDashboardNoticePage(5)),
  ]);

  const domains: DomainData = {
    apiStats,
    asset,
    dataforge,
    governance,
    model,
    workOrderStats,
  };
  return {
    profile,
    stages: buildChainStages(domains),
    health: buildHealthMetrics(domains),
    todo: buildTodoSummary({ governance, todoPage, unreadCount, workOrders }),
    runs: buildRecentRuns(devPage, accessPage, RUN_LIMIT),
    notices: noticePage?.list,
  };
}

/** 数据总览：各域聚合数据 */
export async function loadAnalyticsOverview(
  days: number,
): Promise<AnalyticsOverview> {
  const [
    dataforge,
    governance,
    model,
    asset,
    apiStats,
    qualityTrend,
    workOrderStats,
    safety,
  ] = await Promise.all([
    settle(getDashboardDataforgeSummary()),
    settle(getDashboardGovernanceSummary()),
    settle(getDashboardModelOverview()),
    settle(getDashboardAssetFacets()),
    settle(getDashboardApiLogStatistics(days)),
    settle(getDashboardQualityTrend(days)),
    settle(getDashboardWorkOrderStatistics(days)),
    settle(getDashboardSafetyOverview()),
  ]);
  return {
    dataforge,
    governance,
    model,
    asset,
    apiStats,
    qualityTrend,
    workOrderStats,
    safety,
  };
}

/** 数据总览：仅刷新与天数相关的趋势统计 */
export async function loadAnalyticsTrends(
  days: number,
): Promise<
  Pick<AnalyticsOverview, 'apiStats' | 'qualityTrend' | 'workOrderStats'>
> {
  const [apiStats, qualityTrend, workOrderStats] = await Promise.all([
    settle(getDashboardApiLogStatistics(days)),
    settle(getDashboardQualityTrend(days)),
    settle(getDashboardWorkOrderStatistics(days)),
  ]);
  return { apiStats, qualityTrend, workOrderStats };
}
