import type { App, ComponentPublicInstance, PropType } from 'vue';

import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import Detail from './detail.vue';

type DatabaseType = FoundationDatabaseTypeApi.DatabaseType;
interface ModalOptions {
  cancelText: string;
  centered: boolean;
  onOpenChange: (open: boolean) => Promise<void>;
  showConfirmButton: boolean;
}
interface State {
  detail?: DatabaseType;
  failed: boolean;
  fields: DetailFieldItem[];
}
const harness = vi.hoisted(() => ({
  access: {} as {
    accessCodes: string[];
    accessToken: string;
    refreshToken?: string;
  },
  data: {} as { id?: number | string },
  dictionaries: {} as Record<
    string,
    { label: string; value: number | string }[]
  >,
  get: vi.fn(),
  options: undefined as ModalOptions | undefined,
  state: {} as { loading?: boolean },
  close: vi.fn(),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
}));
vi.mock('@vben/hooks', () => ({
  getDictOptions: (type: string) => harness.dictionaries[type] ?? [],
}));
vi.mock('@vben/utils', () => ({
  formatDateTime: (value: unknown) => `时间 ${String(value ?? '-')}`,
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: ModalOptions) => {
    harness.options = options;
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h('div', { role: 'dialog' }, slots.default?.()),
      }),
      {
        getData: () => harness.data,
        close: () => {
          harness.close();
          return options.onOpenChange(false);
        },
        setState: (value: { loading?: boolean }) => {
          Object.assign(harness.state, value);
        },
      },
    ];
  },
}));
vi.mock('#/api/dataforge/databasetype', () => ({
  getDatabaseType: harness.get,
}));
vi.mock('#/components/connection-icon', () => ({
  ConnectionIcon: defineComponent({
    props: { iconPath: String, alt: String },
    setup: (props) => () => h('img', { src: props.iconPath, alt: props.alt }),
  }),
}));
vi.mock('#/views/modeling/modelcommon/detail-field-form.vue', () => ({
  default: defineComponent({
    props: {
      fields: { type: Array as PropType<DetailFieldItem[]>, default: () => [] },
    },
    setup:
      (props, { slots }) =>
      () =>
        h('section', [
          ...props.fields.map((field) =>
            h('p', { 'data-field': field.key }, String(field.value ?? '')),
          ),
          ...Object.values(slots).map((slot) => slot?.()),
        ]),
  }),
}));
vi.mock('antdv-next', () => ({
  Alert: defineComponent({
    props: { message: String },
    setup: (props) => () => h('p', { role: 'alert' }, props.message),
  }),
}));

let app: App | undefined;
let host: HTMLDivElement;
let state: State;
const connector: DatabaseType = {
  id: '9223372036854775807',
  typeCode: 'POSTGRESQL',
  typeName: 'PostgreSQL',
  description: '关系型数据库连接器',
  categoryCode: 'RELATIONAL',
  protocolType: 'JDBC',
  iconPath: '/connection-icons/postgresql.svg',
  // oxlint-disable-next-line no-template-curly-in-string -- JDBC 模板保留字面占位符。
  urlTemplates: 'jdbc:postgresql://${host}:${port}/${dbName}',
  portTemplates: '5432',
  validationQuery: 'SELECT 1',
  sort: 3,
  status: 0,
  remark: '用于数据开发',
  createTime: 1_792_000_000_000,
};
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
function fieldValue(key: string) {
  return state.fields.find((field) => field.key === key)?.value;
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({
    accessCodes: ['dataforge:database-type:query'],
    accessToken: 'session-a',
    refreshToken: 'refresh-a',
  });
  harness.data = { id: connector.id };
  harness.state = {};
  harness.dictionaries = reactive({
    category_code: [{ label: '关系型数据库', value: 'RELATIONAL' }],
    protocol_type: [{ label: 'JDBC 协议', value: 'JDBC' }],
    common_status: [{ label: '开启', value: 0 }],
  });
  harness.get.mockResolvedValue(connector);
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Detail);
  const vm = app.mount(host) as ComponentPublicInstance;
  state = (vm.$ as unknown as { setupState: State }).setupState;
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('连接器只读详情弹窗', () => {
  it('按无损编号重新读取完整详情，并展示字典、图标和创建时间', async () => {
    await harness.options!.onOpenChange(true);
    await nextTick();
    expect(harness.get).toHaveBeenCalledWith('9223372036854775807');
    expect(harness.options).toMatchObject({
      centered: true,
      showConfirmButton: false,
      cancelText: '关闭',
    });
    expect(state.fields.map((field) => field.key)).toEqual(
      expect.arrayContaining([
        'id',
        'typeCode',
        'typeName',
        'description',
        'categoryCode',
        'protocolType',
        'iconPath',
        'urlTemplates',
        'portTemplates',
        'validationQuery',
        'sort',
        'status',
        'remark',
        'createTime',
      ]),
    );
    expect(fieldValue('id')).toBe(connector.id);
    expect(fieldValue('categoryCode')).toBe('关系型数据库');
    expect(fieldValue('protocolType')).toBe('JDBC 协议');
    expect(fieldValue('status')).toBe('开启');
    expect(fieldValue('createTime')).toBe('时间 1792000000000');
    expect(host.textContent).toContain('关系型数据库连接器');
    expect(host.textContent).toContain('SELECT 1');
    expect(host.querySelector('img')?.getAttribute('src')).toBe(
      connector.iconPath,
    );
    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(harness.state.loading).toBe(false);
  });

  it('保留安全范围内的数值编号', async () => {
    harness.data = { id: 29 };
    harness.get.mockResolvedValueOnce({ ...connector, id: 29 });
    await harness.options!.onOpenChange(true);
    expect(harness.get).toHaveBeenCalledWith(29);
    expect(state.detail?.id).toBe(29);
  });

  it('字典延迟加载时先回显原值，并响应后续字典更新', async () => {
    harness.dictionaries = reactive({});
    await harness.options!.onOpenChange(true);
    expect(fieldValue('categoryCode')).toBe('RELATIONAL');
    expect(fieldValue('protocolType')).toBe('JDBC');
    expect(String(fieldValue('status'))).toBe('0');
    harness.dictionaries.category_code = [
      { label: '关系型数据库', value: 'RELATIONAL' },
    ];
    harness.dictionaries.protocol_type = [
      { label: 'JDBC 协议', value: 'JDBC' },
    ];
    harness.dictionaries.common_status = [{ label: '开启', value: 0 }];
    await nextTick();
    expect(fieldValue('categoryCode')).toBe('关系型数据库');
    expect(fieldValue('protocolType')).toBe('JDBC 协议');
    expect(fieldValue('status')).toBe('开启');
  });

  it.each(['close', 'session', 'permission', 'unmount'] as const)(
    '%s 后清除详情并忽略未完成的响应',
    async (change) => {
      await harness.options!.onOpenChange(true);
      const pending = deferred<DatabaseType>();
      harness.get.mockReturnValueOnce(pending.promise);
      const loading = harness.options!.onOpenChange(true);
      if (change === 'close') await harness.options!.onOpenChange(false);
      if (change === 'session') harness.access.refreshToken = 'refresh-b';
      if (change === 'permission') harness.access.accessCodes = [];
      if (change === 'unmount') {
        app!.unmount();
        app = undefined;
      }
      pending.resolve(connector);
      await loading;
      await nextTick();
      expect(state.detail).toBeUndefined();
      expect(state.failed).toBe(false);
      expect(host.textContent).not.toContain('关系型数据库连接器');
      expect(harness.close).toHaveBeenCalledTimes(
        change === 'session' || change === 'permission' ? 1 : 0,
      );
    },
  );

  it.each(['resolve', 'reject'] as const)(
    '旧请求 %s 不提前结束当前详情的加载状态',
    async (outcome) => {
      const old = deferred<DatabaseType>();
      const current = deferred<DatabaseType>();
      harness.get.mockReturnValueOnce(old.promise);
      const first = harness.options!.onOpenChange(true);
      harness.data = { id: '9007199254740993' };
      harness.get.mockReturnValueOnce(current.promise);
      const second = harness.options!.onOpenChange(true);
      if (outcome === 'resolve') old.resolve(connector);
      else old.reject(new Error('旧请求失败'));
      await first;
      expect(harness.state.loading).toBe(true);
      expect(state.detail).toBeUndefined();
      expect(state.failed).toBe(false);
      current.resolve({
        ...connector,
        id: '9007199254740993',
        typeName: '当前连接器',
      });
      await second;
      expect(state.detail?.typeName).toBe('当前连接器');
      expect(harness.state.loading).toBe(false);
    },
  );

  it.each(['resolve', 'reject'] as const)(
    '较晚结束的旧请求 %s 不覆盖新详情或显示旧错误',
    async (outcome) => {
      const old = deferred<DatabaseType>();
      harness.get.mockReturnValueOnce(old.promise);
      const first = harness.options!.onOpenChange(true);
      harness.data = { id: '9007199254740993' };
      harness.get.mockResolvedValueOnce({
        ...connector,
        id: '9007199254740993',
        typeName: '当前连接器',
      });
      await harness.options!.onOpenChange(true);
      if (outcome === 'resolve') old.resolve(connector);
      else old.reject(new Error('旧请求失败'));
      await first;
      await nextTick();
      expect(state.detail?.typeName).toBe('当前连接器');
      expect(state.failed).toBe(false);
      expect(host.querySelector('[role="alert"]')).toBeNull();
      expect(harness.state.loading).toBe(false);
    },
  );

  it.each(['reject', 'null'] as const)(
    '详情 %s 时不展示错误横幅，并允许重新打开读取',
    async (outcome) => {
      if (outcome === 'reject') {
        harness.get.mockRejectedValueOnce(new Error('读取失败'));
      } else {
        harness.get.mockResolvedValueOnce(null);
      }
      await expect(
        harness.options!.onOpenChange(true),
      ).resolves.toBeUndefined();
      await nextTick();
      expect(state.detail).toBeUndefined();
      expect(state.failed).toBe(true);
      expect(host.textContent).not.toContain('连接器详情加载失败');
      expect(harness.state.loading).toBe(false);
      await harness.options!.onOpenChange(true);
      await nextTick();
      expect(state.detail?.typeName).toBe('PostgreSQL');
      expect(state.failed).toBe(false);
      expect(host.querySelector('[role="alert"]')).toBeNull();
    },
  );
});
