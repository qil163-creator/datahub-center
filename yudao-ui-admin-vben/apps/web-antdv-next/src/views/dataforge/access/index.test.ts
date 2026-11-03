import type { App, Component, SetupContext } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { runAccess } from '#/api/dataforge/access';

import ApiAccess from '../apiaccess/index.vue';
import Access from './index.vue';

const harness = vi.hoisted(() => ({
  access: {} as any, actions: [] as any[], gridOptions: {} as any,
  toolbarActions: [] as any[],
  row: { id: '9007199254740993', taskName: 'Example', runStatus: 3, engineType: 1 },
  open: vi.fn(), setData: vi.fn(), query: vi.fn(), success: vi.fn(),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access, useUserStore: () => ({ userInfo: { userId: 1 } }) }));
vi.mock('@vben/common-ui', () => ({
  Page: (_: unknown, { slots }: SetupContext) => h('main', slots.default?.()),
  useVbenDrawer: () => [() => null, { setData: (data: unknown) => { harness.setData(data); return { open: harness.open }; }, close: vi.fn() }],
}));
vi.mock('antdv-next', () => ({ message: { success: harness.success, loading: () => vi.fn(), warning: vi.fn() }, Tag: () => null }));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('./modules/form.vue', () => ({ default: defineComponent({
  props: { embedded: Boolean, initialData: { type: Object, default: undefined } },
  emits: ['close', 'success'],
  setup: (props, { emit }) => () => h('section', {
    'data-embedded-form': String(props.embedded),
    'data-edit-id': props.initialData?.id ?? '',
  }, [h('button', { onClick: () => emit('close') }, '返回列表'), h('button', { onClick: () => emit('success') }, '保存')]),
}) }));
vi.mock('./modules/task.vue', () => ({ default: {} }));
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  TableAction: Object.assign((props: { actions?: any[]; dropDownActions?: any[] }) => {
    if (props.dropDownActions) harness.actions = [...(props.actions ?? []), ...props.dropDownActions];
    else harness.toolbarActions = props.actions ?? [];
    return h('div');
  }, { props: { actions: Array, dropDownActions: Array } }),
  useVbenVxeGrid: (options: unknown) => {
    harness.gridOptions = options;
    return [(_: unknown, { slots }: SetupContext) => h('div', { 'data-grid': 'true' }, [slots['toolbar-tools']?.(), slots.actions?.({ row: harness.row })]), { query: harness.query }];
  },
}));
vi.mock('#/api/dataforge/access', () => ({
  cancelAccess: vi.fn(), deleteAccess: vi.fn(), getAccessPage: vi.fn(), releaseAccess: vi.fn(),
  runAccess: vi.fn(), stopAccess: vi.fn(),
}));

let app: App;
let host: HTMLDivElement;
const action = (label: string) => harness.actions.find((item) => item.label === label);
async function mountPage(component: Component) {
  host = document.createElement('div'); app = createApp(component); app.mount(host); await nextTick();
}
beforeEach(() => { vi.resetAllMocks(); harness.actions = []; harness.toolbarActions = []; harness.access = reactive({ accessToken: '1', refreshToken: undefined }); harness.row.runStatus = 3; harness.row.engineType = 1; });
afterEach(() => { app?.unmount(); });

describe.each([['data access', Access, '执行'], ['API access', ApiAccess, '执行一次']] as const)('%s entry', (_, component, runLabel) => {
  it('reuses the command identity after response loss and reports the receipt record', async () => {
    await mountPage(component);
    vi.mocked(runAccess).mockRejectedValueOnce(new Error('lost')).mockResolvedValue({ recordId: '9007199254740995', replayed: true, runStatus: 1, stopRequested: false });
    await expect(action(runLabel).popConfirm.confirm()).rejects.toThrow('lost');
    await action(runLabel).popConfirm.confirm();
    expect(vi.mocked(runAccess).mock.calls[0]?.[0]).toEqual({ taskId: harness.row.id, requestId: expect.any(String) });
    expect(vi.mocked(runAccess).mock.calls[1]?.[0]).toEqual(vi.mocked(runAccess).mock.calls[0]?.[0]);
    expect(harness.success).toHaveBeenCalledWith(expect.stringContaining('9007199254740995'));
  });
  it('opens execution records for the selected task', async () => {
    harness.row.runStatus = 2; await mountPage(component);
    expect(action('选择运行并停止')).toBeUndefined();
    expect(action('执行记录')).toBeDefined();
    action('执行记录').onClick();
    expect(harness.setData).toHaveBeenCalledWith(harness.row);
    expect(harness.open).toHaveBeenCalledOnce();
  });
  it('ignores an old-session run receipt after the page identity changes', async () => {
    await mountPage(component);
    let finish!: (value: any) => void;
    vi.mocked(runAccess).mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    const pending = action(runLabel).popConfirm.confirm();
    harness.access.refreshToken = '2'; await nextTick();
    finish({ recordId: '9007199254740995', replayed: false, runStatus: 1, stopRequested: false });
    await pending;
    expect(harness.success).not.toHaveBeenCalled();
    expect(harness.query).not.toHaveBeenCalled();
  });
});

describe('data sync execution', () => {
  it('实时任务可提交执行，并使用现有幂等命令接口', async () => {
    harness.row.engineType = 2;
    await mountPage(Access);
    expect(host.textContent).not.toContain('SeaTunnel 原文新提交当前不可用');
    expect(action('执行').disabled).toBe(false);
    vi.mocked(runAccess).mockResolvedValue({ recordId: '9007199254740995', replayed: false, runStatus: 1, stopRequested: false });
    await action('执行').popConfirm.confirm();
    expect(runAccess).toHaveBeenCalledWith({ taskId: harness.row.id, requestId: expect.any(String) });
  });
});

describe.each([['data sync', Access], ['API collection', ApiAccess]] as const)('%s page editor', (_, component) => {
  it('opens creation in the page and returns to the retained list without opening a modal', async () => {
    await mountPage(component);
    harness.toolbarActions[0].onClick();
    await nextTick();
    expect(host.querySelector('[data-embedded-form="true"]')).not.toBeNull();
    expect((host.querySelector('[data-grid]')!.parentElement as HTMLElement).style.display).toBe('none');
    expect(harness.open).not.toHaveBeenCalled();
    host.querySelector<HTMLButtonElement>('[data-embedded-form] button')!.click();
    await nextTick();
    expect(host.querySelector('[data-embedded-form]')).toBeNull();
    expect((host.querySelector('[data-grid]')!.parentElement as HTMLElement).style.display).toBe('');
  });

  it('passes the lossless task ID for edit and refreshes the list after saving', async () => {
    await mountPage(component);
    action('common.edit').onClick();
    await nextTick();
    expect(host.querySelector<HTMLElement>('[data-embedded-form]')?.dataset.editId).toBe(harness.row.id);
    host.querySelectorAll<HTMLButtonElement>('[data-embedded-form] button')[1]!.click();
    await nextTick();
    expect(host.querySelector('[data-embedded-form]')).toBeNull();
    expect(harness.query).toHaveBeenCalledOnce();
  });

  it('closes the editor when the session identity changes', async () => {
    await mountPage(component);
    action('common.edit').onClick();
    await nextTick();
    harness.access.refreshToken = '2';
    await nextTick();
    expect(host.querySelector('[data-embedded-form]')).toBeNull();
    expect(harness.open).not.toHaveBeenCalled();
  });
});
