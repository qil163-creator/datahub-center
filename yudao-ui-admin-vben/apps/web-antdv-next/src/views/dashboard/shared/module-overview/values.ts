import type { OverviewCount, OverviewDistributionRow } from './types';

import { formatDate } from '@vben/utils';

/* oxlint-disable unicorn/prefer-bigint-literals -- 保持 ES2015 构建目标的语法兼容，运行时仍使用平台已采用的 BigInt。 */
const ZERO = BigInt(0);
const TWO = BigInt(2);
const PERCENT_SCALE = BigInt(1000);
/* oxlint-enable unicorn/prefer-bigint-literals */

function toBigInt(value: OverviewCount): bigint | undefined {
  try {
    return BigInt(value);
  } catch {
    return undefined;
  }
}

/** 计数展示：不可用显示“—”，整数不经过浮点转换，避免大整数失真 */
export function displayCount(value?: null | OverviewCount): string {
  if (value === null || value === undefined) return '—';
  const big = toBigInt(value);
  return big === undefined ? String(value) : big.toLocaleString('zh-CN');
}

/** 汇总分布计数（十进制字符串） */
export function sumCounts(rows: OverviewDistributionRow[]): string {
  return rows
    .reduce((sum, row) => sum + (toBigInt(row.count ?? 0) ?? ZERO), ZERO)
    .toString();
}

/** 分布占比仅用于图表展示，按整数运算四舍五入至一位小数 */
export function sharePercent(
  count: OverviewCount | undefined,
  total: OverviewCount,
): number {
  const denominator = toBigInt(total) ?? ZERO;
  const numerator = toBigInt(count ?? 0) ?? ZERO;
  if (denominator <= ZERO) return 0;
  const scaled = (numerator * PERCENT_SCALE + denominator / TWO) / denominator;
  return Math.min(100, Math.max(0, Number(scaled) / 10));
}

/** 比率钳制到 0~100；无统计返回 0（仅用于进度条宽度） */
export function clampRate(rate?: null | number): number {
  if (rate === null || rate === undefined || Number.isNaN(rate)) return 0;
  return Math.min(100, Math.max(0, rate));
}

/** 比率展示：后端各统计接口统一返回 0~100 口径 */
export function formatRate(rate?: null | number, digits = 1): string {
  if (rate === null || rate === undefined || Number.isNaN(rate)) return '—';
  return `${clampRate(rate).toFixed(digits)}%`;
}

/** 部分占总体的百分比（近似展示），总数为 0 时返回 null */
export function ratioOf(part?: number, total?: number): null | number {
  if (part === undefined || total === undefined || total <= 0) return null;
  return (part / total) * 100;
}

/** 后端时间统一转为毫秒时间戳：兼容时间戳数字、数字字符串及“YYYY-MM-DD HH:mm:ss” */
export function toTimestamp(value: unknown): number | undefined {
  if (value === null || value === undefined || value === '') return undefined;
  if (value instanceof Date) {
    const time = value.getTime();
    return Number.isFinite(time) ? time : undefined;
  }
  if (typeof value === 'number') return Number.isFinite(value) ? value : undefined;
  if (typeof value !== 'string') return undefined;
  const time = /^\d+$/.test(value)
    ? Number(value)
    : Date.parse(value.includes('T') ? value : value.replace(' ', 'T'));
  return Number.isFinite(time) ? time : undefined;
}

/** 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前，超过 30 天显示月日 */
export function relativeTime(time: number, now: number): string {
  const elapsed = Math.max(0, now - time);
  if (elapsed < 60_000) return '刚刚';
  if (elapsed < 3_600_000) return `${Math.floor(elapsed / 60_000)} 分钟前`;
  if (elapsed < 86_400_000) return `${Math.floor(elapsed / 3_600_000)} 小时前`;
  if (elapsed < 2_592_000_000)
    return `${Math.floor(elapsed / 86_400_000)} 天前`;
  return formatDate(time, 'MM-DD');
}
