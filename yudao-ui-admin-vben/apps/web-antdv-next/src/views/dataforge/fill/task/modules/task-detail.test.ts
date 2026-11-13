import type { App, SetupContext } from 'vue';

import type { DataforgeFillApi } from '#/api/dataforge/fill';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/dataforge/fill';

import TaskDetail from './task-detail.vue';

const harness = vi.hoisted(() => ({
  access: {} as { accessToken: number; refreshToken?: number },
  close: vi.fn<() => Promise<void>>(),
  errors: [] as unknown[],
}));

vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => ({ userInfo: { userId: 99 } }),
}));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@vben/utils', () => ({
  formatDateTime: (value: unknown) => String(value ?? ''),
}));
vi.mock('@vben/common-ui', () => ({
  useVbenDrawer: (options: { onOpenChange?: (open: boolean) => void }) => {
    const opened = ref(false);
    harness.close = vi.fn(async () => {
      opened.value = false;
      await options.onOpenChange?.(false);
    });
    return [
      (_: unknown, { attrs, slots }: SetupContext) =>
        h(
          'section',
          { ...attrs, 'data-detail-open': String(opened.value) },
          slots.default?.(),
        ),
      {
        open: () => {
          if (!opened.value) {
            opened.value = true;
            options.onOpenChange?.(true);
          }
        },
        close: harness.close,
      },
    ];
  },
}));
vi.mock('#/components/description', () => ({
  useDescription: (options: {
    data: Record<string, unknown>;
    schema: {
      field: string;
      label: string;
      render?: (value: unknown) => unknown;
    }[];
  }) => {
    const data = ref(options.data);
    return [
      defineComponent({
        setup: () => () =>
          h(
            'dl',
            options.schema.map((field) => {
              const value = data.value[field.field];
              return h('div', { 'data-description-field': field.field }, [
                h('dt', field.label),
                h(
                  'dd',
                  String((field.render ? field.render(value) : value) ?? '-'),
                ),
              ]);
            }),
          ),
      }),
      {
        setDescProps: ({ data: next }: { data: Record<string, unknown> }) => {
          data.value = next;
        },
      },
    ];
  },
}));
vi.mock('antdv-next', () => {
  const slot =
    (tag: string) =>
    (_: unknown, { attrs, slots }: SetupContext) =>
      h(tag, attrs, slots.default?.());
  return {
    Button: slot('button'),
    DatePicker: slot('input'),
    Input: slot('input'),
    Select: slot('select'),
    Space: slot('div'),
    Tag: slot('span'),
    Modal: Object.assign(slot('div'), { confirm: vi.fn() }),
    Table: Object.assign(
      (props: {
        dataSource?: DataforgeFillApi.WorkItem[];
        loading?: boolean;
      }) =>
        h(
          'table',
          { 'aria-busy': String(Boolean(props.loading)) },
          (props.dataSource ?? []).map((item) =>
            h('tr', { key: item.id }, [h('td', item.orgName)]),
          ),
        ),
      { props: { dataSource: Array, loading: Boolean } },
    ),
    message: { success: vi.fn(), warning: vi.fn() },
  };
});
vi.mock('#/api/dataforge/fill', () => ({
  closeTask: vi.fn(),
  extendTaskDeadline: vi.fn(),
  getTask: vi.fn(),
  getTaskProgress: vi.fn(),
  getWorkItemListByTask: vi.fn(),
  switchTaskProcessDefinition: vi.fn(),
  updateWorkItemAssignees: vi.fn(),
  urgeTask: vi.fn(),
  urgeWorkItem: vi.fn(),
}));
vi.mock('#/api/system/user', () => ({
  getSimpleUserList: vi.fn(async () => []),
}));

let app: App | undefined;
let host: HTMLDivElement;
let detail: { open: (row: DataforgeFillApi.Task) => Promise<void> };

function task(id: number): DataforgeFillApi.Task {
  return {
    id,
    planId: 7,
    name: `任务 ${id}`,
    tableId: 8,
    tableVersionId: 9,
    periodKey: '2026-10',
    status: 1,
    totalOrgCount: 2,
    mergedOrgCount: 0,
  };
}

function progress(
  taskId: number,
  completionRate: number,
): DataforgeFillApi.TaskProgress {
  return {
    taskId,
    completionRate,
    totalOrgCount: 2,
    mergedCount: 1,
    overdueCount: 0,
    statusCounts: { 0: 1, 9: 1 },
  };
}

function items(taskId: number, orgName: string): DataforgeFillApi.WorkItem[] {
  return [{ id: taskId, taskId, orgName, status: 0 }];
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

function pendingRequest() {
  const pendingProgress = deferred<DataforgeFillApi.TaskProgress>();
  const pendingItems = deferred<DataforgeFillApi.WorkItem[]>();
  vi.mocked(api.getTaskProgress).mockReturnValueOnce(pendingProgress.promise);
  vi.mocked(api.getWorkItemListByTask).mockReturnValueOnce(
    pendingItems.promise,
  );
  return {
    resolve: (taskId: number, orgName: string, completionRate: number) => {
      pendingProgress.resolve(progress(taskId, completionRate));
      pendingItems.resolve(items(taskId, orgName));
    },
  };
}

async function flush() {
  for (let i = 0; i < 12; i++) await Promise.resolve();
  await nextTick();
}

function description(field: string) {
  return host.querySelector(`[data-description-field="${field}"] dd`)
    ?.textContent;
}

function orgNames() {
  return [...host.querySelectorAll('table td')].map((cell) => cell.textContent);
}

function refreshing() {
  return host.querySelector('table')?.getAttribute('aria-busy');
}

function buttonTexts() {
  return [...host.querySelectorAll('button')].map((element) =>
    element.textContent?.trim(),
  );
}

function refresh() {
  const button = [...host.querySelectorAll('button')].find(
    (element) => element.textContent?.trim() === '刷新',
  );
  expect(button).toBeDefined();
  button!.click();
}

beforeEach(() => {
  vi.resetAllMocks();
  harness.errors = [];
  harness.access = reactive({ accessToken: 1, refreshToken: undefined });
  vi.mocked(api.getTaskProgress).mockResolvedValue(progress(1, 50));
  vi.mocked(api.getWorkItemListByTask).mockResolvedValue(items(1, '初始组织'));
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(TaskDetail);
  app.directive('access', {});
  app.config.errorHandler = (error) => harness.errors.push(error);
  detail = app.mount(host) as unknown as typeof detail;
});

afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
  if (harness.errors.length > 0) {
    throw new AggregateError(harness.errors, '任务进度组件发生运行时错误');
  }
});

describe('采集任务进度异步加载', () => {
  it('切换任务后，旧任务后返回也不能覆盖最新任务', async () => {
    const firstRequest = pendingRequest();
    const secondRequest = pendingRequest();
    const firstOpen = detail.open(task(1));
    const secondOpen = detail.open(task(2));
    secondRequest.resolve(2, '任务二组织', 80);
    await secondOpen;
    await flush();
    expect(description('name')).toBe('任务 2');
    expect(description('completionRate')).toBe('80%');
    expect(orgNames()).toEqual(['任务二组织']);

    firstRequest.resolve(1, '过时组织', 10);
    await firstOpen;
    await flush();
    expect(description('name')).toBe('任务 2');
    expect(description('completionRate')).toBe('80%');
    expect(orgNames()).toEqual(['任务二组织']);
    expect(refreshing()).toBe('false');
  });

  it('加载另一任务时立即清空之前的进度和组织列表', async () => {
    await detail.open(task(1));
    await flush();
    expect(orgNames()).toEqual(['初始组织']);
    const pending = pendingRequest();
    const opening = detail.open(task(2));
    await flush();
    expect(description('name')).toBe('任务 2');
    expect(description('completionRate')).toBe('-');
    expect(orgNames()).toEqual([]);
    expect(refreshing()).toBe('true');
    pending.resolve(2, '最新组织', 80);
    await opening;
  });

  it('关闭抽屉后，尚未完成的请求不能恢复进度或组织列表', async () => {
    const pending = pendingRequest();
    const opening = detail.open(task(1));
    await harness.close();
    pending.resolve(1, '已关闭任务的组织', 90);
    await opening;
    await flush();
    expect(host.querySelector('section')?.dataset.detailOpen).toBe('false');
    expect(description('name')).toBe('-');
    expect(description('completionRate')).toBe('-');
    expect(orgNames()).toEqual([]);
    expect(refreshing()).toBe('false');
  });

  it('同一任务的旧刷新不能回填数据或提前结束新刷新的 loading', async () => {
    await detail.open(task(1));
    await flush();
    const older = pendingRequest();
    const newer = pendingRequest();
    refresh();
    refresh();
    await flush();
    older.resolve(1, '旧刷新组织', 20);
    await flush();
    expect(orgNames()).toEqual([]);
    expect(description('completionRate')).toBe('-');
    expect(refreshing()).toBe('true');

    newer.resolve(1, '新刷新组织', 90);
    await flush();
    expect(description('completionRate')).toBe('90%');
    expect(orgNames()).toEqual(['新刷新组织']);
    expect(refreshing()).toBe('false');
  });

  it('切换登录会话后关闭抽屉，并丢弃原租户的响应', async () => {
    const pending = pendingRequest();
    const opening = detail.open(task(1));
    harness.access.refreshToken = 2;
    await flush();
    expect(harness.close).toHaveBeenCalledOnce();
    pending.resolve(1, '原租户组织', 100);
    await opening;
    await flush();
    expect(description('name')).toBe('-');
    expect(description('completionRate')).toBe('-');
    expect(orgNames()).toEqual([]);
    expect(refreshing()).toBe('false');
  });

  it('只有待开始或进行中的任务显示延期和结束填报', async () => {
    await detail.open(task(1));
    await flush();
    expect(buttonTexts()).toEqual(expect.arrayContaining(['延期', '结束填报']));

    await detail.open({ ...task(1), status: 2 });
    await flush();
    expect(buttonTexts()).not.toContain('延期');
    expect(buttonTexts()).not.toContain('结束填报');
  });
});
