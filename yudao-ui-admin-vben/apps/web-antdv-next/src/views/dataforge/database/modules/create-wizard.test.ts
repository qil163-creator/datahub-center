/* eslint-disable vue/one-component-per-file, vue/require-prop-types -- 隔离子组件以验证真实向导的状态与请求边界。 */
import type { App, SetupContext } from 'vue';

import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';

import {
  createApp,
  defineComponent,
  h,
  nextTick,
  onUnmounted,
  reactive,
} from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import CreateWizard from './create-wizard.vue';

const state = vi.hoisted(() => ({
  access: {} as {
    accessCodes: string[];
    accessToken: string;
    refreshToken: string;
  },
  user: {} as { userInfo: { userId: string }; userRoles: string[] },
  options: {} as {
    onBeforeClose: () => boolean;
    onOpenChange: (open: boolean) => void;
  },
  load: vi.fn(),
  create: vi.fn(),
  read: vi.fn(),
  get: vi.fn(),
  lock: vi.fn(),
  unlock: vi.fn(),
  close: vi.fn(),
  success: vi.fn(),
  toast: vi.fn(),
  details: [] as {
    draft?: Partial<FoundationDatabaseApi.Database>;
    selectedType: FoundationDatabaseTypeApi.DatabaseType;
  }[],
  reviews: [] as FoundationDatabaseApi.Database[],
  reviewUnmounted: vi.fn(),
  reviewEmit: undefined as
    | ((event: 'result' | 'testing', value: unknown) => void)
    | undefined,
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => state.access,
  useUserStore: () => state.user,
}));
vi.mock('@vben/constants', () => ({
  DICT_TYPE: { CATEGORY_CODE: 'category' },
}));
vi.mock('@vben/hooks', () => ({
  getDictOptions: () => [
    { value: 'RELATIONAL', label: '关系型数据库' },
    { value: 'ANALYTICAL', label: '分析型数据库/数据仓库' },
    { value: 'FILE_OBJECT_STORAGE', label: '文件/对象存储' },
    { value: 'NOSQL_CACHE', label: 'NoSQL/缓存' },
    { value: 'MESSAGE_QUEUE', label: '消息队列' },
    { value: 'TIME_SERIES', label: '时序数据库' },
    { value: 'SEMI_STRUCTURED', label: '半结构化存储' },
    { value: 'VECTOR', label: '向量型数据库' },
  ],
}));
vi.mock('#/api/dataforge/databasetype', () => ({
  getDatabaseTypeSimpleList: state.load,
}));
vi.mock('#/api/dataforge/database', () => ({ createDatabase: state.create }));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: typeof state.options) => {
    state.options = options;
    return [
      (_: unknown, { slots }: SetupContext) =>
        h('article', [slots.default?.(), slots.footer?.()]),
      {
        lock: state.lock,
        unlock: state.unlock,
        close: async () => {
          if (!options.onBeforeClose()) return;
          state.close();
          options.onOpenChange(false);
        },
      },
    ];
  },
}));
vi.mock('antdv-next', () => ({
  Button: (
    props: { disabled?: boolean; loading?: boolean },
    { attrs, slots }: SetupContext,
  ) =>
    h(
      'button',
      { ...attrs, disabled: props.disabled || props.loading },
      slots.default?.(),
    ),
  Input: (props: { value?: string }, { attrs }: SetupContext) =>
    h('input', {
      ...attrs,
      value: props.value,
      onInput: (event: Event) =>
        (attrs['onUpdate:value'] as (value: string) => void)(
          (event.target as HTMLInputElement).value,
        ),
    }),
  Alert: (props: { message?: string }, { slots }: SetupContext) =>
    h('aside', [props.message, slots.action?.()]),
  Spin: () => h('span', '正在加载'),
  message: { success: state.toast },
}));
vi.mock('./create-connection-details.vue', () => ({
  default: defineComponent({
    props: ['selectedType', 'draft'],
    setup(props, { expose }) {
      state.details.push({
        selectedType: props.selectedType,
        draft: props.draft,
      });
      expose({ readValues: () => state.read(), getValues: () => state.get() });
      return () =>
        h(
          'div',
          { 'data-details-id': String(props.selectedType.id) },
          '连接参数',
        );
    },
  }),
}));
vi.mock('./create-connection-review.vue', () => ({
  default: defineComponent({
    props: ['connection', 'selectedType'],
    emits: ['result', 'testing'],
    setup(props, { emit }) {
      state.reviews.push(props.connection);
      state.reviewEmit = emit;
      onUnmounted(state.reviewUnmounted);
      return () => h('div', '连接确认');
    },
  }),
}));

function type(
  id: number,
  overrides: Partial<FoundationDatabaseTypeApi.DatabaseType> = {},
): FoundationDatabaseTypeApi.DatabaseType {
  return {
    id,
    typeCode: 'ORACLE',
    productId: 'ORACLE',
    typeName: `Oracle ${id === 3 ? 'SID' : 'Service'}`,
    categoryCode: 'RELATIONAL',
    protocolType: 'JDBC',
    iconPath: '',
    urlTemplates: '',
    portTemplates: '1521',
    validationQuery: 'SELECT 1 FROM DUAL',
    remark: '',
    capabilities: { METADATA: { status: 'DECLARED', reason: '' } },
    ...overrides,
  };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
const values = {
  id: '9223372036854775806',
  databaseType: 3,
  userName: 'fixture-reader',
  password: 'fixture-only',
  databaseIp: 'db.test',
  databasePort: '1521',
  jdbcUrl: 'jdbc:fixture://db.test/app',
  secretKey: 'fixture-key',
  connectionStatus: 1,
  lastTestTime: 123,
  lastTestMsg: 'old',
} as FoundationDatabaseApi.Database;
let app: App | undefined;
let target: HTMLDivElement;
async function flush() {
  for (let index = 0; index < 8; index++) await Promise.resolve();
  await nextTick();
}
function button(text: string) {
  const result = [...target.querySelectorAll<HTMLButtonElement>('button')].find(
    (element) => element.textContent?.trim() === text,
  );
  expect(result).toBeDefined();
  return result!;
}
async function click(text: string) {
  button(text).click();
  await flush();
}
async function select(id: number) {
  target.querySelector<HTMLButtonElement>(`[data-type-id="${id}"]`)!.click();
  await flush();
}
async function open() {
  state.options.onOpenChange(true);
  await flush();
}
async function review() {
  await select(3);
  await click('下一步');
  await click('下一步');
}

beforeEach(async () => {
  vi.resetAllMocks();
  state.access = reactive({
    accessCodes: ['dataforge:database:create'],
    accessToken: 'a',
    refreshToken: '',
  });
  state.user = reactive({ userInfo: { userId: '1' }, userRoles: ['editor'] });
  state.details = [];
  state.reviews = [];
  state.reviewEmit = undefined;
  state.load.mockResolvedValue([
    type(3),
    type(4),
    type(21, {
      typeCode: 'MONGODB',
      productId: 'MONGODB',
      typeName: 'MongoDB',
      categoryCode: 'NOSQL_CACHE',
    }),
    type(22, {
      typeName: '待验证 DB2',
      capabilities: { METADATA: { status: 'UNVERIFIED', reason: '' } },
    }),
  ]);
  state.read.mockResolvedValue(values);
  state.get.mockResolvedValue(values);
  target = document.createElement('div');
  app = createApp({
    render: () => h(CreateWizard, { onSuccess: state.success }),
  });
  app.mount(target);
  await open();
});
afterEach(() => app?.unmount());

describe('新增连接三步编排', () => {
  it('展示全部及八类数量，Oracle 两个模板独立选择，缺少能力的卡片不可选', async () => {
    expect(
      target.querySelectorAll('nav[aria-label="连接分类"] button'),
    ).toHaveLength(9);
    const navigation = target.querySelector('nav[aria-label="连接分类"]')!;
    expect(navigation.textContent).toContain('分析性数据库');
    expect(navigation.textContent).not.toContain('分析型数据库/数据仓库');
    expect(target.textContent).toContain('共 4 种数据连接类型');
    expect(target.querySelectorAll('[data-type-id]')).toHaveLength(4);
    expect(button('下一步').disabled).toBe(true);
    expect(
      target.querySelector<HTMLButtonElement>('[data-type-id="22"]')!.disabled,
    ).toBe(true);
    await select(3);
    expect(
      target.querySelector('[data-type-id="3"]')!.getAttribute('aria-pressed'),
    ).toBe('true');
    await select(4);
    await click('下一步');
    expect(state.details.at(-1)?.selectedType.id).toBe(4);
    const panel = target.querySelector<HTMLElement>('[data-testid="connection-wizard-step-2"]');
    expect(panel).not.toBeNull();
    expect(panel!.style.display).not.toBe('none');
    expect(state.create).not.toHaveBeenCalled();
  });

  it('分类只滚动分组，重复点击全部清除全局搜索并恢复所有卡片', async () => {
    const scroll = vi.fn();
    const container = target.querySelector<HTMLElement>(
      '[aria-label="数据连接类型"]',
    )!;
    container.scrollTo = scroll;
    const navigation = target.querySelector('nav')!;
    const relational = [...navigation.querySelectorAll('button')].find(
      (element) => element.textContent?.includes('关系型数据库'),
    )!;
    relational.click();
    await flush();
    expect(scroll).toHaveBeenCalled();
    expect(target.querySelectorAll('[data-type-id]')).toHaveLength(4);
    const all = [...navigation.querySelectorAll('button')].find((element) =>
      element.textContent?.includes('全部'),
    )!;
    all.click();
    await flush();
    const search = target.querySelector<HTMLInputElement>(
      'input[aria-label="搜索全部连接类型"]',
    )!;
    search.value = 'MongoDB';
    search.dispatchEvent(new Event('input'));
    await flush();
    expect(target.querySelectorAll('[data-type-id]')).toHaveLength(1);
    all.click();
    await flush();
    expect(search.value).toBe('');
    expect(target.querySelectorAll('[data-type-id]')).toHaveLength(4);
  });

  it('无查询权限时可保存未检测连接，提交防重复且不写伪造的诊断状态', async () => {
    await review();
    const saved = deferred<string>();
    state.create.mockReturnValue(saved.promise);
    button('保存').click();
    button('保存').click();
    await flush();
    expect(state.create).toHaveBeenCalledOnce();
    const payload = state.create.mock.calls[0]![0];
    expect(payload).toMatchObject({
      databaseType: 3,
      password: 'fixture-only',
    });
    for (const field of [
      'id',
      'secretKey',
      'connectionStatus',
      'lastTestTime',
      'lastTestMsg',
    ])
      expect(payload).not.toHaveProperty(field);
    expect(button('上一步').disabled).toBe(true);
    expect(button('取消').disabled).toBe(true);
    expect(state.options.onBeforeClose()).toBe(false);
    saved.resolve('9223372036854775807');
    await flush();
    expect(state.success).toHaveBeenCalledOnce();
    expect(state.close).toHaveBeenCalledOnce();
  });

  it('校验失败留在第二步，诊断中禁保存但可返回，表单保留到再次选择模板', async () => {
    await select(3);
    await click('下一步');
    state.read.mockResolvedValueOnce(undefined);
    await click('下一步');
    expect(state.reviews).toHaveLength(0);
    await click('下一步');
    state.reviewEmit!('testing', true);
    await flush();
    expect(button('保存').disabled).toBe(true);
    await click('上一步');
    expect(state.reviewUnmounted).toHaveBeenCalledOnce();
    expect(state.details).toHaveLength(1);
    await click('上一步');
    await select(4);
    await click('下一步');
    expect(state.details).toHaveLength(2);
    expect(state.details.at(-1)?.draft).toMatchObject({ databaseType: 3 });
    expect(state.details.at(-1)?.selectedType.id).toBe(4);
  });

  it('关闭或身份切换使进行中的校验失效，重新打开不残留草稿', async () => {
    await select(3);
    await click('下一步');
    const pending = deferred<FoundationDatabaseApi.Database>();
    state.read.mockReturnValueOnce(pending.promise);
    button('下一步').click();
    state.access.accessToken = 'b';
    await flush();
    pending.resolve(values);
    await flush();
    expect(state.reviews).toHaveLength(0);
    expect(state.create).not.toHaveBeenCalled();
    await open();
    expect(button('下一步').disabled).toBe(true);
    expect(target.querySelector('[data-details-id]')).toBeNull();
  });

  it('卸载后不消费旧保存响应或发出成功事件', async () => {
    await review();
    const pending = deferred<string>();
    state.create.mockReturnValueOnce(pending.promise);
    button('保存').click();
    app!.unmount();
    app = undefined;
    pending.resolve('9007199254740993');
    await flush();
    expect(state.success).not.toHaveBeenCalled();
    expect(state.toast).not.toHaveBeenCalled();
  });
});
