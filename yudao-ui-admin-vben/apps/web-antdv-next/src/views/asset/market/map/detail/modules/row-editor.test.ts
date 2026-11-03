/* eslint-disable vue/one-component-per-file -- 测试桩集中验证行编辑的会话与并发边界 */
import type { App, ComponentPublicInstance } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import RowEditor from './row-editor.vue';

const mock = vi.hoisted(() => ({
  access: {
    accessToken: 'session',
    refreshToken: '',
    loginExpired: false,
    accessCodes: ['asset:data-asset:update'],
  },
  user: { userInfo: { userId: '1' } },
  update: vi.fn(),
  success: vi.fn(),
  saved: vi.fn(),
  options: undefined as
    | undefined
    | {
        onConfirm: () => Promise<void>;
        onOpenChange: (value: boolean) => void;
      },
  data: {} as Record<string, unknown>,
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => mock.access,
  useUserStore: () => mock.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => mock.access.accessCodes.includes(code)),
  }),
}));
vi.mock('#/api/asset/dataasset/imported', () => ({
  updateImportedAssetRow: mock.update,
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: NonNullable<typeof mock.options>) => {
    mock.options = options;
    const api = {
      getData: () => mock.data,
      lock: vi.fn(),
      unlock: vi.fn(),
      close: async () => options.onOpenChange(false),
      setState: ({ isOpen }: { isOpen: boolean }) =>
        options.onOpenChange(isOpen),
    };
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h('section', slots.default?.()),
      }),
      api,
    ];
  },
}));
vi.mock('antdv-next', () => {
  const box = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Form: box,
    FormItem: box,
    Input: box,
    message: { success: mock.success },
  };
});
interface State {
  values: Record<string, null | string>;
  columns: { columnName: string }[];
  target?: { assetId: string; revision: number; rowId: string };
}
let app: App | undefined;
let state: State;
let host: HTMLDivElement;
function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}
beforeEach(() => {
  vi.resetAllMocks();
  mock.access = reactive({
    accessToken: 'session',
    refreshToken: '',
    loginExpired: false,
    accessCodes: ['asset:data-asset:update'],
  });
  mock.user = reactive({ userInfo: { userId: '1' } });
  mock.data = {
    assetId: '9223372036854775807',
    rowId: '9007199254740993',
    revision: 7,
    values: {
      ID: '001',
      AMOUNT: '12345678901234567.000000000000001',
      NOTE: null,
    },
    columns: [
      { id: 1, columnName: 'ID', pkFlag: 1 },
      { id: 2, columnName: 'AMOUNT', dataType: 'DECIMAL' },
      { id: 3, columnName: 'NOTE' },
    ],
  };
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(RowEditor, { onSuccess: mock.saved });
  const vm = app.mount(host) as ComponentPublicInstance;
  state = (vm.$ as unknown as { setupState: State }).setupState;
  mock.options!.onOpenChange(true);
});
afterEach(() => {
  app?.unmount();
  host.remove();
});
describe('导入行编辑', () => {
  it('保留ID、CAS修订、十进制与NULL，主键不提交为可编辑字段', async () => {
    state.values.NOTE = '';
    await mock.options!.onConfirm();
    expect(mock.update).toHaveBeenCalledExactlyOnceWith({
      id: '9223372036854775807',
      rowId: '9007199254740993',
      revision: 7,
      values: { AMOUNT: '12345678901234567.000000000000001', NOTE: '' },
    });
    expect(mock.saved).toHaveBeenCalledOnce();
  });
  it('未改动的NULL保持NULL', async () => {
    await mock.options!.onConfirm();
    expect(mock.update.mock.calls[0]![0].values.NOTE).toBeNull();
  });
  it('重复确认仅发一个请求，关闭后晚到的成功不污染下一次弹窗', async () => {
    const waiting = deferred();
    mock.update.mockReturnValue(waiting.promise);
    const save = mock.options!.onConfirm();
    await mock.options!.onConfirm();
    expect(mock.update).toHaveBeenCalledOnce();
    mock.options!.onOpenChange(false);
    waiting.resolve();
    await save;
    expect(mock.saved).not.toHaveBeenCalled();
    expect(mock.success).not.toHaveBeenCalled();
  });
  it('同令牌会话过期或权限撤回立即清除可写目标', async () => {
    mock.access.loginExpired = true;
    await nextTick();
    expect(state.target).toBeUndefined();
    await mock.options!.onConfirm();
    expect(mock.update).not.toHaveBeenCalled();
    mock.access.loginExpired = false;
    mock.options!.onOpenChange(true);
    mock.access.accessCodes = [];
    await nextTick();
    await mock.options!.onConfirm();
    expect(mock.update).not.toHaveBeenCalled();
  });
});
