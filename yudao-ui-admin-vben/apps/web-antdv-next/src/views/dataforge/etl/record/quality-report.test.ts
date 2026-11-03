import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/dataforge/etl';

import QualityReport from './quality-report.vue';

const harness = vi.hoisted(() => ({
  access: { accessToken: '1', refreshToken: undefined as string | undefined },
  permissions: [] as string[],
  openOrder: vi.fn(),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.every((code) => harness.permissions.includes(code)),
  }),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access }));
vi.mock('#/api/dataforge/etl', () => ({
  getEtlQualityReport: vi.fn(),
  createEtlQualityWorkOrder: vi.fn(),
}));
vi.mock('#/views/governance/quality/work-order/modules/resolution.vue', () => ({
  default: defineComponent({
    setup(_, { expose }) {
      expose({ open: harness.openOrder });
      return () => null;
    },
  }),
}));
vi.mock('antdv-next', () => {
  const slot = (tag: string) =>
    defineComponent({
      setup:
        (_, { attrs, slots }) =>
        () =>
          h(tag, attrs, slots.default?.()),
    });
  const input = defineComponent({
    props: ['value'],
    emits: ['update:value'],
    setup:
      (props, { emit }) =>
      () =>
        h('input', {
          value: props.value,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLInputElement).value),
        }),
  });
  return {
    Alert: defineComponent({
      props: ['message'],
      setup: (props) => () => h('p', props.message),
    }),
    Button: slot('button'),
    Spin: slot('div'),
    DatePicker: slot('div'),
    Input: Object.assign(input, { TextArea: input }),
    Modal: defineComponent({
      props: ['open'],
      setup:
        (props, { attrs, slots }) =>
        () =>
          props.open
            ? h('section', [
                slots.default?.(),
                h('button', { onClick: attrs.onOk }, '确认创建'),
              ])
            : null,
    }),
  };
});
let app: App | undefined;
let host: HTMLDivElement;
const batchId = '9007199254740993';
const report = {
  reportId: '9007199254740994',
  runId: 'run',
  nodeId: 'qc',
  batchId,
  sourceType: 'ETL_INLINE',
  planHash: 'hash',
  totalCount: 3,
  passCount: 1,
  warnCount: 1,
  rejectCount: 1,
  issueReferences: [],
  createTime: 1,
};
async function flush() {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve();
    await nextTick();
  }
}
async function mount() {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(QualityReport, { batchId });
  app.mount(host);
  await flush();
}
function button(text: string) {
  return [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === text,
  )!;
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({ accessToken: '1', refreshToken: undefined });
  harness.permissions = [
    'dataforge:etl:query',
    'governance:quality-audit-log:query',
    'governance:quality-work-order:create',
    'governance:quality-work-order:query',
  ];
  vi.mocked(api.getEtlQualityReport).mockResolvedValue({ ...report });
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('etl 治理报告与整改工单', () => {
  it('复用已有工单详情，保留大整数编号且不重复创建', async () => {
    vi.mocked(api.getEtlQualityReport).mockResolvedValue({
      ...report,
      workOrderId: '9007199254740995',
    });
    await mount();
    expect(host.textContent).toContain('不能验收关闭');
    expect(host.textContent).not.toContain('创建整改工单');
    button('查看工单详情').click();
    expect(harness.openOrder).toHaveBeenCalledWith('9007199254740995');
    expect(api.createEtlQualityWorkOrder).not.toHaveBeenCalled();
  });
  it('创建请求防重复，失败后查询报告确认已创建的未知结果', async () => {
    let reject!: (reason: Error) => void;
    vi.mocked(api.createEtlQualityWorkOrder).mockImplementationOnce(
      () =>
        new Promise((_, fail) => {
          reject = fail;
        }),
    );
    await mount();
    button('创建整改工单').click();
    await flush();
    button('确认创建').click();
    button('确认创建').click();
    await flush();
    expect(api.createEtlQualityWorkOrder).toHaveBeenCalledTimes(1);
    expect(api.createEtlQualityWorkOrder).toHaveBeenCalledWith(
      expect.objectContaining({
        batchId,
        orderName: `ETL 质量整改 - ${batchId}`,
      }),
    );
    vi.mocked(api.getEtlQualityReport).mockResolvedValueOnce({
      ...report,
      workOrderId: '9007199254740995',
    });
    reject(new Error('timeout'));
    await flush();
    expect(api.getEtlQualityReport).toHaveBeenCalledTimes(2);
    expect(host.textContent).toContain('整改工单 #9007199254740995');
    expect(host.textContent).not.toContain('创建整改工单');
  });
  it('没有治理查询权限时不请求报告，不能创建工单', async () => {
    harness.permissions = ['dataforge:etl:query'];
    await mount();
    expect(api.getEtlQualityReport).not.toHaveBeenCalled();
    expect(host.textContent).toBe('');
  });
  it('旧会话迟到报告不能覆盖新会话结果', async () => {
    let resolve!: (value: typeof report) => void;
    vi.mocked(api.getEtlQualityReport).mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    await mount();
    harness.access.refreshToken = '2';
    await flush();
    resolve({ ...report, nodeId: '旧会话节点' });
    await flush();
    expect(host.textContent).not.toContain('旧会话节点');
  });
});
