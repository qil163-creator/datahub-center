import type {
  ModuleOverviewConfig,
  ModuleOverviewData,
  OverviewActivity,
  OverviewActivityTone,
  OverviewDistributionRow,
} from '#/views/dashboard/shared/module-overview/types';
import type { RouteTarget } from '#/views/dashboard/shared/route-resolver';

import {
  getDashboardAccessTaskPage,
  getDashboardDataforgeSummary,
  getDashboardDevRecordPage,
  getDashboardEtlPipelinePage,
  getDashboardEtlRecordPage,
  getDashboardIntegrationPage,
  getDashboardIntegrationStatistics,
  getDashboardOpsStatistics,
} from '#/api/dashboard';
import {
  ratioOf,
  toTimestamp,
} from '#/views/dashboard/shared/module-overview/values';
import { DASHBOARD_TARGETS } from '#/views/dashboard/shared/route-resolver';
import { settle } from '#/views/dashboard/shared/utils';

const targets = {
  offline: {
    names: ['DataforgeOfflineDevelopment'],
    titles: ['离线开发'],
  },
  realtime: {
    names: ['DataforgeRealtimeDevelopment'],
    titles: ['实时开发'],
  },
  opsIntegration: {
    names: ['DataforgeOpsIntegration'],
    titles: ['数据集成实例'],
  },
  job: { names: ['DataforgeJob'], titles: ['作业管理'] },
  etlRecord: { names: ['DataforgeEtlRecord'], titles: ['流程日志'] },
} satisfies Record<string, RouteTarget>;

export const developOverviewConfig: ModuleOverviewConfig = {
  name: '数据研发',
  banner: {
    title: '打造高效协同的数据研发体系',
    description:
      '贯通数据接入、开发调度、可视化 ETL 与运维监控，让数据加工链路可视、可控、可追溯',
    slogan: '让数据流动更高效',
    icons: ['lucide:workflow', 'lucide:code-xml'],
  },
  metrics: [
    {
      key: 'database',
      label: '数据连接',
      icon: 'lucide:database',
      color: 'blue',
      target: DASHBOARD_TARGETS.database,
    },
    {
      key: 'access',
      label: '同步任务',
      icon: 'lucide:database-zap',
      color: 'teal',
      target: DASHBOARD_TARGETS.access,
    },
    {
      key: 'offline',
      label: '离线开发',
      icon: 'lucide:calendar-clock',
      color: 'indigo',
      target: targets.offline,
    },
    {
      key: 'realtime',
      label: '实时开发',
      icon: 'lucide:radio-tower',
      color: 'orange',
      target: targets.realtime,
    },
    {
      key: 'devTask',
      label: '开发任务',
      icon: 'lucide:code-xml',
      color: 'green',
      target: DASHBOARD_TARGETS.devWorkbench,
    },
    {
      key: 'etl',
      label: 'ETL 流程',
      icon: 'lucide:workflow',
      color: 'violet',
      target: DASHBOARD_TARGETS.etlPipeline,
    },
  ],
  process: {
    title: '数据研发主流程',
    note: '从数据接入到运维保障',
    steps: [
      {
        title: '数据连接',
        icon: 'lucide:database',
        lines: ['业务库 / 文件 / 接口', '连接测试与诊断', '数据源统一登记'],
        principle: '统一数据源',
        target: DASHBOARD_TARGETS.database,
      },
      {
        title: '数据接入',
        icon: 'lucide:database-zap',
        lines: ['离线 / 实时同步', '整库同步 / 文件入库', '接口采集 / 数据填报'],
        principle: '统一接入链路',
        target: DASHBOARD_TARGETS.access,
      },
      {
        title: '数据开发',
        icon: 'lucide:code-xml',
        lines: ['SQL / 脚本任务', '依赖编排与调度', '补数据与版本管理'],
        principle: '统一开发规范',
        target: DASHBOARD_TARGETS.devWorkbench,
      },
      {
        title: '可视化 ETL',
        icon: 'lucide:workflow',
        lines: ['拖拽式流程编排', '清洗转换组件', '流程模板复用'],
        principle: '统一加工流程',
        target: DASHBOARD_TARGETS.etlPipeline,
      },
      {
        title: '运维监控',
        icon: 'lucide:monitor-check',
        lines: ['集成 / 开发实例', '作业运行状态', '失败定位与重跑'],
        principle: '统一运维保障',
        target: targets.opsIntegration,
      },
    ],
  },
  downstream: {
    title: '研发产出如何支持后续模块',
    origin: '数据研发产出',
    items: [
      {
        title: '数据建模',
        icon: 'lucide:boxes',
        description: '贴源与明细数据落入分层模型表',
        target: DASHBOARD_TARGETS.logicModel,
      },
      {
        title: '数据治理',
        icon: 'lucide:shield-check',
        description: '为元数据采集与质量检核提供数据基础',
        target: DASHBOARD_TARGETS.metaObject,
      },
      {
        title: '数据资产',
        icon: 'lucide:gem',
        description: '加工后的数据表沉淀为可检索资产',
        target: DASHBOARD_TARGETS.dataAsset,
      },
      {
        title: '数据查询',
        icon: 'lucide:search-code',
        description: '即席查询加工结果，快速验证数据口径',
        target: DASHBOARD_TARGETS.dataQuery,
      },
      {
        title: '数据服务',
        icon: 'lucide:radar',
        description: '为 API 服务提供稳定的数据出口',
        target: DASHBOARD_TARGETS.dataApi,
      },
      {
        title: '主题域与 BI',
        icon: 'lucide:chart-column',
        description: '汇总与应用层数据支撑报表大屏',
        target: DASHBOARD_TARGETS.jimuReport,
      },
    ],
  },
  distributions: [
    {
      key: 'todayRuns',
      title: '今日任务执行分布',
      empty: '今日暂无任务执行，调度或手动运行后查看分布',
      target: targets.opsIntegration,
      linkText: '前往运维管理',
    },
    {
      key: 'todaySuccess',
      title: '今日执行成功率',
      mode: 'rate',
      empty: '今日暂无任务执行，暂无成功率统计',
      target: targets.opsIntegration,
      linkText: '前往运维管理',
    },
  ],
  activity: {
    title: '最近运行',
    empty: '暂无运行记录，任务执行后将在此展示',
    linkText: '查看日志',
    target: targets.etlRecord,
  },
};

const ACTIVITY_LIMIT = 8;

const DEV_STATUS: Record<string, [string, OverviewActivityTone]> = {
  RUNNING: ['运行中', 'processing'],
  SUCCESS: ['成功', 'success'],
  FAILED: ['失败', 'error'],
  STOPPED: ['已停止', 'default'],
};

/** 数据同步执行记录 runStatus：1 等待 2 运行中 3 正常结束 4 异常结束 */
const ACCESS_STATUS: Record<number, [string, OverviewActivityTone]> = {
  1: ['等待中', 'default'],
  2: ['运行中', 'processing'],
  3: ['已完成', 'success'],
  4: ['异常结束', 'error'],
};

/** ETL 运行记录 runStatus：1 等待 2 运行中 3 成功 4 失败 5 停止 */
const ETL_STATUS: Record<number, [string, OverviewActivityTone]> = {
  1: ['等待中', 'default'],
  2: ['运行中', 'processing'],
  3: ['运行成功', 'success'],
  4: ['运行失败', 'error'],
  5: ['已停止', 'warning'],
};

function statusOf<K extends number | string>(
  map: Record<K, [string, OverviewActivityTone]>,
  key: K | undefined,
): [string, OverviewActivityTone] {
  return (key === undefined ? undefined : map[key]) ?? ['', 'default'];
}

/** 数据研发概览：各数据源并行拉取，单个数据源失败只影响对应卡片 */
export async function loadDevelopOverview(): Promise<ModuleOverviewData> {
  const [
    summary,
    offline,
    realtime,
    offlineStats,
    realtimeStats,
    fullSyncStats,
    jobStats,
    etl,
    devRecords,
    accessRecords,
    etlRecords,
  ] = await Promise.all([
    settle(getDashboardDataforgeSummary()),
    settle(getDashboardIntegrationPage('OFFLINE')),
    settle(getDashboardIntegrationPage('REALTIME')),
    settle(getDashboardIntegrationStatistics('OFFLINE')),
    settle(getDashboardIntegrationStatistics('REALTIME')),
    settle(getDashboardOpsStatistics('FULL_SYNC')),
    settle(getDashboardOpsStatistics('JOB')),
    settle(getDashboardEtlPipelinePage()),
    settle(getDashboardDevRecordPage(ACTIVITY_LIMIT)),
    settle(getDashboardAccessTaskPage(ACTIVITY_LIMIT)),
    settle(getDashboardEtlRecordPage(ACTIVITY_LIMIT)),
  ]);

  const accessRun = summary?.todayAccessRun;
  const devRun = summary?.todayDevRun;
  const runSources: {
    executed?: number;
    key: string;
    name: string;
    successRate?: null | number;
  }[] = [
    {
      key: 'access',
      name: '数据同步',
      executed: accessRun?.totalCount,
      successRate: ratioOf(accessRun?.successCount, accessRun?.totalCount),
    },
    {
      key: 'offline',
      name: '离线开发',
      executed: offlineStats?.todayExecuted,
      successRate: offlineStats?.todayExecuted
        ? offlineStats.todaySuccessRate
        : null,
    },
    {
      key: 'realtime',
      name: '实时开发',
      executed: realtimeStats?.todayExecuted,
      successRate: realtimeStats?.todayExecuted
        ? realtimeStats.todaySuccessRate
        : null,
    },
    {
      key: 'dev',
      name: '数据开发',
      executed: devRun?.totalCount,
      successRate: ratioOf(devRun?.successCount, devRun?.totalCount),
    },
    {
      key: 'fullSync',
      name: '整库同步',
      executed: fullSyncStats?.todayExecuted,
      successRate: fullSyncStats?.todayExecuted
        ? fullSyncStats.todaySuccessRate
        : null,
    },
    {
      key: 'job',
      name: '作业管理',
      executed: jobStats?.todayExecuted,
      successRate: jobStats?.todayExecuted ? jobStats.todaySuccessRate : null,
    },
  ];
  // 仅展示可读取的数据源；全部不可用时整个面板降级
  const availableRuns = runSources.filter(
    (source) => source.executed !== undefined,
  );
  const runRows: OverviewDistributionRow[] = availableRuns.map((source) => ({
    key: source.key,
    name: source.name,
    count: source.executed ?? 0,
  }));
  const rateRows: OverviewDistributionRow[] = availableRuns.map((source) => ({
    key: source.key,
    name: source.name,
    rate: source.successRate ?? null,
  }));

  const activities: OverviewActivity[] = [
    ...(devRecords?.list ?? []).map((record) => {
      const [label, tone] = statusOf(DEV_STATUS, record.status);
      return {
        id: `dev-${String(record.id)}`,
        icon: 'lucide:code-xml',
        title: `开发任务「${record.taskName ?? record.taskId ?? record.id}」${label}`,
        time: toTimestamp(record.startTime),
        tone,
      };
    }),
    ...(accessRecords?.list ?? []).map((record) => {
      const [label, tone] = statusOf(ACCESS_STATUS, record.runStatus);
      return {
        id: `access-${String(record.id)}`,
        icon: 'lucide:database-zap',
        title: `同步任务「${record.taskName ?? record.dataAccessId ?? record.id}」${label}`,
        time: toTimestamp(record.startTime ?? record.createTime),
        tone,
      };
    }),
    ...(etlRecords?.list ?? []).map((record) => {
      const [label, tone] = statusOf(ETL_STATUS, record.runStatus);
      return {
        id: `etl-${String(record.id)}`,
        icon: 'lucide:workflow',
        title: `ETL 流程「${record.name ?? record.pipelineId ?? record.id}」${label}`,
        time: toTimestamp(record.startTime ?? record.createTime),
        tone,
      };
    }),
  ]
    .toSorted((a, b) => (b.time ?? 0) - (a.time ?? 0))
    .slice(0, ACTIVITY_LIMIT);

  return {
    generatedAt: Date.now(),
    metrics: {
      database: summary && {
        value: summary.databaseCount ?? 0,
        hint: `数据源类型 ${summary.databaseTypeDistribution?.length ?? 0} 种`,
      },
      access: summary && {
        value: summary.accessJobCount ?? 0,
        hint: `已发布 ${summary.accessReleasedCount ?? 0} 个`,
      },
      offline: offline && {
        value: offline.total,
        hint: offlineStats
          ? `今日执行 ${offlineStats.todayExecuted} 次`
          : undefined,
      },
      realtime: realtime && {
        value: realtime.total,
        hint: realtimeStats ? `运行中 ${realtimeStats.running} 个` : undefined,
      },
      devTask: summary && {
        value: summary.devTaskCount ?? 0,
        hint: `已上线 ${summary.devTaskOnlineCount ?? 0} 个`,
      },
      etl: etl && {
        value: etl.total,
        hint: '可视化编排的生产流程',
      },
    },
    distributions: {
      todayRuns: availableRuns.length > 0 ? { rows: runRows } : undefined,
      todaySuccess: availableRuns.length > 0 ? { rows: rateRows } : undefined,
    },
    activity:
      devRecords || accessRecords || etlRecords
        ? { items: activities }
        : undefined,
  };
}
