import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getEtlGrantAuditPage } from '#/api/supply/app';

import EtlGrantAudit from './etl-grant-audit.vue';

const identity = vi.hoisted(() => ({
  access: {
    accessToken: '1',
    refreshToken: '',
    codes: ['supply:app:query', 'supply:app:grant'],
  },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => identity.access,
  useUserStore: () => ({ userInfo: { userId: '7' } }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => identity.access.codes.includes(code)),
  }),
}));
vi.mock('@vben/utils', () => ({
  formatDateTime: String,
}));
vi.mock('#/api/supply/app', () => ({ getEtlGrantAuditPage: vi.fn() }));
vi.mock('antdv-next', () => {
  const slot = defineComponent({
    setup:
      (_, { attrs, slots }) =>
      () =>
        h('button', attrs, slots.default?.()),
  });
  const dialog = defineComponent({
    props: ['open'],
    setup:
      (props, { slots }) =>
      () =>
        props.open ? h('section', slots.default?.()) : null,
  });
  return {
    Button: slot,
    Drawer: dialog,
    Modal: dialog,
    Alert: defineComponent({
      props: ['message'],
      setup: (props) => () => h('p', props.message),
    }),
    Table: defineComponent({
      props: ['dataSource'],
      setup:
        (props, { slots }) =>
        () =>
          h(
            'div',
            props.dataSource.map((record: object) =>
              h('div', [
                JSON.stringify(record),
                slots.bodyCell?.({ record, column: { key: 'detail' } }),
              ]),
            ),
          ),
    }),
  };
});
let app: App | undefined;
let host: HTMLDivElement;
async function flush() {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve();
    await nextTick();
  }
}
function button(label: string) {
  return [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === label,
  )!;
}
function mount() {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(EtlGrantAudit, { appId: '9007199254740993', open: true });
  app.mount(host);
}
beforeEach(() => {
  vi.resetAllMocks();
  identity.access = reactive({
    accessToken: '1',
    refreshToken: '',
    codes: ['supply:app:query', 'supply:app:grant'],
  });
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});
describe('etl 授权审计', () => {
  it('保持游标和前后主体编号完整，历史只读', async () => {
    vi.mocked(getEtlGrantAuditPage)
      .mockResolvedValueOnce({
        list: [
          {
            id: '9007199254740999',
            appId: '9007199254740993',
            grantId: '21',
            apiId: '31',
            actorId: '7',
            revision: '1',
            action: 'REGRANT',
            before: { permissionUserId: '9007199254740995' },
            after: { permissionUserId: '9007199254740997' },
            reason: '重新授予',
            result: 'SUCCESS',
            createTime: 1,
          },
        ],
        nextBeforeId: '9007199254740999',
        hasMore: true,
      })
      .mockResolvedValue({ list: [], hasMore: false });
    mount();
    await flush();
    button('前后对比').click();
    await flush();
    expect(host.textContent).toContain('9007199254740995');
    expect(host.textContent).toContain('9007199254740997');
    expect(host.textContent).toContain('此处不能恢复权限');
    button('加载更早记录').click();
    await flush();
    expect(getEtlGrantAuditPage).toHaveBeenLastCalledWith({
      appId: '9007199254740993',
      beforeId: '9007199254740999',
      limit: 20,
    });
  });
  it('仅 query 权限不请求管理审计', async () => {
    identity.access.codes = ['supply:app:query'];
    mount();
    await flush();
    expect(getEtlGrantAuditPage).not.toHaveBeenCalled();
  });
  it('丢弃旧会话的迟到历史，保留新请求状态', async () => {
    let resolve!: (
      value: Awaited<ReturnType<typeof getEtlGrantAuditPage>>,
    ) => void;
    vi.mocked(getEtlGrantAuditPage)
      .mockReturnValueOnce(
        new Promise((done) => {
          resolve = done;
        }),
      )
      .mockResolvedValue({ list: [], hasMore: false });
    mount();
    await flush();
    identity.access.refreshToken = '2';
    await flush();
    resolve({
      list: [{ appId: '9007199254740993', reason: 'old-session-audit' } as any],
      hasMore: false,
    });
    await flush();
    expect(host.textContent).not.toContain('old-session-audit');
  });
});
