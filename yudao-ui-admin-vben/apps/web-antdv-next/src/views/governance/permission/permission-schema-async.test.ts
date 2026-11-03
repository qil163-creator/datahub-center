import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getPermissionColumnList } from '#/api/governance/permission';

import FieldTable from './components/field-permission-table.vue';

vi.mock('#/api/governance/permission', () => ({ getPermissionColumnList: vi.fn() }));
vi.mock('@vben/constants', () => ({ CommonStatusEnum: { ENABLE: 0, DISABLE: 1 } }));
vi.mock('@vben/icons', () => ({ IconifyIcon: defineComponent(() => () => null) }));
vi.mock('antdv-next', () => {
  const passthrough = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) });
  return {
    Button: passthrough, Empty: passthrough, Select: passthrough,
    Spin: passthrough, Tag: passthrough,
    Table: defineComponent({
      props: { dataSource: Array },
      setup: (props) => () => h('pre', JSON.stringify(props.dataSource)),
    }),
  };
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

let app: App | undefined;
let host: HTMLDivElement;
const exposed = ref<InstanceType<typeof FieldTable>>();
const table = (schemaName: string) => ({
  databaseId: '9223372036854775807', schemaName, tableName: 'Orders', tableReference: `"${schemaName}"."Orders"`,
});
const currentTable = ref<ReturnType<typeof table>>();

beforeEach(() => {
  vi.clearAllMocks();
  currentTable.value = undefined;
  host = document.createElement('div');
  document.body.append(host);
  app = createApp({ render: () => h(FieldTable, {
    ref: exposed, currentRoleId: '9007199254740993', currentTable: currentTable.value, maskingRules: [], securityLevels: [],
  }) });
  app.mount(host);
});
afterEach(() => { app?.unmount(); app = undefined; host.remove(); });

describe('permission schema latest-wins and exact field mapping', () => {
  it('discards a slower column response for an earlier same-name table in another schema', async () => {
    const old = deferred<{ fieldName: string }[]>();
    const current = deferred<{ fieldName: string }[]>();
    vi.mocked(getPermissionColumnList).mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise);
    currentTable.value = table('Old');
    await nextTick();
    const earlier = exposed.value!.reloadColumns();
    currentTable.value = table('Current');
    await nextTick();
    const latest = exposed.value!.reloadColumns();
    current.resolve([{ fieldName: 'new_column' }]);
    await latest;
    await nextTick();
    old.resolve([{ fieldName: 'old_column' }]);
    await earlier;
    await nextTick();
    expect(host.textContent).toContain('new_column');
    expect(host.textContent).not.toContain('old_column');
    expect(host.textContent).not.toContain('请选择数据表');
    expect(getPermissionColumnList).toHaveBeenNthCalledWith(2, '9223372036854775807', table('Current'));
  });

  it('clearing the selection invalidates a still pending columns request', async () => {
    const pending = deferred<{ fieldName: string }[]>();
    vi.mocked(getPermissionColumnList).mockReturnValueOnce(pending.promise);
    currentTable.value = table('Old');
    await nextTick();
    const earlier = exposed.value!.reloadColumns();
    currentTable.value = undefined;
    await nextTick();
    await exposed.value!.reloadColumns();
    pending.resolve([{ fieldName: 'stale_column' }]);
    await earlier;
    await nextTick();
    expect(host.textContent).not.toContain('stale_column');
    expect(host.textContent).toContain('请选择数据表');
  });

  it('never folds two case-distinct physical columns into one policy', async () => {
    vi.mocked(getPermissionColumnList).mockResolvedValue([{ fieldName: 'Mobile' }, { fieldName: 'mobile' }]);
    currentTable.value = table('Exact');
    await nextTick();
    await exposed.value!.reloadColumns();
    exposed.value!.loadRoleFieldPermissionConfig({
      roleId: '9007199254740993', fieldPermissions: [{ fieldName: 'mobile', permissionType: 0 }],
    });
    const configs = exposed.value!.getCurrentRoleFieldPermissionConfig();
    expect(configs).toHaveLength(1);
    expect(configs[0]).toMatchObject({ fieldName: 'mobile', permissionType: 0 });
  });
});
