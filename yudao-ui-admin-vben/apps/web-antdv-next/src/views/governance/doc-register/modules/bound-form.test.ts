import type { App, Component } from 'vue';

import { createApp, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import CodeSetForm from '#/views/governance/standardcodeset/modules/form.vue';
import ElementForm from '#/views/governance/standardelement/modules/form.vue';

interface ModalOptions {
  onConfirm: () => Promise<void>;
  onOpenChange: (open: boolean) => Promise<void>;
}
const harness = vi.hoisted(() => ({
  access: {
    accessToken: 'session',
    refreshToken: '',
    accessCodes: [] as string[],
  },
  context: {} as Record<string, unknown>,
  values: {} as Record<string, unknown>,
  options: undefined as ModalOptions | undefined,
  createElement: vi.fn(),
  createCodeSet: vi.fn(),
  getDoc: vi.fn(),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access }));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('antdv-next', () => ({ message: { success: vi.fn() } }));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: ModalOptions) => {
    harness.options = options;
    return [
      () => null,
      {
        getData: () => harness.context,
        setState: vi.fn(),
        lock: vi.fn(),
        unlock: vi.fn(),
        close: () => options.onOpenChange(false),
      },
    ];
  },
}));
vi.mock('#/adapter/form', () => ({
  useVbenForm: () => [
    () => null,
    {
      resetForm: async () => {
        harness.values = {};
      },
      setValues: async (values: Record<string, unknown>) => {
        Object.assign(harness.values, values);
      },
      getValues: async () => ({ ...harness.values }),
      updateSchema: vi.fn(),
      validate: async () => ({ valid: true }),
    },
  ],
}));
vi.mock('#/views/governance/standardelement/data', () => ({
  useFormSchema: () => [
    { fieldName: 'standardType' },
    { fieldName: 'documentId' },
  ],
}));
vi.mock('#/views/governance/standardcommon/definition-schema', () => ({
  createStandardDefinitionSchema: () => [
    { fieldName: 'standardType' },
    { fieldName: 'documentId' },
  ],
}));
vi.mock('#/api/governance/doc', () => ({
  getDoc: (...args: unknown[]) => harness.getDoc(...args),
}));
vi.mock('#/api/governance/standardelement', () => ({
  createStandardElement: (...args: unknown[]) => harness.createElement(...args),
  getStandardElement: vi.fn(),
  updateStandardElement: vi.fn(),
}));
vi.mock('#/api/governance/standardcodeset', () => ({
  createStandardCodeSet: (...args: unknown[]) => harness.createCodeSet(...args),
  getStandardCodeSet: vi.fn(),
  updateStandardCodeSet: vi.fn(),
  getStandardCodeSetCategories: vi.fn(),
}));
let app: App | undefined;
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({
    accessToken: 'session',
    refreshToken: '',
    accessCodes: [],
  });
  harness.context = {};
  harness.values = {};
  harness.getDoc.mockResolvedValue({
    id: '9007199254740993',
    stdType: 'NATIONAL',
    nameCn: '绑定标准',
  });
});
afterEach(() => {
  app?.unmount();
  app = undefined;
});
async function mount(component: Component, bound: boolean) {
  harness.context = bound
    ? { documentId: '9007199254740993', standardType: 'NATIONAL' }
    : {};
  app = createApp(component);
  app.mount(document.createElement('div'));
  await harness.options!.onOpenChange(true);
}
describe.each([
  ['数据元', ElementForm, () => harness.createElement] as const,
  ['代码表', CodeSetForm, () => harness.createCodeSet] as const,
])('标准登记内新增%s', (_label, component, create) => {
  it('默认禁用且提交始终绑定当前标准，保留Long编号', async () => {
    await mount(component, true);
    expect(harness.values.status).toBe(1);
    expect(harness.values.documentId).toBe('9007199254740993');
    Object.assign(harness.values, {
      documentId: '9007199254740994',
      standardType: 'LOCAL',
    });
    await harness.options!.onConfirm();
    expect(create()).toHaveBeenCalledWith(
      expect.objectContaining({
        status: 1,
        documentId: '9007199254740993',
        standardType: 'NATIONAL',
      }),
    );
  });
  it('独立新增入口保留原来的启用默认值', async () => {
    await mount(component, false);
    expect(harness.values.status).toBe(0);
    expect(harness.getDoc).not.toHaveBeenCalled();
  });
});
