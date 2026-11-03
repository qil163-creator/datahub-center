/* eslint-disable vue/one-component-per-file -- 测试桩隔离表格与弹窗运行时，保留页面异步状态和鉴权逻辑 */
import type { App, ComponentPublicInstance } from 'vue';

import type { MarketCheckApi } from '#/api/asset/market/check';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import CheckPage from './index.vue';

interface ModalOptions {
  onConfirm: () => Promise<void>;
  onOpenChange: (open: boolean) => void;
}
const mock = vi.hoisted(() => ({
  access: {
    accessToken: 'session',
    refreshToken: '',
    loginExpired: false,
    accessCodes: ['asset:market:check'],
  },
  user: {
    userInfo: { userId: '1' } as null | { userId: string },
    userRoles: [] as string[],
  },
  getPage: vi.fn(),
  getApplicants: vi.fn(),
  formFields: [] as string[],
  columnFields: [] as string[],
  check: vi.fn(),
  success: vi.fn(),
  push: vi.fn(),
  setGridOptions: vi.fn(),
  modalOptions: {} as ModalOptions,
  modal: {
    close: vi.fn(),
    open: vi.fn(),
    setState: vi.fn(),
    unlock: vi.fn(),
    lock: vi.fn(),
  },
}));

vi.mock('#/adapter/form', () => ({
  useVbenForm: (options: { schema: Array<{ fieldName: string }> }) => {
    mock.formFields = options.schema.map((item) => item.fieldName);
    return [() => null, { reset: vi.fn(), validateAndSubmit: vi.fn() }];
  },
}));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mock.push }) }));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => mock.access.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => mock.access,
  useUserStore: () => mock.user,
}));
vi.mock('@vueuse/core', () => ({ useLocalStorage: () => ref(false) }));
vi.mock('#/api/asset/market/check', () => ({
  getMarketCheckPage: mock.getPage,
  getMarketCheckApplicants: mock.getApplicants,
  checkMarketCheck: mock.check,
}));
vi.mock('#/adapter/vxe-table', () => ({
  useVbenVxeGrid: (options: {
    gridOptions: { columns: Array<{ field: string }> };
  }) => {
    mock.columnFields = options.gridOptions.columns.map((item) => item.field);
    return [() => null, { setGridOptions: mock.setGridOptions, grid: {} }];
  },
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', slots.default?.()),
  }),
  useVbenModal: (options: ModalOptions) => {
    mock.modalOptions = options;
    return [() => null, mock.modal];
  },
}));
vi.mock('antdv-next', () => {
  const box = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Alert: box,
    Button: box,
    Input: box,
    message: { success: mock.success },
    Modal: box,
    Select: box,
    TextArea: box,
    Tooltip: box,
  };
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
interface State {
  approvalReason: string;
  review?: MarketCheckApi.Ticket;
  reviewError: string;
  openReview: (row: MarketCheckApi.Ticket, decision: '3' | '4') => void;
  viewDetail: (row: MarketCheckApi.Ticket) => void;
}
let app: App | undefined;
let host: HTMLDivElement;
let state: State;
function ticket(
  overrides: Partial<MarketCheckApi.Ticket> = {},
): MarketCheckApi.Ticket {
  return {
    id: '9223372036854775807',
    origin: 'QDATA',
    displayId: '123',
    martName: '河流维表',
    applicantKey: 'QDATA:23',
    applicantName: '申请人',
    applyType: 1,
    status: '2',
    revision: 7,
    canApprove: true,
    canReject: true,
    canView: true,
    ...overrides,
  };
}
function mount() {
  host = document.createElement('div');
  app = createApp(CheckPage);
  const vm = app.mount(host) as ComponentPublicInstance;
  state = (vm.$ as unknown as { setupState: State }).setupState;
}
async function flush() {
  for (let index = 0; index < 8; index++) await Promise.resolve();
  await nextTick();
}
beforeEach(() => {
  vi.clearAllMocks();
  mock.access = reactive({
    accessToken: 'session',
    refreshToken: '',
    loginExpired: false,
    accessCodes: ['asset:market:check'],
  });
  mock.user = reactive({ userInfo: { userId: '1' }, userRoles: [] });
  mock.getPage.mockResolvedValue({ list: [], total: 0 });
  mock.getApplicants.mockResolvedValue([]);
  mock.check.mockResolvedValue(true);
  mock.modal.setState.mockReturnValue(mock.modal);
  mock.modal.open.mockImplementation(() =>
    mock.modalOptions.onOpenChange(true),
  );
  mock.modal.close.mockImplementation(() =>
    mock.modalOptions.onOpenChange(false),
  );
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('审批页面身份与生命周期', () => {
  it.each(['token', 'permission', 'expired', 'user'])(
    '未获授权时不加载敏感列表或打开审批：%s',
    (change) => {
      if (change === 'token') mock.access.accessToken = '';
      if (change === 'permission') mock.access.accessCodes = [];
      if (change === 'expired') mock.access.loginExpired = true;
      if (change === 'user') mock.user.userInfo = null;
      mount();
      state.openReview(ticket(), '3');
      expect(mock.getPage).not.toHaveBeenCalled();
      expect(mock.modal.open).not.toHaveBeenCalled();
      expect(state.review).toBeUndefined();
    },
  );

  it.each(['session', 'permission', 'user', 'role', 'expired'])(
    '身份上下文变化立即清空列表和审批表单：%s',
    async (change) => {
      const page = deferred<{ list: MarketCheckApi.Ticket[]; total: number }>();
      mock.getPage.mockReturnValueOnce(page.promise);
      mount();
      state.openReview(ticket(), '3');
      state.approvalReason = '旧身份的审批意见';
      mock.modal.close.mockClear();

      if (change === 'session') mock.access.accessToken = 'new-session';
      if (change === 'permission') mock.access.accessCodes = [];
      if (change === 'user') mock.user.userInfo = { userId: '2' };
      if (change === 'role') mock.user.userRoles = ['new-role'];
      if (change === 'expired') mock.access.loginExpired = true;

      expect(state.review).toBeUndefined();
      expect(state.approvalReason).toBe('');
      expect(mock.modal.close).toHaveBeenCalled();
      expect(mock.setGridOptions).toHaveBeenCalledWith({
        data: [],
        loading: false,
        pagerConfig: { total: 0 },
      });
      page.resolve({ list: [ticket()], total: 1 });
      await flush();
      expect(
        mock.setGridOptions.mock.calls.some(
          ([options]) => options.data?.length,
        ),
      ).toBe(false);
    },
  );

  it('卸载后丢弃列表响应', async () => {
    const page = deferred<{ list: MarketCheckApi.Ticket[]; total: number }>();
    mock.getPage.mockReturnValueOnce(page.promise);
    mount();
    app?.unmount();
    app = undefined;
    mock.setGridOptions.mockClear();
    page.resolve({ list: [ticket()], total: 1 });
    await flush();
    expect(mock.setGridOptions).not.toHaveBeenCalled();
  });

  it('只保留资产名称和状态筛选，申请人仍显示在列表中', async () => {
    mount();
    await flush();
    expect(mock.formFields).toEqual(['martName', 'status']);
    expect(mock.columnFields).toContain('applicantName');
    expect(mock.getApplicants).not.toHaveBeenCalled();
    expect(mock.getPage).toHaveBeenCalledWith(
      expect.not.objectContaining({ applicantKey: expect.anything() }),
    );
    expect(mock.getPage.mock.calls[0]?.[0]).not.toHaveProperty('applicantKey');
  });

  it('详情导航保持来源和超出安全整数范围的编号', () => {
    mount();
    state.viewDetail(ticket());
    expect(mock.push).toHaveBeenCalledWith({
      name: 'MarketCheckDetail',
      params: { id: '9223372036854775807' },
      query: { origin: 'QDATA' },
    });
    mock.push.mockClear();
    state.viewDetail(ticket({ canView: false }));
    expect(mock.push).not.toHaveBeenCalled();
  });
});

describe('审批弹窗提交生命周期', () => {
  it('按来源、长编号和修订号提交去空白意见，成功后清空并刷新', async () => {
    mount();
    await flush();
    state.openReview(ticket(), '3');
    state.approvalReason = '  同意申请  ';
    await mock.modalOptions.onConfirm();
    expect(mock.check).toHaveBeenCalledWith({
      id: '9223372036854775807',
      origin: 'QDATA',
      status: '3',
      approvalReason: '同意申请',
      expectedRevision: 7,
    });
    expect(mock.success).toHaveBeenCalledTimes(1);
    expect(mock.getPage).toHaveBeenCalledTimes(2);
    expect(state.review).toBeUndefined();
    expect(state.approvalReason).toBe('');
  });

  it('提交未完成时重复确认只发起一次请求', async () => {
    const request = deferred<boolean>();
    mock.check.mockReturnValue(request.promise);
    mount();
    state.openReview(ticket(), '3');
    state.approvalReason = '同意申请';
    const first = mock.modalOptions.onConfirm();
    const second = mock.modalOptions.onConfirm();
    const calls = mock.check.mock.calls.length;
    request.resolve(true);
    await Promise.all([first, second]);
    expect(calls).toBe(1);
    expect(mock.success).toHaveBeenCalledTimes(1);
  });

  it('关闭清空意见与错误，重新打开不复用旧工单状态', async () => {
    mount();
    state.openReview(ticket(), '4');
    await mock.modalOptions.onConfirm();
    expect(state.reviewError).toBe('请输入驳回理由');
    state.approvalReason = '未完成的意见';
    mock.modalOptions.onOpenChange(false);
    expect(state.review).toBeUndefined();
    expect(state.approvalReason).toBe('');
    expect(state.reviewError).toBe('');
    state.openReview(ticket({ id: '2' }), '3');
    expect(state.review?.id).toBe('2');
    expect(state.approvalReason).toBe('');
    expect(state.reviewError).toBe('');
    expect(mock.check).not.toHaveBeenCalled();
  });

  it('关闭旧请求后新审批仍保持锁定，旧完成不能提示成功或刷新', async () => {
    const first = deferred<boolean>();
    const second = deferred<boolean>();
    mock.check
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);
    mount();
    await flush();
    state.openReview(ticket(), '3');
    state.approvalReason = '第一张工单';
    const firstSubmit = mock.modalOptions.onConfirm();
    mock.modalOptions.onOpenChange(false);
    state.openReview(ticket({ id: '2' }), '4');
    state.approvalReason = '第二张工单';
    const secondSubmit = mock.modalOptions.onConfirm();
    mock.modal.unlock.mockClear();
    mock.modal.close.mockClear();
    first.resolve(true);
    await firstSubmit;
    expect(mock.modal.unlock).not.toHaveBeenCalled();
    expect(mock.modal.close).not.toHaveBeenCalled();
    expect(mock.success).not.toHaveBeenCalled();
    expect(mock.getPage).toHaveBeenCalledTimes(1);
    expect(state.review?.id).toBe('2');
    second.resolve(true);
    await secondSubmit;
    expect(mock.success).toHaveBeenCalledTimes(1);
    expect(mock.getPage).toHaveBeenCalledTimes(2);
  });

  it('接口失败释放锁并保留意见，不刷新或提示成功', async () => {
    const request = deferred<boolean>();
    mock.check.mockReturnValueOnce(request.promise);
    mount();
    await flush();
    state.openReview(ticket(), '4');
    state.approvalReason = '说明驳回原因';
    mock.modal.unlock.mockClear();
    const submission = mock.modalOptions.onConfirm();
    request.reject(new Error('审批状态冲突'));
    await expect(submission).rejects.toThrow('审批状态冲突');
    expect(mock.modal.unlock).toHaveBeenCalledTimes(1);
    expect(state.approvalReason).toBe('说明驳回原因');
    expect(mock.getPage).toHaveBeenCalledTimes(1);
    expect(mock.success).not.toHaveBeenCalled();
  });

  it('审批期间撤销权限立即关闭，旧请求完成不能提示成功或刷新', async () => {
    const request = deferred<boolean>();
    mock.check.mockReturnValueOnce(request.promise);
    mount();
    await flush();
    state.openReview(ticket(), '3');
    state.approvalReason = '同意申请';
    const submission = mock.modalOptions.onConfirm();
    mock.access.accessCodes = [];
    expect(state.review).toBeUndefined();
    expect(state.approvalReason).toBe('');
    request.resolve(true);
    await submission;
    expect(mock.success).not.toHaveBeenCalled();
    expect(mock.getPage).toHaveBeenCalledTimes(1);
  });

  it('卸载时已提交请求完成不能提示成功或刷新', async () => {
    const request = deferred<boolean>();
    mock.check.mockReturnValueOnce(request.promise);
    mount();
    await flush();
    state.openReview(ticket(), '3');
    state.approvalReason = '同意申请';
    const submission = mock.modalOptions.onConfirm();
    app?.unmount();
    app = undefined;
    request.resolve(true);
    await submission;
    expect(mock.success).not.toHaveBeenCalled();
    expect(mock.getPage).toHaveBeenCalledTimes(1);
  });
});
