/* eslint-disable vue/one-component-per-file -- 页面回归使用独立的表格与开关替身。 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import TaskList from './index.vue';

const mock = vi.hoisted(() => ({
  delete: vi.fn(),
  update: vi.fn(),
  schedule: vi.fn(),
  load: vi.fn(),
  rows: [
    { id: '9007199254740993', enabled: 1 },
    { id: '9007199254740995', enabled: 1 },
  ],
  switchChanges: [] as ((checked: boolean) => void)[],
}));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null, addIcon: vi.fn() }));
vi.mock('#/hooks/use-object-request', () => ({
  useObjectRequest: () => ({ begin: () => () => true }),
}));
vi.mock('#/adapter/component/date-picker', () => ({ RangePicker: () => null }));
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  TableAction: defineComponent({
    props: { actions: { type: Array, default: () => [] } },
    setup: (props) => () => h('div', (props.actions as Array<{
      disabled?: boolean; label: string; onClick?: () => void;
    }>).map((action) => h('button', {
      disabled: action.disabled,
      onClick: action.onClick,
    }, action.label))),
  }),
}));
vi.mock('./data', () => ({ useGridColumns: () => [] }));
vi.mock('./modules/form.vue', () => ({ default: () => null }));
vi.mock('#/api/governance/metacollecttask', () => ({
  deleteMetaCollectTaskList: mock.delete,
  getMetaCollectTaskPage: vi.fn(),
  runMetaCollectTask: vi.fn(),
  updateMetaCollectTaskEnabled: mock.update,
  updateMetaCollectTaskScheduleEnabled: mock.schedule,
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', slots.default?.()),
  }),
  confirm: vi.fn(),
  useVbenModal: () => [() => null, {}],
}));
vi.mock('antdv-next', () => ({
  Alert: () => null,
  Input: () => null,
  Button: () => null,
  message: { success: vi.fn() },
  Switch: defineComponent({
    props: { checked: Boolean, disabled: Boolean, loading: Boolean },
    emits: ['change'],
    setup(props, { emit }) {
      mock.switchChanges.push((checked) => emit('change', checked));
      return () =>
        h('button', {
          type: 'button',
          role: 'switch',
          disabled: props.disabled,
          onClick: () => emit('change', !props.checked),
        });
    },
  }),
}));
vi.mock('../metadata-common/use-metadata-grid', () => ({
  useMetadataGrid: () => ({
    Grid: defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h(
            'section',
            [slots['toolbar-tools']?.(), ...mock.rows.map((row) => slots.state?.({ row }))],
          ),
    }),
    load: mock.load,
    selected: selection,
    error: ref(false),
  }),
}));

let app: App | undefined;
let host: HTMLDivElement;
let selection = ref<Array<{ id: string }>>([]);
async function flush() {
  for (let index = 0; index < 8; index++) await Promise.resolve();
  await nextTick();
}
beforeEach(() => {
  vi.clearAllMocks();
  mock.switchChanges = [];
  mock.update.mockResolvedValue(undefined);
  mock.load.mockResolvedValue(undefined);
  mock.delete.mockResolvedValue(undefined);
  selection = ref([]);
  host = document.createElement('div');
  app = createApp(TaskList);
  app.mount(host);
});
afterEach(() => {
  app?.unmount();
  host.remove();
});

describe('采集任务状态切换', () => {
  it('批量删除使用选中任务的无损编号并在完成后刷新列表', async () => {
    const button = [...host.querySelectorAll('button')].find((element) => element.textContent === '删除');
    expect(button?.disabled).toBe(true);
    selection.value = [{ id: '9007199254740993' }];
    await nextTick();
    expect(button?.disabled).toBe(false);
    button?.click();
    await flush();
    expect(mock.delete).toHaveBeenCalledExactlyOnceWith(['9007199254740993']);
    expect(mock.load).toHaveBeenCalledOnce();
  });

  it('等待前次状态提交及刷新后才允许切换另一任务，阻止并发请求遗漏刷新', async () => {
    let finish!: () => void;
    mock.update.mockReturnValueOnce(
      new Promise<void>((resolve) => {
        finish = resolve;
      }),
    );
    const controls =
      host.querySelectorAll<HTMLButtonElement>('[role="switch"]');
    controls[0]!.click();
    await flush();
    expect([...controls].every((control) => control.disabled)).toBe(true);
    // 即使控件已经发出另一个 change 事件，处理器也阻止第二次提交。
    mock.switchChanges[2]!(false);
    expect(mock.update).toHaveBeenCalledExactlyOnceWith('9007199254740993', 0);
    expect(mock.load).not.toHaveBeenCalled();
    finish();
    await flush();
    expect(mock.load).toHaveBeenCalledOnce();
    expect(controls[2]!.disabled).toBe(false);
    controls[2]!.click();
    await flush();
    expect(mock.update).toHaveBeenLastCalledWith('9007199254740995', 0);
    expect(mock.load).toHaveBeenCalledTimes(2);
  });
});
