/* eslint-disable vue/one-component-per-file -- 使用隔离组件验证真实页面的请求生命周期。 */
import type { App, SetupContext } from 'vue';

import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import TaskDetail from './index.vue';

const harness = vi.hoisted(() => ({
  route: {
    name: 'DataforgeDevTaskDetail',
    params: { id: '9007199254740993' as unknown },
    query: { workloadMode: undefined as unknown },
    meta: { activePath: '' },
  },
  access: {
    accessToken: '1',
    refreshToken: undefined as string | undefined,
    codes: ['dataforge:dev-task:query'],
  },
  getTask: vi.fn(),
  getDirs: vi.fn(),
  getUsers: vi.fn(),
  getDatabases: vi.fn(),
  getTypes: vi.fn(),
  push: vi.fn(),
}));
vi.mock('vue-router', () => ({
  useRoute: () => harness.route,
  useRouter: () => ({ push: harness.push }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.access.codes.includes(code)),
  }),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access }));
vi.mock('@vben/common-ui', () => ({
  Page: (_: unknown, { slots }: SetupContext) => h('main', slots.default?.()),
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('#/api/dataforge/dev', () => ({
  getDevTask: harness.getTask,
  getDevDirList: harness.getDirs,
}));
vi.mock('#/api/system/user', () => ({ getSimpleUserList: harness.getUsers }));
vi.mock('#/api/dataforge/database', () => ({
  getDatabaseSimpleList: harness.getDatabases,
}));
vi.mock('#/api/dataforge/databasetype', () => ({
  getDatabaseTypeSimpleList: harness.getTypes,
}));
vi.mock('../modules/task-detail-view.vue', () => ({
  default: defineComponent({
    props: {
      task: Object,
      directoryName: String,
      ownerName: String,
      creatorName: String,
      databaseName: String,
      databaseIconPath: String,
      presentation: String,
    },
    setup:
      (props, { slots }) =>
      () =>
        h('section', { 'data-presentation': props.presentation }, [
          props.task?.name,
          props.directoryName,
          props.ownerName,
          props.creatorName,
          props.databaseName,
          props.databaseIconPath,
          slots.headingExtra?.(),
        ]),
  }),
}));
vi.mock('../workbench.vue', () => ({
  default: defineComponent({
    props: { taskId: String, workloadMode: String, readOnly: Boolean },
    emits: ['back'],
    setup:
      (props, { emit }) =>
      () =>
        h(
          'section',
          {
            'data-readonly-workbench': props.readOnly,
            'data-mode': props.workloadMode,
          },
          [props.taskId, h('button', { onClick: () => emit('back') }, '返回')],
        ),
  }),
}));
vi.mock('antdv-next', () => ({
  Alert: defineComponent({
    props: { message: String },
    setup:
      (props, { slots }) =>
      () =>
        h('aside', [props.message, slots.action?.()]),
  }),
  Empty: defineComponent({
    props: { description: String },
    setup: (props) => () => h('p', props.description),
  }),
  Skeleton: () => h('p', { 'data-loading': true }, 'loading'),
  Button: (_: unknown, { slots }: SetupContext) =>
    h('button', slots.default?.()),
}));

function task(id: string, name = '当前任务'): DataforgeDevApi.Task {
  return {
    id,
    name,
    dirId: 0,
    taskType: 'SQL',
    dbType: 'POSTGRESQL',
    ownerId: '7',
    creator: '7',
    databaseId: '9007199254740994',
  };
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
let app: App | undefined;
let host: HTMLDivElement;
async function flush() {
  for (let index = 0; index < 12; index++) await Promise.resolve();
  await nextTick();
}
async function mount() {
  host = document.createElement('div');
  app = createApp(TaskDetail);
  app.mount(host);
  await flush();
}
function clickButton(label: string) {
  const target = [...host.querySelectorAll('button')].find(
    (button) => button.textContent?.trim() === label,
  );
  expect(target).toBeDefined();
  target!.click();
}
beforeEach(() => {
  vi.resetAllMocks();
  harness.route = reactive({
    name: 'DataforgeDevTaskDetail',
    params: { id: '9007199254740993' },
    query: { workloadMode: undefined },
    meta: { activePath: '' },
  });
  harness.access = reactive({
    accessToken: '1',
    refreshToken: undefined as string | undefined,
    codes: ['dataforge:dev-task:query'],
  });
  harness.getTask.mockResolvedValue(task('9007199254740993'));
  harness.getDirs.mockResolvedValue([{ id: 101, name: '报表类目' }]);
  harness.getUsers.mockResolvedValue([{ id: 7, nickname: '数据管理员' }]);
  harness.getDatabases.mockResolvedValue([
    { id: '9007199254740994', name: '统计数据库' },
  ]);
  harness.getTypes.mockResolvedValue([
    {
      productId: 'POSTGRESQL',
      typeCode: 'POSTGRESQL',
      iconPath: '/test-product.svg',
    },
  ]);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
});

describe('数据开发独立详情页', () => {
  it.each([
    ['BATCH', '/develop/dev/offline'],
    ['STREAMING', '/develop/dev/realtime'],
  ])('%s 详情沿用只读编辑器并返回对应任务列表', async (mode, path) => {
    harness.route.query.workloadMode = mode;
    await mount();
    await flush();
    expect(
      host
        .querySelector<HTMLElement>('[data-readonly-workbench="true"]')
        ?.dataset.mode,
    ).toBe(mode);
    expect(harness.route.meta.activePath).toBe(path);
    clickButton('返回');
    expect(harness.push).toHaveBeenCalledWith(path);
  });
  it('无损加载编号并回显名称，返回数据开发列表', async () => {
    await mount();
    expect(harness.getTask).toHaveBeenCalledWith('9007199254740993');
    expect(host.textContent).toContain('数据开发类目');
    expect(host.textContent).toContain('数据管理员');
    expect(host.textContent).toContain('统计数据库');
    expect(host.textContent).toContain('/test-product.svg');
    expect(host.querySelector('[data-presentation="page"]')).not.toBeNull();
    clickButton('返回');
    expect(harness.push).toHaveBeenCalledWith('/develop/dev/workbench');
  });

  it('编号无效或无查询权限时不请求详情', async () => {
    harness.route.params.id = ['1', '2'];
    await mount();
    expect(host.textContent).toContain('无效的任务编号');
    expect(harness.getTask).not.toHaveBeenCalled();
    harness.access.codes = [];
    harness.route.params.id = '9';
    await flush();
    expect(host.textContent).toContain('暂无任务查询权限');
    expect(harness.getTask).not.toHaveBeenCalled();
  });

  it('旧路由和旧会话响应不能覆盖当前详情，离开详情页后使请求失效', async () => {
    const old = deferred<DataforgeDevApi.Task>();
    const current = deferred<DataforgeDevApi.Task>();
    harness.getTask
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(current.promise)
      .mockResolvedValue(task('9', '新会话任务'));
    await mount();
    harness.route.params.id = '9';
    await flush();
    old.resolve(task('9007199254740993', '旧任务'));
    await flush();
    expect(host.querySelector('[data-loading]')).not.toBeNull();
    expect(host.textContent).not.toContain('旧任务');
    current.resolve(task('9', '当前路由任务'));
    await flush();
    expect(host.textContent).toContain('当前路由任务');
    harness.access.refreshToken = '2';
    await flush();
    expect(host.textContent).toContain('新会话任务');
    const pending = deferred<DataforgeDevApi.Task>();
    harness.getTask.mockReturnValueOnce(pending.promise);
    harness.route.params.id = '10';
    await flush();
    harness.route.name = 'OtherDetail';
    await flush();
    const calls = harness.getTask.mock.calls.length;
    pending.resolve(task('10', '已离开的任务'));
    await flush();
    expect(host.textContent).not.toContain('已离开的任务');
    expect(harness.getTask).toHaveBeenCalledTimes(calls);
  });

  it('加载失败不显示横幅，切换任务后仍可呈现任务及原始编号', async () => {
    harness.getTask.mockRejectedValueOnce(new Error('详情失败'));
    await mount();
    expect(host.textContent).not.toContain('任务详情加载失败');
    expect(host.textContent).not.toContain('任务不存在或已删除');
    harness.getUsers.mockRejectedValueOnce(new Error('选项失败'));
    harness.route.params.id = '10';
    await flush();
    expect(host.textContent).toContain('当前任务');
    expect(host.textContent).not.toContain('部分名称加载失败');
    expect(host.querySelector('[data-presentation="page"]')).not.toBeNull();
    harness.getTask.mockResolvedValueOnce(null);
    harness.route.params.id = '11';
    await flush();
    expect(host.textContent).toContain('任务不存在或已删除');
  });

  it('卸载后完成的请求不能写入页面', async () => {
    const pending = deferred<DataforgeDevApi.Task>();
    harness.getTask.mockReturnValueOnce(pending.promise);
    await mount();
    app!.unmount();
    app = undefined;
    pending.resolve(task('1', '迟到任务'));
    await flush();
    expect(host.textContent).not.toContain('迟到任务');
  });
});
