import type { App, PropType, SetupContext } from 'vue';

import type { VbenFormApi } from '#/adapter/form';

import {
  createApp,
  defineComponent,
  h,
  markRaw,
  nextTick,
  reactive,
} from 'vue';

import { globalShareState, setupVbenForm } from '@vben/common-ui';

import {
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from 'vitest';

import { getConceptModel } from '#/api/modeling/concept-model';
import { getSubjectDomainList } from '#/api/modeling/subjectdomain';

import Form from './form.vue';

const harness = vi.hoisted(() => ({
  access: {
    accessCodes: [
      'modeling:concept-model:update',
      'modeling:data-domain:query',
      'modeling:subject-domain:query',
    ],
    accessToken: 'session-a',
    refreshToken: '',
  },
  data: { id: '3', readonly: true },
  modal: {} as { onOpenChange: (open: boolean) => Promise<void> },
  formApi: undefined as unknown as VbenFormApi,
}));

vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => ({ userInfo: { userId: '1' }, userRoles: ['modeler'] }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.access.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/common-ui', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@vben/common-ui')>()),
  useVbenModal: (options: typeof harness.modal) => {
    harness.modal = options;
    return [
      (_: unknown, { slots }: SetupContext) => h('section', slots.default?.()),
      { getData: () => harness.data, lock: vi.fn(), unlock: vi.fn() },
    ];
  },
}));
vi.mock('#/adapter/form', async (importOriginal) => {
  const actual = await importOriginal<typeof import('#/adapter/form')>();
  return {
    ...actual,
    useVbenForm: (options: Parameters<typeof actual.useVbenForm>[0]) => {
      const result = actual.useVbenForm(options);
      harness.formApi = result[1];
      return result;
    },
  };
});
vi.mock('antdv-next', () => ({
  Alert: () => null,
  message: { success: vi.fn(), warning: vi.fn() },
}));
vi.mock('#/api/modeling/concept-model', () => ({
  getConceptModel: vi.fn(),
  createConceptModel: vi.fn(),
  updateConceptModel: vi.fn(),
}));
vi.mock('#/api/modeling/datadomain', () => ({
  getDataDomainSimpleList: vi.fn(async () => [
    { id: 29, name: '水利监测与防汛调度', status: 0 },
    { id: '9007199254740993', name: '其他板块', status: 0 },
  ]),
}));
vi.mock('#/api/modeling/subjectdomain', () => ({
  getSubjectDomainList: vi.fn(),
}));
vi.mock('./design-editor.vue', () => ({ default: () => null }));

const Select = defineComponent({
  inheritAttrs: false,
  props: {
    disabled: Boolean,
    name: { type: String, default: '' },
    options: {
      type: Array as PropType<{ label: string; value: string }[]>,
      default: () => [],
    },
    value: { type: [Number, String], default: undefined },
  },
  setup: (props) => () =>
    h(
      'output',
      {
        'data-field': props.name,
        'data-disabled': props.disabled,
        'data-options': JSON.stringify(
          props.options.map((option) => option.value),
        ),
      },
      props.options.find((option) => option.value === props.value)?.label ??
        String(props.value ?? ''),
    ),
});

let app: App | undefined;
let host: HTMLDivElement;
async function flush() {
  await nextTick();
  await Promise.resolve();
  await nextTick();
}
beforeAll(() => {
  globalShareState.setComponents({
    ...globalShareState.getComponents(),
    Input: markRaw(Select),
    Select: markRaw(Select),
    Textarea: markRaw(Select),
    TextArea: markRaw(Select),
  });
  setupVbenForm({
    config: { baseModelPropName: 'value' },
    rules: { selectRequired: () => true },
  });
});
beforeEach(async () => {
  vi.clearAllMocks();
  harness.access = reactive({ ...harness.access });
  harness.data = { id: '3', readonly: true };
  vi.mocked(getConceptModel).mockResolvedValue({
    id: 3,
    revision: '9007199254740999',
    name: '河湖管理业务规划',
    code: 'QDATA_RIVER_LAKE',
    dataDomainId: 29,
    dataDomainName: '水利监测与防汛调度',
    subjectDomainId: 115,
    subjectDomainName: '河湖管理',
    status: 'DRAFT',
    design: { entities: [], relations: [] },
  });
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Form);
  app.mount(host);
  await flush();
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('概念模型归属选项与真实表单依赖', () => {
  it('主题选项延迟返回后，详情显示主题名称并保留原始编号', async () => {
    let resolve!: (
      subjects: Awaited<ReturnType<typeof getSubjectDomainList>>,
    ) => void;
    vi.mocked(getSubjectDomainList).mockReturnValueOnce(
      new Promise((done) => {
        resolve = done;
      }),
    );
    const loading = harness.modal.onOpenChange(true);
    await vi.waitFor(() => expect(getSubjectDomainList).toHaveBeenCalledOnce());
    await flush();
    resolve([{ id: 115, dataDomainId: 29, name: '河湖管理', status: 0 }]);
    await loading;
    await flush();

    expect(
      host.querySelector('[data-field="subjectDomainId"]')?.textContent,
    ).toBe('河湖管理');
    expect(await harness.formApi.getValues()).toMatchObject({
      dataDomainId: '29',
      subjectDomainId: '115',
    });
    expect(
      host.querySelector<HTMLOutputElement>('[data-field="subjectDomainId"]')
        ?.dataset.disabled,
    ).toBe('true');
  });

  it('切换数据板块后仅提供当前板块的主题，并保留长编号', async () => {
    harness.data.readonly = false;
    vi.mocked(getSubjectDomainList).mockResolvedValueOnce([
      { id: 115, dataDomainId: 29, name: '河湖管理', status: 0 },
      {
        id: '9007199254740995',
        dataDomainId: '9007199254740993',
        name: '其他主题',
        status: 0,
      },
    ]);
    await harness.modal.onOpenChange(true);
    await flush();
    const control = () =>
      host.querySelector<HTMLOutputElement>('[data-field="subjectDomainId"]');
    expect(control()?.dataset.options).toBe('["115"]');

    await harness.formApi.setValues({
      dataDomainId: '9007199254740993',
      subjectDomainId: '9007199254740995',
    });
    await flush();
    expect(control()?.textContent).toBe('其他主题');
    expect(control()?.dataset.options).toBe('["9007199254740995"]');
    expect(await harness.formApi.getValues()).toMatchObject({
      dataDomainId: '9007199254740993',
      subjectDomainId: '9007199254740995',
    });
  });

  it('主题选项加载失败时仍用详情中的名称回显已有归属', async () => {
    vi.mocked(getSubjectDomainList).mockRejectedValueOnce(
      new Error('选项不可用'),
    );
    await harness.modal.onOpenChange(true);
    await flush();
    expect(
      host.querySelector('[data-field="subjectDomainId"]')?.textContent,
    ).toBe('河湖管理');
  });
});
