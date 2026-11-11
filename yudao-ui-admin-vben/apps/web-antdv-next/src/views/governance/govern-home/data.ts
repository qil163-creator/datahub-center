import type {
  ModuleOverviewConfig,
  ModuleOverviewData,
  OverviewActivity,
  OverviewActivityTone,
} from '#/views/dashboard/shared/module-overview/types';
import type { RouteTarget } from '#/views/dashboard/shared/route-resolver';

import {
  getDashboardGovernanceSummary,
  getDashboardMetaCollectInstancePage,
  getDashboardQualityAlertPage,
  getDashboardQualityWorkOrderPage,
  getDashboardSafetyOverview,
} from '#/api/dashboard';
import {
  formatRate,
  toTimestamp,
} from '#/views/dashboard/shared/module-overview/values';
import { DASHBOARD_TARGETS } from '#/views/dashboard/shared/route-resolver';
import { settle } from '#/views/dashboard/shared/utils';

const targets = {
  fieldTag: { names: ['SafetyFieldTag'], titles: ['敏感字段打标'] },
  safetyLevel: { names: ['SafetyLevel'], titles: ['数据分级'] },
  qualityAlert: { names: ['GovernanceQualityAlert'], titles: ['告警记录'] },
} satisfies Record<string, RouteTarget>;

export const governOverviewConfig: ModuleOverviewConfig = {
  name: '数据治理',
  banner: {
    title: '构建全域可信的数据治理体系',
    description:
      '以元数据为基础，贯通数据标准、数据质量、数据安全与主数据管理，持续提升数据的可信度与可用性',
    slogan: '让数据更可信',
    icons: ['lucide:shield-check', 'lucide:file-search'],
  },
  metrics: [
    {
      key: 'metaTable',
      label: '元数据表',
      icon: 'lucide:table-2',
      color: 'blue',
      unit: '张',
      target: DASHBOARD_TARGETS.metaObject,
    },
    {
      key: 'standard',
      label: '标准数据元',
      icon: 'lucide:book-check',
      color: 'teal',
      target: DASHBOARD_TARGETS.standardElement,
    },
    {
      key: 'qualityRule',
      label: '质量规则',
      icon: 'lucide:list-checks',
      color: 'indigo',
      unit: '条',
      target: DASHBOARD_TARGETS.qualityRule,
    },
    {
      key: 'qualityTask',
      label: '质量任务',
      icon: 'lucide:clipboard-check',
      color: 'orange',
      target: DASHBOARD_TARGETS.qualityTask,
    },
    {
      key: 'sensitive',
      label: '敏感字段',
      icon: 'lucide:lock-keyhole',
      color: 'rose',
      target: targets.fieldTag,
    },
    {
      key: 'masterModel',
      label: '主数据模型',
      icon: 'lucide:crown',
      color: 'violet',
      target: DASHBOARD_TARGETS.masterModel,
    },
  ],
  process: {
    title: '数据治理主流程',
    note: '从摸清家底到持续运营',
    steps: [
      {
        title: '元数据管理',
        icon: 'lucide:scan-search',
        lines: ['采集任务 / 采集实例', '最新 / 定版元数据', '元数据检索与标签'],
        principle: '摸清数据家底',
        target: DASHBOARD_TARGETS.metaCollectTask,
      },
      {
        title: '数据标准',
        icon: 'lucide:book-check',
        lines: ['标准数据元 / 代码表', '标准文件登记', '字段落标映射'],
        principle: '统一数据标准',
        target: DASHBOARD_TARGETS.standardElement,
      },
      {
        title: '数据质量',
        icon: 'lucide:list-checks',
        lines: ['质量规则与规则集', '质量任务与实例', '报告 / 工单 / 评分'],
        principle: '提升数据质量',
        target: DASHBOARD_TARGETS.qualityRule,
      },
      {
        title: '数据安全',
        icon: 'lucide:lock-keyhole',
        lines: ['数据分类分级', '敏感识别与打标', '脱敏 / 权限 / 审计'],
        principle: '保障数据安全',
        target: targets.safetyLevel,
      },
      {
        title: '主数据管理',
        icon: 'lucide:crown',
        lines: ['主数据域与模型', '编码规则与审批', '维护 / 推送 / 变更'],
        principle: '统一核心数据',
        target: DASHBOARD_TARGETS.masterModel,
      },
    ],
  },
  downstream: {
    title: '治理成果如何支撑后续模块',
    origin: '数据治理成果',
    items: [
      {
        title: '数据建模',
        icon: 'lucide:boxes',
        description: '标准数据元约束模型字段与统一口径',
        target: DASHBOARD_TARGETS.logicModel,
      },
      {
        title: '数据研发',
        icon: 'lucide:code-xml',
        description: '质量检核嵌入加工链路，问题早发现',
        target: DASHBOARD_TARGETS.devWorkbench,
      },
      {
        title: '数据资产',
        icon: 'lucide:gem',
        description: '元数据与质量结果丰富资产画像',
        target: DASHBOARD_TARGETS.dataAsset,
      },
      {
        title: '数据服务',
        icon: 'lucide:radar',
        description: '分级与脱敏策略保障对外服务安全',
        target: DASHBOARD_TARGETS.dataApi,
      },
      {
        title: '业务系统',
        icon: 'lucide:send',
        description: '主数据推送保障核心数据一致',
        target: DASHBOARD_TARGETS.masterDistribute,
      },
      {
        title: '主题域与 BI',
        icon: 'lucide:chart-column',
        description: '可信数据支撑经营分析与决策',
        target: DASHBOARD_TARGETS.jimuReport,
      },
    ],
  },
  distributions: [
    {
      key: 'coverage',
      title: '治理覆盖率',
      mode: 'rate',
      empty: '暂无治理统计，采集元数据并执行检核后查看',
      target: DASHBOARD_TARGETS.metaObject,
      linkText: '前往元数据管理',
    },
    {
      key: 'alertLevel',
      title: '质量告警等级分布',
      empty: '暂无质量告警，质量任务执行后查看等级分布',
      target: targets.qualityAlert,
      linkText: '前往告警记录',
    },
  ],
  activity: {
    title: '最近动态',
    empty: '暂无治理动态，采集元数据或执行质量任务后将在此展示',
    linkText: '查看采集实例',
    target: DASHBOARD_TARGETS.metaCollectInstance,
  },
};

const ACTIVITY_LIMIT = 8;

/** 元数据采集实例状态：0 等待 1 运行 2 成功 3 失败 4 部分成功 5 已丢弃 */
const COLLECT_STATUS: Record<number, [string, OverviewActivityTone]> = {
  0: ['等待中', 'default'],
  1: ['采集中', 'processing'],
  2: ['采集成功', 'success'],
  3: ['采集失败', 'error'],
  4: ['部分成功', 'warning'],
  5: ['已丢弃', 'default'],
};

/** 数据治理概览：各数据源并行拉取，单个数据源失败只影响对应卡片 */
export async function loadGovernOverview(): Promise<ModuleOverviewData> {
  const [summary, safety, collects, alerts, workOrders] = await Promise.all([
    settle(getDashboardGovernanceSummary()),
    settle(getDashboardSafetyOverview()),
    settle(getDashboardMetaCollectInstancePage(ACTIVITY_LIMIT)),
    settle(getDashboardQualityAlertPage(ACTIVITY_LIMIT)),
    settle(getDashboardQualityWorkOrderPage(ACTIVITY_LIMIT)),
  ]);

  const meta = summary?.meta;
  const standard = summary?.standard;
  const alert = summary?.qualityAlert;

  const activities: OverviewActivity[] = [
    ...(collects?.list ?? []).map((item) => {
      const [label, tone] = (item.status === undefined
        ? undefined
        : COLLECT_STATUS[item.status]) ?? ['完成采集', 'default'];
      return {
        id: `collect-${String(item.id)}`,
        icon: 'lucide:scan-search',
        title: `元数据${label}「${item.datasourceName ?? item.taskName ?? '数据源'}」`,
        time: toTimestamp(item.startTime ?? item.createTime),
        tone,
      };
    }),
    ...(alerts?.list ?? []).map((item) => ({
      id: `alert-${String(item.id)}`,
      icon: 'lucide:bell-ring',
      title: item.alertTitle ?? `「${item.taskName ?? '质量任务'}」触发质量告警`,
      time: toTimestamp(item.alertTime ?? item.createTime),
      tone: 'error' as const,
    })),
    ...(workOrders?.list ?? []).map((item) => ({
      id: `order-${String(item.id)}`,
      icon: 'lucide:clipboard-check',
      title: `新建问题工单「${item.orderName ?? item.taskName ?? item.id}」`,
      time: toTimestamp(item.createTime),
      tone: 'warning' as const,
    })),
  ]
    .toSorted((a, b) => (b.time ?? 0) - (a.time ?? 0))
    .slice(0, ACTIVITY_LIMIT);

  return {
    generatedAt: Date.now(),
    metrics: {
      metaTable: meta && {
        value: meta.tableCount ?? 0,
        hint: `已定版 ${meta.publishedTableCount ?? 0} 张 · 字段 ${meta.columnCount ?? 0} 个`,
      },
      standard: standard && {
        value: standard.totalCount ?? 0,
        hint: `已发布 ${standard.publishedCount ?? 0} 个 · 落标率 ${formatRate(standard.mappingRate)}`,
      },
      qualityRule: summary && {
        value: summary.qualityRuleCount ?? 0,
        hint: `已启用 ${summary.qualityRuleEnabledCount ?? 0} 条`,
      },
      qualityTask: summary && {
        value: summary.qualityTaskCount ?? 0,
        hint: summary.qualityReport?.totalCount
          ? `检核通过率 ${formatRate(summary.qualityReport.avgPassRate)}`
          : `待处理工单 ${summary.pendingWorkOrderCount ?? 0} 个`,
      },
      sensitive: safety && {
        value: safety.sensitiveColumnCount ?? 0,
        hint: `待确认打标 ${safety.pendingTagCount ?? 0} 个`,
      },
      masterModel: summary && {
        value: summary.masterModelCount ?? 0,
        hint: `已发布 ${summary.masterModelReleasedCount ?? 0} 个`,
      },
    },
    distributions: {
      coverage: summary && {
        rows: [
          { key: 'comment', name: '元数据注释覆盖率', rate: meta?.commentCoverage },
          { key: 'owner', name: '元数据负责人覆盖率', rate: meta?.ownerCoverage },
          { key: 'collect', name: '元数据采集成功率', rate: meta?.collectSuccessRate },
          { key: 'mapping', name: '数据标准落标率', rate: standard?.mappingRate },
          {
            key: 'quality',
            name: '质量检核通过率',
            rate: summary.qualityReport?.totalCount
              ? summary.qualityReport.avgPassRate
              : null,
          },
        ],
      },
      alertLevel: alert && {
        rows: [
          { key: 'general', name: '一般', count: alert.generalCount ?? 0 },
          { key: 'serious', name: '严重', count: alert.seriousCount ?? 0 },
          { key: 'danger', name: '危险', count: alert.dangerCount ?? 0 },
        ],
      },
    },
    activity:
      collects || alerts || workOrders ? { items: activities } : undefined,
  };
}
