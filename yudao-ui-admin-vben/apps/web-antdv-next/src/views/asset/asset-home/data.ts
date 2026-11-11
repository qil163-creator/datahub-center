import type { DataAssetApi } from '#/api/asset/dataasset';
import type {
  ModuleOverviewConfig,
  ModuleOverviewData,
  OverviewActivityTone,
  OverviewDistributionRow,
} from '#/views/dashboard/shared/module-overview/types';
import type { RouteTarget } from '#/views/dashboard/shared/route-resolver';

import {
  getDashboardAssetFacets,
  getDashboardAssetTagPage,
  getDashboardMarketCheckPage,
  getDashboardMarketDemandPage,
  getDashboardMartStatistics,
  getDashboardStandardTermPage,
} from '#/api/dashboard';
import { toTimestamp } from '#/views/dashboard/shared/module-overview/values';
import { DASHBOARD_TARGETS } from '#/views/dashboard/shared/route-resolver';
import { settle } from '#/views/dashboard/shared/utils';

const targets = {
  mart: { names: ['AssetMarketMart'], titles: ['数据集市'] },
  tag: { names: ['AssetTag'], titles: ['资产标签'] },
  term: { names: ['GovernanceStandardTerm'], titles: ['业务术语管理'] },
  check: { names: ['MarketCheck'], titles: ['审批工单'] },
  demand: { names: ['MarketDemandHall'], titles: ['需求大厅'] },
  usage: { names: ['AssetOperationUsage'], titles: ['资产使用与价值'] },
  myAssets: { names: ['AssetMarketMyAssets'], titles: ['我的资产'] },
} satisfies Record<string, RouteTarget>;

export const assetOverviewConfig: ModuleOverviewConfig = {
  name: '数据资产',
  banner: {
    title: '盘活全域数据资产价值',
    description:
      '汇聚注册资产、标签与业务术语，打通资产地图、数据集市与申请审批，让数据可查、可懂、可用',
    slogan: '让数据创造更大价值',
    icons: ['lucide:gem', 'lucide:map'],
  },
  metrics: [
    {
      key: 'asset',
      label: '数据资产',
      icon: 'lucide:gem',
      color: 'blue',
      target: DASHBOARD_TARGETS.dataAsset,
    },
    {
      key: 'mart',
      label: '集市资产',
      icon: 'lucide:store',
      color: 'teal',
      target: targets.mart,
    },
    {
      key: 'tag',
      label: '资产标签',
      icon: 'lucide:tags',
      color: 'indigo',
      target: targets.tag,
    },
    {
      key: 'term',
      label: '业务术语',
      icon: 'lucide:book-open-text',
      color: 'orange',
      target: targets.term,
    },
    {
      key: 'check',
      label: '审批工单',
      icon: 'lucide:file-check-2',
      color: 'green',
      target: targets.check,
    },
    {
      key: 'demand',
      label: '数据需求',
      icon: 'lucide:message-square-more',
      color: 'violet',
      target: targets.demand,
    },
  ],
  process: {
    title: '数据资产主流程',
    note: '从资产注册到价值运营',
    steps: [
      {
        title: '资产注册',
        icon: 'lucide:file-plus-2',
        lines: ['元数据表登记', '类目 / 主题域归属', '业务与技术负责人'],
        principle: '统一资产登记',
        target: DASHBOARD_TARGETS.dataAsset,
      },
      {
        title: '资产编目',
        icon: 'lucide:tags',
        lines: ['资产标签 / 业务术语', '敏感清单标识', '资产地图浏览'],
        principle: '统一资产目录',
        target: targets.tag,
      },
      {
        title: '集市上架',
        icon: 'lucide:store',
        lines: ['数据集市发布', '资产详情与样例', '收藏与检索'],
        principle: '统一资产门户',
        target: targets.mart,
      },
      {
        title: '申请审批',
        icon: 'lucide:file-check-2',
        lines: ['资产使用申请', '审批工单流转', '授权与到期管理'],
        principle: '统一授权流程',
        target: targets.check,
      },
      {
        title: '资产运营',
        icon: 'lucide:chart-no-axes-combined',
        lines: ['使用热度与价值评估', '资产盘点报告', '需求大厅对接'],
        principle: '统一价值运营',
        target: targets.usage,
      },
    ],
  },
  downstream: {
    title: '资产沉淀如何支持数据使用',
    origin: '数据资产目录',
    items: [
      {
        title: '数据查询',
        icon: 'lucide:search-code',
        description: '基于已授权资产进行即席查询',
        target: DASHBOARD_TARGETS.dataQuery,
      },
      {
        title: '我的资产',
        icon: 'lucide:briefcase',
        description: '集中管理已申请与已授权的资产',
        target: targets.myAssets,
      },
      {
        title: '数据服务',
        icon: 'lucide:radar',
        description: '资产封装为 API，对外统一供给',
        target: DASHBOARD_TARGETS.dataApi,
      },
      {
        title: '数据治理',
        icon: 'lucide:shield-check',
        description: '使用反馈驱动质量与安全治理',
        target: DASHBOARD_TARGETS.metaObject,
      },
      {
        title: '数据建模',
        icon: 'lucide:boxes',
        description: '资产语义反哺模型设计与复用',
        target: DASHBOARD_TARGETS.logicModel,
      },
      {
        title: '主题域与 BI',
        icon: 'lucide:chart-column',
        description: '支撑主题分析与报表开发',
        target: DASHBOARD_TARGETS.jimuReport,
      },
    ],
  },
  distributions: [
    {
      key: 'layer',
      title: '资产分层分布',
      empty: '暂无注册资产，注册后查看分层分布',
      target: DASHBOARD_TARGETS.dataAsset,
      linkText: '前往资产地图',
    },
    {
      key: 'category',
      title: '资产类目分布',
      empty: '暂无已归类资产，为资产设置类目后查看分布',
      target: DASHBOARD_TARGETS.dataAsset,
      linkText: '前往资产地图',
    },
  ],
  activity: {
    title: '最近申请',
    empty: '暂无资产申请，发起申请后将在此展示',
    linkText: '查看工单',
    target: targets.check,
  },
};

const ACTIVITY_LIMIT = 8;

const CHECK_TONES: Record<string, OverviewActivityTone> = {
  default: 'processing',
  error: 'error',
  success: 'success',
  warning: 'warning',
};

/** 类目为树：仅统计顶层类目，避免父子重复计数 */
function topLevel(facets: DataAssetApi.Facet[]): DataAssetApi.Facet[] {
  const keys = new Set(facets.map((facet) => facet.key));
  return facets.filter(
    (facet) =>
      !facet.parentKey || facet.parentKey === '0' || !keys.has(facet.parentKey),
  );
}

function toRows(facets: DataAssetApi.Facet[]): OverviewDistributionRow[] {
  return facets.map((facet) => ({
    key: facet.key,
    name: facet.label,
    count: facet.count,
  }));
}

/** 数据资产概览：各数据源并行拉取，单个数据源失败只影响对应卡片 */
export async function loadAssetOverview(): Promise<ModuleOverviewData> {
  const [facets, mart, tags, terms, checks, demands] = await Promise.all([
    settle(getDashboardAssetFacets()),
    settle(getDashboardMartStatistics()),
    settle(getDashboardAssetTagPage()),
    settle(getDashboardStandardTermPage()),
    settle(getDashboardMarketCheckPage(ACTIVITY_LIMIT)),
    settle(getDashboardMarketDemandPage()),
  ]);

  return {
    generatedAt: Date.now(),
    metrics: {
      asset: facets && {
        value: facets.total ?? 0,
        hint: `分层 ${facets.layers?.length ?? 0} 个 · 类目 ${topLevel(facets.categories ?? []).length} 个`,
      },
      mart: mart && {
        value: mart.totalAssetCount,
        hint: `已授权 ${String(mart.authorizedAssetCount)} 个 · 申请中 ${String(mart.applyingAssetCount)} 个`,
      },
      tag: tags && { value: tags.total, hint: '资产标签与同义词' },
      term: terms && { value: terms.total, hint: '统一的业务语言' },
      check: checks && { value: checks.total, hint: '资产使用申请审批' },
      demand: demands && { value: demands.total, hint: '需求大厅发布的数据需求' },
    },
    distributions: {
      layer: facets && { rows: toRows(facets.layers ?? []) },
      category: facets && { rows: toRows(topLevel(facets.categories ?? [])) },
    },
    activity: checks && {
      items: checks.list.map((item) => ({
        id: `check-${item.id}`,
        icon: 'lucide:file-check-2',
        title: `${item.statusLabel ?? '申请'}「${item.martName}」· ${item.applicantName}`,
        time: toTimestamp(item.applyTime),
        tone: CHECK_TONES[item.statusTone ?? 'default'] ?? 'default',
      })),
    },
  };
}
