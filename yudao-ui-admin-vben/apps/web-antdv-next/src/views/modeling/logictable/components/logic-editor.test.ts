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
    setup: (props) => () => h('pre', { 'data-test-fields': true }, JSON.stringify(props.modelValue)),
  }),
}));
vi.mock('antdv-next', () => {
  const plain = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) });
  return {
    Alert: plain, Button: plain, ConfigProvider: plain, Input: plain, Popover: plain, Radio: plain,
    RadioGroup: plain, Select: plain, Spin: plain, TextArea: plain, message: { warning: vi.fn() },
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
