import type { App } from 'vue';

import type { WorkOrderNotify } from '#/api/governance/quality/workorder-notify';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { confirm } from '@vben/common-ui';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/governance/quality/workorder-notify';

import Notifications from './notifications.vue';

const harness = vi.hoisted(() => ({
  access: {
    accessToken: '1',
    refreshToken: '',
    codes: [
      'governance:quality-work-order:query',
      'governance:quality-work-order:process',
    ],
  },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => ({ userInfo: { userId: '7' } }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.access.codes.includes(code)),
  }),
}));
vi.mock('@vben/common-ui', () => ({ confirm: vi.fn() }));
vi.mock('@vben/utils', () => ({
  formatDateTime: String,
}));
vi.mock('#/api/governance/quality/workorder-notify', () => ({
  getWorkOrderNotifyPage: vi.fn(),
  getWorkOrderNotifyDetail: vi.fn(),
  reviewWorkOrderNotify: vi.fn(),
  authorizeWorkOrderNotifyRetry: vi.fn(),
}));
vi.mock('antdv-next', () => {
  const slot = (tag: string) =>
    defineComponent({
      setup:
        (_, { attrs, slots }) =>
        () =>
          h(tag, attrs, slots.default?.()),
    });
  const input = (tag: string) =>
    defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup:
        (props, { attrs, emit }) =>
        () =>
          h(tag, {
            ...attrs,
            value: props.value,
            onInput: (event: Event) =>
              emit('update:value', (event.target as HTMLInputElement).value),
          }),
    });
  return {
    Button: slot('button'),
    Tag: slot('span'),
    Input: input('input'),
    TextArea: input('textarea'),
    Alert: defineComponent({
      props: ['message'],
      setup: (props) => () => h('p', props.message),
    }),
    Modal: defineComponent({
      props: ['open'],
      setup:
        (props, { slots }) =>
        () =>
          props.open ? h('section', slots.default?.()) : null,
    }),
    Select: defineComponent({
      props: ['value', 'options'],
      emits: ['update:value'],
      setup:
        (props, { attrs, emit }) =>
        () =>
          h(
            'select',
            {
              ...attrs,
              value: props.value,
              onChange: (event: Event) =>
                emit('update:value', (event.target as HTMLSelectElement).value),
            },
            props.options.map((option: { label: string; value: string }) =>
              h('option', { value: option.value }, option.label),
            ),
          ),
    }),
    Table: defineComponent({
      props: ['dataSource', 'columns'],
      setup:
        (props, { slots }) =>
        () =>
          h(
            'div',
            props.dataSource.flatMap((record: WorkOrderNotify) =>
              props.columns.map((column: { key: string }) =>
                slots.bodyCell?.({ record, column }),
              ),
            ),
          ),
    }),
  };
});
let app: App | undefined;
let host: HTMLDivElement;
const notification = (
  patch: Partial<WorkOrderNotify> = {},
): WorkOrderNotify => ({
  id: '9007199254740995',
  workOrderId: '9007199254740993',
  eventType: 'ASSIGN',
  receiverUserId: '7',
  payloadSummary: '工单分派提醒',
  sendStatus: 'UNKNOWN',
  retryCount: 1,
  manualRetryCount: 0,
  resolutionStatus: 'UNRESOLVED',
  resolutionVersion: 0,
  createTime: 1,
  reviews: [],
  ...patch,
});
const manual = () =>
  notification({
    resolutionStatus: 'MANUAL_NOT_DELIVERED',
    resolutionVersion: 1,
    reviews: [
      {
        id: '9007199254740997',
        actionType: 'REVIEW',
        outcome: 'NOT_DELIVERED',
        reason: '已人工核对',
        actorUserId: '7',
        resultingResolutionVersion: 1,
        createTime: 2,
      },
    ],
  });
async function flush() {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve();
    await nextTick();
  }
}
function button(label: string) {
  return [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === label,
  );
}
async function click(label: string) {
  expect(button(label)).toBeDefined();
  button(label)!.click();
  await flush();
}
async function input(selector: string, value: string) {
  const element = host.querySelector<HTMLInputElement>(selector)!;
  element.value = value;
  element.dispatchEvent(
    new Event(element.tagName === 'SELECT' ? 'change' : 'input'),
  );
  await flush();
}
async function mount() {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Notifications, { workOrderId: '9007199254740993' });
  app.mount(host);
  await click('查看通知记录');
  await click('查看通知详情');
}
beforeEach(() => {
  vi.resetAllMocks();
  harness.access = reactive({
    accessToken: '1',
    refreshToken: '',
    codes: [
      'governance:quality-work-order:query',
      'governance:quality-work-order:process',
    ],
  });
  vi.mocked(api.getWorkOrderNotifyPage).mockResolvedValue({
    list: [notification()],
    total: 1,
  });
  vi.mocked(api.getWorkOrderNotifyDetail).mockResolvedValue(notification());
  vi.mocked(api.reviewWorkOrderNotify).mockResolvedValue(manual());
  vi.mocked(api.authorizeWorkOrderNotifyRetry).mockResolvedValue(
    notification({
      sendStatus: 'FAILED',
      resolutionStatus: 'RETRY_AUTHORIZED',
      resolutionVersion: 2,
    }),
  );
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('工单通知人工核验与重投', () => {
  it('先记录未送达结论，再用最新决策和版本显式授权；不会自动重发', async () => {
    await mount();
    await input('textarea', '结合工单与站内信人工核对');
    await click('记录人工结论');
    expect(api.reviewWorkOrderNotify).toHaveBeenCalledWith(
      expect.objectContaining({
        id: '9007199254740995',
        expectedResolutionVersion: 0,
        outcome: 'NOT_DELIVERED',
      }),
    );
    expect(api.authorizeWorkOrderNotifyRetry).not.toHaveBeenCalled();
    expect(host.textContent).toContain('不会自动重新投递');
    await input('textarea', '同意再次提醒接收人');
    await click('授权重新投递');
    expect(confirm).toHaveBeenCalledWith(
      expect.stringContaining('可能产生重复提醒'),
    );
    expect(api.authorizeWorkOrderNotifyRetry).toHaveBeenCalledWith(
      expect.objectContaining({
        id: '9007199254740995',
        decisionId: '9007199254740997',
        expectedResolutionVersion: 1,
      }),
    );
    expect(host.textContent).toContain('实际发送结果以投递状态为准');
  });
  it('已送达必须提供消息编号，标记为人工参考而非自动证明', async () => {
    vi.mocked(api.reviewWorkOrderNotify).mockResolvedValue(
      notification({
        resolutionStatus: 'MANUAL_DELIVERED',
        resolutionVersion: 1,
        operatorMessageId: '9007199254740999',
      }),
    );
    await mount();
    await input('select', 'DELIVERED');
    await input('textarea', '通过接收人确认');
    await click('记录人工结论');
    expect(api.reviewWorkOrderNotify).not.toHaveBeenCalled();
    await input('input[aria-label="人工登记消息编号"]', '9007199254740999');
    await click('记录人工结论');
    expect(api.reviewWorkOrderNotify).toHaveBeenCalledWith(
      expect.objectContaining({ operatorMessageId: '9007199254740999' }),
    );
    expect(host.textContent).toContain('人工确认已送达（未系统核验）');
    expect(button('授权重新投递')).toBeUndefined();
  });
  it('未知提交只以同一requestId和原始payload重试', async () => {
    vi.mocked(api.reviewWorkOrderNotify)
      .mockRejectedValueOnce(new Error('timeout'))
      .mockResolvedValue(manual());
    await mount();
    await input('textarea', '人工核验依据');
    await click('记录人工结论');
    const first = vi.mocked(api.reviewWorkOrderNotify).mock.calls[0]![0];
    expect(host.querySelector('textarea')?.disabled).toBe(true);
    await click('原样重试未确认通知请求');
    expect(api.reviewWorkOrderNotify).toHaveBeenCalledTimes(2);
    expect(vi.mocked(api.reviewWorkOrderNotify).mock.calls[1]![0]).toEqual(
      first,
    );
  });
  it('租户切换后不发送待确认的重投授权', async () => {
    vi.mocked(api.getWorkOrderNotifyDetail).mockResolvedValue(manual());
    let resolve!: () => void;
    vi.mocked(confirm).mockReturnValue(
      new Promise<void>((done) => {
        resolve = done;
      }),
    );
    await mount();
    await input('textarea', '明确同意');
    await click('授权重新投递');
    harness.access.refreshToken = '2';
    await flush();
    resolve();
    await flush();
    expect(api.authorizeWorkOrderNotifyRetry).not.toHaveBeenCalled();
    expect(host.textContent).not.toContain('工单分派提醒');
  });
  it('重试再次未知时要求新核验，不复用旧结论', async () => {
    vi.mocked(api.getWorkOrderNotifyDetail).mockResolvedValue(
      notification({ resolutionVersion: 2, reviews: manual().reviews }),
    );
    await mount();
    expect(host.textContent).toContain('需重新人工核验');
    expect(button('记录人工结论')).toBeDefined();
    expect(button('授权重新投递')).toBeUndefined();
  });
  it('达到八次上限不可授权重投，只读人员不可提交结论', async () => {
    vi.mocked(api.getWorkOrderNotifyDetail).mockResolvedValue({
      ...manual(),
      manualRetryCount: 8,
      reviewsTruncated: true,
    });
    await mount();
    expect(host.textContent).toContain('已达到 8 次人工重试上限');
    expect(host.textContent).toContain('最近 50 条人工处置记录');
    expect(button('授权重新投递')).toBeUndefined();
    harness.access.codes = ['governance:quality-work-order:query'];
    await flush();
    await click('查看通知记录');
    await click('查看通知详情');
    expect(button('记录人工结论')).toBeUndefined();
  });
});
