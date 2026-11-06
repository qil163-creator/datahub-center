import type { MarketApi } from '#/api/asset/market';

/** 共享审批决定与授权交付结果的呈现规则，批准不等于已开通。 */

/** 交付结果；undefined 表示这条申请本就不需要远端交付。 */
export type DeliveryResolution = MarketApi.DeliveryResolution;

const DELIVERY_LABELS: Record<DeliveryResolution, string> = {
  APPLIED: '已生效',
  PENDING: '待生效',
  REJECTED: '交付被拒绝',
  REVOKED: '撤销已确认',
  SUPERSEDED: '已被更新操作取代',
  SUSPENDED: '暂停已确认',
  UNKNOWN: '结果待确认',
};

const DELIVERY_COLORS: Record<DeliveryResolution, string> = {
  APPLIED: 'success',
  PENDING: 'processing',
  REJECTED: 'error',
  REVOKED: 'default',
  SUPERSEDED: 'warning',
  SUSPENDED: 'warning',
  UNKNOWN: 'warning',
};

/** 冻结请求意图与交付结果分离；不得从历史技术 opType 推断。 */
const DELIVERY_ACTION_LABELS: Record<MarketApi.DeliveryAction, string> = {
  GRANT: '请求授权',
  REVOKE: '请求撤销',
  SUSPEND: '请求暂停',
  UNKNOWN: '动作待核查',
};

export function deliveryActionLabel(action?: null | string): string {
  return action && Object.hasOwn(DELIVERY_ACTION_LABELS, action)
    ? DELIVERY_ACTION_LABELS[action as MarketApi.DeliveryAction]
    : DELIVERY_ACTION_LABELS.UNKNOWN;
}

export function deliveryLabel(value?: DeliveryResolution): string {
  return value
    ? (DELIVERY_LABELS[value] ?? `未知交付状态（${value}）`)
    : '无需交付';
}

export function deliveryColor(value?: DeliveryResolution): string {
  return value ? (DELIVERY_COLORS[value] ?? 'warning') : 'default';
}

/** 审批决定与交付结果合成的"当前是否真的可用"，批准不等于已开通。 */
export function applyAccessLabel(
  effective?: boolean,
  resolution?: DeliveryResolution,
  usable?: boolean,
): string {
  if (!effective) {
    if (resolution === 'SUSPENDED' || resolution === 'REVOKED') {
      return `未生效，${deliveryLabel(resolution)}`;
    }
    return '未生效';
  }
  if (!resolution || resolution === 'APPLIED') {
    if (usable === true) return '可用';
    return usable === false
      ? '已批准，当前不可用'
      : '已批准，实际访问范围待确认';
  }
  return `已批准，${deliveryLabel(resolution)}`;
}

/** 整体禁用与逐来源撤销是两件事，提示语不能共用。 */
export function controlActionText(disabled: boolean): string {
  return disabled
    ? '整体禁用（保留来源记录，可再启用）'
    : '重新启用（按来源账本恢复）';
}
