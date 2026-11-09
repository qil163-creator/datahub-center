/* eslint-disable vue/one-component-per-file -- 详情回归使用多个隔离展示组件 */
import type { App } from 'vue';
import type { Router } from 'vue-router';

import type { SupplyApiLogApi } from '#/api/supply/apilog';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';
import { createMemoryHistory, createRouter, RouterView } from 'vue-router';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getApiLog } from '#/api/supply/apilog';

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
    accessCodes: ['supply:api-log:query'],
    accessToken: 'session-a',
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
vi.mock('#/api/supply/apilog', () => ({ getApiLog: vi.fn() }));
vi.mock('#/utils', () => ({ getRangePickerDefaultProps: () => ({}) }));
vi.mock('#/views/governance/common/user', () => ({
  getUserNickname: (id?: number | string) => id ?? '-',
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
          h('div', { 'data-field': props.label }, [
            props.label,
            '：',
            slots.default?.(),
          ]),
    }),
    Tag: Container,
  };
});

const initialId = '9007199254740993';
const nextId = '9007199254740995';
const detail: SupplyApiLogApi.ApiLog = {
  id: initialId,
  apiName: '测试调用服务',
  traceId: 'trace-log-001',
  rejectStage: 'EXECUTION',
  releaseId: '9007199254740999',
  callerIp: '192.0.2.10',
  callPath: '/services/v1.0.0/api/test-log',
  method: 'POST',
  paramsJson: '{"mode":"DRAFT_READONLY_PREVIEW","paramNames":["name"]}',
  callTime: '2026-11-03 13:35:56',
  durationMs: 872,
  dataCount: 5,
  status: 0,
  errorInfo: '调用异常测试信息',
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
  for (let index = 0; index < 10; index++) await Promise.resolve();
  await nextTick();
}

async function open(id = initialId) {
  await router.push({ name: 'SupplyApiLogDetailPage', params: { id } });
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
    .toBe('/supply/api-log');
  await flush();
}

beforeEach(async () => {
  vi.resetAllMocks();
  Object.assign(harness.state!, {
    accessCodes: ['supply:api-log:query'],
    accessToken: 'session-a',
    userInfo: { userId: '9' },
    refreshToken: '',
  });
  vi.mocked(getApiLog).mockImplementation(async (id) => ({ ...detail, id }));
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/supply/api-log', component: { render: () => null } },
      {
        path: '/supply/api-log/detail/:id',
        name: 'SupplyApiLogDetailPage',
        component: DetailPage,
      },
    ],
  });
  await router.push('/supply/api-log');
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

describe('调用记录独立详情页面', () => {
  it('按无损编号读取并保留日志字段、格式化JSON与耗时单位', async () => {
    await open();
    expect(getApiLog).toHaveBeenCalledExactlyOnceWith(initialId);
    expect(host.querySelector('h1')?.textContent).toContain('测试调用服务');
    for (const value of [
      initialId,
      'trace-log-001',
      'EXECUTION',
      '9007199254740999',
      '192.0.2.10',
      '/services/v1.0.0/api/test-log',
      'POST',
      '2026-11-03 13:35:56',
      '0.872',
      '调用异常测试信息',
      '失败',
    ])
      expect(host.textContent).toContain(value);
    expect(host.querySelector('pre')?.textContent).toBe(
      JSON.stringify(JSON.parse(detail.paramsJson!), null, 2),
    );
  });

  it('零耗时、零数据量与成功状态都按真实值显示', async () => {
    vi.mocked(getApiLog).mockResolvedValue({
      ...detail,
      durationMs: 0,
      dataCount: 0,
      status: 1,
      errorInfo: '',
    });
    await open();
    const fields = [...host.querySelectorAll<HTMLElement>('[data-field]')];
    expect(
      fields.find((item) => item.dataset.field?.includes('调用耗时'))
        ?.textContent,
    ).toMatch(/：\s*0\s*$/);
    expect(
      host.querySelector('[data-field="调用数据量"]')?.textContent,
    ).toMatch(/：\s*0\s*$/);
    expect(host.textContent).toContain('成功');
    expect(host.textContent).not.toContain('调用异常测试信息');
  });

  it.each(['01', '-1', '1e2', 'invalid-id'])(
    '无效路由编号 %s 不触发读取',
    async (id) => {
      await open(id);
      expect(getApiLog).not.toHaveBeenCalled();
      expect(host.querySelector('[role="alert"]')?.textContent).toContain(
        '无效的调用记录编号',
      );
    },
  );

  it.each([{ accessCodes: [] }, { accessCodes: ['supply:api-log:delete'] }])(
    '没有查询权限 $accessCodes 时阻止读取',
    async ({ accessCodes }) => {
      harness.state!.accessCodes = accessCodes;
      await open();
      expect(getApiLog).not.toHaveBeenCalled();
      expect(host.querySelector('[role="alert"]')?.textContent).toContain(
        '无调用记录查询权限',
      );
    },
  );

  it('拒绝与路由身份不一致的详情响应', async () => {
    vi.mocked(getApiLog).mockResolvedValue({
      ...detail,
      id: nextId,
      apiName: '错误身份的调用服务',
    });
    await open();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain(
      '调用记录身份不匹配，请返回列表后重新打开',
    );
    expect(host.textContent).not.toContain('错误身份的调用服务');
    expect(host.querySelector('[data-field]')).toBeNull();
  });

  it('请求失败显示可操作说明，保留返回入口', async () => {
    vi.mocked(getApiLog).mockRejectedValue(new Error('private upstream error'));
    await open();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain(
      '调用记录详情加载失败，请重试',
    );
    expect(host.textContent).not.toContain('private upstream error');
    await back();
    expect(harness.closeCurrentTab).toHaveBeenCalledOnce();
  });

  it('返回调用记录列表并关闭原详情标签', async () => {
    await open();
    await back();
    expect(harness.closeCurrentTab).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({
        name: 'SupplyApiLogDetailPage',
        params: { id: initialId },
      }),
    );
    expect(host.textContent).not.toContain('测试调用服务');
  });

  it.each(['resolve', 'reject'] as const)(
    '路由切换后旧请求 %s 不覆盖新记录或提前结束加载',
    async (outcome) => {
      const old = deferred<SupplyApiLogApi.ApiLog>();
      const fresh = deferred<SupplyApiLogApi.ApiLog>();
      vi.mocked(getApiLog)
        .mockReturnValueOnce(old.promise)
        .mockReturnValueOnce(fresh.promise);
      await open();
      await open(nextId);
      if (outcome === 'resolve')
        old.resolve({ ...detail, apiName: '迟到的旧调用记录' });
      else old.reject(new Error('旧请求失败'));
      await flush();
      expect(host.textContent).not.toContain('迟到的旧调用记录');
      expect(host.querySelector('[role="alert"]')).toBeNull();
      expect(host.querySelector('[role="status"]')).not.toBeNull();
      fresh.resolve({ ...detail, id: nextId, apiName: '新的调用记录' });
      await flush();
      expect(host.querySelector('h1')?.textContent).toContain('新的调用记录');
      expect(getApiLog).toHaveBeenNthCalledWith(2, nextId);
    },
  );

  it.each(
    (['session', 'user', 'permissions', 'back', 'unmount'] as const).flatMap(
      (change) => [
        { change, outcome: 'resolve' as const },
        { change, outcome: 'reject' as const },
      ],
    ),
  )('$change 后旧详情请求 $outcome 不回写页面', async ({ change, outcome }) => {
    const old = deferred<SupplyApiLogApi.ApiLog>();
    vi.mocked(getApiLog).mockReturnValueOnce(old.promise);
    await open();
    if (change === 'session') harness.state!.refreshToken = 'session-b';
    else if (change === 'user') harness.state!.userInfo = { userId: '10' };
    else if (change === 'permissions') harness.state!.accessCodes = [];
    else if (change === 'back') await back();
    else {
      app!.unmount();
      app = undefined;
    }
    await flush();
    if (outcome === 'resolve')
      old.resolve({ ...detail, apiName: '迟到的旧调用记录' });
    else old.reject(new Error('旧请求失败'));
    await flush();
    expect(host.textContent).not.toContain('迟到的旧调用记录');
    expect(host.textContent).not.toContain('调用记录详情加载失败');
    expect(harness.closeCurrentTab).toHaveBeenCalledTimes(
      change === 'back' ? 1 : 0,
    );
  });
});
