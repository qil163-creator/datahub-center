import type { App, SetupContext } from 'vue';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/governance/doc';

import Page from './index.vue';

const state = vi.hoisted(() => ({
  access: {} as { accessCodes: string[]; accessToken: string; refreshToken: string },
  user: {} as { userInfo: { userId: string }; userRoles: string[] },
}));
vi.mock('@vben/access', () => ({ useAccess: () => ({ hasAccessByCodes: () => true }) }));
vi.mock('@vben/stores', () => ({ useAccessStore: () => state.access, useUserStore: () => state.user }));
vi.mock('@vben/common-ui', () => ({
  Page: (_: unknown, { slots }: SetupContext) => h('div', slots.default?.()),
  useVbenModal: () => [() => null, { setData: () => ({ open: vi.fn() }) }],
}));
vi.mock('@vben/constants', () => ({ DICT_TYPE: {} }));
vi.mock('@vben/hooks', () => ({ getDictLabel: () => '' }));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('@vben/utils', () => ({ formatDate: () => '' }));
vi.mock('antdv-next', () => {
  const slot = (_: unknown, { slots }: SetupContext) => h('div', slots.default?.());
  const Spin = Object.assign(
    (props: { spinning: boolean }, { slots }: SetupContext) =>
      h('div', { 'data-spinning': String(props.spinning) }, slots.default?.()),
    { props: { spinning: Boolean } },
  );
  return { Button: slot, Empty: slot, Input: slot, Spin, message: { warning: vi.fn() } };
});
vi.mock('#/components/list-pagination/index.vue', () => ({
  default: {
    props: ['current', 'pageSize', 'total'],
    emits: ['change'],
    setup: (props: { current: number; pageSize: number; total: number }, { emit }: SetupContext) => () =>
      h('div', { 'data-pagination': `${props.current}/${props.pageSize}/${props.total}` }, [
        h('button', { onClick: () => emit('change', 2, props.pageSize) }, '下一页'),
        h('button', { onClick: () => emit('change', 1, 20) }, '每页20条'),
      ]),
  },
}));
vi.mock('#/components/dict-tag', () => ({ DictTag: () => null }));
vi.mock('#/api/governance/doc', () => ({ getDocPage: vi.fn() }));
vi.mock('./modules/detail.vue', () => ({ default: () => null }));
vi.mock('./modules/preview.vue', () => ({ default: () => null }));

let app: App | undefined;
let host: HTMLDivElement;
async function flush() {
  await Promise.resolve();
  await nextTick();
  await nextTick();
}
beforeEach(async () => {
  vi.clearAllMocks();
  state.access = reactive({ accessCodes: [], accessToken: 'session', refreshToken: '' });
  state.user = reactive({ userInfo: { userId: '1' }, userRoles: [] });
  vi.mocked(api.getDocPage).mockResolvedValue({ list: [], total: 42 });
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Page);
  app.mount(host);
  await flush();
});
afterEach(() => {
  app?.unmount();
  host.remove();
});

describe('标准文件检索分页', () => {
  it('换页和改变每页条数时按新参数查询，空结果仍显示分页', async () => {
    expect(host.querySelector('[data-pagination]')?.getAttribute('data-pagination')).toBe('1/10/42');
    (host.querySelector('button') as HTMLButtonElement).click();
    await flush();
    expect(api.getDocPage).toHaveBeenLastCalledWith({ pageNo: 2, pageSize: 10, nameCn: undefined });
    vi.mocked(api.getDocPage).mockResolvedValueOnce({ list: [], total: 0 });
    (host.querySelectorAll('[data-pagination] button')[1] as HTMLButtonElement).click();
    await flush();
    expect(api.getDocPage).toHaveBeenLastCalledWith({ pageNo: 1, pageSize: 20, nameCn: undefined });
    expect(host.querySelector('[data-pagination]')?.getAttribute('data-pagination')).toBe('1/20/0');
  });
  it('较早请求结束时不能覆盖当前页或关闭加载状态', async () => {
    let resolveOld!: (value: { list: never[]; total: number }) => void;
    let resolveNew!: (value: { list: never[]; total: number }) => void;
    vi.mocked(api.getDocPage)
      .mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve; }))
      .mockImplementationOnce(() => new Promise((resolve) => { resolveNew = resolve; }));
    (host.querySelectorAll('[data-pagination] button')[0] as HTMLButtonElement).click();
    (host.querySelectorAll('[data-pagination] button')[1] as HTMLButtonElement).click();
    resolveOld({ list: [], total: 99 });
    await flush();
    expect(host.querySelector('[data-pagination]')?.getAttribute('data-pagination')).toBe('1/20/42');
    expect(host.querySelector('[data-spinning]')?.getAttribute('data-spinning')).toBe('true');
    resolveNew({ list: [], total: 5 });
    await flush();
    expect(host.querySelector('[data-pagination]')?.getAttribute('data-pagination')).toBe('1/20/5');
    expect(host.querySelector('[data-spinning]')?.getAttribute('data-spinning')).toBe('false');
  });
});
