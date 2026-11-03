/* eslint-disable vue/one-component-per-file -- 本测试需要独立模拟弹窗、表格、按钮与权限动作组件。 */
import type { App, PropType } from 'vue';

import type { QualityIssueDataApi } from '#/api/governance/quality/issue';
import type { QualityReportApi } from '#/api/governance/quality/report';

import {
  createApp,
  defineComponent,
  h,
  KeepAlive,
  nextTick,
  reactive,
  ref,
} from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/governance/quality/issue';

import ProblemData from './problem-data.vue';

interface Action {
  label: string;
  auth?: string[];
  disabled?: boolean;
  ifShow?: boolean;
  onClick?: () => void;
}
const harness = vi.hoisted(() => ({
  access: { accessToken: 'one', refreshToken: 'one' },
  permissions: ['governance:quality-issue-data:update'],
  table: undefined as any,
  success: vi.fn(),
  confirm: vi.fn(),
  autoConfirm: true,
  compact: { value: false },
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access }));
vi.mock('@vueuse/core', async (importOriginal) => ({
  ...(await importOriginal<Record<string, unknown>>()),
  useMediaQuery: () => harness.compact,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.permissions.includes(code)),
  }),
}));
vi.mock('#/api/governance/quality/issue', () => ({
  getIssueRows: vi.fn(),
  actOnIssueRows: vi.fn(),
}));
vi.mock('../../repair-order/modules/form.vue', () => ({
  default: defineComponent({
    setup: (_, { expose }) => {
      expose({ close: vi.fn() });
      return () => null;
    },
  }),
}));
vi.mock('#/adapter/vxe-table', () => ({
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
              {
                'data-action': action.label,
                disabled: action.disabled,
                onClick: action.onClick,
              },
              action.label,
            ),
          ),
      ),
  }),
}));
vi.mock('antdv-next', () => {
  const slot = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  const modal = Object.assign(
    defineComponent({
      props: { open: Boolean, title: { type: String, default: '' } },
      setup:
        (props, { slots }) =>
        () =>
          props.open
            ? h('aside', { 'data-title': props.title }, [
                slots.default?.(),
                slots.footer?.(),
              ])
            : null,
    }),
    {
      confirm: (options: { onOk: () => Promise<unknown> }) => {
        harness.confirm(options);
        return harness.autoConfirm ? options.onOk() : undefined;
      },
    },
  );
  return {
    Alert: defineComponent({
      props: { message: { type: String, default: '' } },
      setup: (props) => () => h('div', props.message),
    }),
    Button: defineComponent({
      setup:
        (_, { attrs, slots }) =>
        () =>
          h('button', attrs, slots.default?.()),
    }),
    Input: slot,
    TextArea: slot,
    Tooltip: slot,
    Modal: modal,
    message: { success: harness.success },
    Table: defineComponent({
      props: {
        dataSource: {
          type: Array as PropType<QualityIssueDataApi.SourceRow[]>,
          default: () => [],
        },
        columns: {
          type: Array as PropType<{ key: string }[]>,
          default: () => [],
        },
        rowSelection: { type: Object, default: undefined },
        rowKey: { type: Function, default: undefined },
        scroll: { type: Object, default: undefined },
      },
      setup:
        (props, { slots }) =>
        () => {
          harness.table = props;
          return h(
            'section',
            props.dataSource.map((record: QualityIssueDataApi.SourceRow) =>
              h(
                'div',
                { 'data-row': record.rowKey },
                props.columns.map((column: { key: string }) =>
                  slots.bodyCell?.({ column, record }),
                ),
              ),
            ),
          );
        },
    }),
  };
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
function rule(id: string): QualityReportApi.Rule {
  return {
    id,
    ruleName: '校验',
    ruleType: 'CHARACTER',
    qualityDim: 'ACCURACY',
    fieldLabels: ['STCD/站码'],
    checkField: 'STCD',
    totalCount: 1,
    failCount: 1,
    problemRatio: '100',
    resultType: 'FAIL',
    issueIds: [],
  };
}
function result(
  value: string,
  rowKey = 'row-one',
): QualityIssueDataApi.RowsResult {
  return {
    columns: ['STCD'],
    list: [
      {
        rowKey,
        values: { STCD: value },
        state: 'UNPROCESSED',
        revision: 4,
        remark: '',
        processable: true,
      },
    ],
    total: 1,
    truncated: false,
  };
}
let app: App;
let host: HTMLDivElement;
let component: InstanceType<typeof ProblemData>;
async function settle() {
  for (let index = 0; index < 5; index++) {
    await Promise.resolve();
    await nextTick();
  }
}
function mount() {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(ProblemData);
  component = app.mount(host) as InstanceType<typeof ProblemData>;
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.autoConfirm = true;
  harness.compact = reactive({ value: false });
  harness.access = reactive({ accessToken: 'one', refreshToken: 'one' });
  harness.permissions = ['governance:quality-issue-data:update'];
  vi.mocked(api.getIssueRows).mockResolvedValue(result('00123'));
  vi.mocked(api.actOnIssueRows).mockResolvedValue(true);
  mount();
});
afterEach(() => {
  app.unmount();
  host.remove();
});

describe('问题数据处理边界', () => {
  it('按报告审计编号查询，保留前导零与大数值文本', async () => {
    vi.mocked(api.getIssueRows).mockResolvedValue(
      result('9007199254740993123.00100'),
    );
    await component.open(rule('9007199254740993'));
    expect(api.getIssueRows).toHaveBeenCalledWith({
      auditLogId: '9007199254740993',
      pageNo: 1,
      pageSize: 10,
      filters: {},
    });
    expect(host.textContent).toContain('9007199254740993123.00100');
  });
  it('切换报告后丢弃前一个报告的迟到结果', async () => {
    const first = deferred<QualityIssueDataApi.RowsResult>();
    vi.mocked(api.getIssueRows)
      .mockReturnValueOnce(first.promise)
      .mockResolvedValueOnce(result('new-value'));
    const oldOpen = component.open(rule('1'));
    await component.open(rule('2'));
    first.resolve(result('old-secret'));
    await oldOpen;
    await settle();
    expect(host.textContent).toContain('new-value');
    expect(host.textContent).not.toContain('old-secret');
  });
  it('关闭和切换登录会话后迟到数据不可重开弹窗', async () => {
    const pending = deferred<QualityIssueDataApi.RowsResult>();
    vi.mocked(api.getIssueRows).mockReturnValueOnce(pending.promise);
    const opening = component.open(rule('1'));
    harness.access.accessToken = 'two';
    await settle();
    pending.resolve(result('old-secret'));
    await opening;
    await settle();
    expect(host.querySelector('aside')).toBeNull();
    expect(host.textContent).not.toContain('old-secret');
  });
  it('重复投影各有展示 key，但不能被选中处置', async () => {
    const data = result('duplicate', 'same');
    data.list[0]!.processable = false;
    data.list.push({ ...data.list[0]! });
    vi.mocked(api.getIssueRows).mockResolvedValue(data);
    await component.open(rule('1'));
    const records = harness.table.dataSource;
    expect(
      new Set(
        records.map((record: QualityIssueDataApi.SourceRow) =>
          harness.table.rowKey(record),
        ),
      ).size,
    ).toBe(2);
    expect(
      harness.table.rowSelection.getCheckboxProps(records[0]).disabled,
    ).toBe(true);
    expect(
      host.querySelector<HTMLButtonElement>('[data-action="忽略"]')?.disabled,
    ).toBe(true);
  });
  it('窄屏与宽屏均固定操作列，并保留源数据横向滚动', async () => {
    harness.compact.value = true;
    await component.open(rule('1'));
    expect(
      harness.table.columns.find(
        (column: { key: string }) => column.key === 'actions',
      ).fixed,
    ).toBe('right');
    expect(harness.table.scroll.x).toBe(2080);
    harness.compact.value = false;
    await settle();
    expect(
      harness.table.columns.find(
        (column: { key: string }) => column.key === 'actions',
      ).fixed,
    ).toBe('right');
    expect(harness.table.scroll.x).toBe(2080);
  });
  it('忽略发送精确 CAS 版本，成功后重新读取，未提交源数据', async () => {
    await component.open(rule('9007199254740993'));
    host.querySelector<HTMLButtonElement>('[data-action="忽略"]')!.click();
    await settle();
    expect(api.actOnIssueRows).toHaveBeenCalledWith({
      auditLogId: '9007199254740993',
      action: 'IGNORE',
      rows: [{ rowKey: 'row-one', revision: 4 }],
    });
    expect(api.getIssueRows).toHaveBeenCalledTimes(2);
    expect(harness.success).toHaveBeenCalledWith('已忽略问题');
  });
  it('只读权限隐藏逐行处置并禁用勾选', async () => {
    harness.permissions = [];
    await component.open(rule('1'));
    expect(host.querySelector('[data-action="忽略"]')).toBeNull();
    expect(host.querySelector('[data-action="添加备注"]')).toBeNull();
    expect(
      harness.table.rowSelection.getCheckboxProps(harness.table.dataSource[0])
        .disabled,
    ).toBe(true);
  });
  it('切换报告后旧确认框不能向新报告提交旧行', async () => {
    harness.autoConfirm = false;
    await component.open(rule('old-audit'));
    host.querySelector<HTMLButtonElement>('[data-action="忽略"]')!.click();
    const confirmation = harness.confirm.mock.calls[0]![0];
    await component.open(rule('new-audit'));
    await confirmation.onOk();
    expect(api.actOnIssueRows).not.toHaveBeenCalled();
  });
  it('离开报告后忽略的迟到响应不显示成功或刷新其他报告', async () => {
    const pending = deferred<boolean>();
    vi.mocked(api.actOnIssueRows).mockReturnValueOnce(pending.promise);
    await component.open(rule('old-audit'));
    host.querySelector<HTMLButtonElement>('[data-action="忽略"]')!.click();
    await component.open(rule('new-audit'));
    pending.resolve(true);
    await settle();
    expect(harness.success).not.toHaveBeenCalled();
    expect(api.getIssueRows).toHaveBeenCalledTimes(2);
  });
  it('缓存标签切换会关闭弹窗并丢弃未完成查询', async () => {
    app.unmount();
    host.remove();
    const active = ref(true);
    const child = ref<InstanceType<typeof ProblemData>>();
    host = document.createElement('div');
    document.body.append(host);
    app = createApp(
      defineComponent({
        setup: () => () =>
          h(KeepAlive, () =>
            active.value ? h(ProblemData, { ref: child }) : null,
          ),
      }),
    );
    app.mount(host);
    await settle();
    const pending = deferred<QualityIssueDataApi.RowsResult>();
    vi.mocked(api.getIssueRows).mockReturnValueOnce(pending.promise);
    const opening = child.value!.open(rule('1'));
    active.value = false;
    await settle();
    pending.resolve(result('inactive-secret'));
    await opening;
    active.value = true;
    await settle();
    expect(host.querySelector('aside')).toBeNull();
    expect(host.textContent).not.toContain('inactive-secret');
  });
});
