import type { App } from 'vue';

import type { MarketApi } from '#/api/asset/market';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getMarketDeliveryOperation,
  getMarketDeliveryReviews,
  recordMarketDeliveryReview,
} from '#/api/asset/market';

import DeliveryDecision from './delivery-decision.vue';

const harness = vi.hoisted(() => ({
  open: undefined as ((operationId: string) => void) | undefined,
  close: undefined as (() => Promise<void>) | undefined,
  confirm: undefined as (() => Promise<void>) | undefined,
  state: undefined as
    | undefined
    | {
        allowed: boolean;
        accessToken: string;
        userInfo: { userId: string };
        refreshToken: string;
      },
  lock: vi.fn(),
  unlock: vi.fn(),
  toast: vi.fn(),
}));
vi.mock('@vben/stores', () => {
  harness.state = reactive({
    allowed: true,
    accessToken: '1',
    refreshToken: '',
    userInfo: { userId: '9' },
  });
  return {
    useAccessStore: () => harness.state,
    useUserStore: () => harness.state,
  };
});
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => harness.state!.allowed }),
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: {
    onConfirm: () => Promise<void>;
    onOpenChange: (open: boolean) => void;
  }) => {
    const visible = ref(false);
    let data: unknown;
    const close = async () => {
      visible.value = false;
      options.onOpenChange(false);
    };
    harness.close = close;
    harness.confirm = options.onConfirm;
    harness.open = (operationId) => {
      data = { applyId: '9223372036854775807', operationId };
      visible.value = true;
      options.onOpenChange(true);
    };
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            visible.value ? h('section', slots.default?.()) : null,
      }),
      {
        close,
        getData: () => data,
        lock: harness.lock,
        unlock: harness.unlock,
      },
    ];
  },
}));
vi.mock('antdv-next', () => ({
  Alert: defineComponent({
    props: { message: { default: '', type: String } },
    setup: (props) => () => h('p', props.message),
  }),
  Button: defineComponent({
    props: { disabled: Boolean },
    setup:
      (props, { slots }) =>
      () =>
        h('button', { disabled: props.disabled }, slots.default?.()),
  }),
  Select: defineComponent({
    props: {
      value: { default: '', type: String },
      disabled: Boolean,
      options: { default: () => [], type: Array },
    },
    setup: (props) => () =>
      h('select', { disabled: props.disabled }, h('option', props.value)),
  }),
  TextArea: defineComponent({
    props: { value: { default: '', type: String }, disabled: Boolean },
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
  getMarketDeliveryOperation: vi.fn(),
  getMarketDeliveryReviews: vi.fn(),
  recordMarketDeliveryReview: vi.fn(),
}));

let app: App | undefined;
let host: HTMLDivElement;
const requestId = '12345678-1234-1234-1234-123456789012';
const operation = (
  operationId = 'original:A',
): MarketApi.DeliveryOperation => ({
  id: '9223372036854775805',
  applyId: '9223372036854775807',
  operationId,
  opType: 'GRANT',
  protocolVersion: 2,
  sourceRevision: '9223372036854775804',
  status: 'MANUAL',
  resolutionStatus: 'REVIEW_REQUIRED',
});
const review = (operationId = 'original:A'): MarketApi.DeliveryReview => ({
  id: '9223372036854775806',
  applyId: '9223372036854775807',
  operationId,
  requestId,
  sourceRevision: '9223372036854775804',
  decision: 'KEEP_PENDING',
  classification: 'ORIGINAL_APPLIED',
  actorUserId: '9',
  actorDeptId: '100',
  reason: '核查原因',
  basis: '核查依据',
  occurredAt: 1_700_000_000_000,
});
async function flush() {
  for (let i = 0; i < 8; i++) await Promise.resolve();
  await nextTick();
}
async function fill() {
  const reason = host.querySelector<HTMLTextAreaElement>(
    '#delivery-decision-reason',
  )!;
  const basis = host.querySelector<HTMLTextAreaElement>(
    '#delivery-decision-basis',
  )!;
  reason.value = ' 核查原因 ';
  reason.dispatchEvent(new Event('input', { bubbles: true }));
  basis.value = ' 核查依据 ';
  basis.dispatchEvent(new Event('input', { bubbles: true }));
  await flush();
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { resolve, promise };
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.stubGlobal('crypto', { randomUUID: vi.fn(() => requestId) });
  Object.assign(harness.state!, {
    allowed: true,
    accessToken: '1',
    refreshToken: '',
    userInfo: { userId: '9' },
  });
  vi.mocked(getMarketDeliveryOperation).mockResolvedValue(operation());
  vi.mocked(getMarketDeliveryReviews).mockResolvedValue({
    items: [],
    hasMore: false,
  });
  vi.mocked(recordMarketDeliveryReview).mockResolvedValue(review());
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(DeliveryDecision);
  app.mount(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
  vi.unstubAllGlobals();
});

describe('durable original delivery review decision', () => {
  it('does not treat a pending suspend intent as confirmation or permission', async () => {
    vi.mocked(getMarketDeliveryOperation).mockResolvedValue({
      ...operation(),
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
    expect(
      host.querySelector<HTMLTextAreaElement>('#delivery-decision-reason')!
        .disabled,
    ).toBe(true);
    await fill();
    await harness.confirm!();
    expect(recordMarketDeliveryReview).not.toHaveBeenCalled();
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
    'shows %s intent separately from technical %s without recording a decision',
    async (action, opType, label) => {
      vi.mocked(getMarketDeliveryOperation).mockResolvedValue(
        Object.assign(operation(), { action, opType }),
      );
      harness.open!('original:A');
      await flush();
      expect(
        host.querySelector('[data-testid="delivery-action"]')?.textContent,
      ).toBe(label);
      expect(host.textContent).toContain('请求动作');
      expect(host.textContent).toContain('技术操作类型 / 协议');
      expect(host.textContent).toContain(`${opType} / 2`);
      expect(host.textContent).toContain('MANUAL / REVIEW_REQUIRED');
      expect(host.textContent).toContain('不代表执行成功或当前可访问');
      expect(host.textContent).not.toContain('暂停已确认');
      expect(host.textContent).not.toContain('撤销已确认');
      expect(
        host.querySelector<HTMLTextAreaElement>('#delivery-decision-reason')!
          .disabled,
      ).toBe(false);
      expect(recordMarketDeliveryReview).not.toHaveBeenCalled();
      expect(harness.toast).not.toHaveBeenCalled();
    },
  );

  it.each(['GRANT', 'SUSPEND', 'REVOKE', 'UNKNOWN', undefined, null])(
    'does not allow a confirmed operation to record a pending decision based on action %s',
    async (action) => {
      vi.mocked(getMarketDeliveryOperation).mockResolvedValue(
        Object.assign(operation(), {
          action,
          opType: 'REVOKE',
          status: 'SUCCESS',
          resolutionStatus: 'CONFIRMED',
        }),
      );
      harness.open!('original:A');
      await flush();
      expect(host.textContent).toContain('SUCCESS / CONFIRMED');
      expect(
        host.querySelector<HTMLTextAreaElement>('#delivery-decision-reason')!
          .disabled,
      ).toBe(true);
      await fill();
      await harness.confirm!();
      expect(recordMarketDeliveryReview).not.toHaveBeenCalled();
      expect(harness.toast).not.toHaveBeenCalled();
    },
  );

  it('does not let an old operation action overwrite the current modal', async () => {
    const old = deferred<MarketApi.DeliveryOperation>();
    vi.mocked(getMarketDeliveryOperation)
      .mockReturnValueOnce(old.promise)
      .mockResolvedValueOnce(
        Object.assign(operation('original:B'), {
          action: 'SUSPEND',
          opType: 'REVOKE',
        }),
      );
    harness.open!('original:A');
    await harness.close!();
    harness.open!('original:B');
    await flush();
    old.resolve(
      Object.assign(operation(), { action: 'REVOKE', opType: 'REVOKE' }),
    );
    await flush();
    expect(host.textContent).toContain('original:B');
    expect(host.textContent).not.toContain('original:A');
    expect(
      host.querySelector('[data-testid="delivery-action"]')?.textContent,
    ).toBe('请求暂停');
    expect(host.textContent).not.toContain('请求撤销');
    expect(recordMarketDeliveryReview).not.toHaveBeenCalled();
  });

  it.each([
    ['MATCHED', '匹配'],
    ['MISSING', '缺失'],
    ['CONFLICT', '冲突'],
    ['UNVERIFIABLE', '无法核验'],
    ['NOT_APPLICABLE', '不适用'],
    ['FUTURE_STATUS', '无法核验'],
    [undefined, '无法核验'],
    [null, '无法核验'],
  ])(
    'shows recorded approval %s separately from APPLIED in history and the save receipt',
    async (approvalStatus, label) => {
      const recorded =
        approvalStatus === undefined
          ? review()
          : { ...review(), approvalStatus };
      vi.mocked(getMarketDeliveryReviews).mockResolvedValue({
        items: [recorded],
        hasMore: false,
      });
      vi.mocked(recordMarketDeliveryReview).mockResolvedValue(recorded);
      harness.open!('original:A');
      await flush();
      const history = host.querySelector('li')!;
      expect(history.textContent).toContain('ORIGINAL_APPLIED');
      expect(history.textContent).toContain(`原批准凭证核对：${label}`);
      expect(host.textContent).toContain('记录时');
      expect(recordMarketDeliveryReview).not.toHaveBeenCalled();

      await fill();
      await harness.confirm!();
      await flush();
      expect(
        host.querySelector('[data-testid="saved-approval-status"]')
          ?.textContent,
      ).toContain(`原批准凭证核对：${label}`);
      expect(
        host.querySelector('[data-testid="saved-review"]')?.textContent,
      ).toContain('ORIGINAL_APPLIED');
      expect(recordMarketDeliveryReview).toHaveBeenCalledExactlyOnceWith({
        requestId,
        operationId: 'original:A',
        decision: 'KEEP_PENDING',
        reason: '核查原因',
        basis: '核查依据',
      });
      expect(harness.toast).toHaveBeenCalledWith(
        '核查结论已记录，未修改授权状态',
      );
    },
  );

  it.each(['session', 'refreshSession', 'user', 'permission', 'close', 'unmount'])(
    'discards recorded approval evidence after %s changes',
    async (change) => {
      const pending = deferred<MarketApi.DeliveryReviewPage>();
      vi.mocked(getMarketDeliveryReviews).mockReturnValue(pending.promise);
      harness.open!('original:A');
      await flush();
      if (change === 'session') harness.state!.accessToken = '2';
      if (change === 'refreshSession') harness.state!.refreshToken = '2';
      if (change === 'user') harness.state!.userInfo.userId = '12';
      if (change === 'permission') harness.state!.allowed = false;
      if (change === 'close') await harness.close!();
      if (change === 'unmount') {
        app!.unmount();
        app = undefined;
      }
      pending.resolve({
        items: [{ ...review(), approvalStatus: 'MATCHED' }],
        hasMore: false,
      });
      await flush();
      expect(host.querySelectorAll('li')).toHaveLength(0);
      expect(host.textContent).not.toContain('原批准凭证核对：匹配');
      expect(recordMarketDeliveryReview).not.toHaveBeenCalled();
    },
  );

  it('requires reason and basis, persists one request and never claims authorization changed', async () => {
    harness.open!('original:A');
    await flush();
    await harness.confirm!();
    expect(recordMarketDeliveryReview).not.toHaveBeenCalled();
    await fill();
    await harness.confirm!();
    await flush();
    expect(recordMarketDeliveryReview).toHaveBeenCalledExactlyOnceWith({
      requestId,
      operationId: 'original:A',
      decision: 'KEEP_PENDING',
      reason: '核查原因',
      basis: '核查依据',
    });
    expect(host.textContent).toContain('已保存核查编号 9223372036854775806');
    expect(harness.toast).toHaveBeenCalledWith(
      '核查结论已记录，未修改授权状态',
    );
  });

  it('reuses the exact frozen request after response loss without automatic retries', async () => {
    vi.mocked(recordMarketDeliveryReview).mockRejectedValueOnce(
      new Error('response lost'),
    );
    harness.open!('original:A');
    await flush();
    await fill();
    await harness.confirm!();
    await flush();
    expect(host.textContent).toContain('内容已冻结');
    expect(recordMarketDeliveryReview).toHaveBeenCalledOnce();
    expect(
      host.querySelector<HTMLTextAreaElement>('#delivery-decision-reason')!
        .disabled,
    ).toBe(true);
    await harness.confirm!();
    await flush();
    expect(recordMarketDeliveryReview).toHaveBeenCalledTimes(2);
    expect(vi.mocked(recordMarketDeliveryReview).mock.calls[0]).toEqual(
      vi.mocked(recordMarketDeliveryReview).mock.calls[1],
    );
    expect(harness.toast).toHaveBeenCalledOnce();
  });

  it('blocks duplicate in-flight submissions', async () => {
    const pending = deferred<MarketApi.DeliveryReview>();
    vi.mocked(recordMarketDeliveryReview).mockReturnValue(pending.promise);
    harness.open!('original:A');
    await flush();
    await fill();
    const first = harness.confirm!();
    await harness.confirm!();
    expect(recordMarketDeliveryReview).toHaveBeenCalledOnce();
    pending.resolve(review());
    await first;
    await flush();
    expect(harness.unlock).toHaveBeenCalledOnce();
  });

  it.each(['session', 'user', 'permission', 'close', 'unmount'])(
    'discards late write receipts after %s changes',
    async (change) => {
      const pending = deferred<MarketApi.DeliveryReview>();
      vi.mocked(recordMarketDeliveryReview).mockReturnValue(pending.promise);
      harness.open!('original:A');
      await flush();
      await fill();
      const saving = harness.confirm!();
      if (change === 'session') harness.state!.refreshToken = '2';
      if (change === 'user') harness.state!.userInfo.userId = '12';
      if (change === 'permission') harness.state!.allowed = false;
      if (change === 'close') await harness.close!();
      if (change === 'unmount') {
        app!.unmount();
        app = undefined;
      }
      pending.resolve(review());
      await saving;
      await flush();
      expect(harness.toast).not.toHaveBeenCalled();
      expect(host.textContent).not.toContain('已保存核查编号');
    },
  );

  it.each([
    'requestId',
    'operationId',
    'applyId',
    'actorUserId',
    'reason',
    'basis',
  ] as const)('does not accept a mismatched saved %s', async (field) => {
    vi.mocked(recordMarketDeliveryReview).mockResolvedValue({
      ...review(),
      [field]: 'different',
    });
    harness.open!('original:A');
    await flush();
    await fill();
    await harness.confirm!();
    await flush();
    expect(host.textContent).toContain('保存回执与本次请求不一致');
    expect(harness.toast).not.toHaveBeenCalled();
  });

  it('loads history using a lossless cursor', async () => {
    vi.mocked(getMarketDeliveryReviews)
      .mockResolvedValueOnce({
        items: [review()],
        hasMore: true,
        nextId: '9223372036854775806',
      })
      .mockResolvedValueOnce({
        items: [{ ...review(), id: '9223372036854775807' }],
        hasMore: false,
      });
    harness.open!('original:A');
    await flush();
    host
      .querySelector<HTMLButtonElement>('[data-testid="more-reviews"]')!
      .click();
    await flush();
    expect(getMarketDeliveryReviews).toHaveBeenLastCalledWith(
      'original:A',
      '9223372036854775806',
    );
    expect(host.querySelectorAll('li')).toHaveLength(2);
    expect(host.textContent).toContain('核查编号 9223372036854775807');
  });

  it('discards another operation or stale modal history', async () => {
    const old = deferred<MarketApi.DeliveryReviewPage>();
    vi.mocked(getMarketDeliveryReviews)
      .mockReturnValueOnce(old.promise)
      .mockResolvedValueOnce({ items: [review('original:A')], hasMore: false });
    harness.open!('original:A');
    await harness.close!();
    vi.mocked(getMarketDeliveryOperation).mockResolvedValue(
      operation('original:B'),
    );
    harness.open!('original:B');
    await flush();
    expect(host.textContent).toContain('返回的核查历史身份或游标不一致');
    old.resolve({ items: [review()], hasMore: false });
    await flush();
    expect(host.querySelectorAll('li')).toHaveLength(0);
  });

  it('does not request data without authorization', async () => {
    harness.state!.allowed = false;
    harness.open!('original:A');
    await flush();
    await harness.confirm!();
    expect(getMarketDeliveryOperation).not.toHaveBeenCalled();
    expect(getMarketDeliveryReviews).not.toHaveBeenCalled();
    expect(recordMarketDeliveryReview).not.toHaveBeenCalled();
  });

  it('requires a secure UUID source and never falls back to a timestamp', async () => {
    vi.stubGlobal('crypto', {});
    harness.open!('original:A');
    await flush();
    await fill();
    await harness.confirm!();
    await flush();
    expect(recordMarketDeliveryReview).not.toHaveBeenCalled();
    expect(host.textContent).toContain('无法生成安全请求编号');
  });

  it('does not allow a new pending conclusion on a confirmed operation', async () => {
    vi.mocked(getMarketDeliveryOperation).mockResolvedValue({
      ...operation(),
      status: 'SUCCESS',
      resolutionStatus: 'CONFIRMED',
    });
    harness.open!('original:A');
    await flush();
    await fill();
    await harness.confirm!();
    expect(recordMarketDeliveryReview).not.toHaveBeenCalled();
  });
});
