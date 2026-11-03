import type { ModelHomeApi } from '#/api/governance/model-home';

/* oxlint-disable unicorn/prefer-bigint-literals -- 保持 ES2015 构建目标的语法兼容，运行时仍使用平台已采用的 BigInt。 */
const ZERO = BigInt(0);
const TWO = BigInt(2);
const PERCENT_SCALE = BigInt(1000);
/* oxlint-enable unicorn/prefer-bigint-literals */

/** 统计数量不经过浮点转换，避免大整数显示失真。 */
export function displayCount(value?: ModelHomeApi.Count | null): string {
  return value === null || value === undefined
    ? '—'
    : BigInt(value).toLocaleString('zh-CN');
}

export function distributionTotal(rows: ModelHomeApi.Distribution[]): string {
  return rows.reduce((sum, row) => sum + BigInt(row.count), ZERO).toString();
}

/** 分布占比仅用于图表展示，按整数运算四舍五入至一位小数。 */
export function distributionPercent(
  count: ModelHomeApi.Count,
  total: ModelHomeApi.Count,
): number {
  const denominator = BigInt(total);
  if (denominator <= ZERO) return 0;
  const scaled =
    (BigInt(count) * PERCENT_SCALE + denominator / TWO) / denominator;
  return Math.min(100, Math.max(0, Number(scaled) / 10));
}
