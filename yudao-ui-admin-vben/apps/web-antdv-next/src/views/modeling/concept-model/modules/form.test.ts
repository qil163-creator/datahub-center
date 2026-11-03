import type { App, SetupContext } from 'vue';

import type { ConceptModelApi } from '#/api/modeling/concept-model';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createConceptModel,
  getConceptModel,
  updateConceptModel,
} from '#/api/modeling/concept-model';

import Form from './form.vue';

const harness = vi.hoisted(() => ({
  access: {} as {
    accessCodes: string[];
    accessToken: string;
    refreshToken: string;
  },
  user: {} as { userInfo: { userId: string }; userRoles: string[] },
  options: {} as {
    onConfirm: () => Promise<void>;
    onOpenChange: (open: boolean) => Promise<void>;
  },
  data: {} as { id?: string; readonly?: boolean },
  values: {} as Record<string, unknown>,
  setValues: vi.fn(),
  setState: vi.fn(),
  close: vi.fn(),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.access.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: typeof harness.options) => {
    harness.options = options;
    return [
      (_: unknown, { slots }: SetupContext) => h('section', slots.default?.()),
      {
        getData: () => harness.data,
        lock: vi.fn(),
        unlock: vi.fn(),
        close: harness.close,
      },
    ];
  },
}));
vi.mock('#/adapter/form', () => {
  const schema = {
    trim: () => schema,
    min: () => schema,
    max: () => schema,
    regex: () => schema,
  };
  return {
    z: { string: () => schema },
    useVbenForm: () => [
      () => h('div'),
      {
        resetForm: async () => {
          harness.values = {};
        },
        setValues: async (values: Record<string, unknown>) => {
          harness.values = values;
          harness.setValues(values);
        },
        getValues: async () => harness.values,
        validate: async () => ({ valid: true }),
        updateSchema: vi.fn(),
        setState: harness.setState,
      },
    ],
  };
});
vi.mock('antdv-next', () => ({
  Alert: (props: { message: string }) => h('p', props.message),
  message: { success: vi.fn(), warning: vi.fn() },
}));
vi.mock('#/api/modeling/concept-model', () => ({
  getConceptModel: vi.fn(),
  createConceptModel: vi.fn(),
  updateConceptModel: vi.fn(),
}));
vi.mock('#/api/modeling/datadomain', () => ({
  getDataDomainSimpleList: vi.fn(async () => [
    { id: '9007199254740994', name: '客户域', status: 0 },
  ]),
}));
vi.mock('#/api/modeling/subjectdomain', () => ({
  getSubjectDomainList: vi.fn(async () => []),
}));
vi.mock('./design-editor.vue', () => ({
  default: (props: { readonly?: boolean }) =>
    h('div', { 'data-readonly': String(props.readonly) }, '设计'),
}));

let app: App | undefined;
let host: HTMLDivElement;
function detail(
  status: ConceptModelApi.Status = 'DRAFT',
): ConceptModelApi.Model {
  return {
    id: '9007199254740995',
    revision: '9007199254740993',
    name: '客户概念',
    code: 'customer',
    dataDomainId: '9007199254740994',
    status,
    design: { entities: [], relations: [] },
  };
}
async function flush() {
  await nextTick();
  await nextTick();
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({
    accessCodes: [
      'modeling:concept-model:create',
      'modeling:concept-model:update',
      'modeling:data-domain:query',
      'modeling:subject-domain:query',
    ],
    accessToken: 'session-a',
    refreshToken: '',
  });
  harness.user = reactive({
    userInfo: { userId: '1' },
    userRoles: ['modeler'],
  });
  harness.data = { id: '9007199254740995' };
  host = document.createElement('div');
  app = createApp(Form);
  app.mount(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('概念模型编辑弹窗', () => {
  it('详情携带最新修订号保存，不把超大ID转成数字', async () => {
    vi.mocked(getConceptModel).mockResolvedValue(detail());
    await harness.options.onOpenChange(true);
    await harness.options.onConfirm();
    expect(updateConceptModel).toHaveBeenCalledWith(
      expect.objectContaining({
        id: '9007199254740995',
        expectedRevision: '9007199254740993',
        dataDomainId: '9007199254740994',
      }),
    );
    expect(createConceptModel).not.toHaveBeenCalled();
  });
  it('详情已启用时强制只读，即使调用确认也不能提交', async () => {
    vi.mocked(getConceptModel).mockResolvedValue(detail('ACTIVE'));
    await harness.options.onOpenChange(true);
    await flush();
    expect(host.querySelector('[data-readonly="true"]')).not.toBeNull();
    await harness.options.onConfirm();
    expect(updateConceptModel).not.toHaveBeenCalled();
    expect(host.textContent).toContain('先在列表中停用');
  });
  it('关闭或权限变化后，迟到的详情不会重新显示旧对象', async () => {
    let resolve!: (value: ConceptModelApi.Model) => void;
    vi.mocked(getConceptModel).mockReturnValue(
      new Promise((done) => {
        resolve = done;
      }),
    );
    const loading = harness.options.onOpenChange(true);
    await flush();
    harness.access.accessCodes = [];
    resolve(detail());
    await loading;
    await flush();
    expect(harness.setValues).not.toHaveBeenCalled();
    expect(host.textContent).not.toContain('设计');
    await harness.options.onConfirm();
    expect(updateConceptModel).not.toHaveBeenCalled();
  });
});
