import type { App, PropType } from 'vue';

import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/dataforge/etl';

import RecordPage from './index.vue';

vi.mock('./target-batches.vue', () => ({
  default: defineComponent({ render: () => null }),
}));
vi.mock('./quality-detail.vue', () => ({
  default: defineComponent({ render: () => null }),
}));
vi.mock('./file-outputs.vue', () => ({
  default: defineComponent({ render: () => null }),
}));
vi.mock('./runtime-observation.vue', () => ({
  default: defineComponent({
    props: ['metrics', 'nodeRecords'],
    setup: (props) => () =>
      h(
        'div',
        { 'data-testid': 'runtime-observation' },
        props.metrics
          ? `${props.metrics.elapsedMillis}:${props.nodeRecords.length}`
          : 'none',
      ),
  }),
}));

interface Action {
  auth?: string[];
  disabled?: boolean;
  ifShow?: boolean;
  label: string;
  onClick?: () => Promise<void>;
  popConfirm?: { confirm: () => Promise<void> };
}
const harness = vi.hoisted(() => ({
  access: { accessToken: '1', refreshToken: undefined as string | undefined },
  actions: [] as Action[],
  permissions: [
    'dataforge:etl:run',
    'dataforge:etl:resolve',
    'dataforge:etl:delete',
  ],
  rows: [] as DataforgeEtlApi.EtlRecord[],
  modal: {} as { cancel: () => void; ok: () => Promise<void> },
  query: vi.fn(),
  success: vi.fn(),
  warning: vi.fn(),
}));
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push: vi.fn() }),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => ({ userInfo: { userId: 1 } }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.permissions.includes(code)),
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
vi.mock('@vben/utils', () => ({
  formatDateTime: (value: unknown) => String(value ?? ''),
}));
vi.mock('#/locales', () => ({ $t: () => '删除' }));
vi.mock('#/api/request', () => ({ requestClient: {} }));
vi.mock('../data', async (importOriginal) => ({
  // 保留真实的状态选项与 optionLabel/optionColor，仅替换依赖 vxe 适配器的列定义
  ...(await importOriginal<typeof import('../data')>()),
  useRecordGridColumns: () => [],
  useRecordGridFormSchema: () => [],
}));
vi.mock('#/adapter/vxe-table', () => ({
  useVbenVxeGrid: () => [
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h(
            'div',
            harness.rows.flatMap((row) => [
              slots.runStatus?.({ row }),
              slots.actions?.({ row }),
            ]),
          ),
    }),
    { query: harness.query },
  ],
  TableAction: defineComponent({
    props: {
      actions: { type: Array as PropType<Action[]>, default: () => [] },
    },
    setup: (props) => () => {
      harness.actions = props.actions;
      return h(
        'div',
        props.actions
          .filter(
            (action) =>
              action.ifShow !== false &&
              (!action.auth ||
                action.auth.some((code) => harness.permissions.includes(code))),
          )
          .map((action) =>
            h(
              'button',
              { disabled: action.disabled, onClick: action.onClick },
              action.label,
            ),
          ),
      );
    },
  }),
}));
vi.mock('antdv-next', () => {
  const slot = (tag: string) =>
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h(tag, slots.default?.()),
    });
  const field = (tag: string) =>
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup:
        (props, { emit, attrs }) =>
        () =>
          h(tag, {
            ...attrs,
            value: props.value,
            onInput: (event: Event) =>
              emit('update:value', (event.target as HTMLInputElement).value),
          }),
    });
  return {
    Alert: slot('div'),
    Button: slot('button'),
    Tag: slot('span'),
    // 按列渲染 bodyCell，并把 expandedRowRender 当作已展开行渲染，便于断言诊断列与节点日志
    Table: defineComponent({
      props: {
        dataSource: {
          type: Array as PropType<Record<string, unknown>[]>,
          default: () => [],
        },
        columns: {
          type: Array as PropType<{ key: string }[]>,
          default: () => [],
        },
      },
      setup:
        (props, { slots }) =>
        () =>
          h(
            'table',
            props.dataSource.map((record, index) =>
              h('tr', [
                ...props.columns.map((column) =>
                  h(
                    'td',
                    { 'data-key': column.key },
                    slots.bodyCell?.({
                      column,
                      record,
                      index,
                      text: record[column.key],
                    }),
                  ),
                ),
                h(
                  'td',
                  { 'data-key': 'expanded' },
                  slots.expandedRowRender?.({
                    record,
                    index,
                    indent: 0,
                    expanded: true,
                  }),
                ),
              ]),
            ),
          ),
    }),
    Input: Object.assign(field('input'), { TextArea: field('textarea') }),
    Checkbox: defineComponent({
      props: ['checked'],
      emits: ['update:checked'],
      setup:
        (props, { emit, slots }) =>
        () =>
          h('label', [
            h('input', {
              type: 'checkbox',
              checked: props.checked,
              onChange: (event: Event) =>
                emit(
                  'update:checked',
                  (event.target as HTMLInputElement).checked,
                ),
            }),
            slots.default?.(),
          ]),
    }),
    Select: field('select'),
    message: { success: harness.success, warning: harness.warning },
    Modal: defineComponent({
      props: ['open', 'title'],
      emits: ['update:open'],
      setup:
        (props, { attrs, slots }) =>
        () => {
          if (!props.open) return null;
          harness.modal = {
            ok: attrs.onOk as () => Promise<void>,
            cancel: attrs.onCancel as () => void,
          };
          return h('aside', [h('h2', props.title), slots.default?.()]);
        },
    }),
  };
});
vi.mock('#/api/dataforge/etl', () => ({
  deleteEtlRecord: vi.fn(),
  getEtlNodeRecords: vi.fn(),
  getEtlRecordLog: vi.fn(),
  getEtlRecordPage: vi.fn(),
  resolveEtlCommand: vi.fn(),
  resolveUnknownEtlRecord: vi.fn(),
  stopEtlCommand: vi.fn(),
  stopEtlRecord: vi.fn(),
}));

const row: DataforgeEtlApi.EtlRecord = {
  id: '9007199254740993',
  pipelineId: '9007199254740995',
  runStatus: 4,
  occupiesSlot: true,
  reviewRequired: true,
  effectState: 'PARTIAL',
  rowVersion: '9007199254740997',
};
let app: App;
let host: HTMLDivElement;
async function flush() {
  await Promise.resolve();
  await nextTick();
  await Promise.resolve();
  await nextTick();
}
async function mountPage() {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(RecordPage);
  app.directive('access', {});
  app.mount(host);
  await flush();
}
function action(label: string) {
  const result = harness.actions.find((item) => item.label === label);
  if (!result) throw new Error(`Missing action ${label}`);
  return result;
}
function enter(selector: string, value: string) {
  const input = host.querySelector<HTMLInputElement>(selector);
  if (!input) throw new Error(`Missing field ${selector}`);
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
}
function check(index: number) {
  const input = host.querySelectorAll<HTMLInputElement>(
    'aside input[type="checkbox"]',
  )[index];
  if (!input) throw new Error('Missing confirmation');
  input.checked = true;
  input.dispatchEvent(new Event('change', { bubbles: true }));
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({ accessToken: '1', refreshToken: undefined });
  harness.permissions = [
    'dataforge:etl:run',
    'dataforge:etl:resolve',
    'dataforge:etl:delete',
  ];
  harness.rows = [{ ...row }];
  harness.actions = [];
  vi.mocked(api.getEtlRecordLog).mockResolvedValue({
    ...row,
    rowVersion: '9007199254740999',
  });
  vi.mocked(api.getEtlNodeRecords).mockResolvedValue([]);
  vi.mocked(api.resolveEtlCommand).mockResolvedValue({
    recordId: row.id!,
    replayed: false,
    runStatus: 4,
    stopRequested: false,
  });
});
afterEach(() => {
  app?.unmount();
  host?.remove();
  vi.useRealTimers();
});

describe('etl execution records', () => {
  it('keeps partial occupied results under independent review and forbids deletion', async () => {
    await mountPage();
    expect(host.textContent).toContain('结果待核查');
    expect(action('登记核查结论').auth).toEqual(['dataforge:etl:resolve']);
    expect(action('删除').ifShow).toBe(false);
  });

  it('loads the latest version and requires both proofs before submitting lossless commands', async () => {
    await mountPage();
    await action('登记核查结论').onClick?.();
    await flush();
    expect(api.getEtlRecordLog).toHaveBeenCalledWith('9007199254740993');
    expect(host.textContent).toContain('9007199254740999');
    check(0);
    enter('textarea', '已核查目标数据');
    enter('input[placeholder*="证据"]', 'ticket:review-123');
    await flush();
    await harness.modal.ok();
    expect(api.resolveEtlCommand).not.toHaveBeenCalled();
    check(1);
    await flush();
    await harness.modal.ok();
    expect(api.resolveEtlCommand).toHaveBeenCalledWith({
      recordId: '9007199254740993',
      requestId: expect.any(String),
      expectedVersion: '9007199254740999',
      confirmedStopped: true,
      outputReviewed: true,
      outcome: 'FAILED',
      reason: '已核查目标数据',
      evidenceRef: 'ticket:review-123',
    });
  });

  it('reuses the same review command after a lost response', async () => {
    vi.mocked(api.resolveEtlCommand).mockRejectedValueOnce(
      new Error('response lost'),
    );
    await mountPage();
    await action('登记核查结论').onClick?.();
    await flush();
    check(0);
    check(1);
    enter('textarea', '核查完成');
    enter('input[placeholder*="证据"]', 'ticket:123');
    await flush();
    await expect(harness.modal.ok()).rejects.toThrow('response lost');
    await harness.modal.ok();
    const [first, second] = vi.mocked(api.resolveEtlCommand).mock.calls;
    expect(second?.[0]).toEqual(first?.[0]);
    expect(first?.[0].requestId).toBeTruthy();
  });

  it('discards a previous session review response', async () => {
    let finish!: (value: DataforgeEtlApi.EtlRecord) => void;
    vi.mocked(api.getEtlRecordLog).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    await mountPage();
    const pending = action('登记核查结论').onClick?.();
    harness.access.refreshToken = '2';
    await flush();
    finish({ ...row });
    await pending;
    await flush();
    expect(host.querySelector('aside')).toBeNull();
    expect(api.resolveEtlCommand).not.toHaveBeenCalled();
  });

  it('stops the selected run with stable identity and only reports acceptance', async () => {
    harness.rows = [{ ...row, runStatus: 2, reviewRequired: false }];
    vi.mocked(api.stopEtlCommand)
      .mockRejectedValueOnce(new Error('response lost'))
      .mockResolvedValue({
        recordId: row.id!,
        replayed: true,
        runStatus: 2,
        stopRequested: true,
      });
    await mountPage();
    await expect(action('停止').popConfirm!.confirm()).rejects.toThrow(
      'response lost',
    );
    await action('停止').popConfirm!.confirm();
    const [first, retry] = vi.mocked(api.stopEtlCommand).mock.calls;
    expect(first?.[0]).toEqual({
      recordId: row.id,
      requestId: expect.any(String),
    });
    expect(retry?.[0]).toEqual(first?.[0]);
    expect(harness.success).toHaveBeenCalledWith(
      expect.stringContaining('待确认'),
    );
  });

  it('shows node duration, throughput, slowest marker and per-node logs for diagnosis', async () => {
    vi.mocked(api.getEtlNodeRecords).mockResolvedValue([
      {
        id: '9007199254741101',
        nodeNo: 'read',
        nodeName: '读取源表',
        runStatus: 3,
        startTime: 1000,
        endTime: 3000,
        readCount: 500,
        writeCount: 500,
        portCounts: { pass: 499, reject: '9007199254740993' },
      },
      {
        id: '9007199254741102',
        nodeNo: 'write',
        nodeName: '写入目标',
        runStatus: 4,
        startTime: 3000,
        endTime: 8000,
        readCount: 500,
        writeCount: 100,
        portCounts: {},
        fileOutputState: 'CLEANUP_PENDING',
        log: 'ERROR duplicate key on target',
      },
    ]);
    await mountPage();
    await action('日志').onClick?.();
    await flush();
    const cells = (key: string) =>
      [
        ...host.querySelectorAll<HTMLElement>(`aside td[data-key="${key}"]`),
      ].map((cell) => cell.textContent?.replaceAll(/\s+/g, ' ').trim());
    expect(host.textContent).toContain(
      '节点 2 个 · 已完成 2 个 · 最慢节点：写入目标 5.0 s',
    );
    expect(cells('runStatus')).toEqual(['成功', '失败']);
    expect(cells('durationMs')).toEqual(['2.0 s', '5.0 s']);
    expect(cells('throughputPerSecond')).toEqual(['250.0', '20.0']);
    expect(cells('nodeName')).toEqual(['读取源表', '写入目标最慢']);
    expect(cells('portCounts')).toEqual([
      'pass: 499；reject: 9007199254740993',
      '无数据端口输出',
    ]);
    expect(cells('fileOutputState')).toEqual(['-', '临时文件待清理']);
    expect(cells('expanded')).toEqual([
      '（暂无节点日志）',
      'ERROR duplicate key on target',
    ]);
  });

  it('uses live sample counts only for running nodes and passes the snapshot to the observation panel', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(10_000);
    vi.mocked(api.getEtlRecordLog).mockResolvedValue({
      ...row,
      runStatus: 2,
      reviewRequired: false,
      runtimeMetrics: {
        elapsedMillis: 4000,
        finished: false,
        transferredRows: 1200,
        transferredBytes: 0,
        bufferedBytes: 0,
        nodes: [
          { nodeNo: 'read', readCount: 0, writeCount: 800 },
          { nodeNo: 'write', readCount: 400, writeCount: 0 },
        ],
        edges: [],
        series: [],
      },
    });
    vi.mocked(api.getEtlNodeRecords).mockResolvedValue([
      {
        id: '9007199254741201',
        nodeNo: 'read',
        nodeName: '读取源表',
        runStatus: 2,
        startTime: 6000,
      },
      {
        id: '9007199254741202',
        nodeNo: 'write',
        nodeName: '写入目标',
        runStatus: 3,
        startTime: 6000,
        endTime: 8000,
        readCount: 400,
        writeCount: 400,
      },
    ]);
    await mountPage();
    await action('日志').onClick?.();
    await flush();
    const cells = (key: string) =>
      [
        ...host.querySelectorAll<HTMLElement>(`aside td[data-key="${key}"]`),
      ].map((cell) => cell.textContent?.replaceAll(/\s+/g, ' ').trim());
    expect(cells('durationMs')).toEqual(['4.0 s （进行中）', '2.0 s']);
    expect(cells('throughputPerSecond')).toEqual(['200.0', '200.0']);
    expect(
      host.querySelector('[data-testid="runtime-observation"]')?.textContent,
    ).toBe('4000:2');
  });

  it('continues polling held partial results and stops after unmount', async () => {
    vi.useFakeTimers();
    await mountPage();
    await action('日志').onClick?.();
    await flush();
    expect(api.getEtlRecordLog).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(5000);
    await flush();
    expect(api.getEtlRecordLog).toHaveBeenCalledTimes(2);
    app.unmount();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(api.getEtlRecordLog).toHaveBeenCalledTimes(2);
    app = undefined as unknown as App;
  });
});
