import type { App, PropType } from 'vue';

import type { SupplyAppApi } from '#/api/supply/app';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { confirm } from '@vben/common-ui';
import { useAccessStore } from '@vben/stores';

import { message } from 'antdv-next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createAppApi,
  getAppApi,
  getAppApiList,
  getSupplyApp,
  revokeAdminGrant,
  setGrantControl,
  updateAppApi,
} from '#/api/supply/app';
import { getDataApiSimpleList } from '#/api/supply/dataapi';

import Detail from './detail.vue';

interface ModalOptions {
  onConfirm?: () => Promise<void> | void;
  onOpenChange?: (open: boolean) => Promise<void> | void;
}
interface ModalHarness {
  data: { id?: SupplyAppApi.Id };
  options: ModalOptions;
  state: { isOpen: boolean; submitting: boolean };
  open: (id?: SupplyAppApi.Id) => void;
  close: () => Promise<void>;
}
const harness = vi.hoisted(() => ({
  modals: [] as ModalHarness[],
  state: undefined as
    | undefined
    | {
        accessCodes: string[];
        accessToken: string;
        refreshToken: string;
        userInfo: { userId: string };
      },
}));
vi.mock('@vben/stores', () => {
  harness.state = reactive({
    accessCodes: ['supply:app:query', 'supply:app:grant'],
    accessToken: '1',
    refreshToken: '',
    userInfo: { userId: '9' },
  });
  return {
    useAccessStore: () => harness.state,
    useUserStore: () => harness.state,
  };
});
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.state!.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('#/components/list-pagination/index.vue', () => ({
  default: defineComponent({ name: 'ListPaginationStub', render: () => null }),
}));
vi.mock('@vben/common-ui', () => {
  const usePopup = (options: ModalOptions) => {
    const index = harness.modals.length;
    const state = reactive({ isOpen: false, submitting: false });
    const modal: ModalHarness = {
      data: {},
      options,
      state,
      open(id) {
        if (id !== undefined) modal.data = { id };
        state.isOpen = true;
        state.submitting = false;
        void options.onOpenChange?.(true);
      },
      async close() {
        state.isOpen = false;
        await options.onOpenChange?.(false);
      },
    };
    harness.modals.push(modal);
    return [
      defineComponent({
        name: `DetailModalStub${index}`,
        props: { confirmDisabled: Boolean },
        setup:
          (props, { slots }) =>
          () =>
            state.isOpen
              ? h('section', { 'data-modal': index }, [
                  slots.default?.(),
                  options.onConfirm
                    ? h(
                        'button',
                        {
                          'data-confirm': index,
                          disabled: props.confirmDisabled || state.submitting,
                          onClick: options.onConfirm,
                        },
                        '提交',
                      )
                    : null,
                ])
              : null,
      }),
      {
        open: () => modal.open(),
        close: modal.close,
        getData: () => modal.data,
        lock: () => {
          state.submitting = true;
        },
        unlock: () => {
          state.submitting = false;
        },
        setState(value: Partial<typeof state>) {
          const before = state.isOpen;
          Object.assign(state, value);
          if (before !== state.isOpen)
            void options.onOpenChange?.(state.isOpen);
        },
      },
    ];
  };
  return {
    confirm: vi.fn(),
    useVbenDrawer: usePopup,
    useVbenModal: usePopup,
  };
});
vi.mock('antdv-next', () => {
  const Container = defineComponent({
    name: 'DetailContainerStub',
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Alert: defineComponent({
      name: 'DetailAlertStub',
      props: { message: { type: String, default: '' } },
      setup:
        (props, { slots }) =>
        () =>
          h('div', { role: 'alert' }, [props.message, slots.action?.()]),
    }),
    Button: defineComponent({
      name: 'DetailButtonStub',
      props: { disabled: Boolean, loading: Boolean },
      setup:
        (props, { slots }) =>
        () =>
          h(
            'button',
            {
              disabled: props.disabled,
              'data-loading': String(props.loading),
            },
            slots.default?.(),
          ),
    }),
    Table: defineComponent({
      name: 'DetailTableStub',
      props: {
        columns: {
          type: Array as PropType<{ key: string }[]>,
          default: () => [],
        },
        dataSource: { type: Array as PropType<object[]>, default: () => [] },
        loading: Boolean,
        rowKey: {
          type: [String, Function] as PropType<
            ((record: object) => string) | string
          >,
          default: 'id',
        },
      },
      setup:
        (props, { slots }) =>
        () =>
          h(
            'table',
            {
              'data-loading': String(props.loading),
            },
            props.dataSource.map((record) =>
              h(
                'tr',
                {
                  'data-row-key':
                    typeof props.rowKey === 'function'
                      ? props.rowKey(record)
                      : String(
                          (record as Record<string, unknown>)[props.rowKey],
                        ),
                },
                props.columns.map((column) =>
                  h('td', slots.bodyCell?.({ record, column })),
                ),
              ),
            ),
          ),
    }),
    Select: defineComponent({
      name: 'DetailSelectStub',
      props: {
        value: { type: [String, Number], default: undefined },
        disabled: Boolean,
        loading: Boolean,
        options: {
          type: Array as PropType<{ label: string; value: string }[]>,
          default: () => [],
        },
      },
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'select',
            {
              value: props.value,
              disabled: props.disabled,
              'data-loading': String(props.loading),
              onChange: (event: Event) =>
                emit('update:value', (event.target as HTMLSelectElement).value),
            },
            [
              h('option', { value: '' }),
              ...props.options.map((option) =>
                h('option', { value: option.value }, option.label),
              ),
            ],
          ),
    }),
    Switch: defineComponent({
      name: 'DetailSwitchStub',
      props: { checked: Boolean, disabled: Boolean, loading: Boolean },
      emits: ['change'],
      setup:
        (props, { attrs, emit }) =>
        () =>
          h(
            'button',
            {
              role: 'switch',
              disabled: props.disabled,
              'aria-checked': String(props.checked),
              'data-loading': String(props.loading),
              onClick: () => emit('change', !props.checked),
            },
            String(attrs['aria-label'] ?? ''),
          ),
    }),
    DatePicker: {
      RangePicker: defineComponent({
        props: {
          value: { type: Array as PropType<string[]>, default: () => [] },
          disabled: Boolean,
        },
        emits: ['update:value'],
        setup:
          (props, { emit }) =>
          () =>
            h(
              'div',
              [0, 1].map((index) =>
                h('input', {
                  'data-date-index': index,
                  disabled: props.disabled,
                  value: props.value?.[index] ?? '',
                  onInput: (event: Event) => {
                    const range = [...(props.value ?? ['', ''])];
                    range[index] = (event.target as HTMLInputElement).value;
                    emit('update:value', range);
                  },
                }),
              ),
            ),
      }),
    },
    Descriptions: Container,
    DescriptionsItem: Container,
    Image: Container,
    Tooltip: Container,
    RadioGroup: defineComponent({
      props: {
        value: { type: Number, default: undefined },
        disabled: Boolean,
        options: {
          type: Array as PropType<{ label: string; value: number }[]>,
          default: () => [],
        },
      },
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'div',
            props.options.map((option) =>
              h('label', [
                h('input', {
                  type: 'radio',
                  value: String(option.value),
                  disabled: props.disabled,
                  checked: props.value === option.value,
                  onChange: () => emit('update:value', option.value),
                }),
                option.label,
              ]),
            ),
          ),
    }),
    Tabs: Container,
    TabPane: Container,
    Tag: Container,
    TextArea: defineComponent({
      props: { value: { type: String, default: undefined }, disabled: Boolean },
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h('textarea', {
            value: props.value,
            disabled: props.disabled,
            onInput: (event: Event) =>
              emit('update:value', (event.target as HTMLTextAreaElement).value),
          }),
    }),
    message: { success: vi.fn(), warning: vi.fn() },
  };
});
vi.mock('#/api/supply/app', () => ({
  getSupplyApp: vi.fn(),
  getAppApiList: vi.fn(),
  getAppApi: vi.fn(),
  setGrantControl: vi.fn(),
  createAppApi: vi.fn(),
  revokeAdminGrant: vi.fn(),
  updateAppApi: vi.fn(),
}));
vi.mock('#/api/supply/dataapi', () => ({ getDataApiSimpleList: vi.fn() }));
vi.mock('#/utils', () => ({ getRangePickerDefaultProps: () => ({}) }));
vi.mock('../data', () => ({ getUserNickname: (id: string) => id }));
vi.mock('#/views/modeling/modelcommon/detail-field-form.vue', () => ({
  default: defineComponent({
    name: 'DetailFieldsStub',
    props: {
      fields: {
        type: Array as PropType<{ value: unknown }[]>,
        default: () => [],
      },
    },
    setup: (props) => () =>
      h(
        'div',
        props.fields?.map((field) => String(field.value ?? '')).join('|'),
      ),
  }),
}));

let app: App | undefined;
let host: HTMLDivElement;
const row = (
  overrides: Partial<SupplyAppApi.AppApi> = {},
): SupplyAppApi.AppApi => ({
  id: '11',
  appId: '1',
  apiId: '21',
  apiName: 'test-api',
  authStatus: 1,
  aggregateRevision: '0',
  aggregateDisabled: false,
  ...overrides,
});
const editableGrant = (
  overrides: Partial<SupplyAppApi.AppApi> = {},
): SupplyAppApi.AppApi =>
  row({
    sourceType: 'ADMIN',
    protocolVersion: 0,
    foreverFlag: 1,
    ...overrides,
  });
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
async function flush() {
  for (let i = 0; i < 10; i++) await Promise.resolve();
  await nextTick();
}
async function open(id: SupplyAppApi.Id = '1') {
  harness.modals[0]!.open(id);
  await flush();
}
async function mountEmbedded(id: SupplyAppApi.Id = '1') {
  app!.unmount();
  harness.modals = [];
  const props = reactive({ appId: id });
  const back = vi.fn();
  app = createApp({
    render: () => h(Detail, { ...props, embedded: true, onBack: back }),
  });
  app.directive('access', {});
  app.mount(host);
  await flush();
  return { props, back };
}
async function click(text: string) {
  const target = [...host.querySelectorAll<HTMLElement>('button,a')].find(
    (item) => item.textContent?.trim() === text,
  );
  expect(target, `missing action ${text}`).toBeDefined();
  target!.click();
  await flush();
}
beforeEach(() => {
  useAccessStore();
  vi.resetAllMocks();
  harness.modals = [];
  Object.assign(harness.state!, {
    accessCodes: ['supply:app:query', 'supply:app:grant'],
    accessToken: '1',
    refreshToken: '',
    userInfo: { userId: '9' },
  });
  vi.mocked(getSupplyApp).mockImplementation(async (id) => ({
    id,
    name: `app-${id}`,
  }));
  vi.mocked(getAppApiList).mockResolvedValue([row()]);
  vi.mocked(getAppApi).mockResolvedValue(editableGrant());
  vi.mocked(getDataApiSimpleList).mockResolvedValue([
    { id: '21', name: 'choice', version: 'v1' },
  ]);
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Detail);
  app.directive('access', {});
  app.mount(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('supply detail regression reproducers', () => {
  it('does not submit a control confirmed after an identity change', async () => {
    const pending = deferred<undefined>();
    vi.mocked(confirm).mockReturnValueOnce(pending.promise);
    await open();
    await click('整体禁用');
    harness.state!.refreshToken = '2';
    pending.resolve(undefined);
    await flush();
    expect(setGrantControl).not.toHaveBeenCalled();
  });
  it('never borrows another row revision for a row without one', async () => {
    vi.mocked(getAppApiList).mockResolvedValue([
      row(),
      row({ id: '12', apiId: '22', aggregateRevision: undefined }),
    ]);
    await open();
    const controls = [...host.querySelectorAll<HTMLElement>('a,button')].filter(
      (item) => item.textContent?.trim() === '整体禁用',
    );
    controls[1]!.click();
    await flush();
    expect(setGrantControl).not.toHaveBeenCalled();
    expect(confirm).not.toHaveBeenCalled();
  });
});

function button(text: string) {
  const target = [...host.querySelectorAll<HTMLButtonElement>('button')].find(
    (item) => item.textContent?.trim() === text,
  );
  expect(target, `missing button ${text}`).toBeDefined();
  return target!;
}
async function selectApi(id = '21') {
  const select = host.querySelector('select')!;
  select.value = id;
  select.dispatchEvent(new Event('change'));
  await flush();
}
async function setDateRange(start: string, end: string) {
  for (const [index, value] of [start, end].entries()) {
    const input = host.querySelector<HTMLInputElement>(
      `[data-date-index="${index}"]`,
    )!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await flush();
  }
}
async function setForever(value: 0 | 1) {
  host
    .querySelector<HTMLInputElement>(`input[type="radio"][value="${value}"]`)!
    .click();
  await flush();
}
const transitions = [
  'session',
  'refreshSession',
  'user',
  'permissions',
  'close',
  'app',
  'unmount',
] as const;
type Transition = (typeof transitions)[number];
async function transition(kind: Transition) {
  switch (kind) {
    case 'app': {
      vi.mocked(getAppApiList).mockResolvedValue([
        row({ id: '12', appId: '2', apiId: '22' }),
      ]);
      await open('2');
      break;
    }
    case 'close': {
      await harness.modals[0]!.close();
      break;
    }
    case 'permissions': {
      harness.state!.accessCodes = ['supply:app:query'];
      break;
    }
    case 'refreshSession': {
      harness.state!.refreshToken = '2';
      break;
    }
    case 'session': {
      harness.state!.accessToken = '2';
      break;
    }
    case 'unmount': {
      app!.unmount();
      app = undefined;
      break;
    }
    case 'user': {
      harness.state!.userInfo = { userId: '10' };
      break;
    }
  }
  await flush();
}
type ReadKind = 'detail' | 'list' | 'options';
async function pendingRead(kind: ReadKind) {
  if (kind === 'detail') {
    const pending = deferred<SupplyAppApi.App>();
    vi.mocked(getSupplyApp).mockReturnValueOnce(pending.promise);
    await open();
    return {
      resolve: () => pending.resolve({ id: '1', name: 'late-old-app' }),
      reject: pending.reject,
    };
  }
  if (kind === 'list') {
    const pending = deferred<SupplyAppApi.AppApi[]>();
    vi.mocked(getAppApiList).mockReturnValueOnce(pending.promise);
    await open();
    return {
      resolve: () => pending.resolve([row({ apiMethod: 'late-old-api' })]),
      reject: pending.reject,
    };
  }
  await open();
  const pending = deferred<Awaited<ReturnType<typeof getDataApiSimpleList>>>();
  vi.mocked(getDataApiSimpleList).mockReturnValueOnce(pending.promise);
  await click('新增');
  return {
    resolve: () => pending.resolve([{ id: '21', name: 'late-old-option' }]),
    reject: pending.reject,
  };
}

describe.each(['detail', 'list', 'options'] as const)(
  '%s lifecycle isolation',
  (kind) => {
    it.each(
      transitions.flatMap((change) => [
        { change, outcome: 'resolve' as const },
        { change, outcome: 'reject' as const },
      ]),
    )('discards $outcome after $change', async ({ change, outcome }) => {
      const pending = await pendingRead(kind);
      await transition(change);
      const callsBefore = vi.mocked(getAppApiList).mock.calls.length;
      pending[outcome]();
      await flush();
      expect(host.textContent).not.toContain('late-old');
      expect(host.textContent).not.toContain('读取失败');
      expect(getAppApiList).toHaveBeenCalledTimes(callsBefore);
      expect(createAppApi).not.toHaveBeenCalled();
      expect(setGrantControl).not.toHaveBeenCalled();
      expect(
        change === 'app' ||
          harness.modals.every(
            (modal) =>
              !modal.state.isOpen ||
              (change === 'unmount' && modal === harness.modals[0]),
          ),
      ).toBe(true);
    });
  },
);

describe('list display correctness', () => {
  it('clears list when automatic refresh fails, then allows recovery', async () => {
    await open();
    vi.mocked(getAppApiList).mockRejectedValueOnce(
      new Error('private internal payload'),
    );
    await click('整体禁用');
    expect(setGrantControl).toHaveBeenCalledExactlyOnceWith({
      id: '11',
      expectedRevision: '0',
      disabled: true,
    });
    expect(host.textContent).not.toContain('授权列表读取失败');
    expect(host.querySelectorAll('tr')).toHaveLength(0);
    expect(host.textContent).not.toContain('private');
    await harness.modals[0]!.close();
    await open();
    expect(getAppApiList).toHaveBeenCalledTimes(3);
    expect(host.querySelectorAll('tr')).toHaveLength(1);
    expect(host.textContent).not.toContain('授权列表读取失败');
    expect(host.textContent).not.toContain('重新加载授权列表');
    expect(setGrantControl).toHaveBeenCalledTimes(1);
  });
  it.each([
    [row({ appId: '2' })],
    [row({ id: undefined })],
    [row({ apiId: undefined })],
    [row({ id: Number.MAX_SAFE_INTEGER + 1 })],
    [row(), row()],
  ])('rejects incorrect or duplicate list identity %j', async (...items) => {
    vi.mocked(getAppApiList).mockResolvedValue(items);
    await open();
    expect(host.textContent).not.toContain('授权列表读取失败');
    expect(host.querySelectorAll('tr')).toHaveLength(0);
  });
  it.each([{}, { id: '2' }, { id: Number.MAX_SAFE_INTEGER + 1 }])(
    'rejects incorrect detail identity %j',
    async (value) => {
      vi.mocked(getSupplyApp).mockResolvedValue(value);
      await open();
      expect(host.textContent).not.toContain('应用详情读取失败');
      expect(getAppApiList).not.toHaveBeenCalled();
      expect(harness.modals[0]!.state.submitting).toBe(false);
    },
  );
  it.each(['-1', '01', '1e1', Number.MAX_SAFE_INTEGER + 1])(
    'rejects invalid selected app %s before reading',
    async (id) => {
      await open(id);
      expect(getSupplyApp).not.toHaveBeenCalled();
      expect(getAppApiList).not.toHaveBeenCalled();
    },
  );
});

describe('latest loading belongs to the latest request', () => {
  it.each(['resolve', 'reject'] as const)(
    'old detail %s cannot unlock a reopened modal',
    async (outcome) => {
      const old = await pendingRead('detail');
      const fresh = deferred<SupplyAppApi.App>();
      vi.mocked(getSupplyApp).mockReturnValueOnce(fresh.promise);
      await open();
      old[outcome]();
      await flush();
      expect(harness.modals[0]!.state.submitting).toBe(true);
      expect(host.textContent).not.toContain('读取失败');
      fresh.resolve({ id: 1, name: 'fresh-app' });
      await flush();
      expect(host.textContent).toContain('fresh-app');
      expect(harness.modals[0]!.state.submitting).toBe(false);
    },
  );
  it.each(['options', 'list'] as const)(
    '%s old success and failure never clear newer loading/data',
    async (kind) => {
      await open();
      const requests = [
        deferred<unknown>(),
        deferred<unknown>(),
        deferred<unknown>(),
      ];
      if (kind === 'options') {
        vi.mocked(getDataApiSimpleList).mockImplementation(
          () =>
            requests.shift()!.promise as ReturnType<
              typeof getDataApiSimpleList
            >,
        );
      } else {
        vi.mocked(getAppApiList).mockImplementation(
          () => requests.shift()!.promise as ReturnType<typeof getAppApiList>,
        );
      }
      // mock 消耗队列前保留各请求引用，验证旧请求不能结束新请求的加载状态。
      const [oldSuccess, oldFailure, latest] = [...requests];
      for (let index = 0; index < 3; index++) {
        if (kind === 'list') await open();
        else {
          if (index > 0) await harness.modals[1]!.close();
          await click('新增');
        }
      }
      const loading = () =>
        kind === 'list'
          ? host.querySelector('table')!.dataset.loading
          : host.querySelector('select')!.dataset.loading;
      oldSuccess!.resolve([]);
      oldFailure!.reject(new Error('late failure'));
      await flush();
      expect(loading()).toBe('true');
      expect(host.textContent).not.toContain('读取失败');
      latest!.resolve(
        kind === 'list'
          ? [row({ apiMethod: 'fresh_method' })]
          : [{ id: '31', name: 'fresh_option' }],
      );
      await flush();
      expect(loading()).toBe('false');
      expect(host.textContent).toContain(
        kind === 'list' ? 'fresh_method' : 'fresh_option',
      );
    },
  );
});

describe('independent ETL batch grants', () => {
  it.each([false, true])(
    'keeps CAS controls for disabled=%s',
    async (disabled) => {
      vi.mocked(getAppApiList).mockResolvedValue([
        row({
          sourceType: 'ADMIN_ETL_BATCH',
          protocolVersion: 0,
          aggregateDisabled: disabled,
        }),
      ]);
      await open();
      expect(host.textContent).toContain('ETL 批次授权');
      await click(disabled ? '重新启用' : '整体禁用');
      expect(setGrantControl).toHaveBeenCalledExactlyOnceWith({
        id: '11',
        expectedRevision: '0',
        disabled: !disabled,
      });
      expect(revokeAdminGrant).not.toHaveBeenCalled();
    },
  );

  it.each(['ADMIN', 'ADMIN_ETL_BATCH'])(
    'permanently closes %s without an enable path',
    async (sourceType) => {
      const grant = row({ sourceType, protocolVersion: 0 });
      vi.mocked(getAppApiList)
        .mockResolvedValueOnce([grant])
        .mockResolvedValue([
          {
            ...grant,
            grantStatus: 3,
            authStatus: 0,
            aggregateDisabled: true,
            aggregateRevision: '1',
          },
        ]);
      await open();
      await click('删除');
      expect(confirm).toHaveBeenCalledWith(
        expect.stringContaining('恢复访问须新增授权'),
      );
      expect(revokeAdminGrant).toHaveBeenCalledExactlyOnceWith({
        id: '11',
        expectedRevision: '0',
      });
      expect(setGrantControl).not.toHaveBeenCalled();
      expect(host.textContent).toContain('已关闭，恢复访问须重新授权');
      expect(
        [...host.querySelectorAll('button')].map((item) =>
          item.textContent?.trim(),
        ),
      ).not.toContain('重新启用');
    },
  );

  it('leaves market grants to their source lifecycle', async () => {
    vi.mocked(getAppApiList).mockResolvedValue([
      row({ sourceType: 'MARKET', protocolVersion: 2 }),
    ]);
    await open();
    expect(host.textContent).not.toContain('删除');
    expect(button('整体禁用').disabled).toBe(false);
  });

  it('rejects a revoke confirmed after session switching', async () => {
    vi.mocked(getAppApiList).mockResolvedValue([
      row({ sourceType: 'ADMIN_ETL_BATCH', protocolVersion: 0 }),
    ]);
    const pending = deferred<undefined>();
    vi.mocked(confirm).mockReturnValueOnce(pending.promise);
    await open();
    await click('删除');
    harness.state!.refreshToken = '2';
    pending.resolve(undefined);
    await flush();
    expect(revokeAdminGrant).not.toHaveBeenCalled();
  });

  it('deduplicates a pending revoke and clears unknown results without retrying', async () => {
    vi.mocked(getAppApiList).mockResolvedValue([
      row({ sourceType: 'ADMIN_ETL_BATCH', protocolVersion: 0 }),
    ]);
    const pending = deferred<boolean>();
    vi.mocked(revokeAdminGrant).mockReturnValueOnce(pending.promise);
    await open();
    const action = button('删除');
    action.click();
    action.click();
    await flush();
    expect(revokeAdminGrant).toHaveBeenCalledTimes(1);
    expect(action.disabled).toBe(true);
    pending.reject(new Error('unknown outcome'));
    await flush();
    expect(host.textContent).toContain('操作结果未确认');
    expect(host.querySelectorAll('tr')).toHaveLength(0);
    expect(revokeAdminGrant).toHaveBeenCalledTimes(1);
    expect(message.success).not.toHaveBeenCalled();
  });
});

describe('control confirmation is a checked boundary, not authority', () => {
  it.each(transitions)(
    'sends ZERO control requests after %s during confirm',
    async (change) => {
      const pending = deferred<undefined>();
      vi.mocked(confirm).mockReturnValueOnce(pending.promise);
      await open();
      await click('整体禁用');
      await transition(change);
      pending.resolve(undefined);
      await flush();
      expect(setGrantControl).toHaveBeenCalledTimes(0);
      expect(message.success).not.toHaveBeenCalled();
    },
  );
  it('does not revive a confirmation after session switches away and back', async () => {
    const pending = deferred<undefined>();
    vi.mocked(confirm).mockReturnValueOnce(pending.promise);
    await open();
    await click('整体禁用');
    harness.state!.accessToken = '2';
    harness.state!.accessToken = '1';
    await open();
    pending.resolve(undefined);
    await flush();
    expect(setGrantControl).toHaveBeenCalledTimes(0);
  });
  it('rechecks the row version after confirmation', async () => {
    const record = reactive(row());
    vi.mocked(getAppApiList).mockResolvedValue([record]);
    const pending = deferred<undefined>();
    vi.mocked(confirm).mockReturnValueOnce(pending.promise);
    await open();
    await click('整体禁用');
    record.aggregateRevision = '2';
    pending.resolve(undefined);
    await flush();
    expect(setGrantControl).toHaveBeenCalledTimes(0);
  });
  it('locks duplicate controls and add-grant while confirming; cancellation releases the lock', async () => {
    const pending = deferred<undefined>();
    vi.mocked(confirm).mockReturnValueOnce(pending.promise);
    await open();
    const action = button('整体禁用');
    action.click();
    action.click();
    button('新增').click();
    await flush();
    expect(confirm).toHaveBeenCalledTimes(1);
    expect(getDataApiSimpleList).not.toHaveBeenCalled();
    expect(setGrantControl).toHaveBeenCalledTimes(0);
    pending.reject();
    await flush();
    expect(button('整体禁用').disabled).toBe(false);
    expect(button('新增').disabled).toBe(false);
    expect(message.success).not.toHaveBeenCalled();
  });
  it.each([undefined, -1, '01', Number.MAX_SAFE_INTEGER + 1])(
    'rejects unusable row revision %s without confirmation',
    async (revision) => {
      vi.mocked(getAppApiList).mockResolvedValue([
        row({ aggregateRevision: revision }),
      ]);
      await open();
      await click('整体禁用');
      expect(confirm).not.toHaveBeenCalled();
      expect(setGrantControl).not.toHaveBeenCalled();
    },
  );
  it('preserves large grant, API, app and revision IDs through lookup and control', async () => {
    const appId = '9007199254740993';
    const id = '9007199254740995';
    const apiId = '9007199254740997';
    const revision = '9007199254740999';
    vi.mocked(getAppApiList).mockResolvedValue([
      row({ id, appId, apiId, aggregateRevision: revision }),
    ]);
    await open(appId);
    await click('整体禁用');
    expect(getSupplyApp).toHaveBeenCalledWith(appId);
    expect(getAppApiList).toHaveBeenCalledWith(appId);
    expect(setGrantControl).toHaveBeenCalledExactlyOnceWith({
      id,
      disabled: true,
      expectedRevision: revision,
    });
    expect(message.success).toHaveBeenCalledExactlyOnceWith('已整体禁用');
  });
  it('submits revision zero and re-reads authoritative state after enabling', async () => {
    vi.mocked(getAppApiList)
      .mockResolvedValueOnce([
        row({ aggregateDisabled: true, aggregateRevision: 0, authStatus: 0 }),
      ])
      .mockResolvedValue([row()]);
    await open();
    await click('重新启用');
    expect(setGrantControl).toHaveBeenCalledExactlyOnceWith({
      id: '11',
      disabled: false,
      expectedRevision: '0',
    });
    expect(getAppApiList).toHaveBeenCalledTimes(2);
    expect(message.success).toHaveBeenCalledExactlyOnceWith('已重新启用');
  });
  it('clears old state after control rejection, and never resends automatically', async () => {
    vi.mocked(setGrantControl).mockRejectedValueOnce(
      new Error('private failure'),
    );
    await open();
    await click('整体禁用');
    expect(setGrantControl).toHaveBeenCalledTimes(1);
    expect(getAppApiList).toHaveBeenCalledTimes(1);
    expect(host.textContent).toContain('操作结果未确认');
    expect(host.textContent).not.toContain('private failure');
    expect(host.querySelectorAll('tr')).toHaveLength(0);
    expect(message.success).not.toHaveBeenCalled();
  });
});

describe('add grant options and mutation ownership', () => {
  it('locks duplicate submits and controls until the grant creation completes', async () => {
    const pending = deferred<number>();
    vi.mocked(createAppApi).mockReturnValueOnce(pending.promise);
    await open();
    await click('新增');
    await selectApi();
    void harness.modals[1]!.options.onConfirm!();
    void harness.modals[1]!.options.onConfirm!();
    button('整体禁用').click();
    await flush();
    expect(createAppApi).toHaveBeenCalledTimes(1);
    expect(setGrantControl).toHaveBeenCalledTimes(0);
    expect(confirm).not.toHaveBeenCalled();
    expect(harness.modals[1]!.state.submitting).toBe(true);
    pending.resolve(123);
    await flush();
    expect(createAppApi).toHaveBeenCalledWith(
      expect.objectContaining({ appId: '1', apiId: '21', foreverFlag: 1 }),
    );
    expect(message.success).toHaveBeenCalledExactlyOnceWith('授权成功');
    expect(harness.modals[1]!.state.isOpen).toBe(false);
    expect(getAppApiList).toHaveBeenCalledTimes(2);
    expect(button('新增').disabled).toBe(false);
  });
  it('does not submit options from a failed or invalid response', async () => {
    vi.mocked(getDataApiSimpleList).mockRejectedValueOnce(
      new Error('internal'),
    );
    await open();
    await click('新增');
    await harness.modals[1]!.options.onConfirm!();
    expect(createAppApi).not.toHaveBeenCalled();
    expect(host.querySelectorAll('select option')).toHaveLength(1);
    expect(host.textContent).not.toContain('API 选项读取失败');
    expect(host.textContent).not.toContain('internal');
    await harness.modals[1]!.close();
    await click('新增');
    expect(host.textContent).not.toContain('API 选项读取失败');
    expect(host.querySelectorAll('select option')).toHaveLength(2);
  });
  it.each([undefined, Number.MAX_SAFE_INTEGER + 1, '00'])(
    'rejects malformed option ID %s',
    async (id) => {
      vi.mocked(getDataApiSimpleList).mockResolvedValue([{ id, name: 'bad' }]);
      await open();
      await click('新增');
      await harness.modals[1]!.options.onConfirm!();
      expect(createAppApi).not.toHaveBeenCalled();
      expect(host.textContent).not.toContain('API 选项读取失败');
    },
  );
  it('preserves a large API option ID in creation', async () => {
    const id = '9007199254740999';
    vi.mocked(getDataApiSimpleList).mockResolvedValue([
      { id, name: 'large-option' },
    ]);
    await open();
    await click('新增');
    await selectApi(id);
    await harness.modals[1]!.options.onConfirm!();
    await flush();
    expect(createAppApi).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({ apiId: id, appId: '1' }),
    );
  });
  it('does not submit through an old child confirmation after closing the parent', async () => {
    await open();
    await click('新增');
    await selectApi();
    await harness.modals[0]!.close();
    expect(harness.modals[1]!.state.isOpen).toBe(false);
    await harness.modals[1]!.options.onConfirm!();
    expect(createAppApi).toHaveBeenCalledTimes(0);
  });
  it('keeps an unknown creation outcome explicit and unlocks without implicit retry', async () => {
    vi.mocked(createAppApi).mockRejectedValueOnce(new Error('raw sql error'));
    await open();
    await click('新增');
    await selectApi();
    await harness.modals[1]!.options.onConfirm!();
    await flush();
    expect(host.textContent).toContain('授权提交结果未确认');
    expect(host.textContent).not.toContain('raw sql');
    expect(createAppApi).toHaveBeenCalledTimes(1);
    expect(message.success).not.toHaveBeenCalled();
    expect(harness.modals[1]!.state.submitting).toBe(false);
    expect(host.querySelectorAll('tr')).toHaveLength(0);
    expect(
      host.querySelector<HTMLButtonElement>('[data-confirm="1"]')!.disabled,
    ).toBe(true);
    await harness.modals[1]!.options.onConfirm!();
    expect(createAppApi).toHaveBeenCalledTimes(1);
    await harness.modals[1]!.close();
    await click('重新加载授权列表');
    await click('新增');
    await selectApi();
    await harness.modals[1]!.options.onConfirm!();
    await flush();
    expect(createAppApi).toHaveBeenCalledTimes(2);
    expect(getDataApiSimpleList).toHaveBeenCalledTimes(2);
  });
});

describe('管理员授权修改', () => {
  it('先读取新修订并无损提交，固定 API 身份且成功后刷新列表', async () => {
    const id = '9007199254740995';
    const appId = '9007199254740993';
    const apiId = '9007199254740997';
    const expectedRevision = '9007199254740999';
    const grant = editableGrant({ id, appId, apiId, aggregateRevision: '1' });
    vi.mocked(getAppApiList).mockResolvedValue([grant]);
    vi.mocked(getAppApi).mockResolvedValue({
      ...grant,
      aggregateRevision: expectedRevision,
      description: '最新描述',
    });
    await open(appId);
    await click('修改');
    expect(getAppApi).toHaveBeenCalledExactlyOnceWith(id);
    const select = host.querySelector('select')!;
    expect(select.disabled).toBe(true);
    expect(select.value).toBe(apiId);
    expect(host.querySelector('textarea')!.value).toBe('最新描述');
    await harness.modals[1]!.options.onConfirm!();
    await flush();
    expect(updateAppApi).toHaveBeenCalledExactlyOnceWith({
      id,
      expectedRevision,
      foreverFlag: 1,
      startDate: undefined,
      endDate: undefined,
      description: '最新描述',
    });
    expect(createAppApi).not.toHaveBeenCalled();
    expect(getDataApiSimpleList).not.toHaveBeenCalled();
    expect(getAppApiList).toHaveBeenCalledTimes(2);
    expect(message.success).toHaveBeenCalledExactlyOnceWith('授权修改成功');
    expect(harness.modals[1]!.state.isOpen).toBe(false);
  });

  it('有限期转永久时不提交旧起止日期', async () => {
    vi.mocked(getAppApiList).mockResolvedValue([editableGrant()]);
    vi.mocked(getAppApi).mockResolvedValue(
      editableGrant({
        foreverFlag: 0,
        startDate: '2026-10-01',
        endDate: '2026-10-31',
      }),
    );
    await open();
    await click('修改');
    expect(
      host.querySelector<HTMLInputElement>('[data-date-index="0"]')!.value,
    ).toBe('2026-10-01');
    await setForever(1);
    expect(host.querySelector('[data-date-index]')).toBeNull();
    await setForever(0);
    expect(
      host.querySelector<HTMLInputElement>('[data-date-index="0"]')!.value,
    ).toBe('');
    expect(
      host.querySelector<HTMLInputElement>('[data-date-index="1"]')!.value,
    ).toBe('');
    await setForever(1);
    await harness.modals[1]!.options.onConfirm!();
    expect(updateAppApi).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({
        foreverFlag: 1,
        startDate: undefined,
        endDate: undefined,
      }),
    );
  });

  it('兼容详情返回数字 ID，有限期按日期提交且接受修订零', async () => {
    const grant = editableGrant({
      id: 11,
      appId: 1,
      apiId: 21,
      aggregateRevision: 0,
      foreverFlag: 0,
      startDate: '2026-10-01',
      endDate: '2026-10-31',
    });
    vi.mocked(getAppApiList).mockResolvedValue([grant]);
    vi.mocked(getAppApi).mockResolvedValue(grant);
    await open();
    await click('修改');
    expect(host.querySelector('select')!.value).toBe('21');
    await harness.modals[1]!.options.onConfirm!();
    expect(updateAppApi).toHaveBeenCalledExactlyOnceWith({
      id: '11',
      expectedRevision: '0',
      foreverFlag: 0,
      startDate: '2026-10-01',
      endDate: '2026-10-31',
      description: undefined,
    });
  });

  it.each([
    ['invalid-date', '2026-10-31'],
    ['2026-10-31', '2026-10-01'],
    ['', '2026-10-31'],
  ])('拒绝无效有限期 %s 到 %s', async (start, end) => {
    vi.mocked(getAppApiList).mockResolvedValue([editableGrant()]);
    await open();
    await click('修改');
    await setForever(0);
    await setDateRange(start, end);
    await harness.modals[1]!.options.onConfirm!();
    expect(updateAppApi).not.toHaveBeenCalled();
    expect(message.warning).toHaveBeenCalled();
    expect(harness.modals[1]!.state.isOpen).toBe(true);
  });

  it.each([
    { sourceType: 'ADMIN_ETL_BATCH', protocolVersion: 0 },
    { sourceType: 'MARKET', protocolVersion: 2 },
    { sourceType: 'ADMIN', protocolVersion: 2 },
    { sourceType: 'ADMIN', protocolVersion: 0, grantStatus: 3 },
  ])('不提供非普通管理员或终态授权的修改入口 %j', async (overrides) => {
    vi.mocked(getAppApiList).mockResolvedValue([editableGrant(overrides)]);
    await open();
    expect(host.textContent).not.toContain('修改');
    expect(getAppApi).not.toHaveBeenCalled();
    expect(updateAppApi).not.toHaveBeenCalled();
  });

  it.each([
    { id: '99' },
    { appId: '2' },
    { apiId: '99' },
    { sourceType: 'ADMIN_ETL_BATCH' },
    { aggregateRevision: undefined },
  ])(
    '读取到身份、来源或修订无效 %j 时阻止修改，重试可恢复',
    async (overrides) => {
      vi.mocked(getAppApiList).mockResolvedValue([editableGrant()]);
      vi.mocked(getAppApi)
        .mockResolvedValueOnce(editableGrant(overrides))
        .mockResolvedValue(editableGrant({ aggregateRevision: '2' }));
      await open();
      await click('修改');
      expect(host.textContent).not.toContain('授权详情读取失败');
      await harness.modals[1]!.options.onConfirm!();
      expect(updateAppApi).not.toHaveBeenCalled();
      await harness.modals[1]!.close();
      await click('修改');
      await harness.modals[1]!.options.onConfirm!();
      expect(updateAppApi).toHaveBeenCalledExactlyOnceWith(
        expect.objectContaining({ id: '11', expectedRevision: '2' }),
      );
    },
  );

  it('未知修改结果清空列表并锁定再次提交，关闭后可核查而不自动重发', async () => {
    vi.mocked(getAppApiList).mockResolvedValue([editableGrant()]);
    vi.mocked(updateAppApi).mockRejectedValueOnce(new Error('private update'));
    await open();
    await click('修改');
    await harness.modals[1]!.options.onConfirm!();
    await flush();
    expect(host.textContent).toContain('授权修改结果未确认');
    expect(host.textContent).not.toContain('private update');
    expect(host.querySelectorAll('tr')).toHaveLength(0);
    expect(message.success).not.toHaveBeenCalled();
    expect(harness.modals[1]!.state.submitting).toBe(false);
    expect(
      host.querySelector<HTMLButtonElement>('[data-confirm="1"]')!.disabled,
    ).toBe(true);
    await harness.modals[1]!.options.onConfirm!();
    expect(updateAppApi).toHaveBeenCalledTimes(1);
    expect(getAppApiList).toHaveBeenCalledTimes(1);
    await harness.modals[1]!.close();
    await click('重新加载授权列表');
    expect(getAppApiList).toHaveBeenCalledTimes(2);
    expect(host.querySelectorAll('tr')).toHaveLength(1);
    expect(updateAppApi).toHaveBeenCalledTimes(1);
  });

  it('修改提交期间阻止重复提交和授权开关，结束后释放互斥', async () => {
    const pending = deferred<boolean>();
    vi.mocked(getAppApiList).mockResolvedValue([editableGrant()]);
    vi.mocked(updateAppApi).mockReturnValueOnce(pending.promise);
    await open();
    await click('修改');
    void harness.modals[1]!.options.onConfirm!();
    void harness.modals[1]!.options.onConfirm!();
    await flush();
    button('整体禁用').click();
    expect(updateAppApi).toHaveBeenCalledTimes(1);
    expect(setGrantControl).not.toHaveBeenCalled();
    expect(button('新增').disabled).toBe(true);
    expect(harness.modals[1]!.state.submitting).toBe(true);
    pending.resolve(true);
    await flush();
    expect(button('新增').disabled).toBe(false);
    expect(getAppApiList).toHaveBeenCalledTimes(2);
  });

  it.each(['resolve', 'reject'] as const)(
    '旧修改详情 %s 不覆盖重新打开后的新详情',
    async (outcome) => {
      const old = deferred<SupplyAppApi.AppApi>();
      vi.mocked(getAppApiList).mockResolvedValue([editableGrant()]);
      vi.mocked(getAppApi)
        .mockReturnValueOnce(old.promise)
        .mockResolvedValueOnce(
          editableGrant({ aggregateRevision: '2', description: '新详情' }),
        );
      await open();
      await click('修改');
      await harness.modals[1]!.close();
      await click('修改');
      if (outcome === 'resolve')
        old.resolve(editableGrant({ description: 'late-edit' }));
      else old.reject(new Error('late edit failure'));
      await flush();
      expect(host.querySelector('textarea')!.value).toBe('新详情');
      expect(host.querySelector('select')!.dataset.loading).toBe('false');
      expect(host.textContent).not.toContain('授权详情读取失败');
      await harness.modals[1]!.options.onConfirm!();
      expect(updateAppApi).toHaveBeenCalledExactlyOnceWith(
        expect.objectContaining({
          expectedRevision: '2',
          description: '新详情',
        }),
      );
    },
  );

  it.each(
    (['read', 'update'] as const).flatMap((stage) =>
      transitions.flatMap((change) => [
        { stage, change, outcome: 'resolve' as const },
        { stage, change, outcome: 'reject' as const },
      ]),
    ),
  )(
    '$stage 请求 $outcome 在 $change 后失效',
    async ({ stage, change, outcome }) => {
      const pendingRead = deferred<SupplyAppApi.AppApi>();
      const pendingUpdate = deferred<boolean>();
      vi.mocked(getAppApiList).mockResolvedValue([editableGrant()]);
      if (stage === 'read')
        vi.mocked(getAppApi).mockReturnValueOnce(pendingRead.promise);
      else vi.mocked(updateAppApi).mockReturnValueOnce(pendingUpdate.promise);
      await open();
      await click('修改');
      if (stage === 'update') {
        void harness.modals[1]!.options.onConfirm!();
        await flush();
      }
      await transition(change);
      const reads = vi.mocked(getAppApiList).mock.calls.length;
      if (outcome === 'resolve') {
        if (stage === 'read')
          pendingRead.resolve(editableGrant({ description: 'late-edit' }));
        else pendingUpdate.resolve(true);
      } else if (stage === 'read')
        pendingRead.reject(new Error('late read failure'));
      else pendingUpdate.reject(new Error('late update failure'));
      await flush();
      expect(host.querySelector('textarea')?.value).not.toBe('late-edit');
      expect(host.textContent).not.toContain('授权详情读取失败');
      expect(host.textContent).not.toContain('授权修改结果未确认');
      expect(message.success).not.toHaveBeenCalled();
      expect(getAppApiList).toHaveBeenCalledTimes(reads);
      expect(updateAppApi).toHaveBeenCalledTimes(stage === 'read' ? 0 : 1);
    },
  );
});

describe.each(['control', 'create'] as const)(
  '%s completion never escapes its identity',
  (kind) => {
    it.each(
      transitions.flatMap((change) => [
        { change, outcome: 'resolve' as const },
        { change, outcome: 'reject' as const },
      ]),
    )('ignores $outcome after $change', async ({ change, outcome }) => {
      const pending = deferred<number>();
      if (kind === 'control')
        vi.mocked(setGrantControl).mockReturnValueOnce(pending.promise);
      else vi.mocked(createAppApi).mockReturnValueOnce(pending.promise);
      await open();
      if (kind === 'control') await click('整体禁用');
      else {
        await click('新增');
        await selectApi();
        void harness.modals[1]!.options.onConfirm!();
        await flush();
      }
      await transition(change);
      const before = vi.mocked(getAppApiList).mock.calls.length;
      if (outcome === 'resolve') pending.resolve(123);
      else pending.reject(new Error('late rejected mutation'));
      await flush();
      expect(message.success).not.toHaveBeenCalled();
      expect(host.textContent).not.toContain('操作结果未确认');
      expect(host.textContent).not.toContain('授权提交结果未确认');
      expect(getAppApiList).toHaveBeenCalledTimes(before);
      expect(
        kind === 'control' ? setGrantControl : createAppApi,
      ).toHaveBeenCalledTimes(1);
    });
  },
);

describe('exact read and write permission boundary', () => {
  it.each([[], ['supply:app:grant'], ['supply:app:update']])(
    'never reads app details without query %j',
    async (...codes) => {
      harness.state!.accessCodes = codes;
      await open();
      expect(getSupplyApp).not.toHaveBeenCalled();
      expect(getAppApiList).not.toHaveBeenCalled();
      expect(getDataApiSimpleList).not.toHaveBeenCalled();
    },
  );
  it('allows query-only users to read grants but disables mutations', async () => {
    harness.state!.accessCodes = ['supply:app:query'];
    await open();
    expect(getAppApiList).toHaveBeenCalledExactlyOnceWith('1');
    expect(button('整体禁用').disabled).toBe(true);
    expect(
      [...host.querySelectorAll('button')].some((item) =>
        ['修改', '删除', '新增'].includes(item.textContent?.trim() ?? ''),
      ),
    ).toBe(false);
    expect(getDataApiSimpleList).not.toHaveBeenCalled();
  });
  it.each(['supply:app:grant', 'supply:app:update'])(
    'permits normal management with query and %s',
    async (code) => {
      harness.state!.accessCodes = ['supply:app:query', code];
      await open();
      await click('整体禁用');
      expect(setGrantControl).toHaveBeenCalledTimes(1);
      await click('新增');
      expect(getDataApiSimpleList).toHaveBeenCalledExactlyOnceWith(1, 'grant');
    },
  );
  it('rejects an old DOM control synchronously after permission removal', async () => {
    await open();
    const control = button('整体禁用');
    harness.state!.accessCodes = ['supply:app:query'];
    control.click();
    await flush();
    expect(confirm).not.toHaveBeenCalled();
    expect(setGrantControl).not.toHaveBeenCalled();
  });
});

describe('child lifetime and newer mutation locks', () => {
  it('closing options invalidates its late read without closing the parent', async () => {
    const pending = await pendingRead('options');
    await harness.modals[1]!.close();
    pending.resolve();
    await flush();
    expect(harness.modals[0]!.state.isOpen).toBe(true);
    expect(host.textContent).not.toContain('late-old-option');
  });
  it('clears the grant modal synchronously when the parent closes', async () => {
    const pending = await pendingRead('options');
    const closing = harness.modals[0]!.close();
    expect(harness.modals[1]!.state.isOpen).toBe(false);
    await closing;
    pending.resolve();
    await flush();
    expect(host.textContent).not.toContain('late-old-option');
  });
  it.each(['resolve', 'reject'] as const)(
    'old creation %s cannot unlock a new creation after reopening',
    async (outcome) => {
      const old = deferred<number>();
      const fresh = deferred<number>();
      vi.mocked(createAppApi)
        .mockReturnValueOnce(old.promise)
        .mockReturnValueOnce(fresh.promise);
      await open();
      await click('新增');
      await selectApi();
      void harness.modals[1]!.options.onConfirm!();
      await flush();
      await harness.modals[0]!.close();
      await open();
      await click('新增');
      await selectApi();
      void harness.modals[1]!.options.onConfirm!();
      await flush();
      if (outcome === 'resolve') old.resolve(1);
      else old.reject();
      await flush();
      expect(message.success).not.toHaveBeenCalled();
      expect(harness.modals[1]!.state.submitting).toBe(true);
      expect(button('整体禁用').disabled).toBe(true);
      expect(getAppApiList).toHaveBeenCalledTimes(2);
      fresh.resolve(2);
      await flush();
      expect(message.success).toHaveBeenCalledTimes(1);
      expect(getAppApiList).toHaveBeenCalledTimes(3);
    },
  );
  it('does not refresh or toast for creation completing after its child closed', async () => {
    const pending = deferred<number>();
    vi.mocked(createAppApi).mockReturnValueOnce(pending.promise);
    await open();
    await click('新增');
    await selectApi();
    void harness.modals[1]!.options.onConfirm!();
    await flush();
    await harness.modals[1]!.close();
    expect(button('整体禁用').disabled).toBe(true);
    pending.resolve(1);
    await flush();
    expect(message.success).not.toHaveBeenCalled();
    expect(getAppApiList).toHaveBeenCalledTimes(1);
    expect(button('整体禁用').disabled).toBe(false);
  });
});

describe('独立应用详情页面生命周期', () => {
  const changes = ['app', 'session', 'permissions', 'back', 'unmount'] as const;
  type EmbeddedChange = (typeof changes)[number];

  async function changeEmbedded(
    kind: EmbeddedChange,
    props: { appId: SupplyAppApi.Id },
  ) {
    if (kind === 'app') {
      vi.mocked(getAppApiList).mockResolvedValue([
        row({ id: '12', appId: '2', apiId: '22' }),
      ]);
      props.appId = '2';
    } else if (kind === 'session') {
      harness.state!.refreshToken = '2';
    } else if (kind === 'permissions') {
      harness.state!.accessCodes = ['supply:app:grant'];
    } else if (kind === 'back') {
      await click('返回');
    } else {
      app!.unmount();
      app = undefined;
    }
    await flush();
  }

  it('路由应用变化后旧详情不能覆盖新应用或提前结束加载', async () => {
    const old = deferred<SupplyAppApi.App>();
    const fresh = deferred<SupplyAppApi.App>();
    vi.mocked(getSupplyApp)
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(fresh.promise);
    const { props } = await mountEmbedded();
    await changeEmbedded('app', props);
    expect(getSupplyApp).toHaveBeenNthCalledWith(2, '2');
    old.resolve({ id: '1', name: 'late-old-app' });
    await flush();
    expect(host.textContent).not.toContain('late-old-app');
    expect(host.querySelector('[role="status"]')).not.toBeNull();
    expect(getAppApiList).not.toHaveBeenCalled();
    fresh.resolve({ id: '2', name: 'fresh-app' });
    await flush();
    expect(host.textContent).toContain('fresh-app');
    expect(host.querySelector('[role="status"]')).toBeNull();
    expect(getAppApiList).toHaveBeenCalledExactlyOnceWith('2');
  });

  it('返回立即撤销尚未确认的授权控制', async () => {
    const { back } = await mountEmbedded();
    const pending = deferred<undefined>();
    vi.mocked(confirm).mockReturnValueOnce(pending.promise);
    await click('整体禁用');
    await click('返回');
    pending.resolve(undefined);
    await flush();
    expect(back).toHaveBeenCalledTimes(1);
    expect(setGrantControl).not.toHaveBeenCalled();
    expect(message.success).not.toHaveBeenCalled();
  });

  it.each(
    (['control', 'create'] as const).flatMap((kind) =>
      (['app', 'unmount'] as const).flatMap((change) => [
        { kind, change, outcome: 'resolve' as const },
        { kind, change, outcome: 'reject' as const },
      ]),
    ),
  )(
    '$kind 请求 $outcome 在 $change 后不刷新列表或提示成功',
    async ({ kind, change, outcome }) => {
      const { props } = await mountEmbedded();
      const pending = deferred<number>();
      if (kind === 'control') {
        vi.mocked(setGrantControl).mockReturnValueOnce(pending.promise);
        await click('整体禁用');
      } else {
        vi.mocked(createAppApi).mockReturnValueOnce(pending.promise);
        await click('新增');
        await selectApi();
        void harness.modals[1]!.options.onConfirm!();
        await flush();
      }
      await changeEmbedded(change, props);
      const reads = vi.mocked(getAppApiList).mock.calls.length;
      if (outcome === 'resolve') pending.resolve(123);
      else pending.reject(new Error('late mutation failure'));
      await flush();
      expect(message.success).not.toHaveBeenCalled();
      expect(host.textContent).not.toContain('结果未确认');
      expect(getAppApiList).toHaveBeenCalledTimes(reads);
      expect(
        kind === 'control' ? setGrantControl : createAppApi,
      ).toHaveBeenCalledTimes(1);
    },
  );
});
