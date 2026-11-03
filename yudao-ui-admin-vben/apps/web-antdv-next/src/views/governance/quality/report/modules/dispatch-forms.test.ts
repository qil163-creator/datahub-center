/* eslint-disable vue/one-component-per-file -- 复用工单入口的异步边界测试需要多个轻量 UI 替身。 */
import type { App, PropType } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getCheckObjectSimpleList } from '#/api/governance/quality/checkobject';
import { getIssueData, getIssueDataPage } from '#/api/governance/quality/issue';
import { createRepairOrder } from '#/api/governance/quality/repair';
import { getQualityTaskSimpleList } from '#/api/governance/quality/task';
import { createWorkOrder } from '#/api/governance/quality/workorder';

import RepairForm from '../../repair-order/modules/form.vue';
import WorkOrderForm from '../../work-order/modules/form.vue';

const harness = vi.hoisted(() => ({
  access: { accessToken: 'one', refreshToken: 'one' },
  options: undefined as any,
  form: undefined as any,
  success: vi.fn(),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access }));
vi.mock('#/api/governance/quality/checkobject', () => ({
  getCheckObjectSimpleList: vi.fn(),
}));
vi.mock('#/api/governance/quality/issue', () => ({
  getIssueData: vi.fn(),
  getIssueDataPage: vi.fn(),
}));
vi.mock('#/api/governance/quality/repair', () => ({
  createRepairOrder: vi.fn(),
  previewRepairSql: vi.fn(),
}));
vi.mock('#/api/governance/quality/task', () => ({
  getQualityTaskSimpleList: vi.fn(),
}));
vi.mock('#/api/governance/quality/workorder', () => ({
  createWorkOrder: vi.fn(),
}));
vi.mock('#/api/system/user', () => ({
  getSimpleUserList: vi.fn(async () => []),
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: {
    onConfirm: () => Promise<void>;
    onOpenChange?: (value: boolean) => void;
  }) => {
    harness.options = options;
    const opened = ref(false);
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            opened.value ? h('aside', slots.default?.()) : null,
      }),
      {
        open: () => {
          opened.value = true;
          options.onOpenChange?.(true);
        },
        close: () => {
          opened.value = false;
          options.onOpenChange?.(false);
        },
        lock: vi.fn(),
        unlock: vi.fn(),
      },
    ];
  },
}));
vi.mock('antdv-next', () => {
  const slot = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Form: defineComponent({
      props: {
        model: { type: Object, required: true },
        rules: { type: Object, default: () => ({}) },
      },
      setup: (props, { slots, expose }) => {
        expose({ validate: vi.fn(async () => true) });
        return () => {
          harness.form = props;
          return h('form', slots.default?.());
        };
      },
    }),
    Select: defineComponent({
      props: {
        options: {
          type: Array as PropType<{ label: string; value: number | string }[]>,
          default: () => [],
        },
      },
      setup: (props) => () =>
        h(
          'div',
          props.options.map((item) => h('span', item.label)),
        ),
    }),
    FormItem: slot,
    Input: slot,
    TextArea: slot,
    Button: slot,
    DatePicker: slot,
    message: { success: harness.success, warning: vi.fn() },
  };
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
let app: App;
let host: HTMLDivElement;
function mount<T>(component: T) {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(component as any);
  return app.mount(host);
}
async function settle() {
  await Promise.resolve();
  await nextTick();
  await Promise.resolve();
  await nextTick();
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({ accessToken: 'one', refreshToken: 'one' });
  vi.mocked(getQualityTaskSimpleList).mockResolvedValue([]);
  vi.mocked(getIssueDataPage).mockResolvedValue({ list: [], total: 0 });
  vi.mocked(getIssueData).mockImplementation(async (id) => ({
    id,
    tableName: '源表',
    checkField: 'CODE',
    issueCount: 1,
  }));
  vi.mocked(getCheckObjectSimpleList).mockResolvedValue([]);
  vi.mocked(createWorkOrder).mockResolvedValue('work-order');
  vi.mocked(createRepairOrder).mockResolvedValue('repair-order');
});
afterEach(() => {
  app.unmount();
  host.remove();
});

describe('报告复用工单入口', () => {
  it('通知处理保留大 ID 并要求责任人，补齐分页之外的问题选项', async () => {
    const component = mount(WorkOrderForm) as InstanceType<
      typeof WorkOrderForm
    >;
    await component.open({
      taskId: '9007199254740993',
      issueIds: ['9007199254740995'],
      orderName: '通知处理测试',
      requireOwner: true,
    });
    await settle();
    expect(getIssueDataPage).toHaveBeenCalledWith(
      expect.objectContaining({ taskId: '9007199254740993' }),
    );
    expect(getIssueData).toHaveBeenCalledWith('9007199254740995');
    expect(harness.form.rules.ownerUserId[0].required).toBe(true);
    harness.form.model.ownerUserId = '9007199254740997';
    await harness.options.onConfirm();
    expect(createWorkOrder).toHaveBeenCalledWith(
      expect.objectContaining({
        taskId: '9007199254740993',
        issueIds: ['9007199254740995'],
        ownerUserId: '9007199254740997',
      }),
    );
  });
  it('重新打开通知处理后旧选项请求不能覆盖新预填', async () => {
    const pending =
      deferred<Awaited<ReturnType<typeof getQualityTaskSimpleList>>>();
    vi.mocked(getQualityTaskSimpleList).mockReturnValueOnce(pending.promise);
    const component = mount(WorkOrderForm) as InstanceType<
      typeof WorkOrderForm
    >;
    const first = component.open({ taskId: '1', orderName: '旧任务' });
    await component.open({ taskId: '2', orderName: '新任务' });
    pending.resolve([{ id: '1', taskName: '旧任务' }]);
    await first;
    await settle();
    expect(harness.form.model.taskId).toBe('2');
    expect(harness.form.model.orderName).toBe('新任务');
    expect(host.textContent).not.toContain('旧任务');
  });
  it('会话改变后旧问题查询不会重新打开通知弹窗', async () => {
    const pending = deferred<Awaited<ReturnType<typeof getIssueDataPage>>>();
    vi.mocked(getIssueDataPage).mockReturnValueOnce(pending.promise);
    const component = mount(WorkOrderForm) as InstanceType<
      typeof WorkOrderForm
    >;
    const opening = component.open({ taskId: '1' });
    await settle();
    harness.access.accessToken = 'two';
    pending.resolve({ list: [], total: 0 });
    await opening;
    await settle();
    expect(host.querySelector('aside')).toBeNull();
  });
  it('修复预填保留大对象编号，关闭后创建响应不影响新弹窗', async () => {
    const pending = deferred<Awaited<ReturnType<typeof createRepairOrder>>>();
    vi.mocked(createRepairOrder).mockReturnValueOnce(pending.promise);
    const component = mount(RepairForm) as InstanceType<typeof RepairForm>;
    await component.open({
      objectId: '9007199254740999',
      tableName: 'source_table',
      orderTitle: '旧修复',
    });
    await settle();
    const submitting = harness.options.onConfirm();
    await settle();
    expect(createRepairOrder).toHaveBeenCalledWith(
      expect.objectContaining({ objectId: '9007199254740999' }),
    );
    await component.open({ orderTitle: '新修复' });
    pending.resolve('repair-order');
    await submitting;
    await settle();
    expect(harness.form.model.orderTitle).toBe('新修复');
    expect(host.querySelector('aside')).not.toBeNull();
    expect(harness.success).not.toHaveBeenCalled();
  });
});
