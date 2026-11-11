import type {
  ModuleOverviewConfig,
  ModuleOverviewData,
} from '#/views/dashboard/shared/module-overview/types';
import type { RouteTarget } from '#/views/dashboard/shared/route-resolver';

import {
  getDashboardApiLogPage,
  getDashboardApiLogStatistics,
  getDashboardSupplyAppPage,
} from '#/api/dashboard';
import {
  formatRate,
  ratioOf,
  toTimestamp,
} from '#/views/dashboard/shared/module-overview/values';
import { DASHBOARD_TARGETS } from '#/views/dashboard/shared/route-resolver';
import { settle } from '#/views/dashboard/shared/utils';

/** 调用统计窗口（天） */
const STAT_DAYS = 7;

const targets = {
  apiTest: { names: ['SupplyApiTest'], titles: ['接口测试'] },
  mart: { names: ['AssetMarketMart'], titles: ['数据集市'] },
  safetyRule: { names: ['SafetyRule'], titles: ['脱敏规则库'] },
  usage: { names: ['AssetOperationUsage'], titles: ['资产使用与价值'] },
} satisfies Record<string, RouteTarget>;

export const supplyOverviewConfig: ModuleOverviewConfig = {
  name: '数据服务',
  banner: {
    title: '打造安全高效的数据服务体系',
    description:
      '统一 API 开发、测试、发布、授权与监控，以标准化服务接口让数据安全、高效地流向业务',
    slogan: '让数据触手可及',
    icons: ['lucide:radar', 'lucide:key-round'],
  },
  metrics: [
    {
      key: 'api',
      label: 'API 总数',
      icon: 'lucide:code-xml',
      color: 'blue',
      target: DASHBOARD_TARGETS.dataApi,
    },
    {
      key: 'online',
      label: '已上线 API',
      icon: 'lucide:rocket',
      color: 'teal',
      target: DASHBOARD_TARGETS.dataApi,
    },
    {
      key: 'app',
      label: '接入应用',
      icon: 'lucide:app-window',
      color: 'indigo',
      target: DASHBOARD_TARGETS.supplyApp,
    },
    {
      key: 'today',
      label: '今日调用',
      icon: 'lucide:activity',
      color: 'orange',
      unit: '次',
      target: DASHBOARD_TARGETS.apiLog,
    },
    {
      key: 'successRate',
      label: `近 ${STAT_DAYS} 日成功率`,
      icon: 'lucide:circle-check',
      color: 'green',
      target: DASHBOARD_TARGETS.apiLog,
    },
    {
      key: 'duration',
      label: `近 ${STAT_DAYS} 日平均耗时`,
      icon: 'lucide:timer',
      color: 'violet',
      target: DASHBOARD_TARGETS.apiLog,
    },
  ],
  process: {
    title: '数据服务主流程',
    note: '从服务开发到运营监控',
    steps: [
      {
        title: '服务开发',
        icon: 'lucide:code-xml',
        lines: ['SQL / 向导式配置', '请求参数与返回定义', '服务分类目录'],
        principle: '统一服务规范',
        target: DASHBOARD_TARGETS.dataApi,
      },
      {
        title: '接口测试',
        icon: 'lucide:flask-conical',
        lines: ['在线调试与预览', '参数校验', '返回结果核对'],
        principle: '统一测试验证',
        target: targets.apiTest,
      },
      {
        title: '发布上线',
        icon: 'lucide:rocket',
        lines: ['版本发布与回滚', '限流 / 熔断 / 缓存', '上线与下线管理'],
        principle: '统一发布管控',
        target: DASHBOARD_TARGETS.dataApi,
      },
      {
        title: '应用授权',
        icon: 'lucide:key-round',
        lines: ['应用注册与密钥', 'API 授权与配额', '签名鉴权'],
        principle: '统一访问授权',
        target: DASHBOARD_TARGETS.supplyApp,
      },
      {
        title: '调用监控',
        icon: 'lucide:activity',
        lines: ['调用记录与日志', '成功率与耗时', '调用排行分析'],
        principle: '统一运营监控',
        target: DASHBOARD_TARGETS.apiLog,
      },
    ],
  },
  downstream: {
    title: '数据服务如何赋能业务',
    origin: '标准数据服务',
    items: [
      {
        title: '业务系统',
        icon: 'lucide:app-window',
        description: '通过标准 API 获取实时、可信的数据',
        target: DASHBOARD_TARGETS.supplyApp,
      },
      {
        title: '数据大屏',
        icon: 'lucide:monitor',
        description: '为可视化大屏提供统一数据接口',
        target: DASHBOARD_TARGETS.goView,
      },
      {
        title: '报表分析',
        icon: 'lucide:chart-column',
        description: '支撑报表与主题分析按需取数',
        target: DASHBOARD_TARGETS.jimuReport,
      },
      {
        title: '数据集市',
        icon: 'lucide:store',
        description: '服务化资产在集市中统一供给',
        target: targets.mart,
      },
      {
        title: '数据安全',
        icon: 'lucide:shield-check',
        description: '结合脱敏规则保障服务输出安全',
        target: targets.safetyRule,
      },
      {
        title: '资产运营',
        icon: 'lucide:gem',
        description: '调用量反哺资产使用与价值评估',
        target: targets.usage,
      },
    ],
  },
  distributions: [
    {
      key: 'trend',
      title: `近 ${STAT_DAYS} 日调用分布`,
      empty: `近 ${STAT_DAYS} 日暂无 API 调用`,
      target: DASHBOARD_TARGETS.apiLog,
      linkText: '前往调用记录',
    },
    {
      key: 'top',
      title: `近 ${STAT_DAYS} 日调用量 TOP 接口`,
      empty: `近 ${STAT_DAYS} 日暂无调用排行`,
      target: DASHBOARD_TARGETS.dataApi,
      linkText: '前往 API 管理',
    },
  ],
  activity: {
    title: '最近调用',
    empty: '暂无调用记录，API 被调用后将在此展示',
    linkText: '查看记录',
    target: DASHBOARD_TARGETS.apiLog,
  },
};

const ACTIVITY_LIMIT = 8;

/** 数据服务概览：各数据源并行拉取，单个数据源失败只影响对应卡片 */
export async function loadSupplyOverview(): Promise<ModuleOverviewData> {
  const [stats, apps, logs] = await Promise.all([
    settle(getDashboardApiLogStatistics(STAT_DAYS)),
    settle(getDashboardSupplyAppPage()),
    settle(getDashboardApiLogPage(ACTIVITY_LIMIT)),
  ]);

  const called = (stats?.totalCount ?? 0) > 0;
  const onlineRate = ratioOf(stats?.onlineApiCount, stats?.apiCount);

  return {
    generatedAt: Date.now(),
    metrics: {
      api: stats && {
        value: stats.apiCount ?? 0,
        hint: `已上线 ${stats.onlineApiCount ?? 0} 个`,
      },
      online: stats && {
        value: stats.onlineApiCount ?? 0,
        hint: `上线率 ${formatRate(onlineRate)}`,
      },
      app: apps && { value: apps.total, hint: '已注册的调用方应用' },
      today: stats && {
        value: stats.todayCount ?? 0,
        hint: `近 ${STAT_DAYS} 日累计 ${stats.totalCount ?? 0} 次`,
      },
      successRate: stats && {
        display: called ? formatRate(stats.successRate) : '—',
        hint: called
          ? `近 ${STAT_DAYS} 日失败 ${stats.failCount ?? 0} 次`
          : `近 ${STAT_DAYS} 日暂无调用`,
      },
      duration: stats && {
        display: called ? `${Math.round(stats.avgDurationMs ?? 0)} ms` : '—',
        hint: called ? '单次调用平均响应耗时' : `近 ${STAT_DAYS} 日暂无调用`,
      },
    },
    distributions: {
      trend: stats && {
        rows: (stats.trend ?? []).map((point, index) => ({
          key: point.date ?? String(index),
          name: point.date?.slice(5) ?? '—',
          count: (point.successCount ?? 0) + (point.failCount ?? 0),
        })),
      },
      top: stats && {
        rows: (stats.topApis ?? []).map((item, index) => ({
          key: String(item.apiId ?? index),
          name: item.name ?? '未命名接口',
          count: item.value ?? 0,
        })),
      },
    },
    activity: logs && {
      items: logs.list.map((log) => ({
        id: `log-${String(log.id)}`,
        icon: log.status === 1 ? 'lucide:circle-check' : 'lucide:circle-x',
        title: `调用「${log.apiName ?? log.callPath ?? '接口'}」${log.status === 1 ? '成功' : '失败'}${log.durationMs === undefined ? '' : ` · ${log.durationMs} ms`}`,
        time: toTimestamp(log.callTime),
        tone: log.status === 1 ? ('success' as const) : ('error' as const),
      })),
    },
  };
}
