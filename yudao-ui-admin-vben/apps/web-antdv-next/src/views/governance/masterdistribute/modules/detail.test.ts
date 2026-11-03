import type { App, PropType } from 'vue';

import type { GovernanceMasterDistributeApi } from '#/api/governance/masterdistribute';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getMasterDistribute } from '#/api/governance/masterdistribute';

import Detail from './detail.vue';

const harness = vi.hoisted(() => ({
  onOpenChange: undefined as ((open: boolean) => Promise<void>) | undefined,
  id: '9007199254740993',
  locked: false,
  state: undefined as
    | undefined
    | { accessToken: string; userInfo: { userId: string }; refreshToken: string; },
}));
vi.mock('@vben/common-ui', () => ({
  useVbenDrawer: (options: {
    onOpenChange: (open: boolean) => Promise<void>;
  }) => {
    harness.onOpenChange = options.onOpenChange;
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h('aside', slots.default?.()),
      }),
      {
        getData: () => ({ id: harness.id }),
        lock: () => {
          harness.locked = true;
        },
        unlock: () => {
          harness.locked = false;
        },
        setState: (state: { loading?: boolean }) => {
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
  });
  return {
    useAccessStore: () => harness.state,
    useUserStore: () => harness.state,
  };
});
vi.mock('#/api/governance/masterdistribute', () => ({
  getMasterDistribute: vi.fn(),
}));
vi.mock('#/api/system/user', () => ({ getSimpleUserList: vi.fn(async () => []) }));
vi.mock('../data', () => ({
  DISTRIBUTE_TYPE_OPTIONS: [],
  TASK_TYPE_OPTIONS: [],
  STATUS_OPTIONS: [],
  RUN_STATUS_OPTIONS: [],
  optionLabel: () => '',
  nextRunTimeLabel: () => '',
  releaseTimeLabel: () => '',
  releaseUserLabel: () => '',
  runBlockedReason: () => '',
}));
vi.mock('#/views/modeling/modelcommon/detail-field-form.vue', () => ({
  default: defineComponent({
    props: {
      fields: {
        type: Array as PropType<{ key: string; value: unknown }[]>,
        default: () => [],
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
function deferred() {
  let resolve!: (value: GovernanceMasterDistributeApi.Distribute) => void;
  const promise = new Promise<GovernanceMasterDistributeApi.Distribute>(
    (yes) => {
      resolve = yes;
    },
  );
  return { promise, resolve };
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.id = '9007199254740993';
  harness.locked = false;
  Object.assign(harness.state!, {
    accessToken: '1',
    refreshToken: '',
    userInfo: { userId: '9' },
  });
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Detail);
  app.mount(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('主数据派发抽屉请求身份', () => {
  it('保留大ID及派发配置，新请求控制展示和loading', async () => {
    const old = deferred();
    const latest = deferred();
    vi.mocked(getMasterDistribute)
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(latest.promise);
    const first = harness.onOpenChange!(true);
    harness.id = '9007199254740994';
    const second = harness.onOpenChange!(true);
    expect(getMasterDistribute).toHaveBeenNthCalledWith(1, '9007199254740993');
    expect(getMasterDistribute).toHaveBeenNthCalledWith(2, '9007199254740994');
    old.resolve({ name: '旧派发' });
    await first;
    await nextTick();
    expect(harness.locked).toBe(true);
    expect(host.textContent).not.toContain('旧派发');
    latest.resolve({
      name: '最新派发',
      config: { api: { url: 'https://example.test/destination' } },
    });
    await second;
    await nextTick();
    expect(harness.locked).toBe(false);
    expect(host.textContent).toContain('最新派发');
    expect(host.querySelector('[data-field="config"]')?.textContent).toContain(
      'destination',
    );
  });
  it.each(['close', 'session', 'unmount'] as const)(
    '在%s后丢弃迟到响应',
    async (action) => {
      const pending = deferred();
      vi.mocked(getMasterDistribute).mockReturnValueOnce(pending.promise);
      const loading = harness.onOpenChange!(true);
      if (action === 'close') await harness.onOpenChange!(false);
      if (action === 'session') {
        harness.state!.refreshToken = '2';
        await nextTick();
      }
      if (action === 'unmount') {
        app!.unmount();
        app = undefined;
      }
      pending.resolve({ name: '失效派发' });
      await loading;
      await nextTick();
      expect(host.textContent).not.toContain('失效派发');
    },
  );
});
