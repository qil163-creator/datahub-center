import type { SupplyDataApiApi } from '#/api/supply/dataapi';

export function etlBatchCursor(value: unknown): string {
  if (typeof value === 'number' && !Number.isSafeInteger(value))
    throw new Error('游标超出安全数字范围，请使用十进制字符串。');
  const cursor = String(value ?? '0').trim();
  if (
    !/^(?:0|[1-9]\d{0,18})$/.test(cursor) ||
    BigInt(cursor) > 9_223_372_036_854_775_807n
  ) {
    throw new Error(
      '游标必须是非负十进制整数字符串，请直接使用上次返回的 nextAfterRow。',
    );
  }
  return cursor;
}
export function etlBatchConfigError(
  api: SupplyDataApiApi.DataApi,
): string | undefined {
  if (api.configType !== 'ETL_BATCH') return;
  const id = String(api.etlBatchId ?? '');
  if (!/^[1-9]\d{0,18}$/.test(id) || BigInt(id) > 9_223_372_036_854_775_807n)
    return '请输入有效的 ETL 目标批次 ID（从运行目标批次详情复制）。';
  if (
    api.apiType !== 'QUERY' ||
    api.resultFormat !== 'LIST' ||
    !['APPKEY', 'SIGN'].includes(api.authType ?? '') ||
    api.cacheEnabled !== 0
  )
    return 'ETL 批次仅支持查询、列表与认证访问，并必须关闭缓存。';
}
