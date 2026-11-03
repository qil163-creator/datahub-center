import type { SupplyApiLogApi } from '#/api/supply/apilog';

export type TrendRange = 7 | 15 | 30;

export interface OverviewMetric {
  color: string;
  icon: string;
  title: string;
  value: number;
}

export interface TrendPoint {
  count: number;
  date: string;
}

export interface RankItem {
  apiId?: string;
  name: string;
  value: number;
}

/** 顶部统计卡片（由概览接口返回值填充） */
export function buildOverviewItems(
  overview?: SupplyApiLogApi.Overview,
): OverviewMetric[] {
  return [
    {
      color: '#2f7df6',
      icon: 'lucide:activity',
      title: '今日调用次数',
      value: overview?.todayCount ?? 0,
    },
    {
      color: '#18a058',
      icon: 'lucide:circle-check',
      title: '今日调用成功次数',
      value: overview?.todaySuccessCount ?? 0,
    },
    {
      color: '#d03050',
      icon: 'lucide:circle-x',
      title: '今日调用失败次数',
      value: overview?.todayFailCount ?? 0,
    },
    {
      color: '#f59e0b',
      icon: 'lucide:calendar-days',
      title: '本月调用总次数',
      value: overview?.monthCount ?? 0,
    },
    {
      color: '#2f7df6',
      icon: 'lucide:code-xml',
      title: 'API总数',
      value: overview?.apiCount ?? 0,
    },
    {
      color: '#18a058',
      icon: 'lucide:rocket',
      title: 'API上线总数',
      value: overview?.onlineApiCount ?? 0,
    },
  ];
}

/** 排行数据兜底（接口字段可空） */
export function buildRankList(
  list?: { apiId?: number | string; name?: string; value?: number }[],
): RankItem[] {
  return (list ?? []).map((item) => ({
    apiId:
      item.apiId === undefined || item.apiId === null
        ? undefined
        : String(item.apiId),
    name: item.name ?? '',
    value: item.value ?? 0,
  }));
}

function formatTrendLabel(dateStr: string, days: TrendRange) {
  const [, month = '', day = ''] = dateStr.split('-');
  if (days === 7) {
    const date = new Date(
      Number(dateStr.split('-')[0]),
      Number(month) - 1,
      Number(day),
    );
    const weekday = ['日', '一', '二', '三', '四', '五', '六'][date.getDay()];
    return `周${weekday}`;
  }
  return `${month}-${day}`;
}

/** 将接口返回的近 30 天趋势裁剪为最近 N 天，并格式化坐标标签 */
export function sliceTrendList(
  trend: SupplyApiLogApi.Overview['trend'],
  days: TrendRange,
): TrendPoint[] {
  return (trend ?? []).slice(-days).map((item) => ({
    count: item.count ?? 0,
    date: formatTrendLabel(item.date ?? '', days),
  }));
}
