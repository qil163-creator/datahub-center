import type { App } from 'vue';

import type { MarketApi } from '#/api/asset/market';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getMarketDeliveryOperation,
  getMarketDeliveryReviews,
  recordMarketDeliveryDisposal,
} from '#/api/asset/market';

import DeliveryDisposal from './delivery-disposal.vue';

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
  Checkbox: defineComponent({
    props: { checked: Boolean, disabled: Boolean },
    emits: ['update:checked'],
    setup:
      (props, { emit, slots }) =>
      () =>
        h('label', [
          h('input', {
            type: 'checkbox',
            'data-testid': 'ack',
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
  Select: defineComponent({
    props: {
      value: { default: '', type: String },
      disabled: Boolean,
      options: { default: () => [], type: Array },
    },
    emits: ['update:value'],
    setup:
      (props, { emit }) =>
      () =>
        h(
          'select',
          {
            'data-testid': 'action',
            disabled: props.disabled,
            value: props.value,
            onChange: (event: Event) =>
              emit('update:value', (event.target as HTMLSelectElement).value),
          },
          [
            h('option', { value: 'REVOKE' }, 'REVOKE'),
            h('option', { value: 'CONFIRM_ORIGINAL' }, 'CONFIRM_ORIGINAL'),
          ],
        ),
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
  recordMarketDeliveryDisposal: vi.fn(),
}));

let app: App | undefined;
let host: HTMLDivElement;
const requestId = '12345678-1234-1234-1234-123456789012';
const decisionRequestId = '11111111-1111-1111-1111-111111111111';
const operation = (
  overrides: Partial<MarketApi.DeliveryOperation> = {},
): MarketApi.DeliveryOperation => ({
  id: '9223372036854775805',
  applyId: '9223372036854775807',
  operationId: 'original:A',
  opType: 'GRANT',
  protocolVersion: 2,
  sourceRevision: '9223372036854775804',
  status: 'PENDING',
  resolutionStatus: 'REVIEW_REQUIRED',
  ...overrides,
});
const decision = (
  overrides: Partial<MarketApi.DeliveryReview> = {},
): MarketApi.DeliveryReview => ({
  id: '9223372036854775800',
  applyId: '9223372036854775807',
  operationId: 'original:A',
  requestId: decisionRequestId,
  sourceRevision: '9223372036854775804',
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
const receipt = (
  action: MarketApi.DeliveryDisposalAction = 'REVOKE',
  overrides: Partial<MarketApi.DeliveryReview> = {},
): MarketApi.DeliveryReview => ({
  id: '9223372036854775806',
  applyId: '9223372036854775807',
  operationId: 'original:A',
  requestId,
  sourceRevision: '9223372036854775804',
  decision: action,
  kind: 'DISPOSAL',
  classification: 'ORIGINAL_APPLIED',
  actorUserId: '9',
  actorDeptId: '100',
  reason: '处置原因',
  basis: '处置依据',
  occurredAt: 1_700_000_000_000,
  resultingOperationId:
    action === 'REVOKE' ? 'asset:asset:apply:9:r3:REVOKE' : null,
  ...overrides,
});
async function flush(times = 8) {
  for (let i = 0; i < times; i++) await Promise.resolve();
  await nextTick();
}
async function fill(options: { ack?: boolean; action?: string } = {}) {
  if (options.action) {
    const select = host.querySelector<HTMLSelectElement>(
      '[data-testid="action"]',
    )!;
    select.value = options.action;
    select.dispatchEvent(new Event('change', { bubbles: true }));
  }
  for (const [id, value] of [
    ['#delivery-disposal-reason', ' 处置原因 '],
    ['#delivery-disposal-basis', ' 处置依据 '],
  ] as const) {
    const area = host.querySelector<HTMLTextAreaElement>(id)!;
    area.value = value;
    area.dispatchEvent(new Event('input', { bubbles: true }));
  }
  if (options.ack !== false) {
    const ack = host.querySelector<HTMLInputElement>('[data-testid="ack"]')!;
    ack.checked = true;
    ack.dispatchEvent(new Event('change', { bubbles: true }));
  }
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
  vi.mocked(getMarketDeliveryReviews).mockResolvedValue(
    reviewPage([decision()]),
  );
  vi.mocked(recordMarketDeliveryDisposal).mockResolvedValue(receipt());
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(DeliveryDisposal);
  app.mount(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
  vi.unstubAllGlobals();
});

describe('historical delivery disposal', () => {
  it.each(['REVOKE', 'CONFIRM_ORIGINAL'] as const)(
    'requires reason, basis and explicit acknowledgement before sending one %s request',
    async (action) => {
      vi.mocked(recordMarketDeliveryDisposal).mockResolvedValue(
        receipt(action),
      );
      harness.open!('original:A');
      await flush();
      await fill({ ack: false, action });
      await harness.confirm!();
      expect(recordMarketDeliveryDisposal).not.toHaveBeenCalled();
      await fill({ action });
      await harness.confirm!();
      await flush();
      expect(recordMarketDeliveryDisposal).toHaveBeenCalledExactlyOnceWith({
        requestId,
        operationId: 'original:A',
        action,
        reason: '处置原因',
        basis: '处置依据',
        basisReviewRequestId: decisionRequestId,
      });
      expect(
        host.querySelector('[data-testid="saved-disposal"]')?.textContent,
      ).toContain('9223372036854775806');
      expect(harness.toast).toHaveBeenCalledOnce();
      await harness.confirm!();
      expect(recordMarketDeliveryDisposal).toHaveBeenCalledOnce();
    },
  );

  it('shows the bound decision and refuses to submit when no DECISION exists', async () => {
    vi.mocked(getMarketDeliveryReviews).mockResolvedValue(
      reviewPage([receipt()]),
    );
    harness.open!('original:A');
    await flush();
    expect(host.textContent).toContain('请先在核查结论中记录决定后重试');
    expect(host.querySelector('[data-testid="basis-decision"]')).toBeNull();
    await fill();
    await harness.confirm!();
    expect(recordMarketDeliveryDisposal).not.toHaveBeenCalled();
  });

  it('disables submission and explains when the decision read fails', async () => {
    vi.mocked(getMarketDeliveryReviews).mockRejectedValue(
      new Error('network'),
    );
    harness.open!('original:A');
    await flush();
    expect(host.textContent).toContain('核查决定读取失败');
    expect(host.textContent).toContain('请先在核查结论中记录决定后重试');
    await fill();
    await harness.confirm!();
    expect(recordMarketDeliveryDisposal).not.toHaveBeenCalled();
  });

  it('keeps paging with the lossless nextId to bind the newest DECISION', async () => {
    vi.mocked(getMarketDeliveryReviews)
      .mockResolvedValueOnce(
        reviewPage(
          [decision({ id: 10, requestId: 'aaaa0000-0000-0000-0000-000000000000' })],
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
          receipt('REVOKE', {
            id: 21,
            requestId: 'cccc0000-0000-0000-0000-000000000000',
          }),
        ]),
      );
    harness.open!('original:A');
    await flush();
    expect(
      vi.mocked(getMarketDeliveryReviews).mock.calls.map(([op, cursor]) => [
        op,
        String(cursor),
      ]),
    ).toEqual([
      ['original:A', '0'],
      ['original:A', '10'],
    ]);
    expect(host.textContent).toContain('编号 20');
    expect(host.textContent).toContain('继续待核查');
    await fill();
    await harness.confirm!();
    expect(recordMarketDeliveryDisposal).toHaveBeenCalledExactlyOnceWith({
      requestId,
      operationId: 'original:A',
      action: 'REVOKE',
      reason: '处置原因',
      basis: '处置依据',
      basisReviewRequestId: 'bbbb0000-0000-0000-0000-000000000000',
    });
  });

  it('caps the decision search at ten pages of fifty', async () => {
    let calls = 0;
    vi.mocked(getMarketDeliveryReviews).mockImplementation(async () => {
      calls += 1;
      return reviewPage([decision({ id: calls })], true, calls);
    });
    harness.open!('original:A');
    await flush(40);
    expect(getMarketDeliveryReviews).toHaveBeenCalledTimes(10);
    expect(host.textContent).toContain('编号 10');
    expect(host.textContent).toContain(decisionRequestId);
    await fill();
    await harness.confirm!();
    expect(recordMarketDeliveryDisposal).toHaveBeenCalledExactlyOnceWith({
      requestId,
      operationId: 'original:A',
      action: 'REVOKE',
      reason: '处置原因',
      basis: '处置依据',
      basisReviewRequestId: decisionRequestId,
    });
  });

  it('rejects an inconsistent decision history cursor and keeps submission disabled', async () => {
    vi.mocked(getMarketDeliveryReviews).mockResolvedValue(
      reviewPage([decision({ id: 10 })], true, 99),
    );
    harness.open!('original:A');
    await flush();
    expect(host.textContent).toContain('游标不一致');
    expect(host.querySelector('[data-testid="basis-decision"]')).toBeNull();
    await fill();
    await harness.confirm!();
    expect(recordMarketDeliveryDisposal).not.toHaveBeenCalled();
  });

  it.each(['close', 'session', 'user'])(
    'discards a late decision response after %s changes',
    async (change) => {
      const late = deferred<MarketApi.DeliveryReviewPage>();
      vi.mocked(getMarketDeliveryReviews).mockReturnValue(late.promise);
      harness.open!('original:A');
      await flush();
      expect(host.textContent).toContain('正在读取核查决定');
      if (change === 'session') harness.state!.refreshToken = '2';
      if (change === 'user') harness.state!.userInfo.userId = '12';
      if (change === 'close') await harness.close!();
      late.resolve(reviewPage([decision()]));
      await flush();
      expect(host.textContent).toBe('');
      expect(host.querySelector('[data-testid="basis-decision"]')).toBeNull();
    },
  );

  it.each([
    { status: 'SUCCESS', resolutionStatus: 'CONFIRMED' },
    { status: 'PENDING', resolutionStatus: 'UNCONFIRMED' },
    { status: 'MANUAL', resolutionStatus: 'CONFIRMED' },
  ])(
    'does not offer disposal for an operation that is not pending review %#',
    async (state) => {
      vi.mocked(getMarketDeliveryOperation).mockResolvedValue(operation(state));
      harness.open!('original:A');
      await flush();
      expect(host.textContent).toContain('不能处置');
      await fill();
      await harness.confirm!();
      expect(recordMarketDeliveryDisposal).not.toHaveBeenCalled();
    },
  );

  it('allows MANUAL/UNKNOWN operations to be disposed', async () => {
    vi.mocked(getMarketDeliveryOperation).mockResolvedValue(
      operation({ status: 'MANUAL', resolutionStatus: 'UNKNOWN' }),
    );
    harness.open!('original:A');
    await flush();
    await fill();
    await harness.confirm!();
    expect(recordMarketDeliveryDisposal).toHaveBeenCalledOnce();
  });

  it('reuses the exact frozen request after response loss', async () => {
    vi.mocked(recordMarketDeliveryDisposal).mockRejectedValueOnce(
      new Error('lost'),
    );
    harness.open!('original:A');
    await flush();
    await fill();
    await harness.confirm!();
    await flush();
    expect(host.textContent).toContain('内容已冻结');
    expect(
      host.querySelector<HTMLTextAreaElement>('#delivery-disposal-reason')!
        .disabled,
    ).toBe(true);
    await harness.confirm!();
    await flush();
    expect(recordMarketDeliveryDisposal).toHaveBeenCalledTimes(2);
    expect(vi.mocked(recordMarketDeliveryDisposal).mock.calls[0]).toEqual(
      vi.mocked(recordMarketDeliveryDisposal).mock.calls[1],
    );
    expect(harness.toast).toHaveBeenCalledOnce();
  });

  it.each([
    ['another operation', { operationId: 'original:B' }],
    ['a pending decision instead of disposal', { kind: 'DECISION' as const }],
    ['another action', { decision: 'CONFIRM_ORIGINAL' as const }],
    ['another actor', { actorUserId: '12' }],
    ['another request', { requestId: '87654321-1234-1234-1234-123456789012' }],
  ])(
    'does not report success for a receipt from %s',
    async (_label, overrides) => {
      vi.mocked(recordMarketDeliveryDisposal).mockResolvedValue(
        receipt('REVOKE', overrides),
      );
      harness.open!('original:A');
      await flush();
      await fill();
      await harness.confirm!();
      await flush();
      expect(harness.toast).not.toHaveBeenCalled();
      expect(host.textContent).toContain('未确认成功');
      expect(host.querySelector('[data-testid="saved-disposal"]')).toBeNull();
    },
  );

  it('blocks duplicate in-flight submissions', async () => {
    const pending = deferred<MarketApi.DeliveryReview>();
    vi.mocked(recordMarketDeliveryDisposal).mockReturnValue(pending.promise);
    harness.open!('original:A');
    await flush();
    await fill();
    const first = harness.confirm!();
    await harness.confirm!();
    expect(recordMarketDeliveryDisposal).toHaveBeenCalledOnce();
    pending.resolve(receipt());
    await first;
    await flush();
    expect(harness.unlock).toHaveBeenCalledOnce();
  });

  it.each(['session', 'user', 'permission', 'close', 'unmount'])(
    'discards a late receipt after %s changes',
    async (change) => {
      const pending = deferred<MarketApi.DeliveryReview>();
      vi.mocked(recordMarketDeliveryDisposal).mockReturnValue(pending.promise);
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
      pending.resolve(receipt());
      await saving;
      await flush();
      expect(harness.toast).not.toHaveBeenCalled();
    },
  );

  it('requires a secure UUID source and never falls back to a timestamp', async () => {
    vi.stubGlobal('crypto', {});
    harness.open!('original:A');
    await flush();
    await fill();
    await harness.confirm!();
    expect(recordMarketDeliveryDisposal).not.toHaveBeenCalled();
    expect(host.textContent).toContain('安全请求编号');
  });

  it('does not load or send without authorization', async () => {
    harness.state!.allowed = false;
    harness.open!('original:A');
    await flush();
    await harness.confirm!();
    expect(getMarketDeliveryOperation).not.toHaveBeenCalled();
    expect(getMarketDeliveryReviews).not.toHaveBeenCalled();
    expect(recordMarketDeliveryDisposal).not.toHaveBeenCalled();
  });
});
