import type { App } from 'vue';

import type { MarketApi } from '#/api/asset/market';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getMarketDeliveryEvidence,
  getMarketDeliveryOperation,
  getMarketDeliveryReviews,
  recheckMarketDelivery,
  recordMarketDeliveryRedeliver,
} from '#/api/asset/market';

import DeliveryReview from './delivery-review.vue';

const harness = vi.hoisted(() => ({
  close: undefined as (() => Promise<void>) | undefined,
  confirm: undefined as (() => Promise<void>) | undefined,
  open: undefined as
    | ((operationId: string, applyId?: string) => void)
    | undefined,
  state: undefined as
    | undefined
    | {
        allowed: boolean;
        redeliver: boolean;
        accessToken: string;
        userInfo: { userId: string };
        refreshToken: string;
      },
  modals: [] as {
    close: () => Promise<void>;
    confirm: (() => Promise<void>) | undefined;
    getData: () => unknown;
    lock: ReturnType<typeof vi.fn>;
    open: (payload?: unknown) => void;
    options: {
      onConfirm?: () => Promise<void>;
      onOpenChange?: (open: boolean) => void;
      title?: string;
    };
    setData: (payload: unknown) => unknown;
    unlock: ReturnType<typeof vi.fn>;
  }[],
  lock: vi.fn(),
  unlock: vi.fn(),
  success: vi.fn(),
  toast: vi.fn(),
  decisionData: vi.fn(),
  decisionOpen: vi.fn(),
  disposalData: vi.fn(),
  disposalOpen: vi.fn(),
}));
vi.mock('@vben/stores', () => {
  harness.state = reactive({
    allowed: true,
    redeliver: true,
    accessToken: '1',
    userInfo: { userId: '9' },
    refreshToken: '',
  });
  return {
    useAccessStore: () => harness.state,
    useUserStore: () => harness.state,
  };
});
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      harness.state!.allowed &&
      (codes[0] !== 'asset:market:redeliver' || harness.state!.redeliver),
  }),
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: {
    connectedComponent?: unknown;
    onConfirm: () => Promise<void>;
    onOpenChange: (open: boolean) => void;
    title?: string;
  }) => {
    if (options.connectedComponent) {
      const disposal =
        (options.connectedComponent as { __name?: string }).__name ===
        'delivery-disposal';
      return [
        defineComponent({ render: () => null }),
        {
          setData: (data: unknown) => {
            if (disposal) {
              harness.disposalData(data);
              return { open: harness.disposalOpen };
            }
            harness.decisionData(data);
            return { open: harness.decisionOpen };
          },
        },
      ];
    }
    const visible = ref(false);
    let data: unknown;
    const entry = {
      options,
      lock: harness.lock,
      unlock: harness.unlock,
      close: async () => {
        visible.value = false;
        options.onOpenChange(false);
      },
      open: (payload?: unknown) => {
        if (payload !== undefined) data = payload;
        visible.value = true;
        options.onOpenChange(true);
      },
      setData: (payload: unknown) => {
        data = payload;
        return entry;
      },
      getData: () => data,
      confirm: options.onConfirm,
    };
    harness.modals.push(entry);
    if (options.title === '原交付操作核查（不重发）') {
      harness.close = entry.close;
      harness.confirm = entry.confirm;
      harness.open = (operationId, applyId = '9223372036854775807') => {
        data = { applyId, operationId };
        visible.value = true;
        options.onOpenChange(true);
      };
    }
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            visible.value ? h('section', slots.default?.()) : null,
      }),
      entry,
    ];
  },
}));
vi.mock('antdv-next', () => ({
  Alert: defineComponent({
    props: ['message'],
    setup: (props) => () => h('p', String(props.message)),
  }),
  Button: defineComponent({
    props: ['disabled'],
    setup:
      (props, { slots }) =>
      () =>
        h('button', { disabled: props.disabled }, slots.default?.()),
  }),
  Checkbox: defineComponent({
    props: { checked: Boolean, disabled: Boolean },
    emits: ['update:checked'],
    setup:
      (props, { emit, slots }) =>
      () =>
        h('label', [
          h('input', {
            type: 'checkbox',
            'data-testid': 'redeliver-ack-input',
            checked: props.checked,
            disabled: props.disabled,
            onChange: (event: Event) =>
              emit(
                'update:checked',
                (event.target as HTMLInputElement).checked,
              ),
          }),
          slots.default?.(),
        ]),
  }),
  TextArea: defineComponent({
    props: ['value', 'disabled'],
    emits: ['update:value'],
    setup:
      (props, { emit }) =>
      () =>
        h('textarea', {
          disabled: props.disabled,
          value: props.value,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLTextAreaElement).value),
        }),
  }),
  message: { success: harness.toast, warning: vi.fn() },
}));
vi.mock('#/api/asset/market', () => ({
  getMarketDeliveryEvidence: vi.fn(),
  getMarketDeliveryOperation: vi.fn(),
  getMarketDeliveryReviews: vi.fn(),
  recheckMarketDelivery: vi.fn(),
  recordMarketDeliveryRedeliver: vi.fn(),
}));

let app: App | undefined;
let host: HTMLDivElement;
const row = (operationId = 'original:A'): MarketApi.DeliveryOperation => ({
  applyId: '9223372036854775807',
  id: '9223372036854775806',
  operationId,
  opType: 'GRANT',
  protocolVersion: 2,
  resolutionStatus: 'UNKNOWN',
  sourceRevision: '9223372036854775805',
  status: 'MANUAL',
  updateTime: 1_700_000_000_000,
});
const originalEvidence = (
  operationId = 'original:A',
): MarketApi.DeliveryEvidence => ({
  applyId: '9223372036854775807',
  operationId,
  sourceRevision: '9223372036854775805',
  classification: 'ORIGINAL_APPLIED',
  originalAction: 'GRANT',
  frozenFieldCount: 2,
  providerOutcome: 'APPLIED',
});
const redeliverRequestId = '12345678-1234-1234-1234-123456789012';
const decisionRequestId = '11111111-1111-1111-1111-111111111111';
const decision = (
  overrides: Partial<MarketApi.DeliveryReview> = {},
): MarketApi.DeliveryReview => ({
  id: '9223372036854775800',
  applyId: '9223372036854775807',
  operationId: 'original:A',
  requestId: decisionRequestId,
  sourceRevision: '9223372036854775805',
  decision: 'KEEP_PENDING',
  kind: 'DECISION',
  classification: 'LEGACY_REVIEW_REQUIRED',
  actorUserId: '9',
  actorDeptId: '100',
  reason: '核查原因',
  basis: '核查依据',
  occurredAt: 1_700_000_000_000,
  ...overrides,
});
const reviewPage = (
  items: MarketApi.DeliveryReview[],
  hasMore = false,
  nextId: MarketApi.Id | null = null,
): MarketApi.DeliveryReviewPage => ({ hasMore, items, nextId });
const redeliverReceipt = (
  overrides: Partial<MarketApi.DeliveryOperation> = {},
): MarketApi.DeliveryOperation => ({
  ...row(),
  action: 'REVOKE',
  opType: 'REVOKE',
  attemptCount: 2,
  resultRef: 'asset:asset:apply:9:r3:REVOKE',
  lastError: null,
  ...overrides,
});
function modalByTitle(title: string) {
  return harness.modals.find((modal) => modal.options.title === title);
}
async function inspectEvidence() {
  host
    .querySelector<HTMLButtonElement>(
      '[data-testid="inspect-original-evidence"]',
    )!
    .click();
  await flush();
}
async function flush() {
  for (let i = 0; i < 6; i++) await Promise.resolve();
  await nextTick();
}
async function reason(text = '核查原回执') {
  const input = host.querySelector('textarea')!;
  input.value = text;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  await flush();
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}
beforeEach(() => {
  vi.resetAllMocks();
  vi.stubGlobal('crypto', { randomUUID: vi.fn(() => redeliverRequestId) });
  harness.modals.length = 0;
  Object.assign(harness.state!, {
    allowed: true,
    redeliver: true,
    accessToken: '1',
    refreshToken: '',
    userInfo: { userId: '9' },
  });
  vi.mocked(getMarketDeliveryOperation).mockResolvedValue(row());
  vi.mocked(getMarketDeliveryEvidence).mockResolvedValue(originalEvidence());
  vi.mocked(getMarketDeliveryReviews).mockResolvedValue(
    reviewPage([decision()]),
  );
  vi.mocked(recordMarketDeliveryRedeliver).mockResolvedValue(
    redeliverReceipt(),
  );
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(DeliveryReview, { onSuccess: harness.success });
  app.mount(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
  vi.unstubAllGlobals();
});

describe('market original delivery review', () => {
  it('keeps a pending suspend intent separate from a confirmed suspension', async () => {
    vi.mocked(getMarketDeliveryOperation).mockResolvedValue({
      ...row(),
      action: 'SUSPEND',
      opType: 'REVOKE',
      status: 'PENDING',
      resolutionStatus: 'UNCONFIRMED',
    });
    harness.open!('original:A');
    await flush();
    expect(
      host.querySelector('[data-testid="delivery-action"]')?.textContent,
    ).toBe('请求暂停');
    expect(host.textContent).toContain('PENDING / UNCONFIRMED');
    expect(host.textContent).not.toContain('暂停已确认');
    expect(host.querySelector('textarea')!.disabled).toBe(true);
    await reason();
    await harness.confirm!();
    expect(recheckMarketDelivery).not.toHaveBeenCalled();
  });

  it.each([
    ['GRANT', 'GRANT', '请求授权'],
    ['SUSPEND', 'REVOKE', '请求暂停'],
    ['REVOKE', 'REVOKE', '请求撤销'],
    ['UNKNOWN', 'REVOKE', '动作待核查'],
    [undefined, 'REVOKE', '动作待核查'],
    [null, 'REVOKE', '动作待核查'],
    ['FUTURE_ACTION', 'REVOKE', '动作待核查'],
    ['', 'GRANT', '动作待核查'],
    [undefined, 'GRANT', '动作待核查'],
  ])(
    'shows %s separately from technical %s without inferring success',
    async (action, opType, label) => {
      vi.mocked(getMarketDeliveryOperation).mockResolvedValue(
        Object.assign(row(), { action, opType }),
      );
      harness.open!('original:A');
      await flush();
      expect(
        host.querySelector('[data-testid="delivery-action"]')?.textContent,
      ).toBe(label);
      expect(host.textContent).toContain('请求动作');
      expect(host.textContent).toContain('技术操作类型 / 协议');
      expect(host.textContent).toContain(`${opType} / 2`);
      expect(host.textContent).toContain('MANUAL / UNKNOWN');
      expect(host.textContent).toContain('不代表执行成功或当前可访问');
      expect(host.textContent).not.toContain('暂停已确认');
      expect(host.textContent).not.toContain('撤销已确认');
      expect(host.textContent).toContain('9223372036854775807');
      expect(host.textContent).toContain('9223372036854775805');
      expect(host.querySelector('textarea')!.disabled).toBe(false);
      expect(recheckMarketDelivery).not.toHaveBeenCalled();
      expect(harness.toast).not.toHaveBeenCalled();
    },
  );

  it.each(['GRANT', 'SUSPEND', 'REVOKE', 'UNKNOWN', undefined, null])(
    'does not allow a confirmed operation to be rechecked based on action %s',
    async (action) => {
      vi.mocked(getMarketDeliveryOperation).mockResolvedValue(
        Object.assign(row(), {
          action,
          opType: 'REVOKE',
          status: 'SUCCESS',
          resolutionStatus: 'CONFIRMED',
        }),
      );
      harness.open!('original:A');
      await flush();
      expect(host.textContent).toContain('SUCCESS / CONFIRMED');
      expect(host.querySelector('textarea')!.disabled).toBe(true);
      await reason();
      await harness.confirm!();
      expect(recheckMarketDelivery).not.toHaveBeenCalled();
      expect(harness.toast).not.toHaveBeenCalled();
    },
  );

  it.each([
    ['MATCHED', '匹配', '不代表当前可访问'],
    ['MISSING', '缺失', '未找到正式批准凭证'],
    ['CONFLICT', '冲突', '与正式批准凭证不一致'],
    ['UNVERIFIABLE', '无法核验', '不能据此确认原批准'],
    ['NOT_APPLICABLE', '不适用', '不以本项证明原批准'],
    ['FUTURE_STATUS', '无法核验', '不能据此确认原批准'],
    [undefined, '无法核验', '不能据此确认原批准'],
    [null, '无法核验', '不能据此确认原批准'],
  ])(
    'shows independent approval %s even when the original receipt is APPLIED',
    async (approvalStatus, label, detail) => {
      vi.mocked(getMarketDeliveryOperation).mockResolvedValue({
        ...row(),
        resolutionStatus: 'REVIEW_REQUIRED',
      });
      vi.mocked(getMarketDeliveryEvidence).mockResolvedValue(
        approvalStatus === undefined
          ? originalEvidence()
          : { ...originalEvidence(), approvalStatus },
      );
      harness.open!('original:A');
      await flush();
      await inspectEvidence();
      const approval = host.querySelector('[data-testid="approval-status"]');
      expect(approval?.textContent).toContain(`原批准凭证核对：${label}`);
      expect(approval?.textContent).toContain(detail);
      expect(host.textContent).toContain('原命令与已执行回执匹配');
      expect(host.querySelector('textarea')!.disabled).toBe(true);
      await reason();
      await harness.confirm!();
      expect(recheckMarketDelivery).not.toHaveBeenCalled();
      expect(harness.toast).not.toHaveBeenCalled();
    },
  );

  it('opens the dedicated decision/history view with the original identity', async () => {
    harness.open!('original:A');
    await flush();
    host
      .querySelector<HTMLButtonElement>(
        '[data-testid="open-delivery-decision"]',
      )!
      .click();
    expect(harness.decisionData).toHaveBeenCalledWith({
      applyId: '9223372036854775807',
      operationId: 'original:A',
    });
    expect(harness.decisionOpen).toHaveBeenCalledOnce();
    expect(harness.disposalOpen).not.toHaveBeenCalled();
  });
  it('opens the separate disposal view with the original identity only on request', async () => {
    harness.open!('original:A');
    await flush();
    expect(harness.disposalOpen).not.toHaveBeenCalled();
    host
      .querySelector<HTMLButtonElement>(
        '[data-testid="open-delivery-disposal"]',
      )!
      .click();
    expect(harness.disposalData).toHaveBeenCalledWith({
      applyId: '9223372036854775807',
      operationId: 'original:A',
    });
    expect(harness.disposalOpen).toHaveBeenCalledOnce();
    expect(harness.decisionOpen).not.toHaveBeenCalled();
    expect(recheckMarketDelivery).not.toHaveBeenCalled();
  });
  it('allows read-only evidence inspection for REVIEW_REQUIRED without approving or rechecking it', async () => {
    vi.mocked(getMarketDeliveryOperation).mockResolvedValue({
      ...row(),
      resolutionStatus: 'REVIEW_REQUIRED',
    });
    harness.open!('original:A');
    await flush();
    await inspectEvidence();
    expect(getMarketDeliveryEvidence).toHaveBeenCalledExactlyOnceWith(
      'original:A',
    );
    expect(host.textContent).toContain(
      '原命令与已执行回执匹配；不代表当前仍可访问',
    );
    expect(host.textContent).toContain('原冻结字段数：2');
    expect(host.textContent).toContain('不形成复核批准');
    await harness.confirm!();
    expect(recheckMarketDelivery).not.toHaveBeenCalled();
    expect(harness.success).not.toHaveBeenCalled();
    expect(harness.toast).not.toHaveBeenCalled();
  });

  it.each(['applyId', 'operationId', 'sourceRevision'] as const)(
    'rejects mismatched evidence %s',
    async (field) => {
      vi.mocked(getMarketDeliveryEvidence).mockResolvedValue({
        ...originalEvidence(),
        [field]: 'different',
      });
      harness.open!('original:A');
      await flush();
      await inspectEvidence();
      expect(host.textContent).toContain('证据身份或来源版本不一致');
      expect(host.textContent).not.toContain('原冻结字段数');
      await harness.confirm!();
      expect(recheckMarketDelivery).not.toHaveBeenCalled();
    },
  );

  it.each(['session', 'user', 'permission', 'close', 'unmount'])(
    'discards late evidence after %s changes',
    async (change) => {
      const pending = deferred<MarketApi.DeliveryEvidence>();
      vi.mocked(getMarketDeliveryEvidence).mockReturnValue(pending.promise);
      harness.open!('original:A');
      await flush();
      await inspectEvidence();
      await inspectEvidence();
      expect(getMarketDeliveryEvidence).toHaveBeenCalledOnce();
      if (change === 'session') harness.state!.refreshToken = '2';
      if (change === 'user') harness.state!.userInfo.userId = '12';
      if (change === 'permission') harness.state!.allowed = false;
      if (change === 'close') await harness.close!();
      if (change === 'unmount') {
        app!.unmount();
        app = undefined;
      }
      pending.resolve({ ...originalEvidence(), approvalStatus: 'MATCHED' });
      await flush();
      expect(host.textContent).not.toContain('原冻结字段数');
      expect(host.querySelector('[data-testid="approval-status"]')).toBeNull();
      expect(harness.success).not.toHaveBeenCalled();
      expect(recheckMarketDelivery).not.toHaveBeenCalled();
    },
  );

  it('clears stale evidence when refreshing and discards the old response', async () => {
    const pending = deferred<MarketApi.DeliveryEvidence>();
    vi.mocked(getMarketDeliveryEvidence).mockReturnValue(pending.promise);
    harness.open!('original:A');
    await flush();
    await inspectEvidence();
    const refresh = [...host.querySelectorAll('button')].find((button) =>
      button.textContent?.includes('刷新原操作状态'),
    )!;
    refresh.click();
    await flush();
    pending.resolve(originalEvidence());
    await flush();
    expect(host.textContent).not.toContain('原冻结字段数');
    expect(getMarketDeliveryOperation).toHaveBeenCalledTimes(2);
  });

  it('does not change the original read-only recheck eligibility or send an approval status', async () => {
    vi.mocked(getMarketDeliveryEvidence).mockResolvedValue({
      ...originalEvidence(),
      approvalStatus: 'CONFLICT',
    });
    vi.mocked(recheckMarketDelivery).mockResolvedValue(row());
    harness.open!('original:A');
    await flush();
    await inspectEvidence();
    expect(host.textContent).toContain('原批准凭证核对：冲突');
    expect(host.querySelector('textarea')!.disabled).toBe(false);
    expect(recheckMarketDelivery).not.toHaveBeenCalled();
    await reason();
    await harness.confirm!();
    await flush();
    expect(recheckMarketDelivery).toHaveBeenCalledExactlyOnceWith({
      operationId: 'original:A',
      note: '核查原回执',
    });
    expect(host.querySelector('[data-testid="approval-status"]')).toBeNull();
  });

  it('requires a fresh ledger read after local state changed during inspection', async () => {
    vi.mocked(getMarketDeliveryEvidence).mockResolvedValue({
      ...originalEvidence(),
      classification: 'LOCAL_STATE_CHANGED',
      originalAction: null,
      frozenFieldCount: null,
      providerOutcome: null,
    });
    harness.open!('original:A');
    await flush();
    await inspectEvidence();
    expect(host.textContent).toContain('旧结果已丢弃，请刷新原操作');
    await reason();
    await harness.confirm!();
    expect(recheckMarketDelivery).not.toHaveBeenCalled();
  });

  it('releases loading after a failed evidence read and does not retry automatically', async () => {
    vi.mocked(getMarketDeliveryEvidence).mockRejectedValueOnce(
      new Error('unavailable'),
    );
    harness.open!('original:A');
    await flush();
    await inspectEvidence();
    expect(host.textContent).toContain('原始证据读取失败，未修改授权');
    expect(getMarketDeliveryEvidence).toHaveBeenCalledOnce();
    await inspectEvidence();
    expect(host.textContent).toContain('原冻结字段数：2');
    expect(getMarketDeliveryEvidence).toHaveBeenCalledTimes(2);
  });

  it('loads the original identity and rechecks once with a reason, without fabricating grant success', async () => {
    const pending = deferred<MarketApi.DeliveryOperation>();
    vi.mocked(recheckMarketDelivery).mockReturnValue(pending.promise);
    harness.open!('original:A');
    await flush();
    expect(getMarketDeliveryOperation).toHaveBeenCalledWith('original:A');
    expect(host.textContent).toContain('9223372036854775807');
    expect(host.textContent).toContain('9223372036854775805');
    await harness.confirm!();
    expect(recheckMarketDelivery).not.toHaveBeenCalled();
    await reason('  核查原回执  ');
    const sent = harness.confirm!();
    await harness.confirm!();
    expect(recheckMarketDelivery).toHaveBeenCalledExactlyOnceWith({
      note: '核查原回执',
      operationId: 'original:A',
    });
    pending.resolve({
      ...row(),
      status: 'SUCCESS',
      resolutionStatus: 'CONFIRMED',
      opType: 'REVOKE',
    });
    await sent;
    await flush();
    expect(host.textContent).toContain('REVOKE');
    expect(host.textContent).toContain('CONFIRMED');
    expect(harness.success).toHaveBeenCalledOnce();
    expect(harness.toast).not.toHaveBeenCalledWith('已开通授权');
    await harness.confirm!();
    expect(recheckMarketDelivery).toHaveBeenCalledOnce();
  });

  it.each(['legacy', 'review', 'success', 'mismatch'])(
    'does not submit ineligible or mismatched %s records',
    async (kind) => {
      const value = row();
      if (kind === 'legacy') value.protocolVersion = 0;
      if (kind === 'review') value.resolutionStatus = 'REVIEW_REQUIRED';
      if (kind === 'success') value.status = 'SUCCESS';
      if (kind === 'mismatch') value.applyId = '2';
      vi.mocked(getMarketDeliveryOperation).mockResolvedValue(value);
      harness.open!('original:A');
      await flush();
      await reason();
      await harness.confirm!();
      expect(recheckMarketDelivery).not.toHaveBeenCalled();
    },
  );

  it('does not request anything without management permission', async () => {
    harness.state!.allowed = false;
    harness.open!('original:A');
    await flush();
    await harness.confirm!();
    expect(getMarketDeliveryOperation).not.toHaveBeenCalled();
    expect(recheckMarketDelivery).not.toHaveBeenCalled();
  });

  it.each(['session', 'user', 'permission', 'close', 'unmount'])(
    'discards a late review result after %s changes',
    async (change) => {
      const pending = deferred<MarketApi.DeliveryOperation>();
      vi.mocked(recheckMarketDelivery).mockReturnValue(pending.promise);
      harness.open!('original:A');
      await flush();
      await reason();
      const sent = harness.confirm!();
      if (change === 'session') harness.state!.refreshToken = '2';
      if (change === 'user') harness.state!.userInfo.userId = '12';
      if (change === 'permission') harness.state!.allowed = false;
      if (change === 'close') await harness.close!();
      if (change === 'unmount') {
        app!.unmount();
        app = undefined;
      }
      await flush();
      pending.resolve(row());
      await sent;
      await flush();
      expect(harness.success).not.toHaveBeenCalled();
      expect(harness.toast).not.toHaveBeenCalled();
      expect(host.textContent).not.toContain('original:A');
    },
  );

  it('discards an older detail response after opening another operation', async () => {
    const old = deferred<MarketApi.DeliveryOperation>();
    vi.mocked(getMarketDeliveryOperation)
      .mockReturnValueOnce(old.promise)
      .mockResolvedValueOnce(
        Object.assign(row('original:B'), {
          action: 'SUSPEND',
          opType: 'REVOKE',
        }),
      );
    harness.open!('original:A');
    await harness.close!();
    harness.open!('original:B');
    await flush();
    old.resolve(Object.assign(row(), { action: 'REVOKE', opType: 'REVOKE' }));
    await flush();
    expect(host.textContent).toContain('original:B');
    expect(host.textContent).not.toContain('original:A');
    expect(
      host.querySelector('[data-testid="delivery-action"]')?.textContent,
    ).toBe('请求暂停');
    expect(host.textContent).not.toContain('请求撤销');
  });

  it('requires reloading the ledger after a failed recheck, without automatic retry', async () => {
    vi.mocked(recheckMarketDelivery).mockRejectedValue(
      new Error('response lost'),
    );
    harness.open!('original:A');
    await flush();
    await reason();
    await harness.confirm!();
    await flush();
    expect(host.textContent).toContain('请重新读取账本');
    await harness.confirm!();
    expect(recheckMarketDelivery).toHaveBeenCalledOnce();
    expect(harness.unlock).toHaveBeenCalledOnce();
    expect(harness.success).not.toHaveBeenCalled();
  });
});

describe('market controlled redeliver', () => {
  async function openWith(
    action: string | undefined,
    overrides: Partial<MarketApi.DeliveryOperation> = {},
  ) {
    vi.mocked(getMarketDeliveryOperation).mockResolvedValue(
      Object.assign(row(), {
        action,
        opType: action === 'GRANT' ? 'GRANT' : 'REVOKE',
        ...overrides,
      }),
    );
    harness.open!('original:A');
    await flush();
  }
  async function openRedeliverModal() {
    host
      .querySelector<HTMLButtonElement>(
        '[data-testid="open-delivery-redeliver"]',
      )!
      .click();
    await flush();
  }
  async function fillRedeliver(ack = true) {
    const area = host.querySelector<HTMLTextAreaElement>('#redeliver-note')!;
    area.value = ' 按原操作重发一次 ';
    area.dispatchEvent(new Event('input', { bubbles: true }));
    if (ack) {
      const checkbox = host.querySelector<HTMLInputElement>(
        '[data-testid="redeliver-ack-input"]',
      )!;
      checkbox.checked = true;
      checkbox.dispatchEvent(new Event('change', { bubbles: true }));
    }
    await flush();
  }

  it.each(['REVOKE', 'SUSPEND'])(
    'shows the redeliver entry for %s MANUAL/UNKNOWN operations',
    async (action) => {
      await openWith(action, {});
      expect(
        host.querySelector('[data-testid="open-delivery-redeliver"]'),
      ).not.toBeNull();
      expect(
        host.querySelector('[data-testid="redeliver-unavailable"]'),
      ).toBeNull();
    },
  );

  it.each<
    [string | undefined, Partial<MarketApi.DeliveryOperation>, string]
  >([
    ['GRANT', {}, 'GRANT 必须重新走申请流程'],
    ['UNKNOWN', {}, '冻结动作是撤销或暂停'],
    [undefined, {}, '冻结动作是撤销或暂停'],
    ['REVOKE', { status: 'SUCCESS' }, '当前账本状态不支持'],
    [
      'REVOKE',
      { resolutionStatus: 'REVIEW_REQUIRED' },
      '当前账本状态不支持',
    ],
    [
      'REVOKE',
      { status: 'PENDING', resolutionStatus: 'UNKNOWN' },
      '当前账本状态不支持',
    ],
  ])(
    'hides the redeliver entry for %s and explains why',
    async (action, overrides, reason) => {
      await openWith(action, overrides);
      expect(
        host.querySelector('[data-testid="open-delivery-redeliver"]'),
      ).toBeNull();
      expect(
        host.querySelector('[data-testid="redeliver-unavailable"]')
          ?.textContent,
      ).toContain(reason);
    },
  );

  it('hides the redeliver entry without the dedicated permission and explains why', async () => {
    harness.state!.redeliver = false;
    await openWith('REVOKE');
    expect(
      host.querySelector('[data-testid="open-delivery-redeliver"]'),
    ).toBeNull();
    expect(
      host.querySelector('[data-testid="redeliver-unavailable"]')?.textContent,
    ).toContain('asset:market:redeliver 权限');
  });

  it('grant never offers redeliver even with the permission', async () => {
    await openWith('GRANT');
    expect(
      host.querySelector('[data-testid="open-delivery-redeliver"]'),
    ).toBeNull();
    expect(
      host.querySelector('[data-testid="redeliver-unavailable"]')?.textContent,
    ).toContain('GRANT 必须重新走申请流程');
    expect(recordMarketDeliveryRedeliver).not.toHaveBeenCalled();
  });

  it('disables submission and explains when no DECISION decision is recorded', async () => {
    vi.mocked(getMarketDeliveryReviews).mockResolvedValue(
      reviewPage([
        decision({ kind: 'DISPOSAL', decision: 'REVOKE' }),
      ]),
    );
    await openWith('REVOKE');
    await openRedeliverModal();
    expect(host.textContent).toContain('请先在核查结论中记录决定后重试');
    expect(
      host.querySelector('[data-testid="redeliver-basis-decision"]'),
    ).toBeNull();
    await fillRedeliver();
    await modalByTitle('受控重投（仅撤销/暂停）')!.confirm!();
    expect(recordMarketDeliveryRedeliver).not.toHaveBeenCalled();
  });

  it('shows the original identity and frozen action inside the redeliver modal', async () => {
    await openWith('SUSPEND');
    await openRedeliverModal();
    expect(host.textContent).toContain('original:A');
    expect(host.textContent).toContain('9223372036854775807');
    expect(
      host.querySelector('[data-testid="redeliver-action"]')?.textContent,
    ).toBe('请求暂停');
    expect(
      host.querySelector('[data-testid="redeliver-basis-decision"]')
        ?.textContent,
    ).toContain(decisionRequestId);
  });

  it('submits the frozen note and the bound basis review decision once', async () => {
    await openWith('REVOKE');
    await openRedeliverModal();
    await fillRedeliver(false);
    await modalByTitle('受控重投（仅撤销/暂停）')!.confirm!();
    expect(recordMarketDeliveryRedeliver).not.toHaveBeenCalled();
    await fillRedeliver();
    await modalByTitle('受控重投（仅撤销/暂停）')!.confirm!();
    await flush();
    expect(recordMarketDeliveryRedeliver).toHaveBeenCalledExactlyOnceWith({
      requestId: redeliverRequestId,
      operationId: 'original:A',
      note: '按原操作重发一次',
      basisReviewRequestId: decisionRequestId,
    });
    expect(
      host.querySelector('[data-testid="saved-redeliver"]')?.textContent,
    ).toContain('asset:asset:apply:9:r3:REVOKE');
    expect(harness.toast).toHaveBeenCalledOnce();
    expect(harness.success).toHaveBeenCalledOnce();
    await modalByTitle('受控重投（仅撤销/暂停）')!.confirm!();
    expect(recordMarketDeliveryRedeliver).toHaveBeenCalledOnce();
  });

  it('reuses the exact frozen request after response loss so the server cannot redeliver twice', async () => {
    vi.mocked(recordMarketDeliveryRedeliver).mockRejectedValueOnce(
      new Error('lost'),
    );
    await openWith('REVOKE');
    await openRedeliverModal();
    await fillRedeliver();
    await modalByTitle('受控重投（仅撤销/暂停）')!.confirm!();
    await flush();
    expect(host.textContent).toContain('内容已冻结');
    expect(host.textContent).toContain('服务端不会重复重发');
    expect(
      host.querySelector<HTMLTextAreaElement>('#redeliver-note')!.disabled,
    ).toBe(true);
    await modalByTitle('受控重投（仅撤销/暂停）')!.confirm!();
    await flush();
    expect(recordMarketDeliveryRedeliver).toHaveBeenCalledTimes(2);
    expect(vi.mocked(recordMarketDeliveryRedeliver).mock.calls[0]).toEqual(
      vi.mocked(recordMarketDeliveryRedeliver).mock.calls[1],
    );
    expect(harness.toast).toHaveBeenCalledOnce();
  });

  it.each([
    ['another operation', { operationId: 'original:B' }],
    ['another apply', { applyId: '2' }],
    ['another action', { action: 'SUSPEND' as const }],
  ])('does not report success for a receipt from %s', async (_label, overrides) => {
    vi.mocked(recordMarketDeliveryRedeliver).mockResolvedValue(
      redeliverReceipt(overrides),
    );
    await openWith('REVOKE');
    await openRedeliverModal();
    await fillRedeliver();
    await modalByTitle('受控重投（仅撤销/暂停）')!.confirm!();
    await flush();
    expect(harness.toast).not.toHaveBeenCalled();
    expect(harness.success).not.toHaveBeenCalled();
    expect(host.textContent).toContain('未确认成功');
    expect(host.querySelector('[data-testid="saved-redeliver"]')).toBeNull();
  });

  it.each(['session', 'user', 'permission', 'close', 'unmount'])(
    'discards a late redeliver receipt after %s changes',
    async (change) => {
      const pending = deferred<MarketApi.DeliveryOperation>();
      vi.mocked(recordMarketDeliveryRedeliver).mockReturnValue(pending.promise);
      await openWith('REVOKE');
      await openRedeliverModal();
      await fillRedeliver();
      const saving = modalByTitle('受控重投（仅撤销/暂停）')!.confirm!();
      if (change === 'session') harness.state!.refreshToken = '2';
      if (change === 'user') harness.state!.userInfo.userId = '12';
      if (change === 'permission') harness.state!.redeliver = false;
      if (change === 'close')
        await modalByTitle('受控重投（仅撤销/暂停）')!.close();
      if (change === 'unmount') {
        app!.unmount();
        app = undefined;
      }
      pending.resolve(redeliverReceipt());
      await saving;
      await flush();
      expect(harness.toast).not.toHaveBeenCalled();
      expect(harness.success).not.toHaveBeenCalled();
    },
  );

  it('pages the decision history with the lossless nextId to bind the newest DECISION', async () => {
    vi.mocked(getMarketDeliveryReviews)
      .mockResolvedValueOnce(
        reviewPage(
          [
            decision({
              id: 10,
              requestId: 'aaaa0000-0000-0000-0000-000000000000',
            }),
          ],
          true,
          10,
        ),
      )
      .mockResolvedValueOnce(
        reviewPage([
          decision({
            id: 20,
            requestId: 'bbbb0000-0000-0000-0000-000000000000',
          }),
          decision({
            id: 21,
            requestId: 'cccc0000-0000-0000-0000-000000000000',
            kind: 'DISPOSAL',
            decision: 'REVOKE',
          }),
        ]),
      );
    await openWith('REVOKE');
    await openRedeliverModal();
    expect(
      vi.mocked(getMarketDeliveryReviews).mock.calls.map(([op, cursor]) => [
        op,
        String(cursor),
      ]),
    ).toEqual([
      ['original:A', '0'],
      ['original:A', '10'],
    ]);
    expect(
      host.querySelector('[data-testid="redeliver-basis-decision"]')
        ?.textContent,
    ).toContain('编号 20');
    await fillRedeliver();
    await modalByTitle('受控重投（仅撤销/暂停）')!.confirm!();
    expect(recordMarketDeliveryRedeliver).toHaveBeenCalledExactlyOnceWith({
      requestId: redeliverRequestId,
      operationId: 'original:A',
      note: '按原操作重发一次',
      basisReviewRequestId: 'bbbb0000-0000-0000-0000-000000000000',
    });
  });

  it('requires a secure UUID source and never falls back to a timestamp', async () => {
    vi.stubGlobal('crypto', {});
    await openWith('REVOKE');
    await openRedeliverModal();
    await fillRedeliver();
    await modalByTitle('受控重投（仅撤销/暂停）')!.confirm!();
    expect(recordMarketDeliveryRedeliver).not.toHaveBeenCalled();
    expect(host.textContent).toContain('安全请求编号');
  });
});
