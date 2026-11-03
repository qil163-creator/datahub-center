import type { App, SetupContext } from 'vue';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import CategoryForm from './form.vue';

interface Options {
  onConfirm: () => Promise<void>;
  onOpenChange: (open: boolean) => Promise<void>;
  onBeforeClose: () => boolean;
}
const mock = vi.hoisted(() => ({
  access: {
    accessToken: 'session-a',
    refreshToken: '',
    accessCodes: [
      'asset:market-category:query',
      'asset:market-category:create',
      'asset:market-category:update',
    ],
  },
  user: { userInfo: { userId: '1' }, userRoles: ['editor'] },
  options: undefined as Options | undefined,
  data: undefined as
    | undefined
    | { id?: number | string; parentId?: number | string },
  values: {} as Record<string, unknown>,
  valid: true,
  formOptions: undefined as
    | undefined
    | {
        commonConfig: { labelWidth: number };
        schema: unknown[];
        wrapperClass: string;
      },
  list: vi.fn(),
  get: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  success: vi.fn(),
  saved: vi.fn(),
  lock: vi.fn(),
  unlock: vi.fn(),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => mock.access,
  useUserStore: () => mock.user,
}));
vi.mock('@vben/hooks', () => ({ getDictOptions: () => [] }));
vi.mock('#/api/asset/marketcategory', () => ({
  getMarketCategoryList: mock.list,
  getMarketCategory: mock.get,
  createMarketCategory: mock.create,
  updateMarketCategory: mock.update,
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: Options) => {
    mock.options = options;
    return [
      (_: unknown, { slots, attrs }: SetupContext) =>
        h('section', attrs, slots.default?.()),
      {
        lock: mock.lock,
        unlock: mock.unlock,
        getData: () => mock.data,
        close: () => options.onOpenChange(false),
      },
    ];
  },
}));
vi.mock('antdv-next', () => ({
  Alert: () => null,
  message: { success: mock.success },
}));
vi.mock('../data', () => ({ useCategoryFormSchema: () => [] }));
vi.mock('#/adapter/form', () => ({
  useVbenForm: (options: NonNullable<typeof mock.formOptions>) => {
    mock.formOptions = options;
    return [
      () => null,
      {
        validate: async () => ({ valid: mock.valid }),
        getValues: async () => ({ ...mock.values }),
        setValues: async (values: Record<string, unknown>) => {
          mock.values = { ...values };
        },
        resetForm: async () => {
          mock.values = {};
        },
      },
    ];
  },
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => mock.access.accessCodes.includes(code)),
  }),
}));
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}
async function flush() {
  for (let index = 0; index < 8; index++) await Promise.resolve();
  await nextTick();
}
let app: App | undefined;
let host: HTMLDivElement;
beforeEach(() => {
  vi.clearAllMocks();
  mock.access = reactive({
    accessToken: 'session-a',
    refreshToken: '',
    accessCodes: [
      'asset:market-category:query',
      'asset:market-category:create',
      'asset:market-category:update',
    ],
  });
  mock.user = reactive({ userInfo: { userId: '1' }, userRoles: ['editor'] });
  mock.data = undefined;
  mock.values = {};
  mock.valid = true;
  mock.list.mockResolvedValue([]);
  mock.create.mockResolvedValue('9007199254740993');
  host = document.createElement('div');
  app = createApp(CategoryForm, { onSuccess: mock.saved });
  app.mount(host);
});
afterEach(() => {
  app?.unmount();
  host.remove();
});

describe('集市类目新增修改', () => {
  it('新建下级保持父节点长 ID，提交只发送领域白名单且阻止重复保存', async () => {
    mock.data = { parentId: '9007199254740993' };
    await mock.options!.onOpenChange(true);
    expect(mock.values.parentId).toBe('9007199254740993');
    mock.values = {
      ...mock.values,
      name: '  新类目  ',
      creator: '不应提交',
      code: '不应提交',
    };
    const pending = deferred<string>();
    mock.create.mockReturnValueOnce(pending.promise);
    const save = mock.options!.onConfirm();
    await flush();
    expect(mock.options!.onBeforeClose()).toBe(false);
    await mock.options!.onConfirm();
    expect(mock.create).toHaveBeenCalledExactlyOnceWith({
      id: undefined,
      parentId: '9007199254740993',
      name: '新类目',
      description: '',
      status: 0,
      sort: 0,
    });
    pending.resolve('9007199254740995');
    await save;
    expect(mock.saved).toHaveBeenCalledOnce();
    expect(mock.options!.onBeforeClose()).toBe(true);
  });

  it('修改重新读取详情，校验失败不发送更新，失败后释放提交锁', async () => {
    const detail = {
      id: '9007199254740993',
      parentId: 0,
      name: '最新详情',
      description: '原描述',
      status: 1,
      sort: 2,
    };
    mock.data = { id: detail.id };
    mock.get.mockResolvedValue(detail);
    await mock.options!.onOpenChange(true);
    expect(mock.get).toHaveBeenCalledWith(detail.id);
    expect(mock.values).toEqual(detail);
    mock.valid = false;
    await mock.options!.onConfirm();
    expect(mock.update).not.toHaveBeenCalled();
    mock.valid = true;
    mock.update.mockRejectedValueOnce(new Error('名称已存在'));
    await expect(mock.options!.onConfirm()).resolves.toBeUndefined();
    expect(mock.options!.onBeforeClose()).toBe(true);
    expect(mock.saved).not.toHaveBeenCalled();
  });

  it('关闭后到达的详情不能恢复旧草稿，新会话也不接收旧保存结果', async () => {
    const old = deferred<unknown>();
    mock.data = { id: 1 };
    mock.get.mockReturnValueOnce(old.promise);
    const opening = mock.options!.onOpenChange(true);
    await flush();
    await mock.options!.onOpenChange(false);
    old.resolve({ id: 1, parentId: 0, name: '旧内容', status: 0, sort: 0 });
    await opening;
    expect(mock.values).toEqual({});
    mock.data = undefined;
    await mock.options!.onOpenChange(true);
    mock.values.name = '新内容';
    const saved = deferred<string>();
    mock.create.mockReturnValueOnce(saved.promise);
    const saving = mock.options!.onConfirm();
    await flush();
    mock.access.accessToken = 'session-b';
    saved.resolve('3');
    await saving;
    expect(mock.saved).not.toHaveBeenCalled();
    expect(mock.success).not.toHaveBeenCalled();
  });
  it('uses the subject-domain two-column layout without adding unrelated fields', async () => {
    await mock.options!.onOpenChange(true);
    expect(mock.formOptions?.wrapperClass).toContain('sm:grid-cols-2');
    expect(mock.formOptions?.commonConfig.labelWidth).toBe(130);
  });
  it('does not save after operation permission is revoked', async () => {
    await mock.options!.onOpenChange(true);
    mock.values.name = '业务类目';
    mock.access.accessCodes = ['asset:market-category:query'];
    await mock.options!.onConfirm();
    expect(mock.create).not.toHaveBeenCalled();
    expect(mock.update).not.toHaveBeenCalled();
  });
  it('does not accept detail data with a different id and leaves the form unready', async () => {
    mock.data = { id: '9007199254740993' };
    mock.get.mockResolvedValueOnce({
      id: '19',
      parentId: 0,
      name: '其他类目',
      status: 0,
      sort: 0,
    });
    await mock.options!.onOpenChange(true);
    await mock.options!.onConfirm();
    expect(mock.update).not.toHaveBeenCalled();
  });
  it('absorbs a load error, unlocks closing and does not save an empty form', async () => {
    mock.list.mockRejectedValueOnce(new Error('list failure'));
    await expect(mock.options!.onOpenChange(true)).resolves.toBeUndefined();
    await mock.options!.onConfirm();
    expect(mock.create).not.toHaveBeenCalled();
    expect(mock.options!.onBeforeClose()).toBe(true);
  });
});
