import type { App, PropType } from 'vue';

import type { QualityTaskInstanceApi } from '#/api/governance/quality/instance';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/governance/quality/instance';

import InstancePage from './index.vue';

interface Action {
  auth?: string[];
  ifShow?: boolean;
  label: string;
  onClick?: () => void;
}
const harness = vi.hoisted(() => ({
  access: { accessToken: '1', refreshToken: undefined as string | undefined },
  grid: undefined as any,
  permissions: [] as string[],
  push: vi.fn(),
  query: vi.fn(),
  route: { query: {} as Record<string, string> },
  rows: [] as QualityTaskInstanceApi.Instance[],
}));
vi.mock('vue-router', () => ({
  useRoute: () => harness.route,
  useRouter: () => ({ push: harness.push }),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
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
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: { VIEW: 'view' },
  useVbenVxeGrid: (options: unknown) => {
    harness.grid = options;
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h(
              'div',
              harness.rows.map((row) =>
                h('div', { 'data-row': String(row.id) }, [
                  slots.instanceStatus?.({ row }),
                  slots.etlSource?.({ row }),
                  slots.actions?.({ row }),
                ]),
              ),
            ),
      }),
      { query: harness.query },
    ];
  },
  TableAction: defineComponent({
    props: {
      actions: { type: Array as PropType<Action[]>, default: () => [] },
    },
    setup: (props) => () =>
      h(
        'span',
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
              { 'data-action': action.label, onClick: action.onClick },
              action.label,
            ),
          ),
      ),
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
  return {
    Button: defineComponent({
      setup:
        (_, { attrs, slots }) =>
        () =>
          h('button', attrs, slots.default?.()),
    }),
    Descriptions: slot('section'),
    DescriptionsItem: defineComponent({
      props: ['label', 'span'],
      setup:
        (props, { slots }) =>
        () =>
          h('div', { 'data-label': props.label }, slots.default?.()),
    }),
    Modal: defineComponent({
      props: ['open', 'title'],
      setup:
        (props, { slots }) =>
        () =>
          props.open ? h('aside', slots.default?.()) : null,
    }),
    Tag: slot('span'),
  };
});
vi.mock('#/api/governance/quality/instance', () => ({
  getTaskInstance: vi.fn(),
  getTaskInstancePage: vi.fn(),
}));

const etlBatch: QualityTaskInstanceApi.EtlBatch = {
  recordId: '9007199254740993',
  targetBatchId: '9007199254740994',
  sinkNodeId: 'sink-1',
  qualityNodeId: 'quality-1',
  qualityBatchId: 'quality-batch-1',
  inlineReportId: 40,
  rowCount: 1000,
  admittedRowCount: 950,
  allowWarnings: true,
  state: 'QUEUED',
  runId: 'run-1',
};
const etlInstance: QualityTaskInstanceApi.Instance = {
  id: '9007199254740995',
  instanceName: '订单质检-ETL批次质检',
  taskName: '订单质检',
  runType: 'ETL_TARGET_BATCH',
  instanceStatus: 'QUEUED',
  etlBatch,
};

let app: App | undefined;
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
  app = createApp(InstancePage);
  app.mount(host);
  await flush();
}
function modal() {
  return host.querySelector('aside');
}
function item(label: string) {
  return modal()
    ?.querySelector(`[data-label="${label}"]`)
    ?.textContent?.replaceAll(/\s+/g, ' ')
    .trim();
}
function modalButton(text: string) {
  return [...(modal()?.querySelectorAll('button') ?? [])].find((button) =>
    button.textContent?.includes(text),
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({ accessToken: '1', refreshToken: undefined });
  harness.permissions = [
    'governance:quality-task-instance:query',
    'dataforge:etl:query',
  ];
  harness.route = { query: {} };
  harness.rows = [];
  harness.grid = undefined;
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('治理任务实例的 ETL 来源', () => {
  it('详情展示 ETL 来源，检测未完成不显示为零，并以无损编号跳转 ETL 运行', async () => {
    harness.route = { query: { instanceId: '9007199254740995' } };
    vi.mocked(api.getTaskInstance).mockResolvedValue(etlInstance);
    await mountPage();

    expect(api.getTaskInstance).toHaveBeenCalledWith('9007199254740995');
    expect(item('运行类型')).toBe('ETL 批次质检');
    expect(item('状态')).toBe('排队中');
    expect(item('检测总数')).toBe('-');
    expect(item('通过率')).toBe('-');
    expect(item('运行编号')).toBe('9007199254740993');
    expect(item('目标批次')).toBe('9007199254740994');
    expect(item('受理状态')).toBe('已受理');
    expect(item('落库行数')).toBe('1000');
    expect(item('准入行数')).toBe('950');
    expect(item('允许警告行准入')).toBe('是');
    expect(item('回放来源')).toBeUndefined();

    modalButton('查看 ETL 运行')?.click();
    await flush();
    expect(harness.push).toHaveBeenCalledWith({
      name: 'DataforgeEtlRecord',
      query: { recordId: '9007199254740993' },
    });
    expect(modal()).toBeNull();
  });

  it('回放批次展示来源批次、运行与整改提交', async () => {
    harness.route = { query: { instanceId: '9007199254740995' } };
    vi.mocked(api.getTaskInstance).mockResolvedValue({
      ...etlInstance,
      etlBatch: {
        ...etlBatch,
        replayOfBatchId: '9007199254740981',
        replayOfRecordId: '9007199254740982',
        workOrderSubmissionId: '9007199254740983',
      },
    });
    await mountPage();

    expect(item('回放来源')).toBe(
      '批次 #9007199254740981 · 运行 #9007199254740982 · 整改提交 #9007199254740983',
    );
  });

  it('没有 ETL 查询权限时不提供跳转入口', async () => {
    harness.permissions = ['governance:quality-task-instance:query'];
    harness.route = { query: { instanceId: '9007199254740995' } };
    harness.rows = [etlInstance];
    vi.mocked(api.getTaskInstance).mockResolvedValue(etlInstance);
    await mountPage();

    expect(item('运行编号')).toBe('9007199254740993');
    expect(modalButton('查看 ETL 运行')).toBeUndefined();
    expect(
      host.querySelector('[data-row="9007199254740995"] [data-action="ETL 运行"]'),
    ).toBeNull();
    expect(harness.push).not.toHaveBeenCalled();
  });

  it('列表区分 ETL 批次质检与研发关联质检，未知结果不显示为成功', async () => {
    harness.rows = [
      { id: 1, runType: 'MANUAL', instanceStatus: 'UNKNOWN' },
      { ...etlInstance, instanceStatus: 'SUCCESS' },
    ];
    await mountPage();

    const runType = harness.grid.gridOptions.columns.find(
      (column: { field?: string }) => column.field === 'runType',
    );
    const label = (cellValue?: string) => runType.formatter({ cellValue });
    expect(label('MANUAL')).toBe('手动');
    expect(label('SCHEDULE')).toBe('调度');
    expect(label('ETL_TARGET_BATCH')).toBe('ETL 批次质检');
    expect(label('DATAFORGE')).toBe('研发关联质检');
    expect(label('CUSTOM')).toBe('CUSTOM');

    const manualRow = host.querySelector('[data-row="1"]');
    expect(manualRow?.textContent).toContain('结果待确认');
    expect(manualRow?.querySelector('[data-action="ETL 运行"]')).toBeNull();
    const etlRow = host.querySelector('[data-row="9007199254740995"]');
    expect(etlRow?.textContent?.replaceAll(/\s+/g, ' ')).toContain(
      '运行 #9007199254740993 · 批次 #9007199254740994',
    );
    etlRow
      ?.querySelector<HTMLButtonElement>('[data-action="ETL 运行"]')
      ?.click();
    expect(harness.push).toHaveBeenCalledWith({
      name: 'DataforgeEtlRecord',
      query: { recordId: '9007199254740993' },
    });
  });

  it('切换登录会话后丢弃旧会话的详情响应并刷新列表', async () => {
    let finish!: (value: QualityTaskInstanceApi.Instance) => void;
    vi.mocked(api.getTaskInstance).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    harness.route = { query: { instanceId: '9007199254740995' } };
    await mountPage();

    harness.access.refreshToken = '2';
    await flush();
    expect(harness.query).toHaveBeenCalledTimes(1);
    finish(etlInstance);
    await flush();
    expect(modal()).toBeNull();
  });
});
