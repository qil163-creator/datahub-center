import type { App, SetupContext } from 'vue';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/governance/masterdistribute';

import PushDetail from './push-detail.vue';

const state = vi.hoisted(() => ({
  access: {} as { accessToken: string; refreshToken: string },
}));
vi.mock('@vben/access', () => ({ useAccess: () => ({ hasAccessByCodes: () => true }) }));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => state.access,
  useUserStore: () => ({ userInfo: { userId: '1' } }),
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: () => [() => null, { close: vi.fn(), lock: vi.fn(), unlock: vi.fn(), setData: () => ({ open: vi.fn() }) }],
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('@vben/utils', () => ({ formatDateTime: (value: unknown) => String(value ?? '') }));
vi.mock('antdv-next', () => {
  const slot = (_: unknown, { slots }: SetupContext) => h('div', slots.default?.());
  return {
    Alert: slot,
    Button: slot,
    Descriptions: slot,
    DescriptionsItem: slot,
    Spin: slot,
    TabPane: slot,
    Tooltip: slot,
    Tabs: (_: unknown, { emit, slots }: SetupContext) => h('div', [
      h('button', { onClick: () => emit('update:activeKey', 'logs') }, '推送日志'),
      slots.default?.(),
    ]),
    Table: Object.assign(
      (props: { dataSource?: { id: string }[]; pagination?: boolean }) =>
        h('div', { 'data-table-pagination': String(props.pagination) }, props.dataSource?.map((row) => String(row.id))),
      { props: { dataSource: Array, pagination: Boolean } },
    ),
    message: { success: vi.fn() },
  };
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
vi.mock('#/components/detail-back-button/index.vue', () => ({ default: () => null }));
vi.mock('#/api/governance/masterdistribute', () => ({
  getMasterDistribute: vi.fn(),
  getMasterDistributeLogPage: vi.fn(),
  getPushModel: vi.fn(),
  updateMasterDistribute: vi.fn(),
}));
vi.mock('../data', () => ({ isRunBlocked: () => false, optionLabel: () => '', RUN_STATUS_OPTIONS: [], RUN_TYPE_OPTIONS: [] }));
vi.mock('../push-config', () => ({ pushModeLabel: () => '', pushScopeLabel: () => '', TRANSFORM_OPTIONS: [], validateMapping: () => '' }));
vi.mock('./log.vue', () => ({ default: () => null }));
vi.mock('./mapping-editor.vue', () => ({ default: () => null }));

let app: App | undefined;
let host: HTMLDivElement;
async function flush() {
  await Promise.resolve();
  await nextTick();
  await nextTick();
}
beforeEach(async () => {
  vi.clearAllMocks();
  state.access = reactive({ accessToken: 'session', refreshToken: '' });
  vi.mocked(api.getMasterDistribute).mockResolvedValue({ id: '42', name: '派发任务' });
  vi.mocked(api.getMasterDistributeLogPage).mockResolvedValue({ list: [], total: 0 });
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(PushDetail, { id: '42' });
  app.mount(host);
  await flush();
  (host.querySelector('button') as HTMLButtonElement).click();
  await flush();
});
afterEach(() => {
  app?.unmount();
  host.remove();
});

describe('主数据推送日志分页', () => {
  it('空记录显示分页，并使用选定的页码和页大小请求日志', async () => {
    expect(host.querySelector('[data-pagination]')?.getAttribute('data-pagination')).toBe('1/10/0');
    expect(host.querySelector('[data-table-pagination="false"]')).not.toBeNull();
    (host.querySelector('[data-pagination] button') as HTMLButtonElement).click();
    await flush();
    expect(api.getMasterDistributeLogPage).toHaveBeenLastCalledWith({ distributeId: '42', pageNo: 2, pageSize: 10 });
    (host.querySelectorAll('[data-pagination] button')[1] as HTMLButtonElement).click();
    await flush();
    expect(api.getMasterDistributeLogPage).toHaveBeenLastCalledWith({ distributeId: '42', pageNo: 1, pageSize: 20 });
  });
  it('旧页响应不会覆盖较新的分页结果', async () => {
    let resolveOld!: (value: { list: { id: string }[]; total: number }) => void;
    let resolveNew!: (value: { list: { id: string }[]; total: number }) => void;
    vi.mocked(api.getMasterDistributeLogPage)
      .mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve; }))
      .mockImplementationOnce(() => new Promise((resolve) => { resolveNew = resolve; }));
    (host.querySelectorAll('[data-pagination] button')[0] as HTMLButtonElement).click();
    (host.querySelectorAll('[data-pagination] button')[1] as HTMLButtonElement).click();
    resolveOld({ list: [{ id: 'old' }], total: 100 });
    await flush();
    expect(host.textContent).not.toContain('old');
    resolveNew({ list: [{ id: 'new' }], total: 1 });
    await flush();
    expect(host.textContent).toContain('new');
    expect(host.querySelector('[data-pagination]')?.getAttribute('data-pagination')).toBe('1/20/1');
  });
  it('登录会话变化时清空旧日志并重新查询第一页', async () => {
    (host.querySelector('[data-pagination] button') as HTMLButtonElement).click();
    await flush();
    expect(api.getMasterDistributeLogPage).toHaveBeenLastCalledWith({ distributeId: '42', pageNo: 2, pageSize: 10 });
    state.access.accessToken = 'new-session';
    await flush();
    expect(api.getMasterDistributeLogPage).toHaveBeenLastCalledWith({ distributeId: '42', pageNo: 1, pageSize: 10 });
    expect(host.querySelector('[data-pagination]')?.getAttribute('data-pagination')).toBe('1/10/0');
  });
});
