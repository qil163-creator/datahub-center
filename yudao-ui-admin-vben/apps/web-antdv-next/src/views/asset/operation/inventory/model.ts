import type { MetricCardItem } from '../../../dashboard/shared/data';

import type { AssetOperationApi } from '#/api/asset/operation';

/** 盘点报告顶部指标卡 */
export function inventoryCards(
  report: AssetOperationApi.Inventory,
): MetricCardItem[] {
  const idle = report.levelDistribution.IDLE ?? 0;
  const usedRate = report.publishedCount
    ? Math.round((report.usedCount * 100) / report.publishedCount)
    : 0;
  return [
    {
      key: 'total',
      title: '资产总数',
      icon: 'carbon:data-base',
      color: '#1677ff',
      value: report.totalCount,
      hint: `已发布 ${report.publishedCount} · 未发布 ${report.unpublishedCount} · 已下线 ${report.offlineCount}`,
    },
    {
      key: 'used',
      title: '被使用资产',
      icon: 'carbon:chart-line-data',
      color: '#13a8a8',
      value: report.usedCount,
      hint: `近 ${report.windowDays} 天有申请、预览/查询或接口调用，约占已发布 ${usedRate}%`,
    },
    {
      key: 'idle',
      title: '闲置资产',
      icon: 'carbon:pause-outline',
      color: '#8c8c8c',
      value: idle,
      hint: '已发布、90 天无使用且无有效授权，建议评估下线',
    },
    {
      key: 'users',
      title: '活跃用户',
      icon: 'carbon:user-multiple',
      color: '#722ed1',
      value: report.activeUserCount,
      hint: `近 ${report.windowDays} 天浏览或使用过资产的用户`,
    },
    {
      key: 'usage',
      title: '使用次数',
      icon: 'carbon:view',
      color: '#fa8c16',
      value: report.usageTotal + report.applyTotal,
      hint: `预览/查询 ${report.usageTotal} · 申请 ${report.applyTotal} · 浏览 ${report.browseTotal}`,
    },
    {
      key: 'api',
      title: '接口调用',
      icon: 'carbon:api',
      color: '#52c41a',
      value: report.apiCallTotal,
      hint: `近 ${report.windowDays} 天开放网关真实调用（不含预览与测试）`,
    },
  ];
}
