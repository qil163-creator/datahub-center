 
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import Form from './form.vue';

const state = vi.hoisted(() => ({
  access: { accessToken: 'a', refreshToken: 'r' },
  modalData: {} as Record<string, unknown>,
  modal: undefined as undefined | { onConfirm: () => Promise<void>; onOpenChange: (open: boolean) => Promise<void>; },
  values: {} as Record<string, unknown>,
  getTerm: vi.fn(), updateTerm: vi.fn(), createTerm: vi.fn(),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => state.access }));
vi.mock('@vben/constants', () => ({ CommonStatusEnum: { ENABLE: 0 } }));
vi.mock('@vben/common-ui', () => ({ useVbenModal: (options: typeof state.modal) => {
  state.modal = options;
  return [defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) }), {
    getData: () => state.modalData, lock() {}, unlock() {}, close: () => options!.onOpenChange(false),
  }];
} }));
vi.mock('antdv-next', () => ({ message: { success: vi.fn() } }));
vi.mock('#/adapter/form', () => ({ useVbenForm: () => [defineComponent(() => () => null), {
  validate: async () => ({ valid: true }), getValues: async () => ({ ...state.values }),
  setValues: async (values: Record<string, unknown>) => { state.values = { ...values }; },
  reset: async () => { state.values = {}; },
}] }));
vi.mock('#/api/governance/standardterm', () => ({ getStandardTerm: state.getTerm, updateStandardTerm: state.updateTerm, createStandardTerm: state.createTerm }));
vi.mock('#/locales', () => ({ $t: (value: string) => value }));
vi.mock('#/views/governance/common/form-layout', () => ({ GOVERNANCE_MODAL_FORM: {} }));
vi.mock('../data', () => ({ useFormSchema: () => [] }));

let app: App;
let host: HTMLDivElement;
beforeEach(() => {
  vi.clearAllMocks();
  state.access = reactive({ accessToken: 'a', refreshToken: 'r' }); state.values = {};
  host = document.createElement('div'); app = createApp(Form); app.mount(host);
});
afterEach(() => app.unmount());

describe('业务术语表单类目', () => {
  it('历史数据元类目保留原始大编号，未归类的术语不被自动关联', async () => {
    state.modalData = { id: '7' };
    state.getTerm.mockResolvedValue({ id: '7', name: '河流', definition: '定义', categoryId: '9007199254740993', termCategoryId: null });
    await state.modal!.onOpenChange(true);
    expect(state.values.categoryId).toBe('9007199254740993');
    expect(state.values.termCategoryId).toBeUndefined();
    await state.modal!.onConfirm();
    expect(state.updateTerm).toHaveBeenCalledWith(expect.objectContaining({ id: '7', categoryId: '9007199254740993', termCategoryId: null }));
  });
  it('清空术语类目时显式提交 null', async () => {
    state.modalData = { id: '8' };
    state.getTerm.mockResolvedValue({ id: '8', name: '河流', definition: '定义', termCategoryId: '9007199254740994' });
    await state.modal!.onOpenChange(true);
    expect(state.values.termCategoryId).toBe('9007199254740994');
    state.values.termCategoryId = undefined;
    await state.modal!.onConfirm();
    expect(state.updateTerm).toHaveBeenCalledWith(expect.objectContaining({ id: '8', termCategoryId: null }));
  });
  it('新建时默认选中当前术语类目', async () => {
    state.modalData = { termCategoryId: '9007199254740994' };
    await state.modal!.onOpenChange(true);
    expect(state.values.termCategoryId).toBe('9007199254740994');
    await nextTick();
  });
});
