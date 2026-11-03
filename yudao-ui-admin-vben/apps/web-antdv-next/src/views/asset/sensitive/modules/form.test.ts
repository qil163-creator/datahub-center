/* eslint-disable vue/one-component-per-file -- 测试桩集中验证表单的会话与级联边界 */
import type { App, ComponentPublicInstance } from 'vue';

import type { SensitiveListApi } from '#/api/asset/sensitive';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import Form from './form.vue';

const mocks = vi.hoisted(() => ({
  access: {
    accessToken: 'session',
    refreshToken: '',
    loginExpired: false,
    accessCodes: ['asset:sensitive-list:create', 'asset:sensitive-list:update'],
  },
  user: { userInfo: { userId: '1' } },
  categories: vi.fn(),
  assets: vi.fn(),
  columns: vi.fn(),
  get: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  success: vi.fn(),
  warning: vi.fn(),
  saved: vi.fn(),
  data: {} as { id?: string },
  options: undefined as
    | undefined
    | {
        onConfirm: () => Promise<void>;
        onOpenChange: (value: boolean) => Promise<void>;
      },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => mocks.access,
  useUserStore: () => mocks.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => mocks.access.accessCodes.includes(code)),
  }),
}));
vi.mock('#/api/asset/sensitive', () => ({
  getSensitiveListCategories: mocks.categories,
  getSensitiveAssetOptions: mocks.assets,
  getSensitiveColumnOptions: mocks.columns,
  getSensitiveList: mocks.get,
  createSensitiveList: mocks.create,
  updateSensitiveList: mocks.update,
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: NonNullable<typeof mocks.options>) => {
    mocks.options = options;
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h('div', slots.default?.()),
      }),
      {
        getData: () => mocks.data,
        lock: vi.fn(),
        unlock: vi.fn(),
        close: async () => options.onOpenChange(false),
        setState: ({ isOpen }: { isOpen: boolean }) =>
          options.onOpenChange(isOpen),
      },
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
    Alert: box,
    Button: box,
    ConfigProvider: box,
    Form: box,
    FormItem: box,
    Input: Object.assign(box, { TextArea: box }),
    Radio: box,
    RadioGroup: box,
    Select: box,
    TreeSelect: box,
    theme: { defaultAlgorithm: '' },
    message: { success: mocks.success, warning: mocks.warning },
  };
});
interface State {
  model: {
    assetId?: string;
    categoryKey?: string;
    columnId?: string;
    description: string;
    status: 0 | 1;
  };
  columns: SensitiveListApi.ColumnOption[];
  assets: SensitiveListApi.AssetOption[];
  ready: boolean;
  error: string;
  changeAsset: (id: string) => void;
  loadMore: (event: { target: EventTarget }) => void;
}
let app: App;
let host: HTMLDivElement;
let state: State;
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}
async function flush() {
  await Promise.resolve();
  await nextTick();
  await Promise.resolve();
}
beforeEach(() => {
  vi.resetAllMocks();
  mocks.access = reactive({
    accessToken: 'session',
    refreshToken: '',
    loginExpired: false,
    accessCodes: ['asset:sensitive-list:create', 'asset:sensitive-list:update'],
  });
  mocks.user = reactive({ userInfo: { userId: '1' } });
  mocks.data = {};
  mocks.categories.mockResolvedValue([
    {
      key: 'dir:24',
      type: 'DIRECTORY',
      name: '目录',
      status: 0,
      selectable: false,
    },
    {
      key: 'category:24',
      parentKey: 'dir:24',
      categoryId: '24',
      type: 'CATEGORY',
      name: '测站编码',
      status: 0,
      selectable: true,
    },
  ]);
  mocks.assets.mockResolvedValue({
    list: [{ id: '9223372036854775807', assetName: '真实资产' }],
    total: 1,
  });
  mocks.columns.mockResolvedValue([
    { id: '9007199254740993', columnName: 'STCD' },
  ]);
  mocks.get.mockResolvedValue({
    id: '9223372036854775806',
    revision: '9007199254740995',
    assetId: '9223372036854775807',
    columnId: '9007199254740993',
    categoryId: '24',
    status: 1,
    description: '清单描述',
  });
  host = document.createElement('div');
  document.body.append(host);
  const vm = (app = createApp(Form, { onSuccess: mocks.saved })).mount(
    host,
  ) as ComponentPublicInstance;
  state = (vm.$ as unknown as { setupState: State }).setupState;
});
afterEach(() => {
  app.unmount();
  host.remove();
});
describe('敏感清单表单', () => {
  it('目录不能提交，叶子ID与修订无损提交，新增默认禁用', async () => {
    await mocks.options!.onOpenChange(true);
    state.changeAsset('9223372036854775807');
    await flush();
    state.model.columnId = '9007199254740993';
    state.model.categoryKey = 'dir:24';
    await mocks.options!.onConfirm();
    expect(mocks.create).not.toHaveBeenCalled();
    state.model.categoryKey = 'category:24';
    await mocks.options!.onConfirm();
    expect(mocks.create).toHaveBeenCalledExactlyOnceWith({
      assetId: '9223372036854775807',
      columnId: '9007199254740993',
      categoryId: '24',
      status: 1,
      description: undefined,
      id: undefined,
      revision: undefined,
    });
  });
  it('修改重新读取并携带本地ID和CAS，允许同次保存启用', async () => {
    mocks.data = { id: '9223372036854775806' };
    mocks.access.accessCodes = ['asset:sensitive-list:update'];
    await mocks.options!.onOpenChange(true);
    state.model.status = 0;
    await mocks.options!.onConfirm();
    expect(mocks.get).toHaveBeenCalledExactlyOnceWith('9223372036854775806');
    expect(mocks.update).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({
        id: '9223372036854775806',
        revision: '9007199254740995',
        status: 0,
        description: '清单描述',
      }),
    );
    expect(mocks.assets).toHaveBeenCalledOnce();
  });
  it('切换资产清除字段，旧字段请求晚返回不能覆盖新资产', async () => {
    await mocks.options!.onOpenChange(true);
    const old = deferred<SensitiveListApi.ColumnOption[]>();
    const current = deferred<SensitiveListApi.ColumnOption[]>();
    mocks.columns
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(current.promise);
    state.model.columnId = '1';
    state.changeAsset('1');
    state.changeAsset('2');
    expect(state.model.columnId).toBeUndefined();
    current.resolve([{ id: '22', columnName: 'NEW' }]);
    await flush();
    old.resolve([{ id: '11', columnName: 'OLD' }]);
    await flush();
    expect(state.columns).toEqual([{ id: '22', columnName: 'NEW' }]);
  });
  it('资产候选支持下一页，追加失败后可以重试同一页', async () => {
    mocks.assets
      .mockResolvedValueOnce({ list: [{ id: '1' }], total: 3 })
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({ list: [{ id: '2' }, { id: '3' }], total: 3 });
    await mocks.options!.onOpenChange(true);
    const element = document.createElement('div');
    state.loadMore({ target: element });
    await flush();
    state.loadMore({ target: element });
    await flush();
    expect(mocks.assets.mock.calls.map((call) => call[0].pageNo)).toEqual([
      1, 2, 2,
    ]);
    expect(state.assets.map((item) => item.id)).toEqual(['1', '2', '3']);
  });
  it('确认防重复，会话过期使在途保存的成功失效', async () => {
    mocks.data = { id: '9223372036854775806' };
    await mocks.options!.onOpenChange(true);
    const saving = deferred<string>();
    mocks.update.mockReturnValue(saving.promise);
    const first = mocks.options!.onConfirm();
    await mocks.options!.onConfirm();
    expect(mocks.update).toHaveBeenCalledOnce();
    mocks.access.loginExpired = true;
    await nextTick();
    saving.resolve('1');
    await first;
    expect(mocks.saved).not.toHaveBeenCalled();
    expect(mocks.success).not.toHaveBeenCalled();
    expect(state.model.assetId).toBeUndefined();
  });
});
