/* oxlint-disable no-template-curly-in-string -- JDBC字面模板用于验证原有连接配置 */
/* eslint-disable vue/one-component-per-file -- 隔离表单/诊断子组件验证真实编辑编排和凭据边界 */
import type { App, ComponentPublicInstance, SetupContext } from 'vue';

import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';

import {
  createApp,
  defineComponent,
  h,
  nextTick,
  onMounted,
  onUnmounted,
  reactive,
} from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import DatabaseForm from './form.vue';
interface Options {
  onOpenChange: (open: boolean) => void;
  onBeforeClose: () => boolean;
}
const mock = vi.hoisted(() => ({
  access: {
    accessCodes: ['dataforge:database:update', 'dataforge:database:query'],
    accessToken: 'a',
    refreshToken: '',
  },
  user: {
    userInfo: { userId: '1' } as null | { userId: string },
    userRoles: ['editor'],
  },
  options: undefined as Options | undefined,
  data: undefined as unknown,
  values: {} as Record<string, unknown>,
  valid: true,
  reading: vi.fn(),
  get: vi.fn(),
  type: vi.fn(),
  save: vi.fn(),
  lock: vi.fn(),
  unlock: vi.fn(),
  close: vi.fn(),
  success: vi.fn(),
  changeType: vi.fn(),
  toast: vi.fn(),
  review: undefined as
    | undefined
    | {
        connection: FoundationDatabaseApi.Database;
        testBlockedReason?: string;
      },
  reviewEmit: undefined as
    | ((event: 'testing', value: boolean) => void)
    | undefined,
  unmountReview: vi.fn(),
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
vi.mock('#/api/dataforge/database', () => ({
  getDatabase: mock.get,
  updateDatabase: mock.save,
}));
vi.mock('#/api/dataforge/databasetype', () => ({ getDatabaseType: mock.type }));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: Options) => {
    mock.options = options;
    return [
      (_: unknown, { slots }: SetupContext) =>
        h('section', [slots.default?.(), slots.footer?.()]),
      {
        getData: () => mock.data,
        lock: mock.lock,
        unlock: mock.unlock,
        close: async () => {
          if (!options.onBeforeClose()) return;
          mock.close();
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
  Alert: (props: { message?: string }, { slots }: SetupContext) =>
    h('aside', [props.message, slots.action?.()]),
  Spin: () => h('span', '读取中'),
  message: { success: mock.toast },
}));
vi.mock('./create-connection-details.vue', () => ({
  default: defineComponent({
    props: {
      draft: { type: Object, default: () => ({}) },
      selectedType: { type: Object, default: undefined },
      mode: { type: String, default: 'create' },
    },
    emits: ['changeType'],
    setup(props, { expose, emit }) {
      onMounted(() => {
        mock.values = { ...props.draft };
      });
      expose({
        getValues: async () => ({ ...mock.values }),
        readValues: async () => {
          await mock.reading();
          return mock.valid ? { ...mock.values } : undefined;
        },
      });
      return () =>
        h('div', { 'data-edit-fields': props.mode }, [
          String(props.draft.name ?? ''),
          h(
            'button',
            { 'data-change-type': true, onClick: () => emit('changeType') },
            '更换连接类型',
          ),
        ]);
    },
  }),
}));
vi.mock('./create-connection-review.vue', () => ({
  default: defineComponent({
    props: {
      connection: { type: Object, required: true },
      testBlockedReason: { type: String, default: undefined },
    },
    emits: ['testing'],
    setup(props, { emit }) {
      mock.review = props as unknown as NonNullable<typeof mock.review>;
      mock.reviewEmit = emit;
      onUnmounted(mock.unmountReview);
      return () =>
        h(
          'div',
          { 'data-review': true },
          props.testBlockedReason || '只读连接测试',
        );
    },
  }),
}));
interface State {
  step: number;
  draft: Partial<FoundationDatabaseApi.Database>;
  loading: boolean;
  loadError: string;
  saving: boolean;
  next: () => Promise<void>;
  previous: () => void;
  save: () => Promise<void>;
}
const connection = {
  id: '9223372036854775806',
  databaseType: 6,
  name: '业务连接',
  databaseIp: 'db.test',
  databasePort: '5432',
  databaseName: 'app',
  databaseSchema: 'public',
  userName: 'reader',
  jdbcUrl: 'jdbc:postgresql://db.test:5432/app?sslmode=require',
  extendConfig: 'applicationName=fixture',
  usageType: 2,
  orgId: '9007199254740993',
  remark: '业务备注',
  connectionStatus: 1,
  lastTestTime: 123,
  lastTestMsg: '此前已检测',
};
const postgres = {
  id: 6,
  typeName: 'PostgreSQL',
  productId: 'POSTGRESQL',
  typeCode: 'POSTGRESQL',
  portTemplates: '5432',
  urlTemplates: 'jdbc:postgresql://${host}:${port}/${dbName}',
} as FoundationDatabaseTypeApi.DatabaseType;
let app: App | undefined;
let host: HTMLDivElement;
let state: State;
async function flush() {
  for (let index = 0; index < 15; index++) await Promise.resolve();
  await nextTick();
}
function button(text: string) {
  return [...host.querySelectorAll<HTMLButtonElement>('button')].find(
    (item) => item.textContent?.trim() === text,
  )!;
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
async function open(data?: unknown) {
  mock.data = data ?? { id: connection.id };
  mock.options!.onOpenChange(true);
  await flush();
}
beforeEach(() => {
  vi.resetAllMocks();
  mock.access = reactive({
    accessCodes: ['dataforge:database:update', 'dataforge:database:query'],
    accessToken: 'a',
    refreshToken: '',
  });
  mock.user = reactive({ userInfo: { userId: '1' }, userRoles: ['editor'] });
  mock.values = {};
  mock.review = undefined;
  mock.valid = true;
  mock.get.mockResolvedValue({
    ...connection,
    password: 'server-only-password',
    secretKey: 'server-only-key',
  });
  mock.type.mockResolvedValue(postgres);
  mock.save.mockResolvedValue(true);
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(DatabaseForm, {
    onSuccess: mock.success,
    onChangeType: mock.changeType,
  });
  const vm = app.mount(host) as ComponentPublicInstance;
  state = (vm.$ as unknown as { setupState: State }).setupState;
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});
describe('connection edit wizard', () => {
  it('opens two steps with fresh configuration and never prepopulates saved secrets', async () => {
    await open();
    expect(
      host.querySelectorAll('ol[aria-label="修改数据连接步骤"] li'),
    ).toHaveLength(2);
    expect(mock.get).toHaveBeenCalledWith(connection.id);
    expect(mock.values).toMatchObject({
      id: connection.id,
      password: '',
      jdbcUrl: connection.jdbcUrl,
      orgId: connection.orgId,
      usageType: 2,
      remark: connection.remark,
    });
    expect(mock.values).not.toHaveProperty('secretKey');
    expect(host.textContent).not.toContain('server-only-password');
    expect(host.textContent).not.toContain('server-only-key');
    expect(state.step).toBe(0);
  });
  it('keeps invalid field input in the first step', async () => {
    await open();
    mock.valid = false;
    await state.next();
    expect(state.step).toBe(0);
    expect(mock.review).toBeUndefined();
    expect(mock.save).not.toHaveBeenCalled();
  });
  it('preserves the exact long id and custom URL for unchanged blank-password diagnosis', async () => {
    await open();
    await state.next();
    await flush();
    expect(mock.review?.connection).toMatchObject({
      id: connection.id,
      password: '',
      jdbcUrl: connection.jdbcUrl,
      extendConfig: connection.extendConfig,
    });
    expect(mock.review?.testBlockedReason).toBeUndefined();
    expect(mock.save).not.toHaveBeenCalled();
  });
  it('keeps edited business fields and new password while returning to the first step', async () => {
    await open();
    Object.assign(mock.values, {
      name: '修改名称',
      usageType: 1,
      orgId: '9223372036854775805',
      remark: '备注变更',
      password: 'new-fixture-password',
    });
    await state.next();
    await flush();
    state.previous();
    await flush();
    expect(mock.unmountReview).toHaveBeenCalledOnce();
    expect(mock.values).toMatchObject({
      name: '修改名称',
      usageType: 1,
      orgId: '9223372036854775805',
      remark: '备注变更',
      password: 'new-fixture-password',
    });
    await state.next();
    await flush();
    expect(mock.review?.connection.name).toBe('修改名称');
  });
  it('目标变化时测试和保存都要求新凭据，防止保存后借用旧凭据测试新目标', async () => {
    await open();
    mock.values.databaseIp = 'other.test';
    await state.next();
    await flush();
    expect(mock.review?.testBlockedReason).toContain('重新输入凭据');
    expect(button('保存').disabled).toBe(true);
    await state.save();
    expect(mock.save).not.toHaveBeenCalled();
  });
  it('服务端明确无已存凭据时，匿名连接可修改目标并保持匿名', async () => {
    mock.get.mockResolvedValue({ ...connection, hasPassword: false });
    await open();
    mock.values.databaseIp = 'anonymous.test';
    await state.next();
    await flush();
    expect(mock.review?.testBlockedReason).toBeUndefined();
    expect(button('保存').disabled).toBe(false);
    await state.save();
    expect(mock.save).toHaveBeenCalledOnce();
  });
  it('allows new credentials for modified targets and removes keys and derived test state on update', async () => {
    await open();
    Object.assign(mock.values, {
      databaseIp: 'other.test',
      password: 'new-fixture-password',
      secretKey: 'untrusted',
      connectionStatus: 2,
      lastTestTime: 999,
      lastTestMsg: 'not persisted',
    });
    await state.next();
    await flush();
    expect(mock.review?.testBlockedReason).toBeUndefined();
    expect(mock.review?.connection).not.toHaveProperty('secretKey');
    await state.save();
    const payload = mock.save.mock.calls[0]![0];
    expect(payload).toMatchObject({
      id: connection.id,
      password: 'new-fixture-password',
    });
    for (const field of [
      'secretKey',
      'connectionStatus',
      'lastTestTime',
      'lastTestMsg',
    ])
      expect(payload).not.toHaveProperty(field);
    expect(mock.success).toHaveBeenCalledOnce();
  });
  it('allows editing and diagnosing hdfs endpoints without a password field', async () => {
    mock.type.mockResolvedValue({
      ...postgres,
      typeCode: 'HDFS',
      typeName: 'HDFS',
    });
    await open();
    Object.assign(mock.values, {
      password: '',
      extendConfig: JSON.stringify({
        basePath: '/changed',
        webHdfsUrl: 'http://namenode:9870',
        allowedDataNodeAuthorities: ['datanode:9864'],
      }),
    });
    await state.next();
    await flush();
    expect(mock.review?.testBlockedReason).toBeUndefined();
    expect(mock.review?.connection.password).toBe('');
    await state.save();
    expect(mock.save).toHaveBeenCalledWith(
      expect.objectContaining({
        id: connection.id,
        password: '',
        extendConfig: mock.values.extendConfig,
      }),
    );
  });
  it('allows blank-password diagnostics for business-only name, usage, organization and remark changes', async () => {
    await open();
    Object.assign(mock.values, {
      name: '名称变更',
      usageType: 1,
      orgId: '19',
      remark: '仅业务信息',
    });
    await state.next();
    await flush();
    expect(mock.review?.testBlockedReason).toBeUndefined();
  });
  it('disables save during diagnosis and cancels the review when returning to edit', async () => {
    await open();
    await state.next();
    await flush();
    mock.reviewEmit!('testing', true);
    await flush();
    expect(button('保存').disabled).toBe(true);
    await state.save();
    expect(mock.save).not.toHaveBeenCalled();
    state.previous();
    await flush();
    expect(mock.unmountReview).toHaveBeenCalledOnce();
    await state.next();
    await flush();
    expect(button('保存').disabled).toBe(false);
  });
  it('deduplicates updates and locks closing until the current save completes', async () => {
    await open();
    await state.next();
    await flush();
    const pending = deferred<boolean>();
    mock.save.mockReturnValueOnce(pending.promise);
    const first = state.save();
    await state.save();
    expect(mock.save).toHaveBeenCalledTimes(1);
    expect(mock.options!.onBeforeClose()).toBe(false);
    expect(mock.lock).toHaveBeenCalled();
    pending.resolve(true);
    await first;
    expect(mock.success).toHaveBeenCalledOnce();
    expect(state.saving).toBe(false);
  });
  it('keeps the review and unlocks a failed update for retry', async () => {
    await open();
    await state.next();
    await flush();
    mock.save.mockRejectedValueOnce(new Error('save failed'));
    await expect(state.save()).resolves.toBeUndefined();
    expect(state.step).toBe(1);
    expect(state.saving).toBe(false);
    expect(mock.options!.onBeforeClose()).toBe(true);
    expect(mock.success).not.toHaveBeenCalled();
    await state.save();
    expect(mock.save).toHaveBeenCalledTimes(2);
  });
  it('does not permit a failed initial detail to proceed or submit', async () => {
    mock.get.mockRejectedValueOnce(new Error('load failed'));
    await open();
    expect(state.loadError).toContain('读取失败');
    expect(state.loading).toBe(false);
    expect(button('下一步').disabled).toBe(true);
    await state.next();
    await state.save();
    expect(mock.save).not.toHaveBeenCalled();
    expect(mock.options!.onBeforeClose()).toBe(true);
  });
  it('keeps the original type and custom URL when its catalog entry cannot load', async () => {
    mock.type.mockRejectedValueOnce(new Error('missing type'));
    await open();
    expect(state.loadError).toBe('');
    expect(mock.values.databaseType).toBe(6);
    expect(mock.values.jdbcUrl).toBe(connection.jdbcUrl);
    await state.next();
    await flush();
    expect(mock.review?.connection.databaseType).toBe(6);
    expect(mock.review?.connection.jdbcUrl).toBe(connection.jdbcUrl);
  });
  it('retains a draft when changing type and reopens with the chosen catalog definition', async () => {
    await open();
    Object.assign(mock.values, {
      name: '更换前草稿',
      password: 'new-fixture-password',
    });
    host.querySelector<HTMLButtonElement>('[data-change-type]')!.click();
    await flush();
    expect(mock.changeType).toHaveBeenCalledWith(
      expect.objectContaining({
        id: connection.id,
        name: '更换前草稿',
        password: 'new-fixture-password',
      }),
    );
    const draft = mock.changeType.mock
      .calls[0]![0] as FoundationDatabaseApi.Database;
    await open({
      draft,
      selectedType: {
        ...postgres,
        id: 7,
        typeName: 'Doris',
        typeCode: 'DORIS',
      },
    });
    expect(state.draft.name).toBe('更换前草稿');
    expect(mock.values.id).toBe(connection.id);
  });
  it('rejects a mismatched returned connection id instead of editing another row', async () => {
    mock.get.mockResolvedValueOnce({ ...connection, id: '19' });
    await open();
    expect(state.loadError).toContain('读取失败');
    await state.next();
    expect(mock.review).toBeUndefined();
  });
  it('ignores a late detail after canceling the wizard', async () => {
    const pending = deferred<FoundationDatabaseApi.Database>();
    mock.get.mockReturnValueOnce(pending.promise);
    await open();
    mock.options!.onOpenChange(false);
    pending.resolve(connection);
    await flush();
    expect(state.draft).toEqual({});
    expect(state.loading).toBe(false);
  });
  it.each(['accessToken', 'refreshToken'] as const)(
    'clears entered credentials after a %s change and suppresses stale save success',
    async (field) => {
      await open();
      mock.values.password = 'new-fixture-password';
      await state.next();
      await flush();
      const pending = deferred<boolean>();
      mock.save.mockReturnValueOnce(pending.promise);
      const first = state.save();
      mock.access[field] = 'other';
      await flush();
      expect(state.draft).toEqual({});
      pending.resolve(true);
      await first;
      expect(mock.success).not.toHaveBeenCalled();
      expect(mock.toast).not.toHaveBeenCalled();
    },
  );
  it('requires update permission and a valid user before loading or saving', async () => {
    mock.access.accessCodes = ['dataforge:database:query'];
    await open();
    expect(mock.get).not.toHaveBeenCalled();
    expect(button('下一步').disabled).toBe(true);
    await state.next();
    await state.save();
    expect(mock.save).not.toHaveBeenCalled();
  });
  it('invalidates a pending validation when the modal closes', async () => {
    await open();
    const pending = deferred<undefined>();
    mock.reading.mockReturnValueOnce(pending.promise);
    const request = state.next();
    mock.options!.onOpenChange(false);
    pending.resolve(undefined);
    await request;
    expect(state.step).toBe(0);
    expect(state.draft).toEqual({});
  });
});
