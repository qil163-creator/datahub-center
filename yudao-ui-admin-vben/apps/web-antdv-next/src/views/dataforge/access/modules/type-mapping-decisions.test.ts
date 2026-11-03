import type { App, SetupContext } from 'vue';

import type { DataforgeAccessApi } from '#/api/dataforge/access';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { previewTypeMap } from '#/api/dataforge/access';

import Decisions from './type-mapping-decisions.vue';

vi.mock('#/api/dataforge/access', () => ({ previewTypeMap: vi.fn() }));
vi.mock('antdv-next', () => ({
  Button: defineComponent({
    props: { disabled: Boolean, loading: Boolean },
    setup:
      (props, { attrs, slots }) =>
      () =>
        h(
          'button',
          {
            ...attrs,
            disabled: props.disabled,
            'data-loading': String(props.loading),
          },
          slots.default?.(),
        ),
  }),
  Alert: (props: { message?: string }) =>
    h('p', { role: 'alert' }, props.message),
  Tag: (_: unknown, { slots }: SetupContext) => h('span', slots.default?.()),
  Select: defineComponent({
    props: { value: String, options: Array },
    emits: ['update:value'],
    setup:
      (props, { attrs, emit }) =>
      () =>
        h(
          'select',
          {
            ...attrs,
            value: props.value ?? '',
            onChange: (event: Event) =>
              emit('update:value', (event.target as HTMLSelectElement).value),
          },
          [
            h('option', { value: '' }, '选择表'),
            ...(props.options as { label: string; value: string }[]).map(
              (item) => h('option', { value: item.value }, item.label),
            ),
          ],
        ),
  }),
  Checkbox: defineComponent({
    props: { checked: Boolean, disabled: Boolean },
    emits: ['update:checked'],
    setup:
      (props, { attrs, emit, slots }) =>
      () =>
        h('label', attrs, [
          h('input', {
            type: 'checkbox',
            checked: props.checked,
            disabled: props.disabled,
            onChange: (event: Event) =>
              emit(
                'update:checked',
                (event.target as HTMLInputElement).checked,
              ),
          }),
          slots.default?.(),
        ]),
  }),
  Table: Object.assign(
    (props: { columns: any[]; dataSource: any[] }, { slots }: SetupContext) =>
      h(
        'div',
        props.dataSource.flatMap((record) =>
          props.columns.map((column) =>
            h(
              'div',
              slots.bodyCell?.({ column, record }) ?? record[column.dataIndex],
            ),
          ),
        ),
      ),
    { props: { columns: Array, dataSource: Array } },
  ),
}));

function result(
  table = 'orders',
  fingerprint = 'a'.repeat(64),
): DataforgeAccessApi.TypeMappingPlan {
  return {
    schemaVersion: 1,
    sourceProduct: 'SQLITE3',
    sourceSchema: 'main',
    sourceTable: table,
    targetProduct: 'POSTGRESQL',
    targetSchema: 'public',
    targetTable: `${table}_copy`,
    fidelity: 'LOSSY',
    accepted: true,
    totalColumns: 3,
    truncated: false,
    columns: [
      {
        column: 'amount',
        sourceNativeType: 'INTEGER',
        logicalType: 'INT64',
        targetNativeType: 'bigint',
        fidelity: 'LOSSY',
        accepted: true,
        mappingFingerprint: fingerprint,
        lossCodes: ['SQLITE_AFFINITY', 'DEFAULT_DROPPED'],
        issues: [
          {
            severity: 'WARNING',
            code: 'SQLITE_AFFINITY',
            detail: 'SQLite 允许混合存储，需要明确接受类型约束变化',
          },
        ],
      },
      {
        column: 'key',
        sourceNativeType: 'TEXT',
        logicalType: 'TEXT',
        targetNativeType: 'text',
        fidelity: 'EXACT',
        accepted: true,
        mappingFingerprint: 'b'.repeat(64),
        lossCodes: [],
        issues: [],
      },
      {
        column: 'special',
        sourceNativeType: 'UNKNOWN',
        logicalType: null,
        targetNativeType: null,
        fidelity: 'UNSUPPORTED',
        accepted: false,
        mappingFingerprint: 'c'.repeat(64),
        lossCodes: [],
        issues: [
          {
            severity: 'ERROR',
            code: 'UNKNOWN_TYPE',
            detail: '原生类型尚未支持',
          },
        ],
      },
    ],
  };
}
function acknowledgement(
  table = 'orders',
  fingerprint = 'a'.repeat(64),
): DataforgeAccessApi.TypeMappingAcknowledgement {
  return {
    targetProduct: 'POSTGRESQL',
    targetSchema: 'public',
    targetTable: `${table}_copy`,
    column: 'amount',
    mappingFingerprint: fingerprint,
    lossCodes: ['SQLITE_AFFINITY', 'DEFAULT_DROPPED'],
  };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
let app: App | undefined;
let host: HTMLDivElement;
let errors: unknown[];
let updates: DataforgeAccessApi.TypeMappingAcknowledgement[][];
let state: {
  decisions: DataforgeAccessApi.TypeMappingAcknowledgement[];
  request: DataforgeAccessApi.TypeMappingPreviewReq;
  tables: { label: string; value: string }[];
};
async function flush() {
  for (let index = 0; index < 8; ++index) await Promise.resolve();
  await nextTick();
}
async function mount() {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp({
    render: () =>
      h(Decisions, {
        modelValue: state.decisions,
        request: state.request,
        tables: state.tables,
        'onUpdate:modelValue': (value) => {
          updates.push(value);
          state.decisions = value;
        },
      }),
  });
  app.config.errorHandler = (error) => errors.push(error);
  app.mount(host);
  await flush();
}
async function selectTable(table = 'orders') {
  const element = host.querySelector('select')!;
  element.value = table;
  element.dispatchEvent(new Event('change'));
  await flush();
}
async function preview() {
  (
    host.querySelector(
      '[data-testid="type-mapping-preview"]',
    ) as HTMLButtonElement
  ).click();
  await flush();
}
async function check(checked = true) {
  const input = host.querySelector(
    '[data-testid="type-mapping-accept-amount"] input',
  ) as HTMLInputElement;
  input.checked = checked;
  input.dispatchEvent(new Event('change'));
  await flush();
}
beforeEach(() => {
  vi.resetAllMocks();
  errors = [];
  updates = [];
  state = reactive({
    decisions: [] as DataforgeAccessApi.TypeMappingAcknowledgement[],
    request: {
      schemaVersion: 1,
      sourceDatabaseId: '9007199254740993',
      targetDatabaseId: '9007199254740995',
      sourceType: 1,
      mapperType: 'ALL',
      sourcePrimaryKeys: [],
      syncDefaultVal: false,
    },
    tables: [
      { label: 'orders', value: 'orders' },
      { label: 'items', value: 'items' },
    ],
  });
  vi.mocked(previewTypeMap).mockResolvedValue(result());
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
  if (errors.length > 0) throw new AggregateError(errors, 'Component raised an unexpected error');
});

describe('type mapping decision component', () => {
  it('requires a selected table and an explicit per-column choice even if the server marks a previous plan accepted', async () => {
    await mount();
    expect((host.querySelector('button') as HTMLButtonElement).disabled).toBe(
      true,
    );
    await selectTable();
    await preview();
    expect(previewTypeMap).toHaveBeenCalledExactlyOnceWith({
      ...state.request,
      previewTableName: 'orders',
    });
    expect(state.decisions).toEqual([]);
    expect(host.querySelectorAll('input[type="checkbox"]')).toHaveLength(1);
    expect(host.textContent).toContain('不能确认放行');
    expect((host.querySelector('input') as HTMLInputElement).checked).toBe(
      false,
    );
    await check();
    expect(state.decisions).toEqual([acknowledgement()]);
    await check(false);
    expect(state.decisions).toEqual([]);
  });

  it('retains other table decisions but removes a stale fingerprint or incomplete reason set on fresh preview', async () => {
    state.decisions = [
      acknowledgement('items'),
      acknowledgement('orders', 'f'.repeat(64)),
    ];
    await mount();
    await selectTable();
    await preview();
    expect(state.decisions).toEqual([acknowledgement('items')]);
    await check();
    await selectTable('items');
    vi.mocked(previewTypeMap).mockResolvedValue(result('items'));
    await preview();
    expect((host.querySelector('input') as HTMLInputElement).checked).toBe(
      true,
    );
    await check(false);
    expect(state.decisions).toEqual([acknowledgement()]);
    state.decisions = [
      { ...acknowledgement('items'), lossCodes: ['SQLITE_AFFINITY'] },
    ];
    await preview();
    expect(state.decisions).toEqual([]);
  });

  it('keeps the newest request loading and ignores a previous context response', async () => {
    const old = deferred<DataforgeAccessApi.TypeMappingPlan>();
    const latest = deferred<DataforgeAccessApi.TypeMappingPlan>();
    vi.mocked(previewTypeMap)
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(latest.promise);
    await mount();
    await selectTable();
    await preview();
    state.request.targetDatabaseId = '9007199254740997';
    await flush();
    await preview();
    old.resolve(result());
    await flush();
    expect(host.querySelector('[data-loading="true"]')).not.toBeNull();
    expect(host.querySelector('input')).toBeNull();
    expect(updates).toEqual([]);
    latest.resolve(result('orders', 'd'.repeat(64)));
    await flush();
    await check();
    expect(state.decisions[0]?.mappingFingerprint).toBe('d'.repeat(64));
  });

  it('invalidates an existing plan when mapping/default/key context changes and discards responses after unmount', async () => {
    await mount();
    await selectTable();
    await preview();
    state.request.syncDefaultVal = true;
    await flush();
    expect(host.querySelector('input')).toBeNull();
    const pending = deferred<DataforgeAccessApi.TypeMappingPlan>();
    vi.mocked(previewTypeMap).mockReturnValueOnce(pending.promise);
    await preview();
    const count = updates.length;
    app!.unmount();
    app = undefined;
    pending.resolve(result());
    await flush();
    expect(updates).toHaveLength(count);
    expect(state.decisions).toEqual([]);
  });

  it('discards responses for a different selected table or a table removed from current scope', async () => {
    const pending = deferred<DataforgeAccessApi.TypeMappingPlan>();
    vi.mocked(previewTypeMap).mockReturnValueOnce(pending.promise);
    await mount();
    await selectTable();
    await preview();
    await selectTable('items');
    pending.resolve(result());
    await flush();
    expect(host.querySelector('input')).toBeNull();
    expect(updates).toEqual([]);
    state.tables = [{ label: 'orders', value: 'orders' }];
    await flush();
    expect((host.querySelector('button') as HTMLButtonElement).disabled).toBe(
      true,
    );
  });

  it('refuses malformed and wrong-table diagnostics without replacing stored choices', async () => {
    state.decisions = [acknowledgement('items')];
    await mount();
    await selectTable();
    for (const response of [
      { ...result(), schemaVersion: 2 },
      result('items'),
      {
        ...result(),
        columns: [{ ...result().columns[0], mappingFingerprint: 'invalid' }],
      },
    ]) {
      vi.mocked(previewTypeMap).mockResolvedValue(
        response as DataforgeAccessApi.TypeMappingPlan,
      );
      await preview();
      expect(host.textContent).toContain('未取得当前映射结果');
      expect(host.querySelector('input')).toBeNull();
    }
    expect(state.decisions).toEqual([acknowledgement('items')]);
  });

  it('shows truncated diagnostics but never emits or accepts any new confirmation', async () => {
    state.decisions = [acknowledgement()];
    vi.mocked(previewTypeMap).mockResolvedValue({
      ...result(),
      truncated: true,
      totalColumns: 5000,
    });
    await mount();
    await selectTable();
    await preview();
    expect(host.textContent).toContain('不能确认列变化');
    expect((host.querySelector('input') as HTMLInputElement).disabled).toBe(
      true,
    );
    await check();
    expect(updates).toEqual([]);
    expect(state.decisions).toEqual([acknowledgement()]);
  });

  it('clears the old preview on request failure and keeps the current stored choices for server revalidation', async () => {
    state.decisions = [acknowledgement()];
    await mount();
    await selectTable();
    await preview();
    vi.mocked(previewTypeMap).mockRejectedValueOnce(
      new Error('synthetic unavailable'),
    );
    await preview();
    expect(host.textContent).toContain('未取得当前映射结果');
    expect(host.querySelector('input')).toBeNull();
    expect(state.decisions).toEqual([acknowledgement()]);
    expect(host.querySelector('[data-loading="true"]')).toBeNull();
  });
});
