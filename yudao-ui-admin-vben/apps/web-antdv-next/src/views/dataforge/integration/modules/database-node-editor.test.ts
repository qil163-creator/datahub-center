/* eslint-disable vue/one-component-per-file, vue/require-default-prop, vue/require-prop-types -- 测试替身仅模拟本组件依赖的输入事件。 */
import type { App } from 'vue';

import type { IntegrationApi } from '#/api/dataforge/integration';

import { createApp, defineComponent, h, nextTick, reactive, shallowRef } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getDatabaseSimpleList, getDatabaseTableMeta } from '#/api/dataforge/database';

import DatabaseNodeEditor from './database-node-editor.vue';

vi.mock('./database-connection-picker.vue', () => ({ default: defineComponent({ props: ['value'], setup: (props) => () => h('span', { 'aria-label': '节点数据连接' }, props.value) }) }));
vi.mock('./table-input-read-options.vue', () => ({ default: defineComponent({ setup: (_props, { expose }) => { expose({ validate: () => true }); return () => h('div'); } }) }));
vi.mock('#/components/monaco/sql-editor.vue', () => ({ default: defineComponent({ props: ['modelValue', 'readonly'], emits: ['update:modelValue'], setup: (props, { emit }) => () => h('textarea', { value: props.modelValue, disabled: props.readonly, 'aria-label': '节点查询SQL', onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLTextAreaElement).value) }) }) }));

vi.mock('@vben/stores', () => ({ useAccessStore: () => ({ accessToken: 'session', refreshToken: 'refresh' }) }));
vi.mock('#/api/dataforge/database', () => ({
  getDatabaseSimpleList: vi.fn(async () => []), getDatabaseTableList: vi.fn(async () => []), getDatabaseTableMeta: vi.fn(),
}));
vi.mock('antdv-next', () => {
  const Box = defineComponent({ setup: (_props, { slots }) => () => h('div', slots.default?.()) });
  const Input = defineComponent({ props: { value: [String, Number, Array] }, emits: ['update:value'], setup: (props, { emit }) => () => h('input', { value: props.value, onInput: (event: Event) => emit('update:value', (event.target as HTMLInputElement).value) }) });
  const TextArea = defineComponent({ props: { value: String }, emits: ['update:value'], setup: (props, { emit }) => () => h('textarea', { value: props.value, onInput: (event: Event) => emit('update:value', (event.target as HTMLTextAreaElement).value) }) });
  const Checkbox = defineComponent({ inheritAttrs: false,
    props: { checked: Boolean, disabled: Boolean, indeterminate: Boolean }, emits: ['update:checked'],
    setup: (props, { emit, slots, attrs }) => () => h('label', [h('input', { ...attrs, type: 'checkbox', checked: props.checked, disabled: props.disabled, onChange: (event: Event) => emit('update:checked', (event.target as HTMLInputElement).checked) }), slots.default?.()]),
  });
  const RadioGroup = defineComponent({ props: ['value', 'options', 'disabled'], emits: ['update:value'], setup: (props, { emit }) => () => h('select', { value: props.value, disabled: props.disabled, onChange: (event: Event) => { const value = (event.target as HTMLSelectElement).value; emit('update:value', props.options.find((option: { value: number | string }) => String(option.value) === value)?.value); } }, props.options?.map((option: { label: string; value: number | string }) => h('option', { value: option.value }, option.label))) });
  return { Input, TextArea, InputNumber: Input, Select: Input, RadioGroup, Checkbox, Switch: Checkbox, Spin: Box, Empty: Box,
    Button: defineComponent({ setup: (_props, { slots }) => () => h('button', slots.default?.()) }),
    Alert: defineComponent({ props: { message: String }, setup: (props) => () => h('div', { role: 'alert' }, props.message) }) };
});

let app: App | undefined;
let host: HTMLDivElement;
const editor = shallowRef<{ validate: () => boolean }>();
const sourceFields = [{ name: 'id', dataType: 'BIGINT' }, { name: 'name', dataType: 'VARCHAR' }];
const columns = [{ fieldName: 'id', fieldTypeName: 'BIGINT' }, { fieldName: 'name', fieldTypeName: 'VARCHAR' }];
beforeEach(() => {
  vi.mocked(getDatabaseSimpleList).mockResolvedValue([
    { id: '1', productId: 'POSTGRESQL' },
    { id: '9007199254740993', productId: 'POSTGRESQL' },
    { id: '2', productId: 'MYSQL' },
  ]);
  vi.mocked(getDatabaseTableMeta).mockResolvedValue({ tableName: 'target', columns });
});
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); vi.clearAllMocks(); });
async function flush() { for (let index = 0; index < 6; index++) await nextTick(); }
async function mount(config: Record<string, any>, toolCode: 'insert-db' | 'read-db' = 'insert-db', resolveFields: () => Promise<IntegrationApi.SchemaField[]> = vi.fn(async () => [])) {
  host = document.createElement('div'); document.body.append(host);
  const state = reactive({ config, disabled: false, engine: 'DATAX', mode: 'OFFLINE', inputFields: sourceFields });
  app = createApp({ render: () => h(DatabaseNodeEditor, { ...state, toolCode, resolveFields, ref: editor }) });
  app.mount(host); await flush(); return state;
}
function checkbox(label: string) { const element = host.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`); expect(element).toBeTruthy(); return element!; }
async function check(label: string, checked: boolean) { const element = checkbox(label); element.checked = checked; element.dispatchEvent(new Event('change', { bubbles: true })); await flush(); }
function rows(side: string) { return [...host.querySelectorAll<HTMLElement>(`[data-mapping-side="${side}"] .qi-db-mapping-row`)]; }
async function click(text: string) { const button = [...host.querySelectorAll('button')].find((item) => item.textContent?.trim() === text)!; expect(button).toBeTruthy(); button.click(); await flush(); }

describe('数据库节点双列字段映射', () => {
  it('表与SQL切换分别保留筛选草稿，SQL只提交自身查询条件', async () => {
    const state = await mount({ databaseId: '9007199254740993', tableName: 'target', whereCondition: 'id > 9007199254740993', selectedColumns: ['id'], sql: 'select id from target' }, 'read-db');
    const mode = host.querySelector<HTMLSelectElement>('select')!;
    mode.value = '1'; mode.dispatchEvent(new Event('change', { bubbles: true })); await flush();
    expect(state.config.ifSql).toBe(1); expect(state.config.whereCondition).toBeUndefined(); expect(state.config.selectedColumns).toBeUndefined();
    expect(state.config.tableReadDraft).toEqual({ whereCondition: 'id > 9007199254740993', selectedColumns: ['id'] });
    expect(editor.value!.validate()).toBe(true);
    mode.value = '0'; mode.dispatchEvent(new Event('change', { bubbles: true })); await flush();
    expect(state.config.whereCondition).toBe('id > 9007199254740993'); expect(state.config.selectedColumns).toEqual(['id']); expect(state.config.sql).toBe('select id from target');
  });

  it('字段序号与主键来自元数据，长度排序保留读取选择及原字段顺序', async () => {
    vi.mocked(getDatabaseTableMeta).mockResolvedValue({ tableName: 'target', columns: [
      { fieldName: 'id', fieldTypeName: 'BIGINT', length: 20, pk: true },
      { fieldName: 'name', fieldTypeName: 'VARCHAR', length: 200 },
      { fieldName: 'age', fieldTypeName: 'INT', length: 10 },
    ] });
    const state = await mount({ databaseId: '1', tableName: 'target', selectedColumns: ['id'] }, 'read-db');
    expect(host.querySelector('tbody tr td')?.textContent).toBe('1'); expect(checkbox('id是否主键').checked).toBe(true); expect(checkbox('id是否主键').disabled).toBe(true);
    await click('字段长度 ↕');
    expect([...host.querySelectorAll('tbody tr')].map((row) => row.children[1]?.textContent)).toEqual(['age', 'id', 'name']);
    expect(state.config.selectedColumns).toEqual(['id']); expect(state.config.fieldHints.fields.map((field: { name: string }) => field.name)).toEqual(['id', 'name', 'age']);
    await check('读取字段age', true); expect(state.config.selectedColumns).toEqual(['id', 'age']);
    state.disabled = true; await flush(); await check('读取字段name', true); expect(state.config.selectedColumns).toEqual(['id', 'age']);
  });

  it('实时表输出保留历史追加并只以明确增量选择使用真实元数据主键', async () => {
    vi.mocked(getDatabaseTableMeta).mockResolvedValue({ tableName: 'target', columns: [{ fieldName: 'id', fieldTypeName: 'BIGINT', pk: true, nullable: false }, columns[1]!] });
    const state = await mount({ databaseId: '1', tableName: 'target', dataSyncType: 1 });
    state.engine = 'FLINK'; state.mode = 'REALTIME'; await flush();
    expect(state.config.dataSyncType).toBe(1);
    expect(editor.value!.validate()).toBe(false);
    expect(host.querySelector('[aria-label="前置SQL"]')).toBeNull();
    state.config.dataSyncType = 2; await flush();
    expect(state.config.upsertKeys).toEqual(['id']);
    expect(editor.value!.validate()).toBe(true);
    state.config.upsertKeys = ['name']; expect(editor.value!.validate()).toBe(false);
    state.config.upsertKeys = ['id']; state.config.fieldMappings = [{ source: 'name', target: 'name', enabled: true }];
    expect(editor.value!.validate()).toBe(false);
  });

  it('幂等 PostgreSQL 输出可编辑 SQL，完整保留换行、引号及语句顺序', async () => {
    const state = await mount({ databaseId: '9007199254740993', tableName: 'target', dataSyncType: 2, upsertKeys: ['id'] });
    const before = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="前置SQL"]')!;
    const after = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="后置SQL"]')!;
    expect(before.disabled).toBe(false); expect(after.disabled).toBe(false);
    const preSql = 'UPDATE "目标" SET name = \'a;b\';\nDELETE FROM "日志" WHERE id = 1;';
    const postSql = 'INSERT INTO "日志"(name) VALUES (\'完成\');';
    before.value = preSql; before.dispatchEvent(new Event('input', { bubbles: true }));
    after.value = postSql; after.dispatchEvent(new Event('input', { bubbles: true })); await flush();
    expect(state.config.preSql).toBe(preSql); expect(state.config.postSql).toBe(postSql);
    expect(state.config.databaseId).toBe('9007199254740993');
    expect(editor.value!.validate()).toBe(true);
    expect(host.textContent).toContain('任一步失败全部回滚');
  });

  it('切换到不支持的引擎或连接不静默清空 SQL，并阻止校验通过', async () => {
    const state = await mount({ databaseId: '1', tableName: 'target', dataSyncType: 2, upsertKeys: ['id'], preSql: 'DELETE FROM target WHERE id=1', postSql: 'UPDATE target SET name=\'done\'' });
    expect(editor.value!.validate()).toBe(true);
    state.engine = 'SPARK'; await flush();
    expect(host.querySelector<HTMLTextAreaElement>('textarea[aria-label="前置SQL"]')!.disabled).toBe(true);
    expect(editor.value!.validate()).toBe(false); await flush();
    expect(host.textContent).toContain('前后置 SQL 仅支持 PostgreSQL DataX 幂等写入');
    expect(state.config.preSql).toBe('DELETE FROM target WHERE id=1');
    state.engine = 'DATAX'; state.config.databaseId = '2'; await flush();
    expect(editor.value!.validate()).toBe(false);
    await click('清除不支持的 SQL 配置');
    expect(state.config.preSql).toBe(''); expect(state.config.postSql).toBe('');
  });

  it('只读任务禁止编辑前后置 SQL，普通追加模式保留但不执行历史 SQL', async () => {
    const state = await mount({ databaseId: '1', tableName: 'target', dataSyncType: 2, upsertKeys: ['id'], preSql: 'DELETE FROM target' });
    state.disabled = true; await flush();
    expect(host.querySelector<HTMLTextAreaElement>('textarea[aria-label="前置SQL"]')!.disabled).toBe(true);
    expect(host.querySelector<HTMLTextAreaElement>('textarea[aria-label="后置SQL"]')!.disabled).toBe(true);
    state.disabled = false; state.config.dataSyncType = 1; await flush();
    expect(editor.value!.validate()).toBe(false);
    expect(state.config.preSql).toBe('DELETE FROM target');
  });

  it('幂等写入必须选择当前目标字段中的唯一键并保留选择', async () => {
    const state = await mount({ databaseId: '1', tableName: 'target', dataSyncType: 2, upsertKeys: [] });
    expect(editor.value!.validate()).toBe(false); await flush();
    expect(host.textContent).toContain('请选择目标表非空唯一键');
    state.config.upsertKeys = ['absent']; expect(editor.value!.validate()).toBe(false);
    state.config.upsertKeys = ['id']; expect(editor.value!.validate()).toBe(true);
    expect(state.config.dataSyncType).toBe(2); expect(state.config.upsertKeys).toEqual(['id']);
  });
  it('独立勾选和全选写三元组，重新打开恢复两侧状态', async () => {
    const state = await mount({ databaseId: '9007199254740993', tableName: 'target' });
    expect(state.config.fieldMappings).toBeUndefined();
    await check('目标字段id', false);
    expect(checkbox('来源字段id').checked).toBe(true); expect(checkbox('目标字段id').checked).toBe(false);
    expect(state.config.fieldMappings[0]).toEqual({ source: 'id', target: 'id', enabled: false });
    const saved = JSON.parse(JSON.stringify(state.config)); app!.unmount(); host.remove(); app = undefined;
    const reopened = await mount(saved);
    expect(checkbox('来源字段id').checked).toBe(true); expect(checkbox('目标字段id').checked).toBe(false);
    await check('全选目标字段', true); expect(reopened.config.fieldMappings.every((item: { enabled: boolean }) => item.enabled)).toBe(true);
    await check('全选来源字段', false); expect(checkbox('目标字段id').checked).toBe(true); expect(reopened.config.fieldMappings.every((item: { enabled: boolean }) => !item.enabled)).toBe(true);
    expect(editor.value!.validate()).toBe(false); await flush(); expect(host.textContent).toContain('请至少配置一个字段映射');
  });

  it('两列独立拖拽和键盘排序，保存按行配对且原图标可用', async () => {
    const state = await mount({ databaseId: '1', tableName: 'target' });
    rows('target')[0]!.dispatchEvent(new Event('dragstart', { bubbles: true, cancelable: true }));
    rows('target')[1]!.dispatchEvent(new Event('drop', { bubbles: true, cancelable: true })); await flush();
    expect(rows('source').map((row) => row.dataset.fieldName)).toEqual(['id', 'name']);
    expect(state.config.fieldMappings).toEqual([{ source: 'id', target: 'name', enabled: true }, { source: 'name', target: 'id', enabled: true }]);
    rows('source')[0]!.dispatchEvent(new KeyboardEvent('keydown', { altKey: true, key: 'ArrowDown', bubbles: true })); await flush();
    expect(state.config.fieldMappings).toEqual([{ source: 'name', target: 'name', enabled: true }, { source: 'id', target: 'id', enabled: true }]);
    expect(host.querySelector<HTMLImageElement>('.qi-db-field-drag')!.getAttribute('src')).toBe('/assets/qdata-integration/icon-field-drag-ref.png');
  });

  it('只读或切换节点后不允许旧拖拽写入草稿', async () => {
    const state = await mount({ databaseId: '1', tableName: 'old' });
    rows('target')[0]!.dispatchEvent(new Event('dragstart', { bubbles: true }));
    state.config = { databaseId: '2', tableName: 'new', fieldMappings: [] }; await flush();
    rows('target')[1]!.dispatchEvent(new Event('drop', { bubbles: true })); await flush();
    expect(state.config.fieldMappings).toEqual([]);
    state.disabled = true; await flush(); const before = JSON.stringify(state.config);
    await check('全选目标字段', true);
    rows('source')[0]!.dispatchEvent(new KeyboardEvent('keydown', { altKey: true, key: 'ArrowDown', bubbles: true }));
    rows('target')[0]!.dispatchEvent(new Event('dragstart', { bubbles: true })); rows('target')[1]!.dispatchEvent(new Event('drop', { bubbles: true })); await flush();
    expect(JSON.stringify(state.config)).toBe(before); expect(rows('target')[0]!.getAttribute('draggable')).toBe('false');
  });

  it('目标字段缺失不偷偷删映射，校验提示并支持显式同名重建', async () => {
    const configured = [{ source: 'id', target: 'absent', enabled: true }];
    const state = await mount({ databaseId: '1', tableName: 'target', fieldMappings: configured });
    expect(state.config.fieldMappings).toEqual(configured); expect(editor.value!.validate()).toBe(false); await flush(); expect(host.textContent).toContain('目标字段不存在：absent');
    await click('同名自动映射'); expect(editor.value!.validate()).toBe(true);
    expect(state.config.fieldMappings).toEqual([{ source: 'id', target: 'id', enabled: true }, { source: 'name', target: 'name', enabled: true }]);
  });

  it('sql解析复用权威schema，未知类型留空且节点切换忽略旧响应', async () => {
    let complete!: (value: { dataType?: string; name: string; }[]) => void;
    const resolveFields = vi.fn(() => new Promise<{ dataType?: string; name: string; }[]>((resolve) => { complete = resolve; }));
    const state = await mount({ databaseId: '1', ifSql: 1, sql: 'select id from source' }, 'read-db', resolveFields);
    await click('SQL解析'); complete([{ name: 'id' }]); await flush();
    expect(host.querySelector('tbody')!.textContent).toContain('id'); expect(host.querySelector('tbody')!.textContent).not.toContain('STRING');
    await click('SQL解析'); state.config = { databaseId: '2', ifSql: 1, sql: 'select name from other' }; await flush();
    complete([{ name: 'stale', dataType: 'VARCHAR' }]); await flush(); expect(host.querySelector('tbody')!.textContent).not.toContain('stale');
  });
});
