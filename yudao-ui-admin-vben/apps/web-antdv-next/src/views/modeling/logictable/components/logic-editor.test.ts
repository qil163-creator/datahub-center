/* eslint-disable vue/one-component-per-file -- 保留真实编辑器初始化，仅隔离选项和控件。 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import { logicFieldPayload, validateLogicFields } from '../editor-state';
import LogicEditor from './logic-editor.vue';

vi.mock('@vben/access', () => ({ useAccess: () => ({ hasAccessByCodes: () => true }) }));
vi.mock('@vben/hooks', () => ({ getDictOptions: () => [], useAntdDesignTokens: () => ({ tokens: { borderRadius: 6 } }) }));
vi.mock('@vben/stores', () => ({ useAccessStore: () => ({ accessToken: 'session', refreshToken: '', accessCodes: [] }) }));
vi.mock('@vueuse/core', async () => {
  const { ref } = await import('vue');
  return { useMediaQuery: () => ref(false) };
});
vi.mock('#/api/modeling/datadomain', () => ({ getDataDomain: vi.fn() }));
vi.mock('#/api/modeling/tablemodel', () => ({
  createTableModel: vi.fn(),
  getTableModel: vi.fn(),
  getTableModelPage: vi.fn(),
  getTableModelDesign: vi.fn(),
  modelSaveData: (value: unknown) => value,
  saveTableModelDraft: vi.fn(),
  updateTableModel: vi.fn(),
}));
vi.mock('../options', async () => {
  const { ref } = await import('vue');
  return {
    useLogicOptions: () => ({
      domains: ref([]), dataDomains: ref([]), entities: ref([]), users: ref([]), documents: ref([]),
      standards: ref([]), codeTables: ref([]), classifications: ref([]), levels: ref([]), layers: ref([]),
      loading: ref(false), error: ref(''), load: vi.fn(async () => {}),
    }),
  };
});
vi.mock('../icons', () => ({ QdIcon: () => null }));
vi.mock('./field-grid.vue', () => ({
  default: defineComponent({
    props: { modelValue: { type: Array, required: true } },
    setup: (props, { slots }) => () => h('div', [
      h('pre', { 'data-test-fields': true }, JSON.stringify(props.modelValue)),
      slots.actions?.(),
    ]),
  }),
}));
const dialogs = vi.hoisted(() => ({
  importOpen: vi.fn(),
  sqlOpen: vi.fn(),
  emitImport: undefined as ((fields: unknown[]) => void) | undefined,
  emitSql: undefined as ((fields: unknown[], replace: boolean) => void) | undefined,
}));
vi.mock('./field-import-modal.vue', () => ({
  default: defineComponent({
    emits: ['import'],
    setup(_, { emit, expose }) {
      dialogs.emitImport = (fields) => emit('import', fields);
      expose({ open: dialogs.importOpen });
      return () => null;
    },
  }),
}));
vi.mock('./sql-parse-modal.vue', () => ({
  default: defineComponent({
    emits: ['import'],
    setup(_, { emit, expose }) {
      dialogs.emitSql = (fields, replace) => emit('import', fields, replace);
      expose({ open: dialogs.sqlOpen });
      return () => null;
    },
  }),
}));
vi.mock('antdv-next', () => {
  const plain = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) });
  return {
    Alert: plain, Button: plain, ConfigProvider: plain, Input: plain, Popover: plain, Radio: plain,
    RadioGroup: plain, Select: plain, Spin: plain, TextArea: plain,
    message: { success: vi.fn(), warning: vi.fn() },
  };
});

let app: App | undefined;
let host: HTMLDivElement;
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('逻辑表编辑器默认主键', () => {
  it.each(['dimension', 'fact'] as const)('%s 新建默认 NUMBER 主键明确初始化总精度和小数位数', async (kind) => {
    host = document.createElement('div');
    document.body.append(host);
    app = createApp(LogicEditor, { kind });
    app.mount(host);
    for (let index = 0; index < 6; index++) {
      await Promise.resolve();
      await nextTick();
    }
    const rows = JSON.parse(host.querySelector('[data-test-fields]')!.textContent!);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ dataType: 'NUMBER', dataPrecision: 19, dataScale: 0, pkFlag: true });
    rows[0].fieldName = 'station_id';
    rows[0].cnName = '测站ID';
    expect(validateLogicFields(rows)).toBe('');
    expect(logicFieldPayload(rows)[0]).toMatchObject({ dataPrecision: 19, dataScale: 0, nullableFlag: false });
  });
});

async function mountEditor(kind: 'application' | 'dimension' | 'fact') {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(LogicEditor, { kind });
  app.mount(host);
  for (let index = 0; index < 6; index++) {
    await Promise.resolve();
    await nextTick();
  }
}

function click(text: string) {
  const button = [...host.querySelectorAll('button')].find((item) => item.textContent?.trim() === text);
  expect(button).toBeDefined();
  button!.click();
}

function currentFields() {
  return JSON.parse(host.querySelector('[data-test-fields]')!.textContent!) as { fieldName?: string; pkFlag?: boolean }[];
}

describe('逻辑表编辑器字段引入', () => {
  it('维度与事实表从逻辑表引入和 SQL 解析打开对应弹窗，引入字段追加且跳过重名', async () => {
    dialogs.importOpen.mockReset();
    dialogs.sqlOpen.mockReset();
    await mountEditor('fact');
    click('从逻辑表引入');
    expect(dialogs.importOpen).toHaveBeenCalledWith('logic');
    click('SQL解析');
    expect(dialogs.sqlOpen).toHaveBeenCalledOnce();
    expect(host.textContent).not.toContain('从派生指标引用');

    dialogs.emitImport!([{ fieldName: 'amount' }, { fieldName: 'AMOUNT' }]);
    await nextTick();
    expect(currentFields().map((row) => row.fieldName)).toEqual([undefined, 'amount']);

    dialogs.emitSql!([{ fieldName: 'region' }], true);
    await nextTick();
    const replaced = currentFields();
    expect(replaced).toHaveLength(2);
    expect(replaced[0]?.pkFlag).toBe(true);
    expect(replaced[1]?.fieldName).toBe('region');
  });

  it('应用表提供从派生指标引用和从代码表引入', async () => {
    dialogs.importOpen.mockReset();
    await mountEditor('application');
    click('从派生指标引用');
    expect(dialogs.importOpen).toHaveBeenLastCalledWith('indicator');
    click('从代码表引入');
    expect(dialogs.importOpen).toHaveBeenLastCalledWith('codeTable');
    expect(host.textContent).not.toContain('从逻辑表引入');
  });
});
