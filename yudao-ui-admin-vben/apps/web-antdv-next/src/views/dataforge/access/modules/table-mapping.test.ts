import type { App } from 'vue';

import type { DataforgeAccessApi } from '#/api/dataforge/access';

import { createApp, h, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getTableMapping,
  updateTableMappings,
  validateDorisOptions,
  validateTableMappings,
} from './table-mapping';
import TableMapping from './table-mapping.vue';

const rows: DataforgeAccessApi.PreviewNameMapper[] = [
  { originalName: 'orders', targetName: 'orders_copy', remarks: '订单' },
  { originalName: 'customers', targetName: 'customers_copy', remarks: '客户' },
];

function dorisOptions(name = 'p_orders'): DataforgeAccessApi.DorisOptions {
  return {
    distributionType: 'HASH',
    distributionColumns: ['id'],
    bucketMode: 'FIXED',
    bucketCount: 10,
    replicationNum: 1,
    partition: {
      kind: 'RANGE',
      columns: ['created_at'],
      partitions: [{ name, values: [['2026-01-01'], ['2026-02-01']] }],
    },
  };
}

let app: App | undefined;
let host: HTMLDivElement;
let state: {
  doris: boolean;
  mappings: Record<string, DataforgeAccessApi.TableMapping>;
};
const editor = ref<InstanceType<typeof TableMapping>>();
const update = vi.fn();
const selectSources = vi.fn();
const remove = vi.fn();

async function flush() {
  for (let index = 0; index < 12; index++) await Promise.resolve();
  await nextTick();
}

async function mount() {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp({
    setup: () => () => h(TableMapping, {
      ref: editor,
      rows,
      doris: state.doris,
      modelValue: state.mappings,
      onRemove: remove,
      onSelectSources: selectSources,
      'onUpdate:modelValue': (value) => {
        update(value);
        state.mappings = value;
      },
    }),
  });
  app.mount(host);
  await flush();
}

async function clickButton(text: string, element: ParentNode = host) {
  const button = [...element.querySelectorAll<HTMLButtonElement>('button')]
    .find((item) => item.textContent?.replaceAll(/\s/g, '') === text);
  expect(button, `button ${text}`).toBeDefined();
  button!.click();
  await flush();
}

function dialog() {
  return document.querySelector<HTMLDivElement>('.ant-modal')!;
}

async function input(label: string, value: string, element: ParentNode = document) {
  const field = element.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`);
  expect(field, `input ${label}`).not.toBeNull();
  field!.value = value;
  field!.dispatchEvent(new Event('input', { bubbles: true }));
  field!.dispatchEvent(new Event('change', { bubbles: true }));
  field!.dispatchEvent(new FocusEvent('blur', { bubbles: true }));
  await flush();
}

async function selectRow(name: string) {
  const checkbox = host.querySelector<HTMLInputElement>(`tr[data-row-key="${name}"] input[type="checkbox"]`);
  expect(checkbox).not.toBeNull();
  checkbox!.click();
  await flush();
}

beforeEach(() => {
  update.mockReset();
  selectSources.mockReset();
  remove.mockReset();
  state = reactive({
    doris: true,
    mappings: {
      orders: {
        sourcePrimaryKeys: ['order_id'],
        regexColumnMapper: [{ fromPattern: '^ID$', toValue: 'order_id' }],
        increaseColumnName: 'updated_at',
        targetTableName: 'saved_orders',
        dorisOptions: dorisOptions(),
      },
      customers: { dorisOptions: dorisOptions('p_customers') },
    },
  });
});

afterEach(async () => {
  app?.unmount();
  app = undefined;
  await flush();
  host?.remove();
  await vi.waitUntil(() => document.querySelector('.ant-modal-root') === null);
});

describe('multi-table mapping with real Antdv Next controls', () => {
  it('requests source selection or removal without discarding saved column mapping drafts', async () => {
    state.doris = false;
    await mount();
    await clickButton('新增');
    expect(selectSources).toHaveBeenCalledOnce();
    host.querySelector<HTMLButtonElement>('button[aria-label="删除 orders"]')!.click();
    await flush();
    expect(remove).toHaveBeenCalledWith('orders');
    expect(update).not.toHaveBeenCalled();
    expect(state.mappings.orders?.sourcePrimaryKeys).toEqual(['order_id']);
  });
  it('preserves existing field and key rules while matching and prefixing selected tables', async () => {
    await mount();
    expect(update).not.toHaveBeenCalled();
    expect(host.textContent).toContain('订单');
    await selectRow('orders');
    await clickButton('配置目标表默认前缀');
    await input('目标表默认前缀', 'ods_');
    await clickButton('确定', dialog());
    expect(state.mappings.orders?.targetTableName).toBe('ods_orders_copy');
    expect(state.mappings.customers?.targetTableName).toBeUndefined();
    expect(state.mappings.orders?.sourcePrimaryKeys).toEqual(['order_id']);
    expect(state.mappings.orders?.regexColumnMapper).toEqual([{ fromPattern: '^ID$', toValue: 'order_id' }]);
    expect(state.mappings.orders?.increaseColumnName).toBe('updated_at');
    await input('orders 目标表名称', 'manual_orders', host);
    expect(state.mappings.orders?.targetTableName).toBe('manual_orders');
    await clickButton('自动匹配目标表');
    expect(state.mappings.orders?.targetTableName).toBe('orders_copy');
    expect(state.mappings.orders?.targetTableComment).toBe('订单');
  });

  it('does not mutate saved Doris settings when the configuration dialog is cancelled', async () => {
    await mount();
    host.querySelector<HTMLButtonElement>('button[aria-label="配置 orders"]')!.click();
    await flush();
    await input('副本数量', '3', dialog());
    await input('p_orders created_at 值 2', '2026-03-01', dialog());
    await clickButton('取消', dialog());
    expect(update).not.toHaveBeenCalled();
    expect(state.mappings.orders?.dorisOptions).toEqual(dorisOptions());
  });

  it('applies batch bucketing to selected tables while retaining each table partition and source keys', async () => {
    await mount();
    await selectRow('orders');
    await selectRow('customers');
    await input('搜索来源表', 'orders', host);
    await clickButton('目标表分桶设置');
    await input('分桶字段', 'id, tenant_id', dialog());
    await input('副本数量', '3', dialog());
    await clickButton('确定', dialog());
    expect(state.mappings.orders?.dorisOptions).toMatchObject({
      distributionType: 'HASH', distributionColumns: ['id', 'tenant_id'], bucketMode: 'AUTO', replicationNum: 3,
    });
    expect(state.mappings.orders?.dorisOptions?.bucketCount).toBeUndefined();
    expect(state.mappings.orders?.dorisOptions?.partition?.partitions[0]?.name).toBe('p_orders');
    expect(state.mappings.customers?.dorisOptions?.partition?.partitions[0]?.name).toBe('p_customers');
    expect(state.mappings.customers?.dorisOptions?.replicationNum).toBe(3);
    expect(state.mappings.orders?.sourcePrimaryKeys).toEqual(['order_id']);
  });

  it('applies only the partition when all selected tables already have a layout', async () => {
    state.mappings.customers!.dorisOptions!.bucketCount = 20;
    await mount();
    await selectRow('orders');
    await selectRow('customers');
    await clickButton('目标表分区设置');
    expect(dialog().querySelector('input[aria-label="分桶字段"]')).toBeNull();
    await input('p_orders created_at 值 2', '2026-04-01', dialog());
    await clickButton('确定', dialog());
    expect(state.mappings.orders?.dorisOptions?.partition?.partitions[0]?.values[1]).toEqual(['2026-04-01']);
    expect(state.mappings.customers?.dorisOptions?.partition?.partitions[0]?.values[1]).toEqual(['2026-04-01']);
    expect(state.mappings.orders?.dorisOptions?.bucketCount).toBe(10);
    expect(state.mappings.customers?.dorisOptions?.bucketCount).toBe(20);
  });

  it('requires an explicit HASH column when configuring a table without overriding untouched defaults', async () => {
    state.mappings = {};
    await mount();
    expect(update).not.toHaveBeenCalled();
    expect(editor.value!.validate()).toBe(true);
    host.querySelector<HTMLButtonElement>('button[aria-label="配置 orders"]')!.click();
    await flush();
    await clickButton('确定', dialog());
    expect(dialog().textContent).toContain('HASH 分桶字段不能为空');
    expect(update).not.toHaveBeenCalled();
    await input('分桶字段', 'id', dialog());
    await clickButton('确定', dialog());
    expect(state.mappings.orders?.dorisOptions).toMatchObject({ distributionType: 'HASH', distributionColumns: ['id'], bucketMode: 'AUTO', replicationNum: 1 });
    expect(Object.hasOwn(state.mappings, 'customers')).toBe(false);
  });

  it('hides Doris controls for custom targets and retains saved options without creating overrides', async () => {
    state.doris = false;
    await mount();
    expect(host.textContent).not.toContain('目标表分区设置');
    expect(host.textContent).not.toContain('副本数量');
    expect(host.querySelector('button[aria-label="配置 orders"]')).toBeNull();
    expect(update).not.toHaveBeenCalled();
    expect(state.mappings.orders?.dorisOptions).toEqual(dorisOptions());
    await clickButton('列表重置');
    expect(state.mappings.orders?.targetTableName).toBeUndefined();
    expect(state.mappings.orders?.dorisOptions).toEqual(dorisOptions());
    expect(state.mappings.orders?.sourcePrimaryKeys).toEqual(['order_id']);
  });

  it('reports an explicitly empty or duplicate target instead of silently falling back', async () => {
    await mount();
    await input('orders 目标表名称', '', host);
    expect(state.mappings.orders?.targetTableName).toBe('');
    expect(editor.value!.validate()).toBe(false);
    await flush();
    expect(host.textContent).toContain('目标表名称不能为空');
    await input('orders 目标表名称', 'customers_copy', host);
    expect(editor.value!.validate()).toBe(false);
    await flush();
    expect(host.textContent).toContain('目标表名称 customers_copy 重复');
  });
});

describe('table mapping data boundaries', () => {
  it('supports a __proto__ source name without reading or modifying a prototype', () => {
    const current = {};
    expect(getTableMapping(current, '__proto__')).toEqual({});
    const updated = updateTableMappings(current, ['__proto__'], (mapping) => ({ ...mapping, targetTableName: 'proto_copy' }));
    expect(Object.hasOwn(updated, '__proto__')).toBe(true);
    expect(Object.getPrototypeOf(updated)).toBe(Object.prototype);
    expect(getTableMapping(updated, '__proto__').targetTableName).toBe('proto_copy');
    expect(Object.hasOwn(current, '__proto__')).toBe(false);
    expect(validateTableMappings([{ originalName: '__proto__', targetName: 'original' }], updated, false)).toBe('');
  });

  it('validates structured range tuples and HASH columns before configuration is accepted', () => {
    expect(validateDorisOptions(dorisOptions())).toBe('');
    const incomplete = dorisOptions();
    incomplete.partition!.partitions[0]!.values = [['2026-01-01']];
    expect(validateDorisOptions(incomplete)).toContain('下界和上界');
    incomplete.partition!.partitions[0]!.values = [['2026-01-01'], ['']];
    expect(validateDorisOptions(incomplete)).toContain('不能为空');
    incomplete.partition = undefined;
    incomplete.distributionColumns = [];
    expect(validateDorisOptions(incomplete)).toContain('HASH 分桶字段');
  });
});
