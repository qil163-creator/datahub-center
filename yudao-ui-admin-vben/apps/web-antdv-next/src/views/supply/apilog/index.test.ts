/* eslint-disable vue/one-component-per-file -- 页面回归使用多个隔离列表组件 */
import type { App } from 'vue';

import {
  createApp,
  defineComponent,
  h,
  nextTick,
  onBeforeUnmount,
  reactive,
} from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import Records from './index.vue';

const harness = vi.hoisted(() => ({
  access: undefined as
    | undefined
    | { accessCodes: string[]; accessToken: string; refreshToken: string },
  logsMounted: vi.fn(),
  logsUnmounted: vi.fn(),
  operationsMounted: vi.fn(),
  operationsUnmounted: vi.fn(),
}));

vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.access!.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/stores', () => {
  harness.access = reactive({
    accessCodes: [] as string[],
    accessToken: '1',
    refreshToken: '',
  });
  return { useAccessStore: () => harness.access };
});
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', slots.default?.()),
  }),
}));
vi.mock('./modules/call-log.vue', () => ({
  default: defineComponent({
    setup() {
      harness.logsMounted();
      onBeforeUnmount(harness.logsUnmounted);
      return () => h('div', { 'data-call-log': '' }, '日志列表');
    },
  }),
}));
vi.mock('./modules/write-operation.vue', () => ({
  default: defineComponent({
    setup() {
      harness.operationsMounted();
      onBeforeUnmount(harness.operationsUnmounted);
      return () => h('div', { 'data-write-operation': '' }, '写操作列表');
    },
  }),
}));

let app: App | undefined;
let host: HTMLDivElement;

async function mount(codes: string[]) {
  harness.access!.accessCodes = codes;
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Records);
  app.mount(host);
  await nextTick();
}

async function openOperations() {
  const tab = [...host.querySelectorAll<HTMLElement>('.ant-tabs-tab')].find(
    (item) => item.textContent?.includes('写操作台账'),
  );
  expect(tab).toBeDefined();
  tab!.click();
  await nextTick();
}

beforeEach(() => {
  vi.clearAllMocks();
  harness.access!.accessToken = '1';
  harness.access!.refreshToken = '';
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('调用记录页签与独立查询权限', () => {
  it('只有台账权限时默认进入写操作台账，不挂载调用日志', async () => {
    await mount(['supply:data-api:operation-query']);

    expect(host.textContent).toContain('写操作台账');
    expect(host.textContent).not.toContain('调用日志');
    expect(harness.logsMounted).not.toHaveBeenCalled();
    expect(harness.operationsMounted).toHaveBeenCalledOnce();
  });

  it('切换页签只挂载当前内容，离开的页签及时卸载', async () => {
    await mount(['supply:api-log:query', 'supply:data-api:operation-query']);

    expect(harness.logsMounted).toHaveBeenCalledOnce();
    expect(harness.operationsMounted).not.toHaveBeenCalled();
    await openOperations();
    expect(harness.logsUnmounted).toHaveBeenCalledOnce();
    expect(host.querySelector('[data-call-log]')).toBeNull();
    expect(harness.operationsMounted).toHaveBeenCalledOnce();
    expect(
      host.querySelectorAll(
        '.ant-tabs-body-holder > .ant-tabs-body > .ant-tabs-content',
      ),
    ).toHaveLength(1);
  });

  it('撤销当前页签的权限后回到可查询页签', async () => {
    await mount(['supply:api-log:query', 'supply:data-api:operation-query']);
    await openOperations();
    harness.access!.accessCodes = ['supply:api-log:query'];
    await nextTick();

    expect(host.querySelector('[data-write-operation]')).toBeNull();
    expect(harness.operationsUnmounted).toHaveBeenCalledOnce();
    expect(host.querySelector('[data-call-log]')).not.toBeNull();
  });

  it('没有查询权限时显示说明，两个列表都不挂载', async () => {
    await mount([]);

    expect(host.textContent).toContain('暂无调用记录查询权限');
    expect(harness.logsMounted).not.toHaveBeenCalled();
    expect(harness.operationsMounted).not.toHaveBeenCalled();
  });

  it('登录会话切换时重建日志列表，清除上一个租户的筛选及列表状态', async () => {
    await mount(['supply:api-log:query']);
    harness.access!.refreshToken = '2';
    await nextTick();

    expect(harness.logsUnmounted).toHaveBeenCalledOnce();
    expect(harness.logsMounted).toHaveBeenCalledTimes(2);
  });
});
