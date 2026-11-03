import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import ConsumptionHistory from './consumption-history.vue';

const mocks = vi.hoisted(() => ({
  balances: vi.fn(), close: vi.fn(), history: vi.fn(), receipt: vi.fn(),
  access: {} as { accessToken: string; refreshToken?: string },
  user: {} as { userInfo: { userId: string } },
  options: {} as { onOpenChange: (open: boolean) => void },
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => mocks.access, useUserStore: () => mocks.user }));
vi.mock('@vben/common-ui', () => ({ useVbenModal: (options: typeof mocks.options) => {
  mocks.options = options;
  return [defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) }), { close: mocks.close }];
} }));
vi.mock('#/api/asset/consumption', () => ({
  getConsumptionBalances: mocks.balances, getConsumptionHistory: mocks.history, getConsumptionReceipt: mocks.receipt,
}));
vi.mock('antdv-next', () => ({
  Alert: defineComponent({ props: { message: String }, setup: (props) => () => h('p', props.message) }),
  Button: defineComponent({ props: { disabled: Boolean, loading: Boolean }, emits: ['click'], setup: (props, { slots, emit }) => () => h('button', { disabled: props.disabled, onClick: () => emit('click') }, slots.default?.()) }),
  Input: defineComponent({ props: { value: String }, emits: ['update:value'], setup: (props, { emit }) => () => h('input', { value: props.value, onInput: (event: Event) => emit('update:value', (event.target as HTMLInputElement).value) }) }),
  Table: defineComponent({ props: { dataSource: Array }, setup: (props) => () => h('pre', JSON.stringify(props.dataSource)) }),
  Tag: defineComponent({ setup: (_, { slots }) => () => h('span', slots.default?.()) }),
}));

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}
function receipt(key = 'request-0001') {
  return { consumerKey: 'PORTAL:3:9007199254740993', requestKey: key, state: 'COMMITTED', actualRows: 2, rowLimit: 5, allocations: [] };
}
let app: App;
let host: HTMLDivElement;
async function flushPromises() {
  await Promise.resolve(); await Promise.resolve(); await nextTick();
}
async function input(index: number, value: string) {
  const element = host.querySelectorAll('input')[index]!;
  element.value = value; element.dispatchEvent(new Event('input')); await nextTick();
}
async function lookup() {
  host.querySelectorAll('button')[1]!.click(); await nextTick();
}
beforeEach(() => {
  vi.clearAllMocks();
  mocks.access = reactive({ accessToken: '1' });
  mocks.user = reactive({ userInfo: { userId: '3' } });
  mocks.history.mockResolvedValue([]); mocks.balances.mockResolvedValue([]);
  mocks.close.mockResolvedValue(undefined);
  host = document.createElement('div'); document.body.append(host);
  app = createApp(ConsumptionHistory); app.mount(host);
});
afterEach(() => { app.unmount(); host.remove(); });

describe('consumption receipt lookup', () => {
  it('keeps the complete asset id and only queries the original receipt', async () => {
    mocks.receipt.mockResolvedValue(receipt());
    mocks.options.onOpenChange(true); await flushPromises();
    await input(0, '9007199254740993');
    await input(1, 'request-0001');
    await lookup(); await flushPromises();
    expect(mocks.receipt).toHaveBeenCalledExactlyOnceWith('9007199254740993', 'request-0001');
    expect(host.textContent).toContain('原请求：已确认消费，实际返回 2 行');
  });
  it('discards a response when lookup inputs change', async () => {
    const pending = deferred<ReturnType<typeof receipt>>(); mocks.receipt.mockReturnValue(pending.promise);
    mocks.options.onOpenChange(true); await flushPromises();
    await input(0, '9007199254740993');
    await input(1, 'request-0001');
    await lookup();
    await input(0, '9007199254740994');
    pending.resolve(receipt()); await flushPromises();
    expect(host.textContent).not.toContain('原请求：已确认消费');
  });
  it('clears and ignores old session history after switching session', async () => {
    const pending = deferred<ReturnType<typeof receipt>[]>(); mocks.history.mockReturnValue(pending.promise);
    mocks.options.onOpenChange(true);
    mocks.access.accessToken = '2'; await flushPromises();
    pending.resolve([receipt('old-session-key')]); await flushPromises();
    expect(mocks.close).toHaveBeenCalled(); expect(host.textContent).not.toContain('old-session-key');
  });
  it('rejects zero ids and invalid request keys before calling the API', async () => {
    mocks.options.onOpenChange(true); await flushPromises();
    await input(0, '0');
    await input(1, '_request-0001');
    expect(host.querySelectorAll('button')[1]!.disabled).toBe(true);
    expect(mocks.receipt).not.toHaveBeenCalled();
  });
});
