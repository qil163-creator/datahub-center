import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/dataforge/etl';

import QualityDetail from './quality-detail.vue';

const harness = vi.hoisted(() => ({
  access: { accessToken: '1', refreshToken: undefined as string | undefined },
  permissions: ['dataforge:etl:run', 'dataforge:etl:query'],
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.permissions.includes(code)),
  }),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access }));
vi.mock('#/api/dataforge/etl', () => ({
  getEtlQualityBatches: vi.fn(),
  getEtlQualityIssues: vi.fn(),
  getEtlQualityIssue: vi.fn(),
  retryEtlQualityRegistration: vi.fn(),
}));
vi.mock('./quality-report.vue', () => ({ default: { render: () => null } }));
vi.mock('antdv-next', () => {
  const slot = (tag: string) =>
    defineComponent({
      setup:
        (_, { attrs, slots }) =>
        () =>
          h(tag, attrs, slots.default?.()),
    });
  const dialog = defineComponent({
    props: { open: Boolean },
    setup:
      (props, { slots }) =>
      () =>
        props.open ? h('section', slots.default?.()) : null,
  });
  return {
    Alert: defineComponent({
      props: { message: String },
      setup: (props) => () => h('p', props.message),
    }),
    Button: slot('button'),
    Drawer: dialog,
    Modal: dialog,
    Empty: slot('p'),
    Select: slot('div'),
    Spin: slot('div'),
    Tag: slot('span'),
    Table: defineComponent({
      props: { dataSource: { type: Array, default: () => [] } },
      setup:
        (props, { slots }) =>
        () =>
          h(
            'div',
            props.dataSource.map((record) =>
              slots.bodyCell?.({ record, column: { key: 'actions' } }),
            ),
          ),
    }),
  };
});
let app: App | undefined;
let host: HTMLDivElement;
let view: { open: (id: string) => Promise<void> };
const batch = {
  id: '9007199254740993',
  recordId: '99',
  nodeNo: 'qc',
  inputCount: 10,
  passCount: 8,
  warnCount: 1,
  rejectCount: 1,
  violationCount: 3,
  registrationStatus: 'RETRY',
};
async function flush() {
  for (let i = 0; i < 6; i++) {
    await Promise.resolve();
    await nextTick();
  }
}
async function mountDetail() {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(QualityDetail);
  view = app.mount(host) as unknown as typeof view;
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({ accessToken: '1', refreshToken: undefined });
  harness.permissions = ['dataforge:etl:run', 'dataforge:etl:query'];
  vi.mocked(api.getEtlQualityBatches).mockResolvedValue([batch]);
  vi.mocked(api.getEtlQualityIssues).mockResolvedValue({
    list: [
      {
        id: '9007199254740995',
        batchId: batch.id,
        rowNumber: 9,
        qualityStatus: 'REJECT',
        violationsJson: '[{"message":"必填字段为空"}]',
      },
    ],
    total: 1,
  });
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('etl 行级质量证据', () => {
  it('检测未完成时计数保持未知且不提供登记重试', async () => {
    vi.mocked(api.getEtlQualityBatches).mockResolvedValue([
      {
        ...batch,
        registrationStatus: 'INCOMPLETE',
        inputCount: null,
        passCount: null,
        warnCount: null,
        rejectCount: null,
        violationCount: null,
      },
    ]);
    await mountDetail();
    await view.open('99');
    await flush();
    expect(host.textContent).toContain('检测未完成');
    expect(host.textContent).toContain('合格 —');
    expect(
      [...host.querySelectorAll('button')].some(
        (button) => button.textContent?.trim() === '重试治理登记',
      ),
    ).toBe(false);
  });
  it('失败登记可单次重试，成功受理后变为待登记且不重跑ETL', async () => {
    let resolve!: (value: boolean) => void;
    vi.mocked(api.retryEtlQualityRegistration).mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    vi.mocked(api.getEtlQualityBatches)
      .mockResolvedValueOnce([{ ...batch, registrationStatus: 'FAILED' }])
      .mockResolvedValueOnce([{ ...batch, registrationStatus: 'PENDING' }]);
    await mountDetail();
    await view.open('99');
    await flush();
    expect(host.textContent).toContain('不重跑 ETL');
    const retry = [...host.querySelectorAll('button')].find(
      (button) => button.textContent?.trim() === '重试治理登记',
    )!;
    retry.click();
    retry.click();
    await flush();
    expect(api.retryEtlQualityRegistration).toHaveBeenCalledTimes(1);
    expect(api.retryEtlQualityRegistration).toHaveBeenCalledWith(batch.id);
    resolve(true);
    await flush();
    expect(host.textContent).toContain('待登记');
    expect(host.textContent).not.toContain('重试治理登记');
  });

  it('只有查询权限的用户不能重试登记', async () => {
    harness.permissions = ['dataforge:etl:query'];
    vi.mocked(api.getEtlQualityBatches).mockResolvedValue([
      { ...batch, registrationStatus: 'FAILED' },
    ]);
    await mountDetail();
    await view.open('99');
    await flush();
    expect(host.textContent).toContain('待人工处理');
    expect(host.textContent).not.toContain('重试治理登记');
    expect(api.retryEtlQualityRegistration).not.toHaveBeenCalled();
  });

  it('显示登记状态，按用户操作读取行详情，并保留原始精确数值', async () => {
    vi.mocked(api.getEtlQualityIssue).mockResolvedValue({
      id: '9007199254740995',
      batchId: batch.id,
      rowNumber: 9,
      qualityStatus: 'REJECT',
      violationsJson: '[]',
      rowJson: '{"id":9007199254740993123,"amount":1.000000000000000001}',
      sourceVerified: true,
    });
    await mountDetail();
    await view.open('99');
    await flush();
    expect(api.getEtlQualityIssues).toHaveBeenCalledWith({
      batchId: batch.id,
      pageNo: 1,
      pageSize: 20,
    });
    expect(api.getEtlQualityIssue).not.toHaveBeenCalled();
    expect(host.textContent).toContain('等待重试');
    [...host.querySelectorAll('button')]
      .find((button) => button.textContent?.trim() === '查看行详情')!
      .click();
    await flush();
    expect(api.getEtlQualityIssue).toHaveBeenCalledWith('9007199254740995');
    expect(host.textContent).toContain('9007199254740993123');
    expect(host.textContent).toContain('1.000000000000000001');
    expect(host.textContent).toContain('原来源已重新验证');
    expect(host.textContent).toContain('经权限处理的来源行');
  });

  it('来源变化时不展示原始内容并保留问题统计入口', async () => {
    vi.mocked(api.getEtlQualityIssue).mockRejectedValue(
      new Error('restricted'),
    );
    await mountDetail();
    await view.open('99');
    await flush();
    [...host.querySelectorAll('button')]
      .find((item) => item.textContent?.trim() === '查看行详情')!
      .click();
    await flush();
    expect(host.textContent).toContain('来源无法安全复核');
    expect(host.textContent).toContain('可继续查看违规原因和统计');
    expect(host.textContent).not.toContain('原来源已重新验证');
  });

  it('切换登录会话时清空证据且忽略旧会话的迟到响应', async () => {
    let resolve!: (value: (typeof batch)[]) => void;
    vi.mocked(api.getEtlQualityBatches).mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    await mountDetail();
    const opening = view.open('99');
    await flush();
    harness.access.refreshToken = '2';
    await flush();
    resolve([batch]);
    await opening;
    await flush();
    expect(host.textContent).toBe('');
    expect(api.getEtlQualityIssues).not.toHaveBeenCalled();
  });
});
