/* eslint-disable vue/one-component-per-file, vue/require-default-prop -- 测试替身保留输入与异步请求的交互边界。 */
import type { App } from 'vue';

import {
  createApp,
  defineComponent,
  h,
  nextTick,
  reactive,
  shallowRef,
} from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as databaseApi from '#/api/dataforge/database';

import Editor from './relation-input-editor.vue';

const readOptionsProps = vi.hoisted(() => ({
  current: undefined as Record<string, any> | undefined,
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({ accessToken: 'session', refreshToken: 'refresh' }),
}));
vi.mock('#/api/dataforge/database', () => ({
  getDatabaseSimpleList: vi.fn(async () => []),
  getDatabaseTableList: vi.fn(async () => []),
  getDatabaseTableMeta: vi.fn(async () => ({ columns: [] })),
}));
vi.mock('./database-connection-picker.vue', () => ({
  default: defineComponent({
    props: { value: [String, Number], disabled: Boolean, databases: Array },
    emits: ['change'],
    setup:
      (props, { emit }) =>
      () =>
        h('input', {
          'aria-label': '源数据库连接',
          value: props.value,
          disabled: props.disabled,
          onInput: (event: Event) =>
            emit('change', (event.target as HTMLInputElement).value),
        }),
  }),
}));
vi.mock('./table-input-sql-editor.vue', () => ({
  default: defineComponent({
    props: { modelValue: String, readonly: Boolean },
    emits: ['update:modelValue'],
    setup:
      (props, { emit }) =>
      () =>
        h('textarea', {
          'aria-label': 'SQL语句',
          value: props.modelValue,
          readonly: props.readonly,
          onInput: (event: Event) =>
            emit(
              'update:modelValue',
              (event.target as HTMLTextAreaElement).value,
            ),
        }),
  }),
}));
vi.mock('./table-input-read-options.vue', () => ({
  default: defineComponent({
    props: {
      config: Object,
      fields: Array,
      batchEnabled: Boolean,
      conditionModalWidth: Number,
      conditionTableHeight: Number,
      fixedBaselineWidth: Number,
      referenceLayout: Boolean,
      showReadMode: Boolean,
      showStartRequired: Boolean,
      showWhere: Boolean,
      timeFormatWidth: Number,
      disabled: Boolean,
    },
    setup: (props, { expose }) => {
      expose({ validate: () => true });
      return () => {
        readOptionsProps.current = props;
        return h('div', { 'data-read-options': true });
      };
    },
  }),
}));
vi.mock('./reference-field-edit-modal.vue', () => ({
  default: defineComponent({ setup: () => () => null }),
}));
vi.mock('antdv-next', () => {
  const Input = defineComponent({
    props: { value: [String, Number], disabled: Boolean },
    emits: ['update:value'],
    setup:
      (props, { emit }) =>
      () =>
        h('input', {
          value: props.value,
          disabled: props.disabled,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLInputElement).value),
        }),
  });
  return {
    Input,
    TextArea: Input,
    Select: defineComponent({
      props: { value: [String, Number], options: Array, disabled: Boolean },
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'select',
            {
              value: props.value,
              disabled: props.disabled,
              onChange: (event: Event) =>
                emit('update:value', (event.target as HTMLSelectElement).value),
            },
            ((props.options ?? []) as { label: string; value: string; }[]).map(
              (item) => h('option', { value: item.value }, item.label),
            ),
          ),
    }),
    RadioGroup: defineComponent({
      props: { value: Number, options: Array, disabled: Boolean },
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'select',
            {
              value: props.value,
              disabled: props.disabled,
              onChange: (event: Event) =>
                emit(
                  'update:value',
                  Number((event.target as HTMLSelectElement).value),
                ),
            },
            ((props.options ?? []) as { label: string; value: number; }[]).map(
              (item) => h('option', { value: item.value }, item.label),
            ),
          ),
    }),
    Button: defineComponent({
      props: { disabled: Boolean },
      setup:
        (props, { slots }) =>
        () =>
          h('button', { disabled: props.disabled }, slots.default?.()),
    }),
    Switch: defineComponent({
      props: { checked: Boolean },
      setup: (props) => () =>
        h('input', {
          type: 'checkbox',
          checked: props.checked,
          disabled: true,
        }),
    }),
    Spin: defineComponent({
      setup:
        (_props, { slots }) =>
        () =>
          h('div', slots.default?.()),
    }),
    Modal: { confirm: vi.fn() },
    Alert: defineComponent({
      props: { message: String },
      setup:
        (props, { slots }) =>
        () =>
          h('div', { role: 'alert' }, [props.message, slots.action?.()]),
    }),
  };
});
let app: App | undefined;
let host: HTMLDivElement;
const editor = shallowRef<{ validate: () => boolean }>();
beforeEach(() => {
  vi.mocked(databaseApi.getDatabaseSimpleList)
    .mockReset()
    .mockResolvedValue([
      {
        id: '9007199254740993',
        name: 'MaxCompute项目',
        productId: 'ODPS',
        databaseSchema: 'project_demo',
      },
    ]);
  vi.mocked(databaseApi.getDatabaseTableList)
    .mockReset()
    .mockResolvedValue([
      { tableName: 'stations', remarks: '站点' },
      { tableName: 'measurements' },
    ]);
  vi.mocked(databaseApi.getDatabaseTableMeta)
    .mockReset()
    .mockResolvedValue({
      tableName: 'stations',
      columns: [
        {
          fieldName: 'id',
          fieldTypeName: 'BIGINT',
          remarks: '编号',
          length: 19,
          pk: true,
        },
        { fieldName: 'changed_at', fieldTypeName: 'TIMESTAMP' },
      ],
    });
});
afterEach(() => {
  app?.unmount();
  host?.remove();
});
async function flush() {
  await nextTick();
  await nextTick();
  await new Promise((resolve) => setTimeout(resolve, 0));
  await nextTick();
}
async function mount(
  config: Record<string, any>,
  toolCode:
    | 'read-hive'
    | 'read-mongodb'
    | 'read-odps'
    | 'read-tdengine' = 'read-odps',
  resolveFields?: () => Promise<any[]>,
  mode = 'OFFLINE',
) {
  const state = reactive({ config, disabled: false, toolCode, mode });
  host = document.createElement('div');
  document.body.append(host);
  app = createApp({
    render: () => h(Editor, { ...state, ref: editor, resolveFields }),
  });
  app.mount(host);
  await flush();
  return state;
}
async function input(label: string, value: string) {
  const element = host.querySelector<HTMLInputElement | HTMLSelectElement>(
    `[aria-label="${label}"]`,
  )!;
  expect(element).toBeTruthy();
  element.value = value;
  element.dispatchEvent(
    new Event(element.tagName === 'SELECT' ? 'change' : 'input', {
      bubbles: true,
    }),
  );
  await flush();
}
async function click(text: string) {
  const element = [...host.querySelectorAll('button')].find(
    (button) => button.textContent?.trim() === text,
  )!;
  expect(element).toBeTruthy();
  element.click();
  await flush();
}

describe('关系输入ODPS交互', () => {
  it('已有字段回显不被自动解析覆盖，选择新表实际读取元数据并显示四列属性', async () => {
    const state = await mount({
      databaseId: '9007199254740993',
      tableName: 'stations',
      readMode: 'FULL',
      columns: [{ name: 'id', type: 'LONG', custom: '保留' }],
    });
    expect(state.config.columns).toEqual([
      { name: 'id', type: 'LONG', custom: '保留' },
    ]);
    expect(
      host
        .querySelector('table[aria-label="关系输入属性字段"]')
        ?.querySelectorAll('th'),
    ).toHaveLength(4);
    expect(
      host.querySelector('table[aria-label="关系输入属性字段"]')?.textContent,
    ).not.toContain('操作');
    expect(readOptionsProps.current?.batchEnabled).toBe(false);
    expect(readOptionsProps.current?.conditionModalWidth).toBe(912);
    await input('选择表/视图', 'measurements');
    expect(databaseApi.getDatabaseTableMeta).toHaveBeenLastCalledWith(
      '9007199254740993',
      'measurements',
      { silentError: true },
    );
    expect(state.config.columns).toEqual([
      { name: 'id', type: 'BIGINT', comment: '编号' },
      { name: 'changed_at', type: 'TIMESTAMP' },
    ]);
    expect(state.config.columns[0]).not.toHaveProperty('length');
    expect(state.config.databaseId).toBe('9007199254740993');
    state.config.readMode = 'ID_INCREMENT';
    await flush();
    expect(host.querySelector('table[aria-label="关系输入属性字段"]')).toBeNull();
    expect(state.config.columns).toHaveLength(2);
    // ODPS follows the qData reference dialog: no advanced field or execution sections.
    expect(host.querySelector('table[aria-label="高级属性字段"]')).toBeNull();
    expect(readOptionsProps.current?.referenceLayout).toBe(true);
  });
  it('数据连接与SQL之间切换仅提交当前分支，回切恢复表草稿，SQL解析使用真实返回结果', async () => {
    const resolveFields = vi.fn(async () => [
      { name: 'station_count', dataType: 'BIGINT' },
    ]);
    const state = await mount(
      {
        databaseId: '9007199254740993',
        tableName: 'stations',
        readMode: 'FULL',
        whereCondition: 'id > 1',
        columns: [{ name: 'id', type: 'LONG' }],
        custom: '保留',
      },
      'read-odps',
      resolveFields,
    );
    await input('连接方式', '1');
    expect(state.config).not.toHaveProperty('tableName');
    expect(state.config).not.toHaveProperty('whereCondition');
    await input('SQL语句', 'select count(*) station_count from stations');
    await click('SQL解析');
    expect(resolveFields).toHaveBeenCalledOnce();
    expect(state.config.columns).toEqual([
      { name: 'station_count', type: 'BIGINT' },
    ]);
    expect(editor.value?.validate()).toBe(true);
    await input('连接方式', '0');
    expect(state.config).toMatchObject({
      tableName: 'stations',
      whereCondition: 'id > 1',
      columns: [{ name: 'id', type: 'LONG' }],
      custom: '保留',
    });
    expect(state.config).not.toHaveProperty('sql');
    expect(editor.value?.validate()).toBe(true);
    await input('连接方式', '1');
    expect(state.config.sql).toBe(
      'select count(*) station_count from stations',
    );
    expect(state.config.columns[0].name).toBe('station_count');
  });
  it('未知SQL字段不报告解析成功，已有声明保留，失败后可继续重试', async () => {
    const resolveFields = vi
      .fn<() => Promise<any[]>>()
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([{ name: 'id', dataType: 'BIGINT' }]);
    const state = await mount(
      {
        databaseId: '9007199254740993',
        ifSql: 1,
        sql: 'select id from stations',
        columns: [{ name: 'old_id', type: 'LONG' }],
      },
      'read-odps',
      resolveFields,
    );
    await click('SQL解析');
    expect(host.textContent).toContain('未返回可确认的SQL字段');
    expect(state.config.columns[0].name).toBe('old_id');
    await click('SQL解析');
    expect(state.config.columns[0].name).toBe('id');
  });
  it('旧表字段的迟到响应不能覆盖新表或只读草稿', async () => {
    let resolve!: (value: FoundationMetadata) => void;
    type FoundationMetadata = Awaited<
      ReturnType<typeof databaseApi.getDatabaseTableMeta>
    >;
    vi.mocked(databaseApi.getDatabaseTableMeta).mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    const state = await mount({
      databaseId: '9007199254740993',
      tableName: 'stations',
      columns: [{ name: 'saved', type: 'STRING' }],
    });
    await input('选择表/视图', 'measurements');
    resolve({
      tableName: 'stations',
      columns: [{ fieldName: 'stale', fieldTypeName: 'STRING' }],
    });
    await flush();
    expect(
      state.config.columns.some((column: any) => column.name === 'stale'),
    ).toBe(false);
    state.disabled = true;
    await flush();
    const before = JSON.stringify(state.config);
    await input('源数据库连接', '2');
    expect(JSON.stringify(state.config)).toBe(before);
  });
  it('目录失败保留已保存值并可重试，换连接不会延续旧表与字段', async () => {
    vi.mocked(databaseApi.getDatabaseTableList).mockRejectedValueOnce(
      new Error('目录离线'),
    );
    const state = await mount({
      databaseId: '9007199254740993',
      tableName: 'stations',
      incrementColumn: 'id',
      columns: [{ name: 'id', type: 'LONG' }],
    });
    expect(host.textContent).toContain('表与视图目录加载失败');
    expect(state.config.tableName).toBe('stations');
    await click('重试表目录');
    expect(host.textContent).not.toContain('表与视图目录加载失败');
    await input('源数据库连接', '9007199254740995');
    expect(state.config.columns).toEqual([]);
    expect(state.config).not.toHaveProperty('tableName');
    expect(state.config).not.toHaveProperty('incrementColumn');
  });
  it('校验当前读取分支，ID仅整数且配置中的凭据不能绕过旧安全校验', async () => {
    const state = await mount({
      databaseId: '9007199254740993',
      tableName: 'stations',
      readMode: 'ID_INCREMENT',
      incrementColumn: 'id',
      incrementStart: '1.5',
      columns: [{ name: 'id', type: 'LONG' }],
    });
    expect(editor.value?.validate()).toBe(false);
    expect(host.textContent).not.toContain('密码');
    state.config.incrementStart = '900719925474099312345';
    expect(editor.value?.validate()).toBe(true);
    state.config.password = 'fixture-secret';
    expect(editor.value?.validate()).toBe(false);
    await flush();
    expect(host.textContent).toContain('节点配置不可保存凭据');
  });
  it('按Hive全量显示七列无操作，ID与时间隐藏主属性且不显示高级配置', async () => {
    const state = await mount({ databaseId: '9007199254740993', tableName: 'stations', readMode: 'FULL', columns: [{ name: 'id', type: 'BIGINT' }] }, 'read-hive');
    const table = host.querySelector('table[aria-label="关系输入属性字段"]')!;
    expect(table.querySelectorAll('th')).toHaveLength(7);
    expect(table.textContent).not.toContain('操作');
    expect(readOptionsProps.current).toMatchObject({ conditionModalWidth: 912, conditionTableHeight: 310, fixedBaselineWidth: 220, showStartRequired: false, timeFormatWidth: 240 });
    state.config.readMode = 'ID_INCREMENT';
    await flush();
    expect(host.querySelector('table[aria-label="关系输入属性字段"]')).toBeNull();
    // Hive follows the qData reference dialog: no advanced field or execution sections.
    expect(host.querySelector('table[aria-label="高级属性字段"]')).toBeNull();
    state.config.readMode = 'TIME_INCREMENT';
    await flush();
    expect(host.querySelector('table[aria-label="关系输入属性字段"]')).toBeNull();
  });
  it('按Hive切换SQL保留可编辑的时间分支，保存与回切均不丢条件或查询草稿', async () => {
    const conditions = [{ field: 'changed_at', baselineType: 'TIME_RANGE', cursorTime: '2026-11-03' }];
    const state = await mount({ databaseId: '9007199254740993', tableName: 'stations', readMode: 'TIME_INCREMENT', dateFormat: 'yyyy-MM-dd', timeConditions: conditions, whereCondition: 'id > 1', columns: [{ name: 'id', type: 'BIGINT' }, { name: 'changed_at', type: 'TIMESTAMP' }] }, 'read-hive');
    await input('连接方式', '1');
    expect(state.config).toMatchObject({ readMode: 'TIME_INCREMENT', dateFormat: 'yyyy-MM-dd', timeConditions: conditions });
    expect(state.config).not.toHaveProperty('whereCondition');
    expect(state.config).not.toHaveProperty('tableName');
    expect(readOptionsProps.current).toMatchObject({ showReadMode: false, showWhere: false });
    expect(host.querySelector('table[aria-label="关系输入属性字段"]')).toBeNull();
    expect(host.querySelector('.qi-relation-sql-toolbar button')?.textContent?.trim()).toBe('SQL解析');
    await input('SQL语句', 'SELECT id, changed_at FROM stations');
    expect(readOptionsProps.current?.fields).toEqual(expect.arrayContaining([expect.objectContaining({ fieldName: 'changed_at', fieldTypeName: 'TIMESTAMP' })]));
    readOptionsProps.current!.config.timeFormat = 'yyyy-MM-dd HH:mm:ss';
    readOptionsProps.current!.config.timeConditions = [{ field: 'changed_at', baselineType: 'SQL', operator: '>', baselineValue: 'CURRENT_TIMESTAMP' }];
    await input('连接方式', '0');
    expect(state.config.tableName).toBe('stations');
    expect(state.config.whereCondition).toBe('id > 1');
    expect(state.config.dateFormat).toBe('yyyy-MM-dd HH:mm:ss');
    expect(state.config.timeConditions[0].baselineValue).toBe('CURRENT_TIMESTAMP');
    await input('连接方式', '1');
    const saved = JSON.parse(JSON.stringify(state.config));
    expect(saved.sql).toBe('SELECT id, changed_at FROM stations');
    expect(saved.readMode).toBe('TIME_INCREMENT');
    expect(saved.timeConditions[0].baselineType).toBe('SQL');
  });
});
