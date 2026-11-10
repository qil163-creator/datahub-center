import type { App, Component } from 'vue';

import type { QualityWorkOrderApi } from '#/api/governance/quality/workorder';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import dayjs from 'dayjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { replayEtlTargetBatch } from '#/api/dataforge/etl';
import {
  confirmWorkOrder,
  getWorkOrder,
  recheckWorkOrder,
  submitWorkOrderResolution,
} from '#/api/governance/quality/workorder';

import Page from '../index.vue';
import Resolution from './resolution.vue';

vi.mock('./notifications.vue', () => ({
  default: defineComponent({ render: () => null }),
}));

const harness = vi.hoisted(() => ({
  access: {
    accessToken: '1',
    refreshToken: undefined as string | undefined,
    denied: [] as string[],
  },
  user: { userInfo: { userId: '4' } },
  row: {} as QualityWorkOrderApi.WorkOrder,
  refresh: vi.fn(),
  push: vi.fn(),
}));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: harness.push }) }));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      !codes.some((code) => harness.access.denied.includes(code)),
  }),
}));
vi.mock('@vben/utils', () => ({
  formatDateTime: (value: unknown) => String(value ?? '-'),
}));
vi.mock('#/api/request', () => ({ requestClient: {} }));
vi.mock('./expiry-failures.vue', () => ({ default: defineComponent({ setup: () => () => null }) }));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', [slots.extra?.(), slots.default?.()]),
  }),
  useVbenModal: (options: { onOpenChange: (open: boolean) => void }) => {
    const visible = ref(false);
    const set = (open: boolean) => {
      visible.value = open;
      options.onOpenChange(open);
    };
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            visible.value
              ? h('section', [
                  h(
                    'button',
                    { 'data-action': 'close', onClick: () => set(false) },
                    '关闭',
                  ),
                  slots.default?.(),
                ])
              : null,
      }),
      { open: () => set(true), close: () => set(false) },
    ];
  },
}));
vi.mock('antdv-next', () => {
  const input = (tag: string, date = false) =>
    defineComponent({
      props: ['value', 'disabled'],
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h(tag, {
            value: date ? '' : props.value,
            disabled: props.disabled,
            onInput: (e: Event) =>
              emit(
                'update:value',
                date
                  ? dayjs((e.target as HTMLInputElement).value)
                  : (e.target as HTMLInputElement).value,
              ),
          }),
    });
  return {
    Input: input('input'),
    TextArea: input('textarea'),
    DatePicker: input('input', true),
    Tag: 'span',
    message: {},
    Modal: {},
    Button: defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('button', slots.default?.()),
    }),
    Alert: defineComponent({
      props: ['title'],
      setup: (props) => () => h('p', props.title),
    }),
    Spin: 'span',
    Select: defineComponent({
      props: ['value', 'options', 'disabled'],
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'select',
            {
              value: props.value,
              disabled: props.disabled,
              onChange: (e: Event) =>
                emit('update:value', (e.target as HTMLSelectElement).value),
            },
            props.options.map(
              (option: { disabled?: boolean; label: string; value: string }) =>
                h(
                  'option',
                  { value: option.value, disabled: option.disabled },
                  option.label,
                ),
            ),
          ),
    }),
    Segmented: defineComponent({
      emits: ['update:value'],
      setup:
        (_, { emit }) =>
        () =>
          h(
            'nav',
            ['all', 'todo', 'stats'].map((tab) =>
              h(
                'button',
                { 'data-tab': tab, onClick: () => emit('update:value', tab) },
                tab,
              ),
            ),
          ),
    }),
  };
});
vi.mock('#/api/governance/quality/workorder', () => ({
  getWorkOrder: vi.fn(),
  confirmWorkOrder: vi.fn(),
  recheckWorkOrder: vi.fn(),
  submitWorkOrderResolution: vi.fn(),
  assignWorkOrder: vi.fn(),
  claimWorkOrder: vi.fn(),
  deleteWorkOrder: vi.fn(),
  getMyTodoWorkOrderList: vi.fn(),
  getWorkOrderPage: vi.fn(),
  getWorkOrderRawIssues: vi.fn(),
  transferWorkOrderCommand: vi.fn(),
  withdrawWorkOrder: vi.fn(),
}));
vi.mock('#/api/dataforge/etl', () => ({ replayEtlTargetBatch: vi.fn() }));
vi.mock('#/api/system/user', () => ({ getSimpleUserList: vi.fn() }));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('./form.vue', () => ({
  default: defineComponent({ setup: () => () => null }),
}));
vi.mock('./statistics.vue', () => ({
  default: defineComponent({ setup: () => () => null }),
}));
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  useVbenVxeGrid: () => [
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('div', slots.actions?.({ row: harness.row })),
    }),
    { query: harness.refresh },
  ],
  TableAction: defineComponent({
    props: ['actions', 'dropDownActions'],
    setup: (props) => () =>
      h(
        'div',
        [...(props.actions ?? []), ...(props.dropDownActions ?? [])]
          .filter(
            (action) =>
              action.ifShow !== false &&
              !action.auth?.some((code: string) =>
                harness.access.denied.includes(code),
              ),
          )
          .map((action) =>
            h('button', { onClick: action.onClick }, action.label),
          ),
      ),
  }),
}));

let app: App;
let host: HTMLDivElement;
let vm: { open: (id: QualityWorkOrderApi.Id) => void };
let saved: ReturnType<typeof vi.fn>;
const ID = '9223372036854775807';
function fixture(
  extra: Partial<QualityWorkOrderApi.WorkOrder> = {},
): QualityWorkOrderApi.WorkOrder {
  return {
    id: ID,
    orderName: '测试工单',
    taskId: '3',
    lockVersion: 7,
    orderStatus: 'IN_PROGRESS',
    allowedActions: ['SUBMIT'],
    ...extra,
  };
}
function confirming(type = 'EXPLAIN'): QualityWorkOrderApi.WorkOrder {
  return fixture({
    orderStatus: 'PENDING_CONFIRMATION',
    allowedActions: ['CONFIRM'],
    currentSubmissionId: '21',
    confirmationPendingType: type,
    currentSubmission: {
      id: '21',
      revision: 2,
      status: 'ACTIVE',
      submissionType: type,
      content: '提交依据',
      submittedBy: '9',
      exceptionScope: '机构甲',
      exceptionExpireAt: Date.now() + 86_400_000,
    },
  });
}
async function flush() {
  for (let i = 0; i < 8; i++) await Promise.resolve();
  await nextTick();
}
async function mount(component: Component = Resolution) {
  app = createApp(component, { onSaved: saved });
  vm = app.mount(host) as unknown as typeof vm;
  await flush();
}
async function open(order = fixture()) {
  vi.mocked(getWorkOrder).mockResolvedValue(order);
  await mount();
  vm.open(ID);
  await flush();
}
function node(selector: string) {
  const found = host.querySelector<HTMLElement>(selector);
  expect(found).not.toBeNull();
  return found!;
}
async function click(action: string) {
  node(`[data-action="${action}"]`).click();
  await flush();
}
async function input(label: string, value: string) {
  const element = node(`[aria-label="${label}"]`) as HTMLInputElement;
  element.value = value;
  element.dispatchEvent(
    new Event(element.tagName === 'SELECT' ? 'change' : 'input', {
      bubbles: true,
    }),
  );
  await flush();
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({
    accessToken: '1',
    refreshToken: undefined,
    denied: [],
  });
  harness.user = reactive({ userInfo: { userId: '4' } });
  harness.row = fixture();
  host = document.createElement('div');
  document.body.append(host);
  saved = vi.fn();
  vi.mocked(submitWorkOrderResolution)
    .mockReset()
    .mockResolvedValue(
      fixture({ allowedActions: [], orderStatus: 'PENDING_RECHECK' }),
    );
  vi.mocked(confirmWorkOrder)
    .mockReset()
    .mockResolvedValue(fixture({ allowedActions: [], closeType: 'EXPLAINED' }));
  vi.mocked(recheckWorkOrder)
    .mockReset()
    .mockResolvedValue(
      fixture({ allowedActions: [], orderStatus: 'RECHECKING' }),
    );
  vi.mocked(replayEtlTargetBatch)
    .mockReset()
    .mockResolvedValue({
      recordId: '9007199254740993',
      replayed: false,
      runStatus: 1,
      stopRequested: false,
    });
  harness.push.mockReset().mockResolvedValue(undefined);
});
afterEach(() => {
  app?.unmount();
  host.remove();
});

describe('真实工单处理组件', () => {
  it('etl 内联工单仅允许提交源端整改说明；没有当前 SOURCE_FIX 时不开放回放', async () => {
    await open(
      fixture({
        sourceType: 'ETL_INLINE',
        taskId: undefined,
        allowedActions: ['SUBMIT', 'RECHECK', 'CONFIRM'],
      }),
    );
    expect(host.textContent).toContain('受控回放完成复检');
    const options = [
      ...host.querySelectorAll<HTMLOptionElement>('select option'),
    ];
    expect(
      options
        .filter((option) => !option.disabled)
        .map((option) => option.value),
    ).toEqual(['SOURCE_FIX']);
    expect(host.querySelector('[data-action="recheck"]')).toBeNull();
    expect(host.querySelector('[data-action="confirm"]')).toBeNull();
    expect(host.querySelector('[data-action="etl-replay"]')).toBeNull();
    await input('整改内容', '已在源端补齐数据');
    await click('submit');
    expect(submitWorkOrderResolution).toHaveBeenCalledWith(
      expect.objectContaining({
        submissionType: 'SOURCE_FIX',
        content: '已在源端补齐数据',
      }),
    );
  });
  it('etl SOURCE_FIX 用户需确认替换，提交幂等回放并跳转到执行记录', async () => {
    harness.access.denied = [];
    await open(
      fixture({
        sourceType: 'ETL_INLINE',
        taskId: undefined,
        orderStatus: 'PENDING_RECHECK',
        currentSubmissionId: '21',
        allowedActions: ['SUBMIT', 'CONFIRM'],
        currentSubmission: {
          id: '21',
          submissionType: 'SOURCE_FIX',
          submittedBy: '4',
          recheckStatus: 'WAITING',
        },
      }),
    );
    expect(host.querySelector('[data-action="etl-replay"]')).not.toBeNull();
    await input('回放原因', '源端已修正完整人口');
    await click('etl-replay');
    expect(host.textContent).toContain('请先确认回放');
    const checkbox = node(
      '[aria-label="确认替换原目标批次"]',
    ) as HTMLInputElement;
    checkbox.checked = true;
    checkbox.dispatchEvent(new Event('input', { bubbles: true }));
    checkbox.dispatchEvent(new Event('change', { bubbles: true }));
    await flush();
    await click('etl-replay');
    expect(host.textContent).not.toContain('请先确认回放');
    expect(host.textContent).not.toContain('请填写本次 ETL 回放原因');
    expect(replayEtlTargetBatch).toHaveBeenCalledWith(
      expect.objectContaining({
        workOrderId: ID,
        submissionId: '21',
        snapshotMode: 'ORIGINAL',
        reason: '源端已修正完整人口',
        confirmedTargetReplacement: true,
        requestId: expect.any(String),
      }),
    );
    expect(harness.push).toHaveBeenCalledWith({
      name: 'DataforgeEtlRecord',
      query: { recordId: '9007199254740993' },
    });
  });
  it('旧弹窗的写响应不能改写后来打开的工单或刷新父列表', async () => {
    const result = deferred<QualityWorkOrderApi.WorkOrder>();
    vi.mocked(submitWorkOrderResolution).mockReturnValueOnce(result.promise);
    await open();
    await input('整改内容', '依据');
    await click('submit');
    await click('close');
    vi.mocked(getWorkOrder).mockResolvedValue(
      fixture({ id: '2', orderName: '新工单' }),
    );
    vm.open('2');
    await flush();
    result.resolve(fixture({ orderName: '旧提交响应' }));
    await flush();
    expect(host.textContent).toContain('新工单');
    expect(host.textContent).not.toContain('旧提交响应');
    expect(saved).not.toHaveBeenCalled();
  });
  it('缺版本的旧服务响应明确报错，不用版本零继续提交', async () => {
    await open(fixture({ lockVersion: undefined }));
    expect(host.textContent).not.toContain('详情加载失败');
    expect(host.querySelector('[data-action="submit"]')).toBeNull();
  });
  it('处理权限撤销会关闭弹窗并丢弃未完成结果', async () => {
    const result = deferred<QualityWorkOrderApi.WorkOrder>();
    vi.mocked(submitWorkOrderResolution).mockReturnValueOnce(result.promise);
    await open();
    await input('整改内容', '依据');
    await click('submit');
    harness.access.denied.push('governance:quality-work-order:process');
    await flush();
    result.resolve(fixture());
    await flush();
    expect(saved).not.toHaveBeenCalled();
    expect(host.querySelector('[data-action="retry"]')).toBeNull();
  });
  it.each(['SOURCE_FIX', 'PLATFORM_REPAIR', 'EXPLAIN', 'EXCEPTION'] as const)(
    '提交 %s 的真实载荷并保持 Long 无损',
    async (type) => {
      await open();
      await input('整改类型', type);
      await input('整改内容', '书面处理依据');
      await input('证据引用', '附件索引');
      if (type === 'PLATFORM_REPAIR') await input('修复单编号', ID);
      if (type === 'EXCEPTION') {
        await input('例外范围', '机构甲');
        await input('例外到期时间', '2099-01-01T10:00:00');
      }
      await click('submit');
      expect(submitWorkOrderResolution).toHaveBeenCalledOnce();
      const sent = vi.mocked(submitWorkOrderResolution).mock.calls[0]![0];
      expect(sent).toMatchObject({
        id: ID,
        expectedVersion: 7,
        submissionType: type,
        content: '书面处理依据',
        evidenceRef: '附件索引',
        requestId: expect.any(String),
      });
      expect(sent.repairOrderId).toBe(
        type === 'PLATFORM_REPAIR' ? ID : undefined,
      );
      expect(sent.exceptionScope).toBe(
        type === 'EXCEPTION' ? '机构甲' : undefined,
      );
      expect(sent.exceptionExpireAt).toBe(
        type === 'EXCEPTION'
          ? dayjs('2099-01-01T10:00:00').valueOf()
          : undefined,
      );
      expect(saved).toHaveBeenCalledOnce();
    },
  );
  it('校验空说明、非法修复单、例外范围和期限', async () => {
    await open();
    await click('submit');
    expect(host.textContent).toContain('请填写');
    await input('整改内容', '依据');
    await input('整改类型', 'PLATFORM_REPAIR');
    await input('修复单编号', '9223372036854775808');
    await click('submit');
    expect(host.textContent).toContain('有效修复单');
    await input('整改类型', 'EXCEPTION');
    await click('submit');
    expect(host.textContent).toContain('未来的到期时间');
    await input('例外范围', '机构甲');
    await input('例外到期时间', '2020-01-01');
    await click('submit');
    expect(submitWorkOrderResolution).not.toHaveBeenCalled();
  });
  it('无基线默认说明，禁止两类质量整改，不生成虚假通过', async () => {
    await open(fixture({ taskId: undefined }));
    expect((node('[aria-label="整改类型"]') as HTMLSelectElement).value).toBe(
      'EXPLAIN',
    );
    expect(
      (node('option[value="SOURCE_FIX"]') as HTMLOptionElement).disabled,
    ).toBe(true);
    await input('整改内容', '没有基线的说明');
    await click('submit');
    expect(
      vi.mocked(submitWorkOrderResolution).mock.calls[0]![0].submissionType,
    ).toBe('EXPLAIN');
    expect(confirmWorkOrder).not.toHaveBeenCalled();
  });
  it('退回必须写原因并绑定最新版本和当前提交', async () => {
    await open(confirming());
    await click('return');
    expect(confirmWorkOrder).not.toHaveBeenCalled();
    await input('验收意见', '请补材料');
    await click('return');
    expect(confirmWorkOrder).toHaveBeenCalledWith(
      expect.objectContaining({
        id: ID,
        expectedVersion: 7,
        submissionId: '21',
        confirmAction: 'RETURN',
        reason: '请补材料',
      }),
    );
  });
  it.each([
    ['EXPLAIN', 'ACCEPT_EXPLAIN', 'EXPLAINED', '说明被接受'],
    ['EXCEPTION', 'ACCEPT_EXCEPTION', 'EXCEPTION_ACCEPTED', '例外被接受'],
  ])('验收 %s 不能展示为质量通过', async (type, action, closeType, label) => {
    vi.mocked(confirmWorkOrder).mockResolvedValue(
      fixture({ orderStatus: 'PROCESSED', allowedActions: [], closeType }),
    );
    await open(confirming(type));
    await click('accept');
    expect(confirmWorkOrder).toHaveBeenCalledWith(
      expect.objectContaining({ confirmAction: action }),
    );
    expect(host.textContent).toContain(label);
    expect(host.textContent).toContain('不代表质量通过');
  });
  it('有效本轮证据才提供质量验收；历史轮次单独展示', async () => {
    const order = confirming('RECHECK_PASS');
    order.currentSubmission = {
      ...order.currentSubmission,
      submissionType: 'SOURCE_FIX',
      instanceId: '102',
      recheckStatus: 'PASS',
      comparableFlag: 1,
      rechecks: [
        {
          id: '2',
          attemptNo: 2,
          instanceId: '102',
          status: 'PASS',
          comparableFlag: 1,
          evidenceJson: '{"checks":[{"reasonCode":"EQUIVALENT"}]}',
        },
        {
          id: '1',
          attemptNo: 1,
          instanceId: '101',
          status: 'WAITING_DATA',
          comparableFlag: 0,
          lastError: '等待同步数据',
        },
      ],
    };
    await open(order);
    expect(host.textContent).toContain('第 2 轮');
    expect(host.textContent).toContain('尚不可比');
    expect(host.textContent).toContain('等待同步数据');
    expect(host.textContent).toContain('EQUIVALENT');
    await click('accept');
    expect(confirmWorkOrder).toHaveBeenCalledWith(
      expect.objectContaining({ confirmAction: 'VERIFY_PASS' }),
    );
  });
  it.each(['UNKNOWN', 'EXCEPTION'])(
    '未知待确认或已到期例外不可接受，但可以退回 (%s)',
    async (type) => {
      const order = confirming(type);
      order.currentSubmission!.exceptionExpireAt = 1;
      await open(order);
      expect(host.querySelector('[data-action="accept"]')).toBeNull();
      expect(host.querySelector('[data-action="return"]')).not.toBeNull();
    },
  );
  it.each(['permission', 'self', 'designated'])(
    '不允许无权退回 (%s)',
    async (kind) => {
      const order = confirming();
      if (kind === 'permission')
        harness.access.denied.push('governance:quality-work-order:confirm');
      if (kind === 'self') order.currentSubmission!.submittedBy = '4';
      if (kind === 'designated') order.confirmerUserId = '99';
      await open(order);
      expect(host.querySelector('[data-action="return"]')).toBeNull();
      expect(confirmWorkOrder).not.toHaveBeenCalled();
    },
  );
  it('没有查询权限不读取详情或显示旧数据', async () => {
    harness.access.denied.push('governance:quality-work-order:query');
    await open();
    expect(getWorkOrder).not.toHaveBeenCalled();
    expect(host.textContent).toContain('无权查看');
  });
  it('关闭后打开另一工单，旧详情不能覆盖新工单', async () => {
    const old = deferred<QualityWorkOrderApi.WorkOrder>();
    vi.mocked(getWorkOrder)
      .mockReturnValueOnce(old.promise)
      .mockResolvedValueOnce(fixture({ id: '2', orderName: '新工单' }));
    await mount();
    vm.open(ID);
    await flush();
    await click('close');
    vm.open('2');
    await flush();
    old.resolve(fixture({ orderName: '旧工单' }));
    await flush();
    expect(host.textContent).toContain('新工单');
    expect(host.textContent).not.toContain('旧工单');
  });
  it.each(['session', 'user', 'unmount'])(
    '身份变化或卸载后忽略未完成写响应 (%s)',
    async (kind) => {
      const result = deferred<QualityWorkOrderApi.WorkOrder>();
      vi.mocked(submitWorkOrderResolution).mockReturnValueOnce(result.promise);
      await open();
      await input('整改内容', '依据');
      await click('submit');
      if (kind === 'session') harness.access.refreshToken = '2';
      else if (kind === 'user') harness.user.userInfo.userId = '22';
      else app.unmount();
      await flush();
      result.resolve(fixture({ orderName: '旧响应' }));
      await flush();
      expect(saved).not.toHaveBeenCalled();
      expect(host.textContent).not.toContain('旧响应');
    },
  );
  it('响应丢失和重复点击不会给同一复检生成新 requestId', async () => {
    const result = deferred<QualityWorkOrderApi.WorkOrder>();
    vi.mocked(recheckWorkOrder).mockReturnValueOnce(result.promise);
    await open(
      fixture({
        orderStatus: 'PENDING_RECHECK',
        allowedActions: ['RECHECK'],
        currentSubmissionId: '21',
        currentSubmission: {
          id: '21',
          status: 'ACTIVE',
          recheckStatus: 'WAITING_DATA',
        },
      }),
    );
    await click('recheck');
    await click('recheck');
    expect(recheckWorkOrder).toHaveBeenCalledOnce();
    result.reject(new Error('lost'));
    await flush();
    expect(host.textContent).toContain('原样重试');
    vi.mocked(recheckWorkOrder).mockResolvedValue(
      fixture({
        allowedActions: [],
        currentSubmission: { id: '21' },
        recheckAttempt: {
          id: '73',
          attemptNo: 3,
          instanceId: ID,
          status: 'RUNNING',
        },
      }),
    );
    await click('retry');
    expect(recheckWorkOrder).toHaveBeenCalledTimes(2);
    expect(vi.mocked(recheckWorkOrder).mock.calls[0]).toEqual(
      vi.mocked(recheckWorkOrder).mock.calls[1],
    );
    expect(host.textContent).toContain('本次命令回执：第 3 轮');
  });
  it('过期列表版本不用于提交，父页入口重新获取详情并刷新表格', async () => {
    harness.row.lockVersion = 1;
    vi.mocked(getWorkOrder).mockResolvedValue(fixture());
    await mount(Page);
    [...host.querySelectorAll('button')]
      .find((button) => button.textContent === '提交整改')!
      .click();
    await flush();
    expect(getWorkOrder).toHaveBeenCalledWith(ID);
    await input('整改内容', '依据');
    await click('submit');
    expect(submitWorkOrderResolution).toHaveBeenCalledWith(
      expect.objectContaining({ expectedVersion: 7 }),
    );
    expect(harness.refresh).toHaveBeenCalledTimes(2);
  });
});
