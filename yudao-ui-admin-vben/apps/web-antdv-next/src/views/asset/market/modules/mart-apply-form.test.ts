/* eslint-disable vue/one-component-per-file -- 测试桩集中模拟弹窗和表单 */
import type { App, ComponentPublicInstance } from 'vue';

import type { MarketApi } from '#/api/asset/market';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import ApplyForm from './mart-apply-form.vue';

const mock = vi.hoisted(() => ({
  access: {
    accessToken: 'session',
    refreshToken: '',
    loginExpired: false,
    accessCodes: ['asset:market:apply'],
  },
  user: { userInfo: { userId: '1' } as null | { userId: string } },
  create: vi.fn(),
  success: vi.fn(),
  close: vi.fn(),
  validate: vi.fn(),
  data: { assetId: '9223372036854775807', assetName: '河流维表' },
  options: undefined as undefined | { onOpenChange: (open: boolean) => void },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => mock.access,
  useUserStore: () => mock.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: () =>
      mock.access.accessCodes.includes('asset:market:apply'),
  }),
}));
vi.mock('#/api/asset/market', () => ({ createMarketApply: mock.create }));
vi.mock('#/adapter/component/date-picker', () => ({
  RangePicker: defineComponent({ setup: () => () => null }),
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: { onOpenChange: (open: boolean) => void }) => {
    mock.options = options;
    const box = defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('section', slots.default?.()),
    });
    return [
      box,
      {
        unlock: vi.fn(),
        lock: vi.fn(),
        getData: () => mock.data,
        close: mock.close,
        setState: vi.fn(),
      },
    ];
  },
}));
vi.mock('antdv-next', () => {
  const box = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('section', slots.default?.()),
  });
  const form = defineComponent({
    setup: (_, { slots, expose }) => {
      expose({ validate: mock.validate, clearValidate: vi.fn() });
      return () => h('section', slots.default?.());
    },
  });
  return {
    Button: box,
    Form: form,
    FormItem: box,
    Input: box,
    Select: box,
    TextArea: box,
    message: { success: mock.success },
  };
});
interface State {
  model: {
    assetId?: MarketApi.Id;
    dates?: [string, string];
    name: string;
    period?: 'CUSTOM' | 'PERMANENT';
    reason: string;
  };
  submit: () => Promise<void>;
  saving: boolean;
}
let app: App | undefined;
let host: HTMLDivElement;
let state: State;
async function flush() {
  for (let index = 0; index < 8; index++) await Promise.resolve();
  await nextTick();
}
function mount() {
  app = createApp(ApplyForm);
  const vm = app.mount(host) as ComponentPublicInstance;
  state = (vm.$ as unknown as { setupState: State }).setupState;
  mock.options?.onOpenChange(true);
}
beforeEach(() => {
  vi.resetAllMocks();
  mock.access = reactive({
    accessToken: 'session',
    refreshToken: '',
    loginExpired: false,
    accessCodes: ['asset:market:apply'],
  });
  mock.user = reactive({ userInfo: { userId: '1' } });
  mock.validate.mockResolvedValue({});
  mock.create.mockResolvedValue('1');
  mock.close.mockResolvedValue(undefined);
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});
describe('集市资产权限申请', () => {
  it('长期有效保留无损资产编号且不设置截止日期', async () => {
    mount();
    state.model.period = 'PERMANENT';
    state.model.reason = '  水情研究  ';
    await state.submit();
    expect(mock.create).toHaveBeenCalledWith(
      expect.objectContaining({
        assetId: '9223372036854775807',
        purpose: '水情研究',
        submit: true,
        applyStartTime: undefined,
        applyEndTime: undefined,
      }),
    );
    expect(mock.success).toHaveBeenCalledOnce();
  });
  it('自定义周期转为真实时间戳，同一天允许申请', async () => {
    mount();
    state.model.period = 'CUSTOM';
    state.model.reason = '水情研究';
    state.model.dates = ['2026-10-22', '2026-10-22'];
    await state.submit();
    const payload = mock.create.mock.calls[0]?.[0] as MarketApi.Apply;
    expect(typeof payload.applyStartTime).toBe('number');
    expect(typeof payload.applyEndTime).toBe('number');
    expect(payload.applyEndTime).toBeGreaterThan(
      payload.applyStartTime as number,
    );
  });
  it('缺少周期、理由或自定义日期时不创建申请', async () => {
    mount();
    await state.submit();
    state.model.period = 'CUSTOM';
    state.model.reason = '用途';
    await state.submit();
    expect(mock.create).not.toHaveBeenCalled();
  });
  it('提交期间阻止重复请求，会话变化后丢弃旧成功事件', async () => {
    let resolve!: (value: string) => void;
    mock.create.mockReturnValueOnce(
      new Promise<string>((yes) => {
        resolve = yes;
      }),
    );
    mount();
    state.model.period = 'PERMANENT';
    state.model.reason = '用途';
    const request = state.submit();
    await flush();
    await state.submit();
    expect(mock.create).toHaveBeenCalledTimes(1);
    mock.access.accessToken = 'new';
    resolve('1');
    await request;
    expect(mock.success).not.toHaveBeenCalled();
    expect(state.saving).toBe(false);
  });
});
