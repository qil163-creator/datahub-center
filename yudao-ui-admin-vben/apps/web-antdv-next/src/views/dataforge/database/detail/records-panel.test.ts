import type { App, SetupContext } from 'vue';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getDatabaseAlarmPage,
  getDatabaseMonitorLogPage,
  handleDatabaseAlarm,
} from '#/api/dataforge/database/monitor';

import RecordsPanel from './records-panel.vue';

const harness = vi.hoisted(() => ({
  access: {} as any,
  actions: [] as any[],
  modalOptions: [] as any[],
  row: { id: '9007199254740995', status: 'PENDING', ruleName: '连接断开' },
  setGridOptions: vi.fn(),
  setLoading: vi.fn(),
  success: vi.fn(),
  warning: vi.fn(),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access }));
vi.mock('@vben/utils', () => ({
  formatDateTime: String,
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: any) => {
    harness.modalOptions.push(options);
    return [
      (_: unknown, { slots }: SetupContext) => h('div', slots.default?.()),
      {
        open: () => options.onOpenChange?.(true),
        close: () => options.onOpenChange?.(false),
        lock: vi.fn(),
        unlock: vi.fn(),
      },
    ];
  },
}));
vi.mock('antdv-next', () => ({
  Alert: () => null,
  Descriptions: () => null,
  DescriptionsItem: () => null,
  Tag: () => null,
  Input: {
    TextArea: Object.assign(
      (props: any) =>
        h('textarea', {
          value: props.value,
          onInput: (event: Event) =>
            props['onUpdate:value']?.(
              (event.target as HTMLTextAreaElement).value,
            ),
        }),
      { props: ['value', 'onUpdate:value'] },
    ),
  },
  message: { success: harness.success, warning: harness.warning },
}));
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  TableAction: Object.assign(
    (props: { actions: any[] }) => {
      harness.actions = props.actions;
      return null;
    },
    { props: ['actions'] },
  ),
  useVbenVxeGrid: () => [
    (_: unknown, { slots }: SetupContext) =>
      h('div', slots.actions?.({ row: harness.row })),
    { setGridOptions: harness.setGridOptions, setLoading: harness.setLoading },
  ],
}));
vi.mock('#/api/dataforge/database/monitor', () => ({
  getDatabaseAlarm: vi.fn(),
  getDatabaseAlarmPage: vi.fn(),
  getDatabaseMonitorLogPage: vi.fn(),
  handleDatabaseAlarm: vi.fn(),
}));

let app: App;
let host: HTMLDivElement;
async function settle() {
  await nextTick();
  await Promise.resolve();
  await nextTick();
}
async function mount(mode: 'alarm' | 'log') {
  host = document.createElement('div');
  app = createApp(RecordsPanel, { databaseId: '9007199254740993', mode });
  app.mount(host);
  await settle();
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({ accessToken: '1', refreshToken: undefined });
  harness.modalOptions = [];
  harness.actions = [];
  vi.mocked(getDatabaseAlarmPage).mockResolvedValue({ list: [], total: 0 });
  vi.mocked(getDatabaseMonitorLogPage).mockResolvedValue({
    list: [],
    total: 0,
  });
});
afterEach(() => app?.unmount());

describe('database record panels', () => {
  it('keeps read/write audit logs read-only and queries by the lossless database ID', async () => {
    await mount('log');
    expect(getDatabaseMonitorLogPage).toHaveBeenCalledWith({
      databaseId: '9007199254740993',
      pageNo: 1,
      pageSize: 20,
    });
    expect(
      harness.actions
        .filter((item) => item.ifShow !== false)
        .map((item) => item.label),
    ).toEqual(['详情']);
  });

  it('discards an old session response after the new session finishes loading', async () => {
    let finish!: (value: any) => void;
    vi.mocked(getDatabaseAlarmPage).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    await mount('alarm');
    const current = { list: [{ id: 'current' }], total: 1 };
    vi.mocked(getDatabaseAlarmPage).mockResolvedValueOnce(current as any);
    harness.access.refreshToken = '2';
    await settle();
    finish({ list: [{ id: 'stale' }], total: 1 });
    await settle();
    const updates = harness.setGridOptions.mock.calls
      .map(([value]) => value.data)
      .filter(Boolean);
    expect(updates.at(-1)).toEqual(current.list);
    expect(
      updates.some((rows) => rows.some((row: any) => row.id === 'stale')),
    ).toBe(false);
  });

  it('requires an explanation and sends the selected disposition with scoped IDs', async () => {
    await mount('alarm');
    harness.actions.find((item) => item.label === '忽略').onClick();
    await harness.modalOptions[1].onConfirm();
    expect(handleDatabaseAlarm).not.toHaveBeenCalled();
    const textarea = host.querySelector('textarea')!;
    textarea.value = '维护窗口，无需处理';
    textarea.dispatchEvent(new Event('input'));
    await settle();
    await harness.modalOptions[1].onConfirm();
    expect(handleDatabaseAlarm).toHaveBeenCalledWith({
      databaseId: '9007199254740993',
      id: '9007199254740995',
      action: 'IGNORE',
      remark: '维护窗口，无需处理',
    });
  });
});
