import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getMetaColumnDetail,
  updateMetaColumnBiz,
} from '#/api/governance/metaobject';

import ColumnBizForm from './column-biz-form.vue';

const harness = vi.hoisted(() => ({
  options: undefined as
    | undefined
    | {
        onConfirm: () => Promise<void>;
        onOpenChange: (open: boolean) => Promise<void>;
      },
  data: { id: '9007199254740993', businessName: '过期列表值' },
  state: { confirmDisabled: false, loading: false },
  access: undefined as
    | undefined
    | { accessToken: string; refreshToken: string },
  locked: false,
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: typeof harness.options) => {
    harness.options = options;
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h('div', slots.default?.()),
      }),
      {
        getData: () => harness.data,
        lock: () => {
          harness.locked = true;
        },
        unlock: () => {
          harness.locked = false;
        },
        setState: (state: object) => Object.assign(harness.state, state),
        close: () => options?.onOpenChange(false),
      },
    ];
  },
}));
vi.mock('@vben/stores', () => {
  harness.access = reactive({ accessToken: 'session-1', refreshToken: '' });
  return { useAccessStore: () => harness.access };
});
vi.mock('#/api/governance/metaobject', () => ({
  getMetaColumnDetail: vi.fn(),
  updateMetaColumnBiz: vi.fn(),
}));
vi.mock('#/views/governance/components/published-standard-picker.vue', () => ({
  default: defineComponent({
    setup: (_, { expose }) => {
      expose({ close: () => {} });
      return () => h('div');
    },
  }),
}));
vi.mock('antdv-next', () => ({
  Input: defineComponent({
    props: ['value'],
    setup: (props) => () => h('input', { value: props.value }),
  }),
  TextArea: defineComponent({
    props: ['value'],
    setup: (props) => () => h('textarea', { value: props.value }),
  }),
  RadioGroup: defineComponent({ setup: () => () => h('div') }),
  Alert: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', [slots.default?.(), slots.action?.()]),
  }),
  Button: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('button', slots.default?.()),
  }),
  message: { success: vi.fn() },
}));
let app: App | undefined;
let host: HTMLDivElement;
const currentDetail = {
  id: '9007199254740993',
  revision: 9,
  businessName: '当前详情值',
  columnName: 'station_code',
  standardElementId: 123,
  standardElementName: '站点编码',
  standardElementCode: 'stationCode',
  safetyLevelId: '9007199254740995',
};
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}
beforeEach(() => {
  vi.resetAllMocks();
  harness.data = { id: '9007199254740993', businessName: '过期列表值' };
  harness.locked = false;
  Object.assign(harness.access!, {
    accessToken: 'session-1',
    refreshToken: '',
  });
  vi.mocked(getMetaColumnDetail).mockResolvedValue(currentDetail);
  vi.mocked(updateMetaColumnBiz).mockResolvedValue(true);
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(ColumnBizForm);
  app.mount(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('字段业务编辑读取与并发保护', () => {
  it('按大ID读取当前详情，回显关联标准，提交当前revision', async () => {
    await harness.options!.onOpenChange(true);
    await nextTick();
    expect(getMetaColumnDetail).toHaveBeenCalledExactlyOnceWith(
      '9007199254740993',
    );
    expect(host.textContent).toContain('站点编码（stationCode）');
    expect(
      [...host.querySelectorAll('input')].map((input) => input.value),
    ).toContain('当前详情值');
    expect(harness.state.confirmDisabled).toBe(false);
    await harness.options!.onConfirm();
    expect(updateMetaColumnBiz).toHaveBeenCalledWith(
      expect.objectContaining({
        id: '9007199254740993',
        revision: 9,
        businessName: '当前详情值',
        standardElementId: 123,
        safetyLevelId: '9007199254740995',
      }),
    );
    expect(harness.locked).toBe(false);
  });

  it('读取失败不能提交列表旧值，冲突后禁止盲目再次提交', async () => {
    vi.mocked(getMetaColumnDetail).mockRejectedValueOnce(new Error('读取失败'));
    await harness.options!.onOpenChange(true);
    await harness.options!.onConfirm();
    expect(updateMetaColumnBiz).not.toHaveBeenCalled();
    await nextTick();
    expect(host.textContent).not.toContain('字段读取失败');
    expect(harness.state.confirmDisabled).toBe(true);
    await harness.options!.onOpenChange(true);
    await expect.poll(() => harness.state.confirmDisabled).toBe(false);
    expect(getMetaColumnDetail).toHaveBeenLastCalledWith('9007199254740993');
    vi.mocked(updateMetaColumnBiz).mockRejectedValueOnce(new Error('冲突'));
    await harness.options!.onConfirm();
    expect(harness.state.confirmDisabled).toBe(true);
    await nextTick();
    expect(host.textContent).toContain('重新加载');
    expect(harness.locked).toBe(false);
    await harness.options!.onConfirm();
    expect(updateMetaColumnBiz).toHaveBeenCalledTimes(1);
  });

  it('旧对象响应不能覆盖新对象和新请求的loading', async () => {
    const old = deferred<typeof currentDetail>();
    const latest = deferred<typeof currentDetail>();
    vi.mocked(getMetaColumnDetail)
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(latest.promise);
    const first = harness.options!.onOpenChange(true);
    harness.data = { id: '9007199254740994', businessName: '另一个列表值' };
    const second = harness.options!.onOpenChange(true);
    old.resolve(currentDetail);
    await first;
    expect(harness.state.loading).toBe(true);
    latest.resolve({
      ...currentDetail,
      id: '9007199254740994',
      businessName: '新对象',
    });
    await second;
    await harness.options!.onConfirm();
    expect(updateMetaColumnBiz).toHaveBeenCalledWith(
      expect.objectContaining({
        id: '9007199254740994',
        businessName: '新对象',
      }),
    );
  });

  it.each(['close', 'session', 'unmount'] as const)(
    '%s后字段详情响应失效',
    async (action) => {
      const pending = deferred<typeof currentDetail>();
      vi.mocked(getMetaColumnDetail).mockReturnValueOnce(pending.promise);
      const opening = harness.options!.onOpenChange(true);
      if (action === 'close') await harness.options!.onOpenChange(false);
      if (action === 'session') {
        harness.access!.refreshToken = 'session-2';
        await nextTick();
      }
      if (action === 'unmount') {
        app!.unmount();
        app = undefined;
      }
      pending.resolve(currentDetail);
      await opening;
      await harness.options!.onConfirm();
      expect(updateMetaColumnBiz).not.toHaveBeenCalled();
      expect(host.textContent).not.toContain('站点编码');
    },
  );
});
