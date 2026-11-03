/* eslint-disable vue/one-component-per-file -- 页面、表单与共享布局桩用于同一组生命周期测试。 */
import type { App, SetupContext } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import Plan from './index.vue';

interface Action {
  label?: string;
  onClick?: () => void;
}

const harness = vi.hoisted(() => ({
  access: {} as { accessToken: string; refreshToken?: string },
  actions: [] as Action[],
  toolbarActions: [] as Action[],
  user: {} as { userInfo: { userId: string } },
  row: { id: '9007199254740993', name: '采集计划', status: 0 },
  open: vi.fn(),
  query: vi.fn(),
  getPlan: vi.fn(),
  getPlanPage: vi.fn(),
  getPlanCategoryList: vi.fn(),
  gridQuery: undefined as
    | ((page: { page: { currentPage: number; pageSize: number } }, values: object) => Promise<unknown>)
    | undefined,
  setValues: vi.fn(),
  errors: [] as unknown[],
}));

vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('@vben/common-ui', () => ({
  Page: (_: unknown, { slots }: SetupContext) => h('main', slots.default?.()),
  useVbenModal: () => [() => null, { setData: () => ({ open: harness.open }) }],
}));
vi.mock('antdv-next', () => ({
  Input: () => null,
  Modal: Object.assign(() => null, { confirm: vi.fn() }),
  Tag: () => null,
  Tooltip: () => null,
  message: { success: vi.fn() },
}));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('../data', () => ({
  getDefaultPlanFormValues: () => ({}),
  toPlanFormValues: (value: unknown) => value,
  toPlanSaveReq: (value: unknown) => value,
  usePlanFormSchema: () => [],
  usePlanGridColumns: () => [],
  usePlanGridFormSchema: () => [],
  validatePlanOrgs: () => undefined,
}));
vi.mock('#/views/dataforge/common/form-wizard', () => ({
  useDataforgeWizardForm: (options: { steps: unknown[] }) => ({
    Form: () => null,
    formApi: {
      reset: vi.fn(),
      setValues: harness.setValues,
      getValues: vi.fn(),
    },
    currentStep: ref(0),
    steps: options.steps,
    goToStep: vi.fn(),
    resetSteps: vi.fn(),
    validateAll: vi.fn(),
    busy: ref(false),
  }),
}));
vi.mock('#/views/dataforge/common/form-wizard-page.vue', () => ({
  default: (_: unknown, { slots }: SetupContext) =>
    h('section', slots.default?.()),
}));
vi.mock('./modules/plan-form.vue', () => ({
  default: defineComponent({
    props: { initialData: { type: Object, default: undefined } },
    emits: ['close', 'success'],
    setup:
      (props, { emit }) =>
      () =>
        h(
          'section',
          {
            'data-plan-form': 'true',
            'data-edit-id': props.initialData?.id ?? '',
            'data-category-id': props.initialData?.categoryId ?? '',
          },
          [
            h('button', { onClick: () => emit('close') }, '返回列表'),
            h('button', { onClick: () => emit('success') }, '保存'),
          ],
        ),
  }),
}));
vi.mock('./modules/category-form.vue', () => ({ default: {} }));
vi.mock('./modules/category-side-tree.vue', () => ({
  default: defineComponent({
    props: {
      items: { type: Array, default: () => [] },
      selected: { type: [Number, String], default: undefined },
    },
    emits: ['update:selected'],
    setup: (props, { emit }) => () =>
      h('aside', { 'data-category-tree': 'true' }, [
        h('span', { 'data-category-items': 'true' }, JSON.stringify(props.items)),
        h('button', { onClick: () => emit('update:selected', '9007199254740993') }, '选择分类'),
        h('button', { onClick: () => emit('update:selected', '0') }, '未分类'),
        h('button', { onClick: () => emit('update:selected', undefined) }, '全部计划'),
      ]),
  }),
}));
vi.mock('./modules/detail.vue', () => ({ default: {} }));
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  TableAction: Object.assign(
    (props: { actions?: Action[]; dropDownActions?: Action[] }) => {
      if (props.dropDownActions) {
        harness.actions = props.actions ?? [];
      } else {
        harness.toolbarActions = props.actions ?? [];
      }
      return h('div');
    },
    { props: { actions: Array, dropDownActions: Array } },
  ),
  useVbenVxeGrid: (options: {
    gridOptions: { proxyConfig: { ajax: { query: typeof harness.gridQuery } } };
  }) => {
    harness.gridQuery = options.gridOptions.proxyConfig.ajax.query;
    return [
    (_: unknown, { slots }: SetupContext) =>
      h('div', { 'data-grid': 'true' }, [
        slots['toolbar-tools']?.(),
        slots.actions?.({ row: harness.row }),
      ]),
    { query: harness.query },
    ];
  },
}));
vi.mock('#/api/dataforge/fill', () => ({
  createPlan: vi.fn(),
  deletePlan: vi.fn(),
  deletePlanCategory: vi.fn(),
  disablePlan: vi.fn(),
  generateTask: vi.fn(),
  getPlan: harness.getPlan,
  getPlanPage: harness.getPlanPage,
  getPlanCategoryList: harness.getPlanCategoryList,
  publishPlan: vi.fn(),
  updatePlan: vi.fn(),
}));

let app: App;
let host: HTMLDivElement;

function form() {
  return host.querySelector<HTMLElement>('[data-plan-form]');
}

function listContainer() {
  return host.querySelector<HTMLElement>('[data-grid]')!.parentElement!;
}

async function editPlan() {
  harness.actions.find((action) => action.label === 'common.edit')!.onClick!();
  await nextTick();
}

beforeEach(() => {
  vi.resetAllMocks();
  harness.actions = [];
  harness.toolbarActions = [];
  harness.errors = [];
  harness.getPlanCategoryList.mockResolvedValue([]);
  harness.access = reactive({ accessToken: '1', refreshToken: undefined });
  harness.user = reactive({ userInfo: { userId: '1' } });
  host = document.createElement('div');
  document.body.append(host);
});

afterEach(() => {
  app.unmount();
  host.remove();
  if (harness.errors.length > 0) {
    throw new AggregateError(harness.errors, '采集计划组件发生运行时错误');
  }
});

describe('采集计划页面式向导', () => {
  beforeEach(async () => {
    app = createApp(Plan);
    app.mount(host);
    await nextTick();
  });

  it('新建时挂载空表单，返回列表保留表格且不刷新', async () => {
    const grid = host.querySelector('[data-grid]');
    harness.toolbarActions[0]!.onClick!();
    await nextTick();

    expect(form()?.dataset.editId).toBe('');
    expect(listContainer().style.display).toBe('none');
    expect(harness.open).not.toHaveBeenCalled();

    form()!.querySelector<HTMLButtonElement>('button')!.click();
    await nextTick();

    expect(form()).toBeNull();
    expect(host.querySelector('[data-grid]')).toBe(grid);
    expect(listContainer().style.display).toBe('');
    expect(harness.query).not.toHaveBeenCalled();
  });

  it('编辑传递无损计划 ID，保存后返回列表并刷新', async () => {
    await editPlan();
    expect(form()?.dataset.editId).toBe(harness.row.id);

    form()!.querySelectorAll<HTMLButtonElement>('button')[1]!.click();
    await nextTick();

    expect(form()).toBeNull();
    expect(listContainer().style.display).toBe('');
    expect(harness.query).toHaveBeenCalledOnce();
  });

  it('选择分类以无损编号查询，新建计划默认归入当前分类', async () => {
    host.querySelector<HTMLButtonElement>('[data-category-tree] button')!.click();
    await nextTick();
    harness.getPlanPage.mockResolvedValue({ list: [], total: 0 });
    await harness.gridQuery!({ page: { currentPage: 1, pageSize: 20 } }, { name: '月报' });
    expect(harness.getPlanPage).toHaveBeenCalledWith({
      categoryId: '9007199254740993', name: '月报', pageNo: 1, pageSize: 20,
    });
    harness.toolbarActions[0]!.onClick!();
    await nextTick();
    expect(form()?.dataset.categoryId).toBe('9007199254740993');
  });

  it('未分类查询传 0，全部计划移除分类筛选', async () => {
    harness.getPlanPage.mockResolvedValue({ list: [], total: 0 });
    const buttons = host.querySelectorAll<HTMLButtonElement>('[data-category-tree] button');
    buttons[1]!.click();
    await nextTick();
    await harness.gridQuery!({ page: { currentPage: 1, pageSize: 20 } }, {});
    expect(harness.getPlanPage.mock.lastCall?.[0].categoryId).toBe('0');
    buttons[2]!.click();
    await nextTick();
    await harness.gridQuery!({ page: { currentPage: 1, pageSize: 20 } }, {});
    expect(harness.getPlanPage.mock.lastCall?.[0].categoryId).toBeUndefined();
  });

  it('切换分类后丢弃旧分类的列表响应', async () => {
    let resolve!: (value: unknown) => void;
    harness.getPlanPage.mockReturnValue(new Promise((done) => { resolve = done; }));
    const pending = harness.gridQuery!({ page: { currentPage: 1, pageSize: 20 } }, {});
    host.querySelector<HTMLButtonElement>('[data-category-tree] button')!.click();
    await nextTick();
    resolve({ list: [harness.row], total: 1 });
    expect(await pending).toEqual({ list: [], total: 0 });
  });

  it('切换登录会话后忽略旧会话分类树响应', async () => {
    let resolve!: (value: unknown) => void;
    harness.getPlanCategoryList.mockReturnValueOnce(new Promise((done) => { resolve = done; }));
    harness.access.refreshToken = '2';
    await nextTick();
    harness.getPlanCategoryList.mockResolvedValueOnce([{ id: '22', parentId: '0', name: '新会话分类', sort: 0 }]);
    harness.access.refreshToken = '3';
    await nextTick();
    await Promise.resolve();
    resolve([{ id: '11', parentId: '0', name: '旧会话分类', sort: 0 }]);
    await Promise.resolve();
    await nextTick();
    const items = host.querySelector('[data-category-items]')!.textContent;
    expect(items).toContain('新会话分类');
    expect(items).not.toContain('旧会话分类');
  });

  it.each(['session', 'refreshSession', 'user'] as const)(
    '%s 身份变化后关闭向导并清空编辑状态',
    async (changed) => {
      await editPlan();
      if (changed === 'session') harness.access.accessToken = '2';
      if (changed === 'refreshSession') harness.access.refreshToken = '2';
      if (changed === 'user') harness.user.userInfo.userId = '2';
      await nextTick();

      expect(form()).toBeNull();
      expect(harness.query).not.toHaveBeenCalled();
      harness.toolbarActions[0]!.onClick!();
      await nextTick();
      expect(form()?.dataset.editId).toBe('');
    },
  );
});

describe('采集计划编辑初始化失败', () => {
  async function mountForm(onClose: () => void) {
    const { default: PlanForm } = await vi.importActual<
      typeof import('./modules/plan-form.vue')
    >('./modules/plan-form.vue');
    app = createApp(PlanForm, {
      initialData: { id: harness.row.id },
      onClose,
    });
    app.config.errorHandler = (error) => harness.errors.push(error);
    app.mount(host);
    for (let i = 0; i < 8; i++) await Promise.resolve();
    await nextTick();
  }

  it('编辑详情失败后返回列表，不能解锁空表单继续编辑', async () => {
    harness.getPlan.mockRejectedValueOnce(new Error('详情加载失败'));
    const close = vi.fn();
    await mountForm(close);

    expect(harness.getPlan).toHaveBeenCalledWith(harness.row.id);
    expect(close).toHaveBeenCalledOnce();
    expect(harness.setValues).not.toHaveBeenCalled();
    expect(harness.errors).toEqual([]);
  });

  it('租户变化后忽略旧详情失败，不重复关闭新身份页面', async () => {
    let reject!: (reason: Error) => void;
    harness.getPlan.mockReturnValueOnce(
      new Promise<never>((_, fail) => {
        reject = fail;
      }),
    );
    const close = vi.fn();
    await mountForm(close);
    harness.access.refreshToken = '2';
    await nextTick();
    expect(close).toHaveBeenCalledOnce();

    reject(new Error('旧会话详情加载失败'));
    for (let i = 0; i < 8; i++) await Promise.resolve();
    await nextTick();

    expect(close).toHaveBeenCalledOnce();
    expect(harness.setValues).not.toHaveBeenCalled();
    expect(harness.errors).toEqual([]);
  });
});
