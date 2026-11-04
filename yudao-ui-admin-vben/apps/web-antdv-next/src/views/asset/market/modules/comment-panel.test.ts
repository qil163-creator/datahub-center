/* eslint-disable vue/one-component-per-file -- 测试桩集中验证评论写入边界 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import CommentPanel from './comment-panel.vue';

const mock = vi.hoisted(() => ({
  access: {
    accessCodes: ['asset:market:query'],
    accessToken: 'session',
    refreshToken: '',
    loginExpired: false,
  },
  user: { userInfo: { userId: '1' }, userRoles: [] as string[] },
  page: vi.fn(),
  create: vi.fn(),
  reply: vi.fn(),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => mock.access,
  useUserStore: () => mock.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => mock.access.accessCodes.includes(code)),
  }),
}));
vi.mock('#/api/asset/market', () => ({
  getMarketCommentPage: mock.page,
  createMarketComment: mock.create,
  replyMarketComment: mock.reply,
}));
vi.mock('antdv-next', () => {
  const box = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('section', slots.default?.()),
  });
  return {
    Button: box,
    Empty: box,
    Input: box,
    Rate: box,
    Select: box,
    Spin: box,
    Tag: box,
    TextArea: box,
    message: { warning: vi.fn(), success: vi.fn() },
  };
});

interface State {
  commentStr: string;
  commentKind: number;
  problemType: string;
  impactScope: string;
  submitting: boolean;
  replyMap: Record<string, string>;
  submit: () => Promise<void>;
  reply: (id: string) => Promise<void>;
}
let app: App | undefined;
let host: HTMLDivElement;
let state: State;
async function flush() {
  for (let index = 0; index < 6; index++) await Promise.resolve();
  await nextTick();
}
function mount(assetId = '9007199254740993') {
  app = createApp(CommentPanel, { assetId });
  state = (app.mount(host).$ as unknown as { setupState: State }).setupState;
}
beforeEach(() => {
  vi.resetAllMocks();
  mock.access = reactive({
    accessCodes: ['asset:market:query'],
    accessToken: 'session',
    refreshToken: '',
    loginExpired: false,
  });
  mock.user = reactive({ userInfo: { userId: '1' }, userRoles: [] });
  let sequence = 0;
  vi.stubGlobal('crypto', {
    randomUUID: vi.fn(
      () => `00000000-0000-4000-8000-${String(++sequence).padStart(12, '0')}`,
    ),
  });
  mock.page.mockResolvedValue({
    list: [{ id: '1', commentStr: '已有评价' }],
    total: 1,
  });
  mock.create.mockResolvedValue('2');
  mock.reply.mockResolvedValue(true);
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
  vi.unstubAllGlobals();
});

function fillCheck(content = '校核问题') {
  state.commentKind = 2;
  state.commentStr = content;
  state.problemType = '口径';
}

function deferred() {
  let resolve!: (value: string) => void;
  const promise = new Promise<string>((finish) => {
    resolve = finish;
  });
  return { promise, resolve };
}

describe('structured check operation identity', () => {
  beforeEach(() => mock.access.accessCodes.push('asset:market:comment'));

  it('preserves the draft and stable retry UUID across a same-user token refresh', async () => {
    mount();
    fillCheck('网络结果未知的同一校核');
    mock.create.mockRejectedValueOnce(new Error('网络结果未知'));
    await expect(state.submit()).rejects.toThrow('网络结果未知');
    const key = mock.create.mock.calls[0]![0].idempotentKey;
    mock.access.accessToken = 'refreshed-access';
    mock.access.refreshToken = 'refreshed-refresh';
    expect(state.commentStr).toBe('网络结果未知的同一校核');
    expect(state.problemType).toBe('口径');
    await state.submit();
    expect(mock.create.mock.calls[1]![0].idempotentKey).toBe(key);
  });

  it('uses distinct short operation UUIDs for independent reports with the same first 32 characters', async () => {
    mount('9223372036854775807');
    const prefix = '相'.repeat(32);
    fillCheck(`${prefix}第一处问题`);
    await state.submit();
    fillCheck(`${prefix}第二处问题`);
    await state.submit();
    const first = mock.create.mock.calls[0]![0];
    const second = mock.create.mock.calls[1]![0];
    expect(first.assetId).toBe('9223372036854775807');
    expect(first.idempotentKey).toHaveLength(36);
    expect(second.idempotentKey).toHaveLength(36);
    expect(first.idempotentKey).not.toBe(second.idempotentKey);
  });

  it('keeps the same key after an uncertain failure and releases the submit lock for retry', async () => {
    mount();
    fillCheck();
    mock.create.mockRejectedValueOnce(new Error('网络结果未知'));
    await expect(state.submit()).rejects.toThrow('网络结果未知');
    expect(state.submitting).toBe(false);
    await state.submit();
    expect(mock.create.mock.calls[0]![0].idempotentKey).toBe(
      mock.create.mock.calls[1]![0].idempotentKey,
    );
  });

  it('starts a new operation when the report content changes after failure', async () => {
    mount();
    fillCheck();
    mock.create.mockRejectedValueOnce(new Error('网络结果未知'));
    await expect(state.submit()).rejects.toThrow('网络结果未知');
    state.problemType = '漏数';
    await state.submit();
    expect(mock.create.mock.calls[0]![0].idempotentKey).not.toBe(
      mock.create.mock.calls[1]![0].idempotentKey,
    );
  });

  it('rejects double submission and preserves a new draft typed while the old request is pending', async () => {
    mount();
    fillCheck();
    const pending = deferred();
    mock.create.mockReturnValueOnce(pending.promise);
    const first = state.submit();
    await state.submit();
    expect(mock.create).toHaveBeenCalledOnce();
    state.commentStr = '请求期间新写的校核';
    pending.resolve('2');
    await first;
    expect(state.commentStr).toBe('请求期间新写的校核');
    expect(state.submitting).toBe(false);
    await state.submit();
    expect(mock.create.mock.calls[0]![0].idempotentKey).not.toBe(
      mock.create.mock.calls[1]![0].idempotentKey,
    );
  });

  it('does not let an old session completion clear or unlock a new session request', async () => {
    mount();
    fillCheck();
    const oldRequest = deferred();
    const newRequest = deferred();
    mock.create
      .mockReturnValueOnce(oldRequest.promise)
      .mockReturnValueOnce(newRequest.promise);
    const first = state.submit();
    mock.access.refreshToken = 'new-session';
    fillCheck('新会话校核');
    const second = state.submit();
    oldRequest.resolve('2');
    await first;
    expect(state.commentStr).toBe('新会话校核');
    expect(state.submitting).toBe(true);
    expect(mock.create.mock.calls[0]![0].idempotentKey).not.toBe(
      mock.create.mock.calls[1]![0].idempotentKey,
    );
    newRequest.resolve('3');
    await second;
    expect(state.submitting).toBe(false);
  });

  it('does not send a structured check without a secure request identifier', async () => {
    mount();
    fillCheck();
    vi.stubGlobal('crypto', {});
    await state.submit();
    expect(mock.create).not.toHaveBeenCalled();
    expect(state.submitting).toBe(false);
  });
});

describe('market comment action permissions', () => {
  it('keeps the reader available while hiding and rejecting all writes without action permissions', async () => {
    mount();
    await flush();
    expect(host.textContent).toContain('已有评价');
    expect(host.textContent).not.toContain('发表评价');
    expect(host.textContent).not.toContain('回复');
    state.commentStr = '无权评论';
    state.replyMap['1'] = '无权回复';
    await state.submit();
    await state.reply('1');
    expect(mock.create).not.toHaveBeenCalled();
    expect(mock.reply).not.toHaveBeenCalled();
  });
  it('permits a comment only with its own permission and rejects it immediately after revocation', async () => {
    mock.access.accessCodes.push('asset:market:comment');
    mount();
    await flush();
    expect(host.textContent).toContain('发表评价');
    state.commentStr = '使用反馈';
    await state.submit();
    expect(mock.create).toHaveBeenCalledWith(
      expect.objectContaining({
        assetId: '9007199254740993',
        commentStr: '使用反馈',
      }),
    );
    state.replyMap['1'] = '无权回复';
    await state.reply('1');
    expect(mock.reply).not.toHaveBeenCalled();
    mock.access.accessCodes = ['asset:market:query'];
    state.commentStr = '已被撤权';
    await state.submit();
    expect(mock.create).toHaveBeenCalledOnce();
  });
  it('permits asset-side replies only with check permission', async () => {
    mock.access.accessCodes.push('asset:market:check');
    mount();
    await flush();
    state.replyMap['1'] = '已核实';
    await state.reply('1');
    expect(mock.reply).toHaveBeenCalledWith({ id: '1', replyStr: '已核实' });
    state.commentStr = '无权评价';
    await state.submit();
    expect(mock.create).not.toHaveBeenCalled();
    mock.access.accessCodes = ['asset:market:query'];
    state.replyMap['1'] = '已撤权';
    await state.reply('1');
    expect(mock.reply).toHaveBeenCalledOnce();
  });
});
