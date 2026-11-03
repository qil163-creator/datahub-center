import type { App, SetupContext } from 'vue';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createDatabaseRule,
  getDatabaseRule,
  getDatabaseRulePage,
  updateDatabaseRule,
} from '#/api/dataforge/database/monitor';

import RulesPanel from './rules-panel.vue';

const harness = vi.hoisted(() => ({
  access: {} as any,
  actions: [] as any[],
  modalOptions: [] as any[],
  formValues: {} as any,
  row: { id: '9007199254740995', name: '旧名称', status: 0 },
  setValues: vi.fn(),
  success: vi.fn(),
  warning: vi.fn(),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access }));
vi.mock('@vben/utils', () => ({
  formatDateTime: String,
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: any) => {
    harness.modalOptions.push(options);
    return [
      () => null,
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
  Switch: () => null,
  message: { success: harness.success, warning: harness.warning },
}));
vi.mock('#/adapter/form', () => ({
  useVbenForm: () => [
    () => null,
    {
      reset: vi.fn(),
      validate: async () => ({ valid: true }),
      getValues: async () => harness.formValues,
      setValues: (values: any) => {
        harness.formValues = values;
        harness.setValues(values);
      },
    },
  ],
}));
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  TableAction: Object.assign(
    (props: { actions: any[] }) => {
      harness.actions.push(...props.actions);
      return null;
    },
    { props: ['actions'] },
  ),
  useVbenVxeGrid: () => [
    (_: unknown, { slots }: SetupContext) =>
      h('div', [
        slots['toolbar-actions']?.(),
        slots.actions?.({ row: harness.row }),
      ]),
    { setGridOptions: vi.fn(), setLoading: vi.fn() },
  ],
}));
vi.mock('#/api/dataforge/database/monitor', () => ({
  createDatabaseRule: vi.fn(),
  deleteDatabaseRule: vi.fn(),
  getDatabaseRule: vi.fn(),
  getDatabaseRulePage: vi.fn(),
  updateDatabaseRule: vi.fn(),
  updateDatabaseRuleStatus: vi.fn(),
}));

let app: App;
async function settle() {
  await nextTick();
  await Promise.resolve();
  await nextTick();
}
async function mount() {
  app = createApp(RulesPanel, { databaseId: '9007199254740993' });
  app.mount(document.createElement('div'));
  await settle();
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({ accessToken: '1', refreshToken: undefined });
  harness.modalOptions = [];
  harness.actions = [];
  harness.formValues = {};
  vi.mocked(getDatabaseRulePage).mockResolvedValue({ list: [], total: 0 });
});
afterEach(() => app?.unmount());

describe('database alarm rules', () => {
  it('normalizes connection rules to the supported disconnected condition', async () => {
    await mount();
    await harness.actions.find((item) => item.label === '新增').onClick();
    harness.formValues = {
      ...harness.formValues,
      name: '断开告警',
      operator: 'GT',
      threshold: '3000',
    };
    await harness.modalOptions[0].onConfirm();
    expect(createDatabaseRule).toHaveBeenCalledWith(
      expect.objectContaining({
        databaseId: '9007199254740993',
        metric: 'CONNECTION_STATUS',
        operator: 'EQ',
        threshold: '0',
      }),
    );
  });

  it('rejects zero response thresholds and preserves a valid decimal string', async () => {
    await mount();
    await harness.actions.find((item) => item.label === '新增').onClick();
    harness.formValues = {
      ...harness.formValues,
      name: '超时告警',
      metric: 'RESPONSE_TIME',
      operator: 'GT',
      threshold: '0.000',
    };
    await harness.modalOptions[0].onConfirm();
    expect(createDatabaseRule).not.toHaveBeenCalled();
    harness.formValues.threshold = '3000.0000000000000001';
    await harness.modalOptions[0].onConfirm();
    expect(createDatabaseRule).toHaveBeenCalledWith(
      expect.objectContaining({ threshold: '3000.0000000000000001' }),
    );
  });

  it('reloads the current rule before editing and preserves its large ID', async () => {
    await mount();
    const current = {
      ...harness.row,
      databaseId: '9007199254740993',
      name: '最新名称',
      metric: 'CONNECTION_STATUS',
      operator: 'EQ',
      threshold: '0',
      level: 'GENERAL',
      owner: '',
    };
    vi.mocked(getDatabaseRule).mockResolvedValue(current as any);
    await harness.actions.find((item) => item.label === '修改').onClick();
    expect(getDatabaseRule).toHaveBeenCalledWith(
      '9007199254740993',
      harness.row.id,
    );
    await harness.modalOptions[0].onConfirm();
    expect(updateDatabaseRule).toHaveBeenCalledWith(
      expect.objectContaining({ id: harness.row.id, name: current.name }),
    );
  });

  it('does not refill an editor with an old session rule response', async () => {
    let finish!: (value: any) => void;
    vi.mocked(getDatabaseRule).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    await mount();
    const pending = harness.actions
      .find((item) => item.label === '修改')
      .onClick();
    await settle();
    harness.access.refreshToken = '2';
    finish({ ...harness.row, name: '旧会话规则' });
    await pending;
    expect(harness.setValues).not.toHaveBeenCalled();
  });
});
