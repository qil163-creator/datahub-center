import type { App, ComponentPublicInstance, SetupContext } from 'vue';

import type { AssetMarketCategoryApi } from '#/api/asset/marketcategory';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import CategoryDetail from './detail.vue';
type Category = AssetMarketCategoryApi.MarketCategory;
const mock = vi.hoisted(() => ({
  access: {
    accessCodes: ['asset:market-category:query'],
    accessToken: 'a',
    refreshToken: '',
  },
  user: {
    userInfo: { userId: '1' } as null | { userId: string },
    userRoles: ['admin'],
  },
  data: { id: '9223372036854775807' as unknown },
  get: vi.fn(),
  list: vi.fn(),
  options: undefined as
    | undefined
    | { centered: boolean; onOpenChange: (open: boolean) => Promise<void> },
  setState: vi.fn(),
  close: vi.fn(),
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
vi.mock('@vben/utils', () => ({
  formatDateTime: (value: unknown) => String(value ?? '—'),
}));
vi.mock('#/api/asset/marketcategory', () => ({
  getMarketCategory: mock.get,
  getMarketCategoryList: mock.list,
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: NonNullable<typeof mock.options>) => {
    mock.options = options;
    return [
      (_: unknown, { slots, attrs }: SetupContext) =>
        h('section', attrs, slots.default?.()),
      {
        getData: () => mock.data,
        setState: mock.setState,
        close: () => {
          mock.close();
          return options.onOpenChange(false);
        },
      },
    ];
  },
}));
vi.mock('antdv-next', () => ({
  Alert: (props: { message: string }) => h('p', props.message),
}));
vi.mock('#/views/modeling/modelcommon/detail-field-form.vue', () => ({
  default: defineComponent({
    props: { fields: { type: Array, default: () => [] } },
    setup: (props) => () => h('section', JSON.stringify(props.fields)),
  }),
}));
interface State {
  detail?: Category;
  parentName: string;
  fields: { key: string; value: unknown }[];
}
let app: App | undefined;
let host: HTMLDivElement;
let state: State;
const row = (
  id = '9223372036854775807',
  parentId: Category['parentId'] = '9007199254740993',
): Category => ({
  id,
  parentId,
  name: '市场类目',
  description: '业务描述',
  status: 0,
  sort: 3,
  creator: '12',
  creatorName: '管理员',
  createTime: 1_792_000_000_000,
});
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
async function flush() {
  for (let index = 0; index < 8; index++) await Promise.resolve();
  await nextTick();
}
beforeEach(() => {
  vi.resetAllMocks();
  mock.access = reactive({
    accessCodes: ['asset:market-category:query'],
    accessToken: 'a',
    refreshToken: '',
  });
  mock.user = reactive({ userInfo: { userId: '1' }, userRoles: ['admin'] });
  mock.data = { id: '9223372036854775807' };
  mock.get.mockResolvedValue(row());
  mock.list.mockResolvedValue([
    { ...row('9007199254740993', 0), name: '完整父级' },
  ]);
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(CategoryDetail);
  const vm = app.mount(host) as ComponentPublicInstance;
  state = (vm.$ as unknown as { setupState: State }).setupState;
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});
describe('market category readonly detail', () => {
  it('loads authoritative detail and a complete parent list with exact long ids', async () => {
    expect(mock.options?.centered).toBe(true);
    await mock.options!.onOpenChange(true);
    expect(mock.get).toHaveBeenCalledWith('9223372036854775807');
    expect(mock.list).toHaveBeenCalledWith();
    expect(state.parentName).toBe('完整父级');
    expect(state.fields.map((field) => field.key)).toEqual([
      'id',
      'name',
      'parentName',
      'status',
      'sort',
      'description',
      'creator',
      'createTime',
    ]);
    expect(
      state.fields.find((field) => field.key === 'description')?.value,
    ).toBe('业务描述');
    expect(
      host.querySelector('input:not([readonly]),textarea:not([readonly])'),
    ).toBeNull();
  });
  it('recognizes a top-level parent and disabled state', async () => {
    mock.get.mockResolvedValueOnce({
      ...row('9223372036854775807', 0),
      status: 1,
    });
    await mock.options!.onOpenChange(true);
    expect(state.parentName).toBe('顶级节点');
    expect(state.fields.find((field) => field.key === 'status')?.value).toBe(
      '停用',
    );
  });
  it('matches numeric and string parent ids without coercing large ids', async () => {
    mock.get.mockResolvedValueOnce(row('9223372036854775807', '12'));
    mock.list.mockResolvedValueOnce([
      { ...row('12', 0), id: 12, name: '数值父节点' },
    ]);
    await mock.options!.onOpenChange(true);
    expect(state.parentName).toBe('数值父节点');
  });
  it('falls back to the original parent id when the parent no longer exists', async () => {
    mock.list.mockResolvedValueOnce([]);
    await mock.options!.onOpenChange(true);
    expect(state.parentName).toBe('类目 9007199254740993');
  });
  it('does not query without category permission or a valid user', async () => {
    mock.access.accessCodes = ['asset:market:query'];
    await mock.options!.onOpenChange(true);
    expect(mock.get).not.toHaveBeenCalled();
    expect(mock.list).not.toHaveBeenCalled();
    mock.access.accessCodes = ['asset:market-category:query'];
    mock.user.userInfo = null;
    await mock.options!.onOpenChange(true);
    expect(mock.get).not.toHaveBeenCalled();
  });
  it('rejects unsafe numeric or ambiguous ids before reading', async () => {
    mock.data.id = Number.MAX_SAFE_INTEGER + 1;
    await mock.options!.onOpenChange(true);
    mock.data.id = ['1', '2'];
    await mock.options!.onOpenChange(true);
    expect(mock.get).not.toHaveBeenCalled();
  });
  it('rejects identity mismatch without displaying another category', async () => {
    mock.get.mockResolvedValueOnce(row('12'));
    await mock.options!.onOpenChange(true);
    await flush();
    expect(state.detail).toBeUndefined();
    expect(host.textContent).not.toContain('详情读取失败');
  });
  it.each(['resolve', 'reject'] as const)(
    'ignores an older detail %s after a new selection',
    async (outcome) => {
      const pending = deferred<Category>();
      mock.get.mockReturnValueOnce(pending.promise);
      const first = mock.options!.onOpenChange(true);
      mock.data.id = '12';
      mock.get.mockResolvedValueOnce({ ...row('12'), name: '当前类目' });
      await mock.options!.onOpenChange(true);
      if (outcome === 'resolve') pending.resolve({ ...row(), name: '旧类目' });
      else pending.reject(new Error('old'));
      await first;
      expect(state.detail?.name).toBe('当前类目');
    },
  );
  it('invalidates details synchronously on session changes and suppresses old responses', async () => {
    const pending = deferred<Category>();
    mock.get.mockReturnValueOnce(pending.promise);
    const first = mock.options!.onOpenChange(true);
    mock.access.accessToken = 'b';
    mock.access.accessToken = 'a';
    pending.resolve(row());
    await first;
    expect(state.detail).toBeUndefined();
    expect(state.parentName).toBe('');
    expect(mock.close).toHaveBeenCalled();
  });
  it('clears detail immediately on close and does not restore a pending response', async () => {
    const pending = deferred<Category>();
    mock.get.mockReturnValueOnce(pending.promise);
    const first = mock.options!.onOpenChange(true);
    await mock.options!.onOpenChange(false);
    pending.resolve(row());
    await first;
    expect(state.detail).toBeUndefined();
  });
  it('shows a recoverable read error and clears only its own loading', async () => {
    mock.get.mockRejectedValueOnce(new Error('failure'));
    await expect(mock.options!.onOpenChange(true)).resolves.toBeUndefined();
    expect(mock.setState).toHaveBeenLastCalledWith({ loading: false });
    expect(state.detail).toBeUndefined();
    await mock.options!.onOpenChange(true);
    expect(state.detail?.name).toBe('市场类目');
  });
  it('invalidates pending requests when unmounted', async () => {
    const pending = deferred<Category>();
    mock.get.mockReturnValueOnce(pending.promise);
    const first = mock.options!.onOpenChange(true);
    app!.unmount();
    app = undefined;
    pending.resolve(row());
    await first;
    expect(state.detail).toBeUndefined();
  });
});
