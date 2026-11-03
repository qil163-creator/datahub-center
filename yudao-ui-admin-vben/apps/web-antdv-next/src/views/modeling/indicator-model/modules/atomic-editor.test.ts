import type { App, SetupContext } from 'vue';

import type { IndicatorModelApi } from '#/api/modeling/indicator-model';

import { createApp, h, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import AtomicEditor from './atomic-editor.vue';

const harness = vi.hoisted(() => ({
  access: {} as {
    accessCodes: string[];
    accessToken: string;
    refreshToken: string;
  },
  detail: vi.fn(),
  fields: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  entities: vi.fn(),
}));
const ids = {
  domain: '9007199254740993',
  subject: '9007199254740994',
  entity: '9007199254740995',
  source: '9007199254740996',
  indicator: '9007199254740999',
  revision: '9007199254740900',
};
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access }));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/hooks', () => ({
  useAntdDesignTokens: () => ({ tokens: { borderRadius: 6 } }),
  getDictOptions: () => [{ label: '天（T+1）', value: '1' }],
}));
vi.mock('#/views/modeling/logictable/icons', () => ({
  QdIcon: () => h('span'),
}));
vi.mock('#/api/modeling/business-entity', () => ({
  getBusinessEntityPage: () => harness.entities(),
}));
vi.mock('#/api/modeling/indicator-model', () => ({
  getIndicator: (id: string) => harness.detail(id),
  getIndicatorSourceFields: (id: string) => harness.fields(id),
  createIndicator: (data: unknown) => harness.create(data),
  updateIndicator: (data: unknown) => harness.update(data),
}));
vi.mock('./use-metric-options', () => ({
  useMetricOptions: () => ({
    domains: ref([{ id: '9007199254740993', name: '水利', status: 0 }]),
    subjects: ref([
      {
        id: '9007199254740994',
        dataDomainId: '9007199254740993',
        name: '水文',
        status: 0,
      },
    ]),
    users: ref([]),
    models: ref([
      {
        id: '9007199254740996',
        dataDomainId: '9007199254740993',
        subjectDomainId: '9007199254740994',
        entityId: '9007199254740995',
        tableType: 'DETAIL',
        enabled: 1,
        status: 'PUBLISHED',
        dataTimeliness: '1',
      },
    ]),
    error: ref(false),
    loading: ref(false),
    reload: vi.fn(),
    userPhone: () => '',
  }),
}));
vi.mock('./entity-selector.vue', () => ({
  default: {
    props: ['modelValue'],
    emits: ['update:modelValue', 'change'],
    setup:
      (
        props: { modelValue?: string },
        { emit }: { emit: (name: string, value: string) => void },
      ) =>
      () =>
        h('input', {
          value: props.modelValue,
          onChange: (event: Event) => {
            const value = (event.target as HTMLInputElement).value;
            emit('update:modelValue', value);
            emit('change', value);
          },
        }),
  },
}));
vi.mock('antdv-next', () => {
  const plain = {
    setup:
      (_: unknown, { slots }: SetupContext) =>
      () =>
        h('div', slots.default?.()),
  };
  const control = {
    props: ['value', 'disabled', 'placeholder', 'readonly'],
    emits: ['update:value', 'change'],
    setup:
      (props: { disabled?: boolean; placeholder?: string; readonly?: boolean; value?: string; }, { emit }: SetupContext) =>
      () =>
        h('input', {
          value: props.value,
          disabled: props.disabled,
          readonly: props.readonly,
          placeholder: props.placeholder,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLInputElement).value),
          onChange: (event: Event) => {
            const value = (event.target as HTMLInputElement).value;
            emit('update:value', value);
            emit('change', value);
          },
        }),
  };
  return {
    ConfigProvider: plain,
    Spin: plain,
    Input: control,
    TextArea: control,
    Select: control,
    TreeSelect: control,
    RadioGroup: control,
    Radio: plain,
    Alert: {
      props: ['message'],
      setup:
        (props: { message: string }, { slots }: SetupContext) =>
        () =>
          h('aside', [props.message, slots.action?.()]),
    },
    Button: {
      setup:
        (_: unknown, { slots }: SetupContext) =>
        () =>
          h('button', slots.default?.()),
    },
  };
});

let app: App | undefined;
let host: HTMLDivElement;
let editor: InstanceType<typeof AtomicEditor>;
const model = (
  overrides: Partial<IndicatorModelApi.Model> = {},
): IndicatorModelApi.Model => ({
  id: ids.indicator,
  revision: ids.revision,
  indicatorType: 'ATOMIC',
  status: 'DRAFT',
  code: 'RAIN',
  name: '降雨',
  dataDomainId: ids.domain,
  subjectDomainId: ids.subject,
  entityId: ids.entity,
  ownerUserId: '41',
  ownerUserName: '负责人',
  ownerUserPhone: '13800000000',
  dataFreshness: '1',
  sourceModelId: ids.source,
  valueFieldUid: 'amount-uid',
  timeFieldUid: 'time-uid',
  aggregation: 'SUM',
  resultDataType: 'DECIMAL',
  unit: '毫米',
  definition: '降雨总量',
  ...overrides,
});
async function flush() {
  for (let index = 0; index < 8; index++) await nextTick();
}
function change(id: string, value: string) {
  const input = host.querySelector<HTMLInputElement>(`#atomic-${id}`)!;
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  input.dispatchEvent(new Event('change', { bubbles: true }));
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
beforeEach(async () => {
  vi.clearAllMocks();
  harness.access = reactive({
    accessCodes: [],
    accessToken: 'session',
    refreshToken: '',
  });
  harness.detail.mockResolvedValue(model());
  harness.entities.mockResolvedValue({
    list: [{ id: ids.entity, name: '降雨采集', status: 0 }],
    total: 1,
  });
  harness.fields.mockResolvedValue([
    { fieldUid: 'amount-uid', fieldName: 'amount', dataType: 'DECIMAL' },
    { fieldUid: 'time-uid', fieldName: 'occurred_at', dataType: 'TIMESTAMP' },
  ]);
  harness.create.mockResolvedValue(ids.indicator);
  harness.update.mockResolvedValue(true);
  host = document.createElement('div');
  document.body.append(host);
  app = createApp({
    render: () =>
      h(AtomicEditor, {
        ref: (value) => {
          editor = value as InstanceType<typeof AtomicEditor>;
        },
      }),
  });
  app.mount(host);
  await flush();
});
afterEach(() => {
  app?.unmount();
  host.remove();
});

describe('原子指标编辑异步边界', () => {
  it('保存期间拒绝重复提交，选项失败重试保留已填定义', async () => {
    harness.entities.mockRejectedValueOnce(
      new Error('temporary entity failure'),
    );
    await editor.open({ indicatorType: 'ATOMIC', cloneId: ids.indicator });
    change('code', 'RAIN_COPY');
    await flush();
    expect(host.textContent).toContain('业务实体加载失败');
    [...host.querySelectorAll('button')]
      .find((button) => button.textContent === '重试')
      ?.click();
    await flush();
    expect(host.querySelector<HTMLInputElement>('#atomic-code')?.value).toBe(
      'RAIN_COPY',
    );
    host.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    expect(harness.create).not.toHaveBeenCalled();
    const saved = deferred<string>();
    harness.create.mockReturnValueOnce(saved.promise);
    const first = editor.submit();
    expect(editor.ready).toBe(false);
    expect(await editor.submit()).toBe(false);
    expect(harness.create).toHaveBeenCalledTimes(1);
    saved.resolve(ids.indicator);
    expect(await first).toBe(true);
  });
  it('编辑按来源模型读取当前稳定字段并使用CAS，保留未知单位及负责人信息', async () => {
    await editor.open({ indicatorType: 'ATOMIC', id: ids.indicator });
    expect(harness.fields).toHaveBeenCalledWith(ids.source);
    expect(host.textContent).not.toContain('加载失败');
    expect(host.querySelector<HTMLInputElement>('#atomic-unit')?.value).toBe(
      '毫米',
    );
    expect(
      host.querySelector<HTMLInputElement>(
        'input[disabled][placeholder="请输入联系方式"]',
      )?.value,
    ).toBe('13800000000');
    expect(await editor.submit()).toBe(true);
    expect(harness.update).toHaveBeenCalledWith(
      expect.objectContaining({
        id: ids.indicator,
        expectedRevision: ids.revision,
        sourceModelId: ids.source,
        valueFieldUid: 'amount-uid',
        timeFieldUid: 'time-uid',
        entityId: ids.entity,
        ownerUserId: '41',
        unit: '毫米',
      }),
    );
  });
  it('来源字段请求失败后可重试，保存失败保留输入并可重试', async () => {
    await editor.open({ indicatorType: 'ATOMIC', cloneId: ids.indicator });
    change('code', 'RAIN_COPY');
    harness.fields.mockRejectedValueOnce(new Error('temporary'));
    change('source', ids.source);
    await flush();
    expect(host.textContent).toContain('来源字段加载失败');
    const retry = [...host.querySelectorAll('button')].find(
      (button) => button.textContent === '重试',
    );
    retry?.click();
    await flush();
    expect(harness.fields).toHaveBeenLastCalledWith(ids.source);
    change('value', 'amount-uid');
    change('time', 'time-uid');
    await flush();
    harness.create.mockRejectedValueOnce(new Error('CAS or source changed'));
    expect(await editor.submit()).toBe(false);
    expect(host.querySelector<HTMLInputElement>('#atomic-code')?.value).toBe(
      'RAIN_COPY',
    );
    expect(await editor.submit()).toBe(true);
    expect(harness.create).toHaveBeenLastCalledWith(
      expect.objectContaining({
        id: undefined,
        expectedRevision: undefined,
        sourceModelId: ids.source,
        status: 'DRAFT',
      }),
    );
  });
  it('对象切换忽略迟到详情，启用记录只读但克隆可保存草稿', async () => {
    const first = deferred<IndicatorModelApi.Model>();
    harness.detail.mockReturnValueOnce(first.promise);
    const pending = editor.open({ indicatorType: 'ATOMIC', id: '12' });
    harness.detail.mockResolvedValueOnce(
      model({ id: '13', name: '当前指标', status: 'ACTIVE' }),
    );
    await editor.open({ indicatorType: 'ATOMIC', id: '13' });
    first.resolve(model({ name: '迟到指标' }));
    await pending;
    await flush();
    expect(host.querySelector<HTMLInputElement>('#atomic-name')?.value).toBe(
      '当前指标',
    );
    expect(await editor.submit()).toBe(false);
    expect(harness.update).not.toHaveBeenCalled();
    harness.detail.mockResolvedValueOnce(model({ status: 'ACTIVE' }));
    await editor.open({ indicatorType: 'ATOMIC', cloneId: ids.indicator });
    change('code', 'CLONED');
    await flush();
    expect(await editor.submit()).toBe(true);
    expect(harness.create).toHaveBeenCalledWith(
      expect.objectContaining({ status: 'DRAFT', id: undefined }),
    );
  });
  it('会话变化丢弃正在保存的响应，关闭重开不会保留saving锁', async () => {
    await editor.open({ indicatorType: 'ATOMIC', id: ids.indicator });
    const saved = deferred<boolean>();
    harness.update.mockReturnValueOnce(saved.promise);
    const pending = editor.submit();
    harness.access.accessToken = 'another-session';
    saved.resolve(true);
    expect(await pending).toBe(false);
    editor.reset();
    await editor.open({ indicatorType: 'ATOMIC', id: ids.indicator });
    expect(editor.ready).toBe(true);
    expect(await editor.submit()).toBe(true);
  });
});
