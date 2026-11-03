import type { MarketApi } from '#/api/asset/market';

interface ApprovalStatusDisplay {
  message: string;
  type: 'error' | 'info' | 'warning';
}

// 原批准凭证核对不是系统字典，也不是交付结果或当前访问权限。
const APPROVAL_STATUS: Record<
  MarketApi.DeliveryApprovalStatus,
  Readonly<ApprovalStatusDisplay>
> = {
  MATCHED: {
    message:
      '原批准凭证核对：匹配。原交付命令与正式批准凭证一致，不代表当前可访问。',
    type: 'info',
  },
  MISSING: {
    message:
      '原批准凭证核对：缺失。未找到正式批准凭证，不能用已执行回执替代批准证明。',
    type: 'warning',
  },
  CONFLICT: {
    message:
      '原批准凭证核对：冲突。原交付证据与正式批准凭证不一致，已执行回执不能消除该冲突。',
    type: 'error',
  },
  UNVERIFIABLE: {
    message:
      '原批准凭证核对：无法核验。证据不足、旧记录未核验或返回状态未知，不能据此确认原批准。',
    type: 'warning',
  },
  NOT_APPLICABLE: {
    message:
      '原批准凭证核对：不适用。本次操作不适用批准凭证核对，不以本项证明原批准或当前可访问。',
    type: 'info',
  },
};

/** 只呈现服务端结果；不得据此放行、重投或推断原批准/当前访问权限。 */
export function approvalStatusDisplay(
  value: unknown,
): Readonly<ApprovalStatusDisplay> {
  return typeof value === 'string' && Object.hasOwn(APPROVAL_STATUS, value)
    ? APPROVAL_STATUS[value as MarketApi.DeliveryApprovalStatus]
    : APPROVAL_STATUS.UNVERIFIABLE;
}
