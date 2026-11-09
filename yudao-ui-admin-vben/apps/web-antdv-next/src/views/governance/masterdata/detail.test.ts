/* eslint-disable vue/one-component-per-file -- 页面壳测试使用隔离的布局和详情组件 */
import type { App, ShallowRef } from 'vue';

import {
  createApp,
  defineComponent,
  h,
  nextTick,
  reactive,
  shallowRef,
} from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import DetailPage from './detail.vue';

interface RouteState {
  fullPath: string;
  name: string;
  params: { id?: unknown };
  path: string;
}

const harness = vi.hoisted(() => ({
  closeCurrentTab: vi.fn(),
  currentRoute: undefined as ShallowRef<RouteState> | undefined,
  errors: [] as unknown[],
  push: vi.fn(),
  route: undefined as RouteState | undefined,
}));

vi.mock('vue-router', () => ({
  useRoute: () => harness.route,
  useRouter: () => ({
    currentRoute: harness.currentRoute,
    push: harness.push,
  }),
}));
vi.mock('@vben/hooks', () => ({
  useTabs: () => ({ closeCurrentTab: harness.closeCurrentTab }),
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', slots.default?.()),
  }),
}));
vi.mock('./modules/model-detail.vue', () => ({
  default: defineComponent({
    props: { modelId: { type: String, default: undefined } },
    emits: ['back'],
    setup:
      (props, { emit }) =>
      () =>
        h('section', { 'data-model-id': props.modelId }, [
          h('button', { onClick: () => emit('back') }, '返回'),
        ]),
  }),
}));

const initialId = '9007199254740995';
const listPath = '/govern/master/masterdata';
let app: App | undefined;
let host: HTMLDivElement;

function listRoute(): RouteState {
  return {
    fullPath: listPath,
    name: 'GovernanceMasterData',
    params: {},
    path: listPath,
  };
}

function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

async function flush() {
  for (let index = 0; index < 5; index++) await Promise.resolve();
  await nextTick();
}

beforeEach(() => {
  vi.resetAllMocks();
  harness.errors = [];
  harness.route = reactive({
    fullPath: `${listPath}/detail/${initialId}`,
    name: 'GovernanceMasterModelDetailPage',
    params: { id: initialId },
    path: `${listPath}/detail/${initialId}`,
  });
  harness.currentRoute = shallowRef(harness.route);
  harness.push.mockResolvedValue(undefined);
  harness.closeCurrentTab.mockResolvedValue(undefined);
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(DetailPage);
  app.config.errorHandler = (error) => harness.errors.push(error);
  app.mount(host);
});

afterEach(() => {
  app?.unmount();
  host.remove();
});

describe('主数据模型详情页面壳', () => {
  it('按路由变化向详情传递无损的十进制字符串编号', async () => {
    expect(
      host.querySelector<HTMLElement>('[data-model-id]')?.dataset.modelId,
    ).toBe(initialId);

    harness.route!.params.id = '9007199254740997';
    await nextTick();
    expect(
      host.querySelector<HTMLElement>('[data-model-id]')?.dataset.modelId,
    ).toBe('9007199254740997');

    harness.route!.params.id = 42;
    await nextTick();
    expect(
      host.querySelector<HTMLElement>('[data-model-id]')?.dataset.modelId,
    ).toBe('42');
    expect(harness.errors).toEqual([]);
  });

  it.each([
    undefined,
    '',
    '0',
    '-1',
    '1.5',
    'invalid',
    [initialId],
    0,
    -1,
    Number.MAX_SAFE_INTEGER + 1,
  ])('无效或已丢失精度的路由编号 %j 不传给详情', async (id) => {
    harness.route!.params.id = id;
    await nextTick();
    expect(host.querySelector('[data-model-id]')).toBeNull();
    expect(harness.errors).toEqual([]);
  });

  it('返回列表完成后才关闭捕获的原详情标签页', async () => {
    const navigation = deferred();
    const original = harness.currentRoute!.value;
    const events: string[] = [];
    harness.push.mockImplementation(async () => {
      events.push('push:start');
      await navigation.promise;
      harness.currentRoute!.value = listRoute();
      events.push('push:done');
    });
    harness.closeCurrentTab.mockImplementation(async () => {
      events.push('close');
    });

    host.querySelector('button')!.click();
    expect(harness.push).toHaveBeenCalledExactlyOnceWith(listPath);
    expect(harness.closeCurrentTab).not.toHaveBeenCalled();
    expect(events).toEqual(['push:start']);

    navigation.resolve();
    await flush();
    expect(events).toEqual(['push:start', 'push:done', 'close']);
    expect(harness.currentRoute!.value.path).toBe(listPath);
    expect(harness.closeCurrentTab).toHaveBeenCalledExactlyOnceWith(original);
    expect(harness.closeCurrentTab.mock.calls[0]![0]).toBe(original);
    expect(harness.closeCurrentTab.mock.calls[0]![0]).not.toBe(
      harness.currentRoute!.value,
    );
    expect(harness.errors).toEqual([]);
  });

  it('返回导航失败时保留原详情标签页', async () => {
    const original = harness.currentRoute!.value;
    const failure = new Error('导航失败');
    harness.push.mockRejectedValueOnce(failure);

    host.querySelector('button')!.click();
    await flush();

    expect(harness.push).toHaveBeenCalledExactlyOnceWith(listPath);
    expect(harness.closeCurrentTab).not.toHaveBeenCalled();
    expect(harness.currentRoute!.value).toBe(original);
    expect(harness.errors).toEqual([failure]);
  });
});
