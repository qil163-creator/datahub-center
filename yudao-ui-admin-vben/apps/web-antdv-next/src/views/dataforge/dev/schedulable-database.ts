import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { getDevSchedulableProducts } from '#/api/dataforge/dev';

/** 可调度产品清单；为 undefined 表示清单未能读取，此时不做界面限制，以后端保存与提交校验为准。 */
export type SchedulableProducts = ReadonlySet<string> | undefined;

export const UNSCHEDULABLE_DATABASE_HINT = '（暂不支持调度）';

/** 读取可调度执行 SQL 任务的数据库产品；失败已由请求客户端统一提示，这里降级为不限制。 */
export async function loadSchedulableProducts(): Promise<SchedulableProducts> {
  try {
    return new Set(await getDevSchedulableProducts());
  } catch {
    return undefined;
  }
}

/** 判断数据源能否调度执行 SQL 任务；清单未知时返回 undefined。 */
export function isSchedulableDatabase(
  productId: string | undefined,
  products: SchedulableProducts,
): boolean | undefined {
  if (products === undefined) return undefined;
  return productId !== undefined && products.has(productId);
}

/** SQL 任务的数据源下拉项：不可调度的连接保留标注回显，但不能再被选中。 */
export function schedulableDatabaseOption(option: {
  label: string;
  schedulable?: boolean;
  value: DataforgeDevApi.Id;
}) {
  const blocked = option.schedulable === false;
  return {
    label: blocked ? `${option.label}${UNSCHEDULABLE_DATABASE_HINT}` : option.label,
    value: String(option.value),
    disabled: blocked,
  };
}
