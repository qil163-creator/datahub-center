/* eslint-disable vue/one-component-per-file -- 隔离展示控件，验证只读类目详情的权限与请求生命周期。 */
import type { App } from 'vue';

import type { ProjectCategoryApi as Api } from '#/api/dataforge/projectcategory';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createProjectCategory,
  getProjectCategory,
  getProjectCategoryList,
  updateProjectCategory,
} from '#/api/dataforge/projectcategory';

import CategoryDetail from './detail.vue';

const harness = vi.hoisted(() => ({
  access: {} as {
    accessCodes: string[];
    accessToken: string;
    loginExpired: boolean;
    refreshToken: string;
    userInfo?: { userId: string };
    userRoles: string[];
  },
  errors: [] as unknown[],
  formatDate: vi.fn((value: unknown) => `格式化时间:${value}`),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.access,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.access.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/utils', () => ({ formatDate: harness.formatDate }));
vi.mock('#/api/dataforge/projectcategory', () => ({
  createProjectCategory: vi.fn(),
  getProjectCategory: vi.fn(),
  getProjectCategoryList: vi.fn(),
  updateProjectCategory: vi.fn(),
}));
vi.mock('antdv-next', () => ({
  Modal: defineComponent({
    props: {
      open: Boolean,
      centered: Boolean,
      title: { type: String, default: '' },
      width: { type: String, default: '' },
    },
    emits: ['cancel'],
    setup:
      (props, { slots }) =>
      () =>
        props.open
          ? h(
              'section',
              {
                role: 'dialog',
                'data-centered': String(props.centered),
                'data-width': props.width,
              },
              [h('h2', props.title), slots.default?.(), slots.footer?.()],
            )
          : null,
  }),
  Alert: defineComponent({
    props: { message: { type: String, default: '' } },
    setup:
      (props, { slots }) =>
      () =>
        h('div', { role: 'alert' }, [props.message, slots.action?.()]),
  }),
  Button: defineComponent({
    setup:
      (_, { slots, attrs }) =>
      () =>
        h('button', attrs, slots.default?.()),
  }),
  Spin: defineComponent({
    props: { spinning: Boolean },
    setup:
      (props, { slots }) =>
      () =>
        h('div', { 'aria-busy': String(props.spinning) }, slots.default?.()),
  }),
  Descriptions: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('dl', slots.default?.()),
  }),
  DescriptionsItem: defineComponent({
    props: { label: { type: String, default: '' } },
    setup:
      (props, { slots }) =>
      () =>
        h('div', [h('dt', props.label), h('dd', slots.default?.())]),
  }),
  Tag: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('span', slots.default?.()),
  }),
}));

const id = '9007199254740993';
const parentId = '9007199254740995';
const detail: Api.Category = {
  id,
  parentId,
  name: '最新类目名称',
  status: 0,
  sort: 12,
  description: '完整只读说明\n保留第二行',
  childCount: '9007199254740997',
  taskCount: '9007199254740999',
  creator: '9007199254740991',
  creatorName: '创建人张三',
  createTime: 1_791_939_600_000,
  updateTime: 1_791_939_700_000,
};
let app: App | undefined;
let host: HTMLDivElement;
let instance: InstanceType<typeof CategoryDetail>;
let context: { kind: Api.Kind };
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
async function flush() {
  for (let index = 0; index < 8; index++) await Promise.resolve();
  await nextTick();
}
function mount(kind: Api.Kind = 'integration') {
  context = reactive({ kind });
  const component = ref<InstanceType<typeof CategoryDetail>>();
  app = createApp(
    defineComponent({
      setup: () => () =>
        h(CategoryDetail, { kind: context.kind, ref: component }),
    }),
  );
  app.config.errorHandler = (error) => harness.errors.push(error);
  app.mount(host);
  instance = component.value!;
}
function button(text: string) {
  return [...host.querySelectorAll<HTMLButtonElement>('button')].find(
    (node) => node.textContent?.trim() === text,
  )!;
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({
    accessCodes: [
      'dataforge:integration-category:query',
      'dataforge:dev-category:query',
      'governance:master-push-category:query',
    ],
    accessToken: 'session-a',
    loginExpired: false,
    refreshToken: 'refresh-a',
    userInfo: { userId: '7' },
    userRoles: ['reader'],
  });
  harness.errors = [];
  vi.mocked(getProjectCategory).mockReset().mockResolvedValue(detail);
  vi.mocked(getProjectCategoryList)
    .mockReset()
    .mockResolvedValue([
      { ...detail, id: parentId, parentId: '0', name: '最新上级类目' },
    ]);
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
  if (harness.errors.length > 0)
    throw new Error('类目详情未捕获异常', { cause: harness.errors[0] });
});

describe('三类项目类目的只读居中详情', () => {
  it.each([
    ['integration', '数据集成类目详情'],
    ['development', '数据开发类目详情'],
    ['masterPush', '主数据推送类目详情'],
  ] as const)(
    '%s 只需查询权限，读取最新详情和完整父级列表并保留原始值',
    async (kind, title) => {
      mount(kind);
      await instance.open({ id });
      await flush();
      expect(getProjectCategory).toHaveBeenCalledExactlyOnceWith(kind, id);
      expect(getProjectCategoryList).toHaveBeenCalledExactlyOnceWith(kind);
      const dialog = host.querySelector<HTMLElement>('[role="dialog"]')!;
      expect(dialog.dataset.centered).toBe('true');
      expect(dialog.dataset.width).toBe('min(800px, calc(100vw - 32px))');
      for (const value of [
        title,
        id,
        detail.name,
        '最新上级类目',
        '启用',
        '12',
        detail.description!,
        detail.childCount,
        detail.taskCount,
        detail.creatorName!,
        `格式化时间:${detail.createTime}`,
        `格式化时间:${detail.updateTime}`,
      ]) {
        expect(dialog.textContent).toContain(String(value));
      }
      expect(harness.formatDate).toHaveBeenCalledWith(
        detail.createTime,
        'YYYY-MM-DD HH:mm:ss',
      );
      expect(dialog.querySelector('input,select,textarea')).toBeNull();
      expect(
        [...dialog.querySelectorAll('button')].map((node) => node.textContent),
      ).toEqual(['关闭']);
      expect(createProjectCategory).not.toHaveBeenCalled();
      expect(updateProjectCategory).not.toHaveBeenCalled();
      button('关闭').click();
      await flush();
      expect(host.querySelector('[role="dialog"]')).toBeNull();
    },
  );

  it('后打开对象不会被旧响应覆盖，旧响应也不能提前结束新加载', async () => {
    const old = deferred<Api.Category>();
    const latest = deferred<Api.Category>();
    vi.mocked(getProjectCategory)
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(latest.promise);
    mount();
    const first = instance.open({ id });
    const second = instance.open({ id: parentId });
    old.resolve({ ...detail, name: '过期对象' });
    await first;
    await flush();
    expect(host.textContent).not.toContain('过期对象');
    expect(host.querySelector('[aria-busy="true"]')).not.toBeNull();
    latest.resolve({
      ...detail,
      id: parentId,
      parentId: '0',
      name: '当前对象',
    });
    await second;
    await flush();
    expect(host.textContent).toContain('当前对象');
    expect(host.textContent).toContain('顶级类目');
    expect(host.querySelector('[aria-busy="true"]')).toBeNull();
  });

  it.each([
    'close',
    'session',
    'kind',
    'user',
    'permission',
    'logout',
    'unmount',
  ] as const)('%s 后忽略未完成详情', async (change) => {
    const pending = deferred<Api.Category>();
    vi.mocked(getProjectCategory).mockReturnValueOnce(pending.promise);
    mount();
    const opening = instance.open({ id });
    if (change === 'close') instance.close();
    if (change === 'session') harness.access.refreshToken = 'refresh-b';
    if (change === 'kind') context.kind = 'development';
    if (change === 'user') harness.access.userInfo = { userId: '8' };
    if (change === 'permission') harness.access.accessCodes = [];
    if (change === 'logout') harness.access.accessToken = '';
    if (change === 'unmount') {
      app!.unmount();
      app = undefined;
    }
    await flush();
    pending.resolve(detail);
    await opening;
    await flush();
    expect(host.querySelector('[role="dialog"]')).toBeNull();
    expect(host.textContent).not.toContain(detail.name);
  });

  it('权限丢失清除已展示详情，无查询权限不能发起读取', async () => {
    mount();
    await instance.open({ id });
    await flush();
    expect(host.textContent).toContain(detail.name);
    harness.access.accessCodes = [];
    await flush();
    expect(host.querySelector('[role="dialog"]')).toBeNull();
    await instance.open({ id });
    expect(getProjectCategory).toHaveBeenCalledTimes(1);
    expect(getProjectCategoryList).toHaveBeenCalledTimes(1);
  });

  it('响应编号不一致时不显示错误对象，重试仍读取选中的原编号', async () => {
    vi.mocked(getProjectCategory).mockResolvedValueOnce({
      ...detail,
      id: parentId,
      name: '错误对象',
    });
    mount();
    await instance.open({ id });
    await flush();
    expect(host.textContent).not.toContain('错误对象');
    expect(host.querySelector('[role="alert"]')?.textContent).toContain(
      '详情加载失败',
    );
    button('重试').click();
    await flush();
    expect(getProjectCategory).toHaveBeenLastCalledWith('integration', id);
    expect(host.textContent).toContain(detail.name);
    expect(host.querySelector('[role="alert"]')).toBeNull();
  });

  it('父级已不可见时保留无损父编号，缺失审计值不伪造数据', async () => {
    vi.mocked(getProjectCategoryList).mockResolvedValueOnce([]);
    vi.mocked(getProjectCategory).mockResolvedValueOnce({
      ...detail,
      taskCount: null,
      creatorName: undefined,
      createTime: undefined,
      updateTime: undefined,
    });
    mount();
    await instance.open({ id });
    await flush();
    expect(host.textContent).toContain(`类目#${parentId}`);
    expect(host.textContent).toContain(detail.creator!);
    expect(harness.formatDate).not.toHaveBeenCalled();
  });

  it.each(['0', '-1', 'invalid', 9_007_199_254_740_996])(
    '非法编号 %s 不读取详情',
    async (invalidId) => {
      mount();
      await instance.open({ id: invalidId });
      expect(getProjectCategory).not.toHaveBeenCalled();
      expect(getProjectCategoryList).not.toHaveBeenCalled();
      expect(host.querySelector('[role="dialog"]')).toBeNull();
    },
  );
});
