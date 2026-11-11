import type { RouteTarget } from '../route-resolver';

/**
 * 模块概览页（基础管理 / 数据研发 / 数据治理 / 数据资产 / 数据服务）的通用契约。
 *
 * 静态部分（横幅、指标卡定义、主流程、下游支撑、面板标题）由各模块页面声明；
 * 动态部分（指标值、分布、动态）由各模块的加载函数从既有接口聚合得到。
 * 约定：取值为 undefined 表示该数据源无权限或服务不可用，与真实的 0 严格区分。
 */

/** 计数遵循后端 Long 无损传输协议：安全范围内为 number，超出时为十进制字符串 */
export type OverviewCount = number | string;

export type OverviewColor =
  | 'blue'
  | 'green'
  | 'indigo'
  | 'orange'
  | 'rose'
  | 'teal'
  | 'violet';

export interface OverviewMetricDefinition {
  key: string;
  label: string;
  icon: string;
  color: OverviewColor;
  /** 数值单位，默认“个” */
  unit?: string;
  target?: RouteTarget;
}

export interface OverviewMetricValue {
  /** 计数值；null 表示服务明确返回“暂无统计” */
  value?: null | OverviewCount;
  /** 已格式化的数值（如成功率、耗时），优先于 value 展示 */
  display?: string;
  /** 卡片底部说明 */
  hint?: string;
}

export interface OverviewStep {
  title: string;
  icon: string;
  lines: string[];
  principle: string;
  target?: RouteTarget;
}

export interface OverviewLink {
  title: string;
  icon: string;
  description: string;
  target?: RouteTarget;
}

/**
 * 分布面板：
 * - share：各行为计数，按占总数的比例绘制进度条；
 * - rate：各行为 0~100 的比率（覆盖率、通过率等），按比率本身绘制进度条。
 */
export type OverviewDistributionMode = 'rate' | 'share';

export interface OverviewDistributionDefinition {
  key: string;
  title: string;
  mode?: OverviewDistributionMode;
  /** 有权限但没有数据时的提示 */
  empty: string;
  target?: RouteTarget;
  /** 空态下的跳转按钮文案 */
  linkText?: string;
}

export interface OverviewDistributionRow {
  key: string;
  name: string;
  code?: null | string;
  /** share 模式的计数 */
  count?: OverviewCount;
  /** rate 模式的比率（0~100），null 表示暂无统计 */
  rate?: null | number;
}

export interface OverviewDistributionValue {
  rows: OverviewDistributionRow[];
}

export type OverviewActivityTone =
  | 'default'
  | 'error'
  | 'processing'
  | 'success'
  | 'warning';

export interface OverviewActivity {
  id: string;
  icon: string;
  title: string;
  /** 毫秒时间戳 */
  time?: number;
  tone?: OverviewActivityTone;
  /** 右侧补充标签（如状态），未提供时展示相对时间 */
  tag?: string;
}

export interface OverviewActivityDefinition {
  title: string;
  empty: string;
  linkText?: string;
  target?: RouteTarget;
}

export interface OverviewActivityValue {
  items: OverviewActivity[];
}

export interface ModuleOverviewConfig {
  /** 用于无障碍标签，如“基础管理” */
  name: string;
  banner: {
    description: string;
    /** 横幅插画左右两侧的悬浮图标 */
    icons: [string, string];
    slogan: string;
    title: string;
  };
  metrics: OverviewMetricDefinition[];
  process: {
    note: string;
    steps: OverviewStep[];
    title: string;
  };
  downstream: {
    items: OverviewLink[];
    origin: string;
    title: string;
  };
  distributions: [OverviewDistributionDefinition, OverviewDistributionDefinition];
  activity: OverviewActivityDefinition;
}

export interface ModuleOverviewData {
  metrics: Record<string, OverviewMetricValue | undefined>;
  /** undefined 表示无权限或服务不可用 */
  distributions: Record<string, OverviewDistributionValue | undefined>;
  activity?: OverviewActivityValue;
  /** 统计生成时间（毫秒），用于计算相对时间 */
  generatedAt: number;
}
