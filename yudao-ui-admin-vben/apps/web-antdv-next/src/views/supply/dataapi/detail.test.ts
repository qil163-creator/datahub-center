import type { App, PropType } from 'vue';
import type { Router } from 'vue-router';

import type { SupplyDataApiApi } from '#/api/supply/dataapi';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';
import { createMemoryHistory, createRouter, RouterView } from 'vue-router';

import { useAccessStore } from '@vben/stores';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getApiCategoryList } from '#/api/supply/apicategory';
import { getDataApi } from '#/api/supply/dataapi';

import DetailPage from './detail.vue';

const harness = vi.hoisted(() => ({
  closeCurrentTab: vi.fn(),
  state: undefined as
    | undefined
    | {
        accessCodes: string[];
        accessToken: string;
        refreshToken: string;
        userInfo: { userId: string };
      },
}));

vi.mock('@vben/stores', () => {
  harness.state = reactive({
    accessCodes: ['supply:data-api:query'],
    accessToken: '1',
    userInfo: { userId: '9' },
    refreshToken: '',
  });
  return {
    useAccessStore: () => harness.state,
    useUserStore: () => harness.state,
  };
});
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.state!.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', slots.default?.()),
  }),
}));
vi.mock('@vben/hooks', () => ({
  useTabs: () => ({ closeCurrentTab: harness.closeCurrentTab }),
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('#/components/list-pagination/index.vue', () => ({
  default: defineComponent({ name: 'ListPaginationStub', render: () => null }),
}));
vi.mock('#/api/supply/dataapi', () => ({ getDataApi: vi.fn() }));
vi.mock('#/api/supply/apicategory', () => ({ getApiCategoryList: vi.fn() }));
vi.mock('#/utils', () => ({ getRangePickerDefaultProps: () => ({}) }));
vi.mock('../utils/user', () => ({
  getUserNickname: (id?: string) => id ?? '-',
}));
vi.mock('antdv-next', () => {
  const Container = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Alert: defineComponent({
      props: { message: { type: String, default: '' } },
      setup:
        (props, { slots }) =>
        () =>
          h('section', { role: 'alert' }, [props.message, slots.action?.()]),
    }),
    Button: defineComponent({
      props: { disabled: Boolean, loading: Boolean },
      setup:
        (props, { slots }) =>
        () =>
          h('button', { disabled: props.disabled }, slots.default?.()),
    }),
    Descriptions: Container,
    DescriptionsItem: defineComponent({
      props: { label: { type: String, default: '' } },
      setup:
        (props, { slots }) =>
        () =>
          h('div', [props.label, '：', slots.default?.()]),
    }),
    Empty: Container,
    Pagination: Container,
    Spin: Container,
    Table: defineComponent({
      props: {
        columns: {
          type: Array as PropType<{ dataIndex?: string; key: string }[]>,
          default: () => [],
        },
        dataSource: {
          type: Array as PropType<Record<string, unknown>[]>,
          default: () => [],
        },
        rowKey: { type: String, default: 'id' },
      },
      setup:
        (props, { slots }) =>
        () =>
          h(
            'table',
            props.dataSource.map((record) =>
              h(
                'tr',
                { 'data-row-key': String(record[props.rowKey]) },
                props.columns.map((column) =>
                  h(
                    'td',
                    slots.bodyCell?.({
                      column,
                      record,
                      text: record[column.dataIndex ?? column.key],
                    }) ?? String(record[column.dataIndex ?? column.key] ?? ''),
                  ),
                ),
              ),
            ),
          ),
    }),
    TabPane: Container,
    Tabs: Container,
    Tag: Container,
  };
});

const initialId = '9007199254740993';
const nextId = '9007199254740995';
const details: SupplyDataApiApi.DataApi = {
  id: initialId,
  categoryId: '99',
  name: '测试 API 服务',
  version: 'v1',
  path: '/service/test',
  method: 'POST',
  resultFormat: 'LIST',
  status: 1,
  authType: 'APPKEY',
  configType: 'SQL',
  description: '接口服务描述',
  sqlText: 'select result_id from test_table',
  requestParams: [
    {
      name: 'request_id',
      paramType: 'STRING',
      nullable: 0,
      exampleValue: '9007199254740993',
      defaultValue: 'default-id',
      description: '请求编号说明',
    },
  ],
  responseFields: [
    {
      name: 'result_id',
      outputName: 'resultId',
      cnName: '结果编号',
      dataType: 'BIGINT',
      description: '返回编号说明',
      exampleValue: '9007199254740999',
      desensitize: 1,
      model: 'HIDE',
    },
  ],
  remark: '服务备注内容',
  creator: '9',
  createTime: '2026-10-14 10:00:00',
  updateTime: '2026-10-14 11:00:00',
};

let app: App | undefined;
let host: HTMLDivElement;
let router: Router;
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
async function flush() {
  for (let i = 0; i < 10; i++) await Promise.resolve();
  await nextTick();
}
async function open(id = initialId) {
  await router.push({ name: 'SupplyDataApiDetailPage', params: { id } });
  await flush();
}
async function back() {
  const button = [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === '返回',
  );
  expect(button).toBeDefined();
  button!.click();
  await expect
    .poll(() => router.currentRoute.value.path)
    .toBe('/supply/data-api');
  await flush();
}

beforeEach(async () => {
  useAccessStore();
  vi.resetAllMocks();
  Object.assign(harness.state!, {
    accessCodes: ['supply:data-api:query'],
    accessToken: '1',
    userInfo: { userId: '9' },
    refreshToken: '',
  });
  vi.mocked(getDataApi).mockImplementation(async (id) => ({ ...details, id }));
  vi.mocked(getApiCategoryList).mockResolvedValue([]);
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/supply/data-api', component: { render: () => null } },
      {
        path: '/supply/data-api/detail/:id',
        name: 'SupplyDataApiDetailPage',
        component: DetailPage,
      },
    ],
  });
  await router.push('/supply/data-api');
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(RouterView);
  app.use(router);
  app.mount(host);
  await flush();
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('数据API服务独立详情页面', () => {
  it('按无损路由编号读取并展示概要、请求参数、返回字段和详细信息', async () => {
    await open();
    expect(getDataApi).toHaveBeenCalledExactlyOnceWith(initialId);
    for (const value of [
      '测试 API 服务',
      '/service/test',
      '接口服务描述',
      'request_id',
      '请求编号说明',
      'default-id',
      'result_id',
      '结果编号',
      'BIGINT',
      '返回编号说明',
      '服务备注内容',
      '2026-10-14 10:00:00',
    ])
      expect(host.textContent).toContain(value);
    expect(getApiCategoryList).not.toHaveBeenCalled();
  });

  it.each(['01', '-1', '1e2', 'not-an-id'])(
    '无效路由身份 %s 不触发读取',
    async (id) => {
      await open(id);
      expect(getDataApi).not.toHaveBeenCalled();
      expect(getApiCategoryList).not.toHaveBeenCalled();
      expect(host.querySelector('[role="alert"]')).not.toBeNull();
    },
  );

  it.each([[], ['supply:data-api:update'], ['supply:data-api:test']])(
    '缺少query权限 %j 不触发读取',
    async (...accessCodes) => {
      harness.state!.accessCodes = accessCodes;
      await open();
      expect(getDataApi).not.toHaveBeenCalled();
      expect(getApiCategoryList).not.toHaveBeenCalled();
      expect(host.querySelector('[role="alert"]')).not.toBeNull();
    },
  );

  it('拒绝与路由身份不一致的详情响应', async () => {
    vi.mocked(getDataApi).mockResolvedValue({
      ...details,
      id: nextId,
      name: 'wrong-identity-detail',
    });
    await open();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain(
      '身份不匹配',
    );
    expect(host.textContent).not.toContain('wrong-identity-detail');
    expect(host.querySelectorAll('table')).toHaveLength(0);
  });

  it('类目名称请求失败仍显示API详情和可辨认的类目编号', async () => {
    harness.state!.accessCodes.push('supply:api-category:query');
    vi.mocked(getApiCategoryList).mockRejectedValue(
      new Error('private category failure'),
    );
    await open();
    expect(getApiCategoryList).toHaveBeenCalledExactlyOnceWith({});
    expect(host.textContent).toContain('测试 API 服务');
    expect(host.textContent).toContain('类目 99（名称暂不可用）');
    expect(host.textContent).not.toContain('private category failure');
    expect(host.querySelector('[role="alert"]')).toBeNull();
  });

  it('旧会话类目名称响应不能覆盖新会话的名称', async () => {
    harness.state!.accessCodes.push('supply:api-category:query');
    const old = deferred<Awaited<ReturnType<typeof getApiCategoryList>>>();
    vi.mocked(getApiCategoryList)
      .mockReturnValueOnce(old.promise)
      .mockResolvedValue([{ id: '99', name: 'fresh-category' }]);
    await open();
    expect(host.textContent).toContain('名称加载中');
    harness.state!.refreshToken = '2';
    await flush();
    expect(host.textContent).toContain('fresh-category');
    old.resolve([{ id: '99', name: 'late-old-category' }]);
    await flush();
    expect(host.textContent).toContain('fresh-category');
    expect(host.textContent).not.toContain('late-old-category');
    expect(getDataApi).toHaveBeenCalledTimes(2);
  });

  it('返回列表并关闭原详情标签', async () => {
    await open();
    await back();
    expect(harness.closeCurrentTab).toHaveBeenCalledWith(
      expect.objectContaining({
        name: 'SupplyDataApiDetailPage',
        params: { id: initialId },
      }),
    );
    expect(host.textContent).not.toContain('测试 API 服务');
  });

  it.each(['resolve', 'reject'] as const)(
    '路由身份变化后的旧详情 %s 不能覆盖新详情',
    async (outcome) => {
      const old = deferred<SupplyDataApiApi.DataApi>();
      const fresh = deferred<SupplyDataApiApi.DataApi>();
      vi.mocked(getDataApi)
        .mockReturnValueOnce(old.promise)
        .mockReturnValueOnce(fresh.promise);
      await open();
      await open(nextId);
      if (outcome === 'resolve')
        old.resolve({ ...details, name: 'late-old-detail' });
      else old.reject(new Error('late upstream error'));
      await flush();
      expect(host.textContent).not.toContain('late-old-detail');
      expect(host.textContent).not.toContain('late upstream error');
      expect(host.querySelector('[role="alert"]')).toBeNull();
      expect(host.querySelector('[role="status"]')).not.toBeNull();
      fresh.resolve({ ...details, id: nextId, name: 'fresh-detail' });
      await flush();
      expect(host.textContent).toContain('fresh-detail');
      expect(getDataApi).toHaveBeenNthCalledWith(2, nextId);
    },
  );

  it.each(
    (['session', 'permissions', 'back', 'unmount'] as const).flatMap(
      (change) => [
        { change, outcome: 'resolve' as const },
        { change, outcome: 'reject' as const },
      ],
    ),
  )(
    '$change 后旧详情请求 $outcome 不再写入页面',
    async ({ change, outcome }) => {
      const old = deferred<SupplyDataApiApi.DataApi>();
      vi.mocked(getDataApi).mockReturnValueOnce(old.promise);
      await open();
      if (change === 'session') harness.state!.refreshToken = '2';
      else if (change === 'permissions') harness.state!.accessCodes = [];
      else if (change === 'back') await back();
      else {
        app!.unmount();
        app = undefined;
      }
      await flush();
      if (outcome === 'resolve')
        old.resolve({ ...details, name: 'late-old-detail' });
      else old.reject(new Error('late upstream error'));
      await flush();
      expect(host.textContent).not.toContain('late-old-detail');
      expect(host.textContent).not.toContain('late upstream error');
      expect(host.textContent).not.toContain('API详情加载失败');
      expect(harness.closeCurrentTab).toHaveBeenCalledTimes(
        change === 'back' ? 1 : 0,
      );
    },
  );
});
