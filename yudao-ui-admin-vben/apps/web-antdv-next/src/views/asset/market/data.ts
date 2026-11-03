import type { MarketApi } from '#/api/asset/market';

export const DELIVERY_TYPE_OPTIONS = [
  { label: '数据库表', value: 1 },
  { label: 'API接口', value: 2 },
  { label: '文件', value: 3 },
];

/** 计数保持十进制精度，只在展示时加入分组分隔符。 */
export function marketCount(value?: MarketApi.Id | null): string {
  if (typeof value === 'number' && (!Number.isSafeInteger(value) || value < 0))
    return '--';
  if (value === undefined || value === null || !/^\d+$/.test(String(value)))
    return '--';
  return BigInt(value).toLocaleString('zh-CN');
}
export function deliveryLabels(values?: readonly number[]) {
  return (values ?? []).map(
    (value) =>
      DELIVERY_TYPE_OPTIONS.find((option) => option.value === value)?.label ||
      `类型 ${value}`,
  );
}
