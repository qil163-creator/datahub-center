import type { MyAssetsApi } from '#/api/asset/my-assets';

const ACCESS_STATES: Record<
  MyAssetsApi.AccessStatus,
  { color: string; label: string }
> = {
  AVAILABLE: { label: '已生效', color: 'success' },
  NOT_STARTED: { label: '未开始', color: 'processing' },
  EXPIRED: { label: '已过期', color: 'warning' },
  PAUSED: { label: '已暂停', color: 'warning' },
  REVOKED: { label: '已收回', color: 'default' },
  DELIVERY_PENDING: { label: '交付待生效', color: 'processing' },
  DELIVERY_UNKNOWN: { label: '交付待确认', color: 'warning' },
  UNAVAILABLE: { label: '当前不可用', color: 'default' },
};

/** 新状态保守展示，不把未知状态视为可消费。 */
export function accessState(status?: string) {
  return status && Object.hasOwn(ACCESS_STATES, status)
    ? ACCESS_STATES[status as MyAssetsApi.AccessStatus]
    : {
        label: status ? `状态待确认（${status}）` : '状态待确认',
        color: 'warning',
      };
}

export function assetTitle(row: MyAssetsApi.Asset) {
  return row.marketName || row.assetName || '未命名资产';
}

export function canOpenDelivery(
  row: MyAssetsApi.Asset,
  type: 'document' | 'preview',
) {
  if (row.accessStatus !== 'AVAILABLE' || row.usable !== true) return false;
  return type === 'preview'
    ? row.deliveryType === 1 && row.canPreview === true
    : row.deliveryType === 2 && row.canViewApiDocument === true;
}
