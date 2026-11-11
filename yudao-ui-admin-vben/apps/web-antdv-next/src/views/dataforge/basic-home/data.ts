import type {
  ModuleOverviewConfig,
  ModuleOverviewData,
  OverviewActivity,
  OverviewDistributionValue,
} from '#/views/dashboard/shared/module-overview/types';
import type { RouteTarget } from '#/views/dashboard/shared/route-resolver';

import {
  getDashboardAuditRuleDimensionList,
  getDashboardAuditRulePage,
  getDashboardCleanRulePage,
  getDashboardDatabasePage,
  getDashboardDatabaseTypePage,
  getDashboardDataforgeSummary,
  getDashboardFileConnections,
  getDashboardSystemSourcePage,
} from '#/api/dashboard';
import { toTimestamp } from '#/views/dashboard/shared/module-overview/values';
import { DASHBOARD_TARGETS } from '#/views/dashboard/shared/route-resolver';
import { settle } from '#/views/dashboard/shared/utils';

/** 通用启用状态（CommonStatusEnum.ENABLE） */
const ENABLED = 0;

const targets = {
  database: { names: ['Databases', 'Database'], titles: ['数据连接'] },
  databaseType: { names: ['DatabaseType'], titles: ['连接器管理'] },
  systemSource: { names: ['SystemSource'], titles: ['来源系统'] },
  dataFile: { names: ['DataforgeDataFiles'], titles: ['文件管理'] },
  auditRule: { names: ['GovernanceQualityAuditRule'], titles: ['稽查规则'] },
  cleanRule: { names: ['GovernanceQualityCleanRule'], titles: ['清洗规则'] },
  category: {
    names: ['DataforgeIntegrationCategory', 'CategoryData'],
    titles: ['数据集成类目', '数据分类类目'],
  },
  materialized: {
    names: ['ModelingModelMaterialized'],
    titles: ['发布模型', '模型物化'],
  },
} satisfies Record<string, RouteTarget>;

export const basicOverviewConfig: ModuleOverviewConfig = {
  name: '基础管理',
  banner: {
    title: '夯实统一可信的数据基础底座',
    description:
      '统一管理连接器、数据连接、来源系统、规则库与类目体系，为采集、建模、治理与服务提供一致的基础配置',
    slogan: '基础稳固，数据畅通',
    icons: ['lucide:plug-zap', 'lucide:folder-tree'],
  },
  metrics: [
    {
      key: 'database',
      label: '数据连接',
      icon: 'lucide:database',
      color: 'blue',
      target: targets.database,
    },
    {
      key: 'databaseType',
      label: '连接器',
      icon: 'lucide:plug-zap',
      color: 'teal',
      unit: '种',
      target: targets.databaseType,
    },
    {
      key: 'systemSource',
      label: '来源系统',
      icon: 'lucide:server',
      color: 'indigo',
      target: targets.systemSource,
    },
    {
      key: 'fileConnection',
      label: '文件连接',
      icon: 'lucide:folder-open',
      color: 'orange',
      target: targets.dataFile,
    },
    {
      key: 'auditRule',
      label: '稽查规则',
      icon: 'lucide:shield-check',
      color: 'green',
      unit: '条',
      target: targets.auditRule,
    },
    {
      key: 'cleanRule',
      label: '清洗规则',
      icon: 'lucide:sparkles',
      color: 'violet',
      unit: '条',
      target: targets.cleanRule,
    },
  ],
  process: {
    title: '基础配置主流程',
    note: '从接入协议到分类体系',
    steps: [
      {
        title: '连接器管理',
        icon: 'lucide:plug-zap',
        lines: ['数据库 / 文件 / 消息', '连接模板与默认端口', '能力声明与校验'],
        principle: '统一接入协议',
        target: targets.databaseType,
      },
      {
        title: '数据连接',
        icon: 'lucide:database',
        lines: ['数据源登记与测连', '连接诊断与监控', '部门归属与授权'],
        principle: '统一连接管理',
        target: targets.database,
      },
      {
        title: '来源系统',
        icon: 'lucide:server',
        lines: ['业务系统登记', '负责人与归属部门', '系统与数据源关联'],
        principle: '统一来源归属',
        target: targets.systemSource,
      },
      {
        title: '规则库',
        icon: 'lucide:shield-check',
        lines: ['稽查规则 / 质量维度', '清洗规则 / 转换策略', '规则启停管理'],
        principle: '统一规则口径',
        target: targets.auditRule,
      },
      {
        title: '类目管理',
        icon: 'lucide:folder-tree',
        lines: ['集成 / 开发 / 填报', '标准 / 术语 / 标签', '集市 / 质量 / 推送'],
        principle: '统一分类体系',
        target: targets.category,
      },
    ],
  },
  downstream: {
    title: '基础配置如何支撑后续模块',
    origin: '基础管理配置',
    items: [
      {
        title: '数据集成',
        icon: 'lucide:database-zap',
        description: '复用数据连接与连接器，快速配置同步任务',
        target: DASHBOARD_TARGETS.access,
      },
      {
        title: '数据建模',
        icon: 'lucide:boxes',
        description: '以数据连接为物化目标，落地模型结构',
        target: targets.materialized,
      },
      {
        title: '数据开发',
        icon: 'lucide:code-xml',
        description: '按开发类目组织任务，统一调度与运维',
        target: DASHBOARD_TARGETS.devWorkbench,
      },
      {
        title: '数据治理',
        icon: 'lucide:shield-check',
        description: '稽查与清洗规则驱动质量检核与修复',
        target: DASHBOARD_TARGETS.qualityRule,
      },
      {
        title: '数据资产',
        icon: 'lucide:gem',
        description: '来源系统与类目沉淀资产归属与目录',
        target: DASHBOARD_TARGETS.dataAsset,
      },
      {
        title: '数据服务',
        icon: 'lucide:radar',
        description: '基于数据连接发布 API，统一服务出口',
        target: DASHBOARD_TARGETS.dataApi,
      },
    ],
  },
  distributions: [
    {
      key: 'databaseType',
      title: '数据连接类型分布',
      empty: '暂无数据连接，新增连接后查看类型分布',
      target: targets.database,
      linkText: '前往数据连接',
    },
    {
      key: 'auditDimension',
      title: '稽查规则质量维度分布',
      empty: '暂无稽查规则，维护规则后查看维度分布',
      target: targets.auditRule,
      linkText: '前往稽查规则',
    },
  ],
  activity: {
    title: '最近接入',
    empty: '暂无数据连接或来源系统，接入后将在此展示',
    linkText: '查看连接',
    target: targets.database,
  },
};

const ACTIVITY_LIMIT = 8;

async function loadAuditDimensions(): Promise<
  OverviewDistributionValue | undefined
> {
  const dimensions = await settle(getDashboardAuditRuleDimensionList());
  if (!dimensions) return undefined;
  const sorted = dimensions.toSorted((a, b) => a.sort - b.sort);
  const pages = await Promise.all(
    sorted.map((dimension) =>
      settle(getDashboardAuditRulePage({ dimensionId: dimension.id })),
    ),
  );
  // 任一维度统计失败都视为不可用，避免展示残缺的占比
  if (pages.some((page) => page === undefined)) return undefined;
  return {
    rows: sorted.map((dimension, index) => ({
      key: String(dimension.id),
      name: dimension.name,
      code: dimension.code,
      count: pages[index]?.total ?? 0,
    })),
  };
}

/** 基础管理概览：各数据源并行拉取，单个数据源失败只影响对应卡片 */
export async function loadBasicOverview(): Promise<ModuleOverviewData> {
  const [
    summary,
    databases,
    types,
    enabledTypes,
    sources,
    enabledSources,
    files,
    auditRules,
    enabledAuditRules,
    cleanRules,
    enabledCleanRules,
    auditDimensions,
  ] = await Promise.all([
    settle(getDashboardDataforgeSummary()),
    settle(getDashboardDatabasePage(ACTIVITY_LIMIT)),
    settle(getDashboardDatabaseTypePage()),
    settle(getDashboardDatabaseTypePage(ENABLED)),
    settle(getDashboardSystemSourcePage(ACTIVITY_LIMIT)),
    settle(getDashboardSystemSourcePage(1, ENABLED)),
    settle(getDashboardFileConnections()),
    settle(getDashboardAuditRulePage()),
    settle(getDashboardAuditRulePage({ status: ENABLED })),
    settle(getDashboardCleanRulePage()),
    settle(getDashboardCleanRulePage(ENABLED)),
    loadAuditDimensions(),
  ]);

  const typeDistribution = summary?.databaseTypeDistribution;
  const enabledHint = (total: number | undefined, unit: string) =>
    total === undefined ? undefined : `已启用 ${total} ${unit}`;

  const activities: OverviewActivity[] = [
    ...(databases?.list ?? []).map((item) => ({
      id: `database-${String(item.id)}`,
      icon: 'lucide:database',
      title: `新增「${item.name}」数据连接`,
      time: toTimestamp(item.createTime),
      tone: 'processing' as const,
    })),
    ...(sources?.list ?? []).map((item) => ({
      id: `source-${String(item.id)}`,
      icon: 'lucide:server',
      title: `登记「${item.name}」来源系统`,
      time: toTimestamp(item.createTime),
    })),
  ]
    .toSorted((a, b) => (b.time ?? 0) - (a.time ?? 0))
    .slice(0, ACTIVITY_LIMIT);

  return {
    generatedAt: Date.now(),
    metrics: {
      database: databases && {
        value: databases.total,
        hint: typeDistribution
          ? `覆盖 ${typeDistribution.length} 种数据源类型`
          : undefined,
      },
      databaseType: types && {
        value: types.total,
        hint: enabledHint(enabledTypes?.total, '种'),
      },
      systemSource: sources && {
        value: sources.total,
        hint: enabledHint(enabledSources?.total, '个'),
      },
      fileConnection: files && {
        value: files.length,
        hint: '文件管理可浏览的 FTP / HDFS 连接',
      },
      auditRule: auditRules && {
        value: auditRules.total,
        hint: enabledHint(enabledAuditRules?.total, '条'),
      },
      cleanRule: cleanRules && {
        value: cleanRules.total,
        hint: enabledHint(enabledCleanRules?.total, '条'),
      },
    },
    distributions: {
      databaseType: typeDistribution && {
        rows: typeDistribution.map((item, index) => ({
          key: `${index}-${item.name ?? ''}`,
          name: item.name ?? '未知类型',
          count: item.count ?? 0,
        })),
      },
      auditDimension: auditDimensions,
    },
    activity:
      databases || sources ? { items: activities } : undefined,
  };
}
