/* eslint-disable vue/one-component-per-file -- 隔离表格与控件，验证真实页面的查询、命令及会话边界。 */
import type { App } from 'vue';

import type { BatchFileApi as Api } from '#/api/dataforge/batchfile';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  executeBatchFile,
  getBatchFile,
  getBatchFilePage,
  getBatchFileSummary,
  updateBatchFileStatus,
} from '#/api/dataforge/batchfile';

import BatchFilePage from './index.vue';

interface Filters {
  taskName?: string;
  lastStatus?: Api.LastStatus;
}
interface GridState {
  columns: { field?: string; slots?: { default: string } }[];
  data: Api.Task[];
  loading?: boolean;
  pagerConfig: { currentPage: number; pageSize: number; total: number };
}
interface GridOptions {
  formOptions: {
    handleReset: () => Promise<unknown>;
    handleSubmit: (values: Filters) => Promise<unknown>;
  };
  gridOptions: GridState;
  gridEvents: {
    pageChange: (event: { currentPage: number; pageSize: number }) => void;
    sortChange: (event: { field: string; order: string }) => void;
  };
}
const harness = vi.hoisted(() => ({
  access: { accessToken: 'actor-a', refreshToken: '', accessCodes: ['all'] },
  confirm: undefined as
    | undefined
    | { onOk: () => Promise<unknown> | undefined },
  destroy: vi.fn(),
  push: vi.fn(),
}));
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: harness.push }),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => ({ userInfo: { userId: '1' }, userRoles: [] }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: () => harness.access.accessCodes.length > 0,
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
vi.mock('#/api/dataforge/batchfile', () => ({
  deleteBatchFile: vi.fn(),
  executeBatchFile: vi.fn(),
  getBatchFile: vi.fn(),
  getBatchFilePage: vi.fn(),
  getBatchFileSummary: vi.fn(),
  updateBatchFileStatus: vi.fn(),
}));
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: { ADD: 'lucide:plus' },
  useVbenVxeGrid: (options: GridOptions) => {
    const state = reactive({ ...options.gridOptions });
    const values = reactive<Filters>({});
    const formApi = {
      getValues: async () => ({ ...values }),
      reset: async () => {
        values.taskName = undefined;
        values.lastStatus = undefined;
      },
      validateAndSubmit: () => options.formOptions.handleSubmit({ ...values }),
    };
    const grid = defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('section', [
            slots.top?.(),
            h('input', {
              'aria-label': '任务名称',
              value: values.taskName ?? '',
              onInput: (event: Event) => {
                values.taskName = (event.target as HTMLInputElement).value;
              },
            }),
            h(
              'select',
              {
                'aria-label': '最近状态',
                value: values.lastStatus ?? '',
                onChange: (event: Event) => {
                  values.lastStatus = (event.target as HTMLSelectElement)
                    .value as Api.LastStatus;
                },
              },
              [h('option', { value: '' }, '全部'), h('option', 'FAILED')],
            ),
            ...Object.keys(slots)
              .filter((name) => name.startsWith('form-'))
              .map((name) => ({
                name: name.slice('form-'.length),
                render: slots[name],
              }))
              .filter(({ name }) =>
                ['reset-before', 'submit-before'].includes(name),
              )
              .map(({ render }) => render?.()),
            slots['toolbar-tools']?.(),
            h(
              'button',
              {
                onClick: () =>
                  options.gridEvents.pageChange({
                    currentPage: 3,
                    pageSize: 50,
                  }),
              },
              '第3页，每页50条',
            ),
            h(
              'button',
              {
                onClick: () =>
                  options.gridEvents.sortChange({
                    field: 'fileCount',
                    order: 'asc',
                  }),
              },
              '文件数升序',
            ),
            h('output', { 'aria-label': '总条数' }, state.pagerConfig.total),
            ...state.data.map((row) =>
              h(
                'article',
                { 'data-row-id': String(row.id), key: String(row.id) },
                state.columns.map((column) => {
                  const slot = column.slots?.default;
                  return slot
                    ? slots[slot]?.({ row })
                    : h(
                        'span',
                        String(row[column.field as keyof Api.Task] ?? ''),
                      );
                }),
              ),
            ),
            state.data.length === 0 ? slots.empty?.() : null,
          ]),
    });
    return [
      grid,
      {
        formApi,
        setGridOptions: (patch: Partial<GridState>) =>
          Object.assign(state, patch),
        grid: { sort: vi.fn(async () => undefined) },
        toggleSearchForm: vi.fn(),
      },
    ];
  },
}));
vi.mock('antdv-next', () => {
  const box = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  const button = defineComponent({
    setup:
      (_, { attrs, slots }) =>
      () =>
        h('button', attrs, slots.default?.()),
  });
  return {
    Alert: box,
    Button: button,
    Dropdown: defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('div', [slots.default?.(), slots.popupRender?.()]),
    }),
    Menu: box,
    MenuItem: button,
    Switch: defineComponent({
      props: { checked: Boolean, disabled: Boolean },
      emits: ['change'],
      setup:
        (props, { emit, attrs }) =>
        () =>
          h('button', {
            ...attrs,
            role: 'switch',
            disabled: props.disabled,
            'aria-checked': props.checked,
            onClick: () => emit('change', !props.checked),
          }),
    }),
    Tag: box,
    Tooltip: box,
    message: { success: vi.fn() },
    Modal: {
      confirm: vi.fn((config) => {
        harness.confirm = config;
        return { destroy: harness.destroy };
      }),
    },
  };
});

const task: Api.Task = {
  id: '9007199254740993',
  revision: '9007199254740995',
  taskName: '导入任务',
  responsibleUserId: '8',
  responsibleName: '负责人',
  responsiblePhone: '示例电话',
  description: '',
  remark: '',
  targetDatabaseId: '7',
  targetDatabaseName: '目标数据库',
  online: true,
  fileCount: 1,
  targetCount: 1,
  lastStatus: 'NOT_EXECUTED',
  createTime: 1,
  updateTime: 1,
  creator: '创建人',
};
let app: App | undefined;
let host: HTMLDivElement;
async function settle() {
  for (let index = 0; index < 12; index++) await nextTick();
}
async function mount() {
  app = createApp(BatchFilePage);
  app.mount(host);
  await settle();
}
function button(label: string) {
  const result = [...host.querySelectorAll('button')].find(
    (node) => node.textContent?.trim() === label,
  );
  if (!result) throw new Error(`缺少按钮 ${label}`);
  return result;
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.confirm = undefined;
  harness.access = reactive({
    accessToken: 'actor-a',
    refreshToken: '',
    accessCodes: ['all'],
  });
  vi.mocked(getBatchFilePage)
    .mockReset()
    .mockResolvedValue({
      list: [{ ...task }],
      total: 100,
    });
  vi.mocked(getBatchFileSummary).mockResolvedValue({
    taskCount: 100,
    successCount: 1,
    failedCount: 0,
    runCount: 1,
    statisticsTime: 1,
  });
  vi.mocked(getBatchFile).mockResolvedValue({
    ...task,
    revision: '9007199254740997',
    files: [],
    targets: [],
    successFiles: 0,
    failedFiles: 0,
  });
  vi.mocked(updateBatchFileStatus).mockResolvedValue(true);
  vi.mocked(executeBatchFile).mockReset().mockResolvedValue({
    id: '9007199254740999',
    status: 'QUEUED',
    replayed: false,
  });
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('文件入库列表交互', () => {
  it('表单提交应用筛选，分页及远端排序保留筛选，重置清空条件并回到首页', async () => {
    await mount();
    const name = host.querySelector<HTMLInputElement>(
      '[aria-label="任务名称"]',
    )!;
    const status = host.querySelector<HTMLSelectElement>(
      '[aria-label="最近状态"]',
    )!;
    name.value = '导入';
    name.dispatchEvent(new Event('input'));
    status.value = 'FAILED';
    status.dispatchEvent(new Event('change'));
    button('查询').click();
    await settle();
    expect(getBatchFilePage).toHaveBeenLastCalledWith({
      pageNo: 1,
      pageSize: 10,
      taskName: '导入',
      lastStatus: 'FAILED',
      sortField: 'id',
      sortOrder: 'desc',
    });
    button('第3页，每页50条').click();
    await settle();
    expect(getBatchFilePage).toHaveBeenLastCalledWith(
      expect.objectContaining({
        pageNo: 3,
        pageSize: 50,
        taskName: '导入',
        lastStatus: 'FAILED',
      }),
    );
    button('文件数升序').click();
    await settle();
    expect(getBatchFilePage).toHaveBeenLastCalledWith({
      pageNo: 1,
      pageSize: 50,
      taskName: '导入',
      lastStatus: 'FAILED',
      sortField: 'fileCount',
      sortOrder: 'asc',
    });
    button('重置').click();
    await settle();
    expect(getBatchFilePage).toHaveBeenLastCalledWith(
      expect.objectContaining({
        pageNo: 1,
        pageSize: 50,
        taskName: undefined,
        lastStatus: undefined,
      }),
    );
    expect(name.value).toBe('');
    expect(status.value).toBe('');
  });

  it('登录会话变化后旧列表响应不得覆盖新会话的数据和分页总数', async () => {
    let release!: (value: { list: Api.Task[]; total: number }) => void;
    vi.mocked(getBatchFilePage).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          release = resolve;
        }),
    );
    await mount();
    vi.mocked(getBatchFilePage).mockResolvedValue({
      list: [{ ...task, taskName: '新会话任务' }],
      total: 7,
    });
    harness.access.accessToken = 'actor-b';
    await settle();
    expect(host.textContent).toContain('新会话任务');
    release({ list: [{ ...task, taskName: '旧会话任务' }], total: 999 });
    await settle();
    expect(host.textContent).not.toContain('旧会话任务');
    expect(host.querySelector('[aria-label="总条数"]')!.textContent).toBe('7');
  });

  it('详情和运行实例导航保留超出安全整数范围的完整任务编号', async () => {
    await mount();
    button('详情').click();
    expect(harness.push).toHaveBeenLastCalledWith({
      name: 'DataforgeBatchFileDetail',
      params: { id: '9007199254740993' },
      query: {},
    });
    button('运行实例').click();
    expect(harness.push).toHaveBeenLastCalledWith({
      name: 'DataforgeBatchFileDetail',
      params: { id: '9007199254740993' },
      query: { tab: 'runs' },
    });
  });

  it('任务状态确认先读取详情，并携带最新无损 revision 执行 CAS', async () => {
    await mount();
    host.querySelector<HTMLButtonElement>('[role="switch"]')!.click();
    expect(getBatchFile).not.toHaveBeenCalled();
    expect(updateBatchFileStatus).not.toHaveBeenCalled();
    await harness.confirm!.onOk();
    await settle();
    expect(getBatchFile).toHaveBeenCalledWith('9007199254740993');
    expect(updateBatchFileStatus).toHaveBeenCalledWith({
      id: '9007199254740993',
      revision: '9007199254740997',
      online: false,
    });
  });

  it('执行必须确认，响应丢失后重试复用 requestId 和无损任务编号', async () => {
    await mount();
    vi.mocked(executeBatchFile).mockRejectedValueOnce(
      new Error('response lost'),
    );
    button('执行一次').click();
    expect(executeBatchFile).not.toHaveBeenCalled();
    await expect(harness.confirm!.onOk()).rejects.toThrow('response lost');
    await settle();
    button('执行一次').click();
    await harness.confirm!.onOk();
    const calls = vi.mocked(executeBatchFile).mock.calls;
    expect(calls).toHaveLength(2);
    expect(calls[0]![0]).toEqual(calls[1]![0]);
    expect(calls[0]![0].id).toBe('9007199254740993');
    expect(calls[0]![0].requestId).toMatch(/^[\da-f-]{36}$/u);
  });
});
