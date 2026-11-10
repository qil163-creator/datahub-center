/* eslint-disable vue/one-component-per-file */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import Page from './index.vue';

const mocks = vi.hoisted(() => ({
  reload: vi.fn(),
  loadRole: vi.fn(),
  applyFields: vi.fn(),
  getConfig: vi.fn(),
  save: vi.fn(),
}));
const first = { databaseId: '9007199254740993', schemaName: 'a', tableName: 'orders', tableReference: 'a.orders' };
const second = { ...first, schemaName: 'b', tableReference: 'b.orders' };

vi.mock('@vben/common-ui', () => ({ Page: defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) }) }));
vi.mock('@vben/constants', () => ({ CommonStatusEnum: { ENABLE: 0, DISABLE: 1 } }));
vi.mock('@vben/icons', () => ({ IconifyIcon: defineComponent(() => () => null) }));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({ accessToken: 'token' }),
  useUserStore: () => ({ userInfo: { userId: '1' } }),
}));
vi.mock('antdv-next', () => {
  const component = defineComponent({ setup: (_, { slots }) => () => h('div', [slots.default?.(), slots.action?.()]) });
  return { Alert: component, Card: component, Tag: component,
    Button: defineComponent({ setup: (_, { slots, attrs }) => () => h('button', attrs, slots.default?.()) }),
    message: { warning: vi.fn(), error: vi.fn(), success: vi.fn() } };
});
vi.mock('#/api/governance/level', () => ({ getSimpleLevelList: vi.fn().mockResolvedValue([]) }));
vi.mock('#/api/system/role', () => ({ getSimpleRoleList: vi.fn().mockResolvedValue([]) }));
vi.mock('#/api/governance/permission', () => ({
  getPermissionConfig: mocks.getConfig,
  getRuleSimpleList: vi.fn().mockResolvedValue({ list: [] }),
  savePermissionConfig: mocks.save,
}));
vi.mock('./components/batch-config-modal.vue', () => ({ default: defineComponent(() => () => null) }));
vi.mock('./components/field-permission-modal.vue', () => ({ default: defineComponent(() => () => null) }));
vi.mock('./components/database-tree-panel.vue', () => ({ default: defineComponent({
  emits: ['tableSelect'], setup: (_, { emit }) => () => h('div', [
    h('button', { id: 'first', onClick: () => emit('tableSelect', first) }, 'first'),
    h('button', { id: 'second', onClick: () => emit('tableSelect', second) }, 'second'),
  ]),
}) }));
vi.mock('./components/permission-config-panel.vue', () => ({ default: defineComponent({
  props: { configReady: Boolean }, emits: ['roleChange', 'change'], setup: (props, { emit, expose }) => {
    expose({ loadRoleConfig: mocks.loadRole, getCurrentRoleTablePermissionConfig: () => ({ roleId: '99', roleName: '角色', tablePermission: { permissionType: 0 } }) });
    return () => h('div', [h('button', { id: 'role', onClick: () => emit('roleChange', '99') }, 'role'), h('button', { id: 'other-role', onClick: () => emit('roleChange', '98') }, 'other'), h('span', { id: 'ready' }, String(props.configReady)), h('button', { id: 'edit', onClick: () => emit('change') }, 'edit')]);
  },
}) }));
vi.mock('./components/field-permission-table.vue', () => ({ default: defineComponent({
  setup: (_, { expose }) => {
    expose({ reloadColumns: mocks.reload, loadRoleFieldPermissionConfig: mocks.applyFields, getCurrentRoleFieldPermissionConfig: () => [] });
    return () => null;
  },
}) }));

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}
function config(table: typeof first) {
  return { ...table, rolePermissionConfigs: [{ roleId: '99', fieldPermissions: [{ fieldName: 'secret', permissionType: 0 }] }] };
}
async function flush() { for (let i = 0; i < 4; i++) { await Promise.resolve(); await nextTick(); } }
let app: App;
let host: HTMLDivElement;
beforeEach(() => {
  vi.clearAllMocks();
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Page);
  app.mount(host);
});
afterEach(() => { app.unmount(); host.remove(); });
function click(id: string) { (host.querySelector(id) as HTMLButtonElement).click(); }

describe('数据权限配置加载顺序', () => {
  it('选择角色时先等待字段元数据，再应用已保存字段策略', async () => {
    const pending = deferred<undefined>();
    mocks.reload.mockReturnValueOnce(pending.promise);
    mocks.getConfig.mockResolvedValueOnce(config(first));
    click('#first');
    click('#role');
    await flush();
    expect(mocks.getConfig).not.toHaveBeenCalled();
    expect(host.querySelector('#ready')?.textContent).toBe('false');
    pending.resolve(undefined);
    await flush();
    expect(mocks.getConfig).toHaveBeenCalledOnce();
    expect(mocks.applyFields).toHaveBeenCalledWith(config(first).rolePermissionConfigs[0]);
    expect(host.querySelector('#ready')?.textContent).toBe('true');
  });

  it('切换表后较旧角色响应不能覆盖当前表的配置', async () => {
    const old = deferred<ReturnType<typeof config>>();
    const latest = deferred<ReturnType<typeof config>>();
    mocks.reload.mockResolvedValue(undefined);
    mocks.getConfig.mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise);
    click('#first');
    click('#role');
    await flush();
    click('#second');
    await flush();
    latest.resolve(config(second));
    await flush();
    old.resolve(config(first));
    await flush();
    expect(mocks.applyFields).toHaveBeenCalledTimes(1);
    expect(mocks.loadRole).toHaveBeenCalledWith('99', config(second).rolePermissionConfigs[0]);
  });

  it('切换角色后较旧角色响应不能覆盖当前配置', async () => {
    const old = deferred<ReturnType<typeof config>>();
    const latest = deferred<ReturnType<typeof config>>();
    mocks.reload.mockResolvedValue(undefined);
    mocks.getConfig.mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise);
    click('#first');
    click('#role');
    await flush();
    click('#other-role');
    await flush();
    const next = config(first);
    next.rolePermissionConfigs[0]!.roleId = '98';
    latest.resolve(next);
    await flush();
    old.resolve(config(first));
    await flush();
    expect(mocks.loadRole).toHaveBeenCalledTimes(1);
    expect(mocks.loadRole).toHaveBeenCalledWith('98', next.rolePermissionConfigs[0]);
  });

  it('保存当前表时保留其他表的未保存配置', async () => {
    mocks.reload.mockResolvedValue(undefined);
    mocks.getConfig.mockImplementation(async (_databaseId, table) => config(table));
    mocks.save.mockResolvedValue(undefined);
    click('#first');
    click('#role');
    await flush();
    click('#edit');
    click('#second');
    await flush();
    click('#edit');
    await flush();
    const save = [...host.querySelectorAll('button')].find((item) => item.textContent?.includes('保存配置'));
    save?.click();
    await flush();
    expect(mocks.save).toHaveBeenCalledOnce();
    expect(mocks.save.mock.calls[0]?.[0]).toMatchObject({ schemaName: 'b', tableName: 'orders' });
    const calls = mocks.getConfig.mock.calls.length;
    click('#first');
    await flush();
    expect(mocks.getConfig).toHaveBeenCalledTimes(calls);
    expect(host.textContent).toContain('有未保存的配置');
  });

  it('字段加载失败时不显示提示条且禁止编辑', async () => {
    mocks.reload.mockRejectedValueOnce(new Error('failed')).mockResolvedValueOnce(undefined);
    mocks.getConfig.mockResolvedValue(config(first));
    click('#first');
    click('#role');
    await flush();
    expect(host.querySelector('#ready')?.textContent).toBe('false');
    expect(mocks.getConfig).not.toHaveBeenCalled();
    expect(host.textContent).not.toContain('字段或角色配置加载失败');
    click('#edit');
    expect(mocks.save).not.toHaveBeenCalled();
  });
});
