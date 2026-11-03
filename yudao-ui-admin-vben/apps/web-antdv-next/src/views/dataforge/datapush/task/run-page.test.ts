import type { App, PropType } from 'vue';

import { createApp, defineComponent, h, nextTick } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import PushTaskPage from './index.vue';

const state = vi.hoisted(() => ({
  row: {
    id: '9007199254740993',
    name: '未知任务',
    status: 0,
    blockingRunStatus: 5,
    lastRunStatus: 3,
    running: false,
  },
}));

vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', slots.default?.()),
  }),
  useVbenModal: () => [
    defineComponent(() => () => null),
    { setData: vi.fn(), open: vi.fn() },
  ],
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({ accessToken: '1' }),
  useUserStore: () => ({ userInfo: { userId: '1' } }),
}));
vi.mock('@vben/icons', () => ({
  IconifyIcon: defineComponent(() => () => null),
}));
vi.mock('antdv-next', () => ({
  Alert: defineComponent(() => () => null),
  Button: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('button', slots.default?.()),
  }),
  Input: defineComponent(() => () => h('input')),
  Tooltip: defineComponent({
    setup: (_, { slots }) => () => h('span', slots.default?.()),
  }),
  Tree: defineComponent(() => () => null),
  Tag: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('span', slots.default?.()),
  }),
  message: { loading: vi.fn(() => vi.fn()), success: vi.fn() },
}));
vi.mock('#/api/dataforge/datapush/category', () => ({
  getPushCategoryTree: vi.fn().mockResolvedValue([]),
}));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('#/components/text-ellipsis-cell', () => ({
  TextEllipsisCell: defineComponent(() => () => null),
}));
vi.mock('#/api/dataforge/datapush/task', () => ({
  cancelDataPushTask: vi.fn(),
  copyDataPushTask: vi.fn(),
  deleteDataPushTask: vi.fn(),
  getDataPushTaskPage: vi.fn(),
  handRunDataPushTask: vi.fn(),
  releaseDataPushTask: vi.fn(),
  stopDataPushTask: vi.fn(),
}));
vi.mock('./data', () => ({
  CHANNEL_TYPE_OPTIONS: [],
  PUSH_MODE_OPTIONS: [],
  PUSH_TYPE_OPTIONS: [],
  RUN_STATUS_OPTIONS: [],
  STATUS_OPTIONS: [],
  PUSH_MODE: { INCREMENT: 2 },
  RELEASE_STATUS: { RELEASED: 1 },
  TASK_TYPE: { ONCE: 1 },
  optionColor: () => 'default',
  optionLabel: () => '',
  useGridColumns: () => [],
  useGridFormSchema: () => [],
}));
vi.mock('./modules/detail.vue', () => ({ default: {} }));
vi.mock('./modules/form.vue', () => ({
  default: defineComponent({
    props: { initialData: Object },
    emits: ['close'],
    setup:
      (props, { emit }) =>
      () =>
        h('section', { 'data-task-form': true }, [
          h('span', props.initialData?.id ?? '新建任务'),
          h('button', { onClick: () => emit('close') }, '返回列表'),
        ]),
  }),
}));
vi.mock('./modules/log.vue', () => ({ default: {} }));
vi.mock('./modules/preview.vue', () => ({ default: {} }));
vi.mock('./modules/watermark.vue', () => ({ default: {} }));
vi.mock('#/adapter/vxe-table', () => {
  interface Action {
    label: string;
    disabled?: boolean;
    ifShow?: boolean;
    onClick?: () => void;
  }
  return {
    ACTION_ICON: {},
    TableAction: defineComponent({
      props: {
        actions: Array as PropType<Action[]>,
        dropDownActions: Array as PropType<Action[]>,
      },
      setup: (props) => () =>
        h(
          'div',
          [...(props.actions ?? []), ...(props.dropDownActions ?? [])]
            .filter((action) => action.ifShow !== false)
            .map((action) =>
              h(
                'button',
                { disabled: action.disabled, onClick: action.onClick },
                action.label,
              ),
            ),
        ),
    }),
    useVbenVxeGrid: () => [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h('section', [
              slots['toolbar-tools']?.(),
              slots.running?.({ row: state.row }),
              slots.actions?.({ row: state.row }),
            ]),
      }),
      { query: vi.fn() },
    ],
  };
});

let app: App | undefined;
let element: HTMLDivElement | undefined;
afterEach(() => {
  app?.unmount();
  element?.remove();
});

async function mountRow(blockingRunStatus: number, running: boolean) {
  state.row.blockingRunStatus = blockingRunStatus;
  state.row.running = running;
  element = document.createElement('div');
  document.body.append(element);
  app = createApp(PushTaskPage);
  app.mount(element);
  await nextTick();
  return element;
}

describe('推送真实父页状态接线', () => {
  it('新建和编辑在列表页切换完整任务向导，返回后恢复列表', async () => {
    const root = await mountRow(0, false);
    const buttons = () => [...root.querySelectorAll('button')];
    buttons()
      .find((button) => button.textContent === 'common.create')!
      .click();
    await nextTick();
    expect(root.querySelector('[data-task-form]')?.textContent).toContain(
      '新建任务',
    );
    buttons()
      .find((button) => button.textContent === '返回列表')!
      .click();
    await nextTick();
    expect(root.querySelector('[data-task-form]')).toBeNull();
    buttons()
      .find((button) => button.textContent === 'common.edit')!
      .click();
    await nextTick();
    expect(root.querySelector('[data-task-form]')?.textContent).toContain(
      '9007199254740993',
    );
  });
  it('旧未知占位即使最近日志成功也显示待核查，并禁止重跑和修改', async () => {
    const root = await mountRow(5, false);
    expect(root.textContent).toContain('待核查（禁止重跑）');
    const buttons = [...root.querySelectorAll('button')];
    expect(
      buttons.find((button) => button.textContent === '执行')?.disabled,
    ).toBe(true);
    expect(
      buttons.find((button) => button.textContent === '发布')?.disabled,
    ).toBe(true);
    expect(buttons.some((button) => button.textContent === 'common.edit')).toBe(
      false,
    );
    expect(
      buttons.some((button) => button.textContent === 'common.delete'),
    ).toBe(false);
  });
  it('运行中仍提供停止，无占位允许执行', async () => {
    const root = await mountRow(2, true);
    expect(root.textContent).toContain('运行中');
    expect(
      [...root.querySelectorAll('button')].find(
        (button) => button.textContent === '停止',
      )?.disabled,
    ).toBe(false);
  });
});
