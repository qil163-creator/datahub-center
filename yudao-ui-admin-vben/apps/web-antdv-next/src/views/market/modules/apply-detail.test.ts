import type { App, PropType } from 'vue';

import type { MarketApi } from '#/api/asset/market';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getMarketApply } from '#/api/asset/market';

import ApplyDetail from './apply-detail.vue';

const harness = vi.hoisted(() => ({
  open: undefined as (() => Promise<void>) | undefined,
  onOpenChange: undefined as ((open: boolean) => Promise<void>) | undefined,
  id: '9223372036854775807',
  locked: false,
  push: vi.fn(),
  hasRoute: vi.fn(),
  drawerState: vi.fn(),
  warning: vi.fn(),
  state: undefined as
    | undefined
    | { accessCodes: string[]; accessToken: string; loginExpired: boolean; refreshToken: string; userInfo: { userId: string }; userRoles: string[] },
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: () => [
    defineComponent({ render: () => null }),
    { setState: vi.fn() },
  ],
  useVbenDrawer: (options: {
    onOpenChange?: (open: boolean) => Promise<void>;
  }) => {
    harness.onOpenChange = options.onOpenChange;
    harness.open = () => options.onOpenChange!(true);
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h('section', slots.default?.()),
      }),
      {
        getData: () => ({ id: harness.id }),
        lock: () => {
          harness.locked = true;
        },
        unlock: () => {
          harness.locked = false;
        },
        setState: (state: { isOpen?: boolean; loading?: boolean; }) => {
          harness.drawerState(state);
          if (state.loading !== undefined) harness.locked = state.loading;
        },
      },
    ];
  },
}));
vi.mock('@vben/stores', () => {
  harness.state = reactive({
    accessToken: '1',
    refreshToken: '',
    userInfo: { userId: '9' },
    accessCodes: ['asset:market:my-assets:query'], loginExpired: false, userRoles: [],
  });
  return {
    useAccessStore: () => harness.state,
    useUserStore: () => harness.state,
  };
});
vi.mock('vue-router', () => ({ useRouter: () => ({ push: harness.push, hasRoute: harness.hasRoute }) }));
vi.mock('@vben/access', () => ({ useAccess: () => ({ hasAccessByCodes: (codes: string[]) => codes.some((code) => harness.state!.accessCodes.includes(code)) }) }));
vi.mock('antdv-next', () => ({
  message: { warning: harness.warning },
  Button: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('button', slots.default?.()),
  }),
  Empty: defineComponent({ render: () => null }),
  Tag: defineComponent({ render: () => null }),
  Timeline: defineComponent({ render: () => null }),
  TimelineItem: defineComponent({ render: () => null }),
}));
vi.mock('#/api/asset/market', () => ({ getMarketApply: vi.fn() }));
vi.mock('#/api/asset/dataasset', () => ({ ASSET_TYPE_OPTIONS: [] }));
vi.mock('./preview-drawer.vue', () => ({
  default: defineComponent({ render: () => null }),
}));
vi.mock('#/views/modeling/modelcommon/detail-field-form.vue', () => ({
  default: defineComponent({
    props: {
      fields: {
        required: true,
        type: Array as PropType<Array<{ key: string; value?: unknown }>>,
      },
    },
    setup: (props) => () =>
      h(
        'dl',
        props.fields.map((field) =>
          h('dd', { 'data-field': field.key }, String(field.value ?? '')),
        ),
      ),
  }),
}));

let app: App | undefined;
let host: HTMLDivElement;
beforeEach(() => {
  vi.resetAllMocks();
  harness.id = '9223372036854775807';
  harness.locked = false;
  harness.hasRoute.mockReturnValue(true);
  harness.push.mockResolvedValue(undefined);
  Object.assign(harness.state!, {
    accessToken: '1',
    refreshToken: '',
    userInfo: { userId: '9' },
    accessCodes: ['asset:market:my-assets:query'], loginExpired: false, userRoles: [],
  });
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(ApplyDetail);
  app.mount(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe.each([
  ['SUSPENDED', '暂停已确认'],
  ['REVOKED', '撤销已确认'],
] as const)('application detail for %s', (deliveryResolution, label) => {
  it.each([true, false, undefined])(
    'keeps result visible and unavailable with usable=%s',
    async (usable) => {
      for (const effective of [true, false]) {
        const row: MarketApi.Apply = {
          id: '9223372036854775807',
          assetType: 1,
          effective,
          authStatus: effective ? 1 : 0,
          usable,
          deliveryResolution,
          renewalOfApplyId: '9223372036854775806',
        };
        vi.mocked(getMarketApply).mockResolvedValue(row);
        await harness.open!();
        await nextTick();
        expect(
          host.querySelector('[data-field="deliveryResolution"]')?.textContent,
        ).toBe(label);
        expect(
          host.querySelector('[data-field="authStatus"]')?.textContent,
        ).toBe(`${effective ? '已批准' : '未生效'}，${label}`);
        expect(
          host.querySelector('[data-field="renewalOfApplyId"]')?.textContent,
        ).toBe('9223372036854775806');
        expect(host.querySelectorAll('button')).toHaveLength(0);
        expect(getMarketApply).toHaveBeenLastCalledWith('9223372036854775807');
      }
    },
  );
});

it('does not turn confirmed GRANT into usable access after expiry', async () => {
  vi.mocked(getMarketApply).mockResolvedValue({
    effective: false,
    authStatus: 0,
    usable: true,
    deliveryResolution: 'APPLIED',
  });
  await harness.open!();
  await nextTick();
  expect(
    host.querySelector('[data-field="deliveryResolution"]')?.textContent,
  ).toBe('已生效');
  expect(host.querySelector('[data-field="authStatus"]')?.textContent).toBe(
    '未生效',
  );
});

function deferred() {
  let resolve!: (value: MarketApi.Apply) => void;
  const promise = new Promise<MarketApi.Apply>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}

it('本人历史批准通过动态菜单跳到资产并关闭原申请详情，不执行预览', async () => {
  vi.mocked(getMarketApply).mockResolvedValue({ id: harness.id, applyUserId: '9', status: 5, assetType: 2, effective: false });
  await harness.open!(); await nextTick(); const button = host.querySelector('button')!;
  expect(button.textContent).toContain('查看已授权资产'); button.click();
  for (let index = 0; index < 6; index++) await Promise.resolve(); await nextTick();
  expect(harness.push).toHaveBeenCalledWith({ name: 'AssetMarketMyAssets', query: { applyId: harness.id } });
  expect(harness.drawerState).toHaveBeenCalledWith({ isOpen: false }); expect(host.querySelector('button')).toBeNull();
});

it('守卫取消导航时保留申请详情，提供方不能通过共用详情跳他人消费记录', async () => {
  vi.mocked(getMarketApply).mockResolvedValue({ id: harness.id, applyUserId: '9', status: 2 });
  await harness.open!(); await nextTick(); harness.push.mockResolvedValueOnce({ type: 4 });
  host.querySelector('button')!.click(); for (let index = 0; index < 6; index++) await Promise.resolve(); await nextTick();
  expect(host.querySelector('button')?.textContent).toContain('查看已授权资产'); expect(harness.drawerState).not.toHaveBeenCalledWith({ isOpen: false });
  vi.mocked(getMarketApply).mockResolvedValue({ id: harness.id, applyUserId: '10', status: 2 });
  await harness.open!(); await nextTick(); expect(host.querySelector('button')).toBeNull();
});

it('新申请详情保持当前loading，迟到响应不能覆盖新申请', async () => {
  const old = deferred();
  const latest = deferred();
  vi.mocked(getMarketApply)
    .mockReturnValueOnce(old.promise)
    .mockReturnValueOnce(latest.promise);
  const first = harness.open!();
  harness.id = '9223372036854775806';
  const second = harness.open!();
  old.resolve({ title: '旧申请' });
  await first;
  await nextTick();
  expect(harness.locked).toBe(true);
  expect(host.textContent).not.toContain('旧申请');
  latest.resolve({ title: '新申请' });
  await second;
  await nextTick();
  expect(harness.locked).toBe(false);
  expect(host.textContent).toContain('新申请');
});

it.each(['close', 'session', 'user', 'unmount'] as const)(
  '在%s后迟到的申请详情失效',
  async (action) => {
    const pending = deferred();
    vi.mocked(getMarketApply).mockReturnValueOnce(pending.promise);
    const loading = harness.open!();
    if (action === 'close') await harness.onOpenChange!(false);
    if (action === 'session') harness.state!.refreshToken = '2';
    if (action === 'user') harness.state!.userInfo.userId = '10';
    if (action === 'unmount') {
      app!.unmount();
      app = undefined;
    }
    pending.resolve({ title: '旧身份申请' });
    await loading;
    await nextTick();
    expect(host.textContent).not.toContain('旧身份申请');
    expect(harness.locked).toBe(false);
  },
);
