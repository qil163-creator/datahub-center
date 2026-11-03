/* eslint-disable vue/one-component-per-file -- Controls are isolated while exercising the real rule editor and async lifecycle. */
import type { App, PropType } from 'vue';

import type { QualityTaskApi } from '#/api/governance/quality/task';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getDatabaseTableMeta } from '#/api/dataforge/database';
import {
  sampleQualityTaskRule,
  spotCheckQualityTaskRule,
} from '#/api/governance/quality/task';

import { newEvaluation } from '../data';
import RuleConfig from './rule-config.vue';
import RuleEditor from './rule-editor.vue';

const harness = vi.hoisted(() => ({
  access: { accessToken: 'actor-a', refreshToken: '' },
  success: vi.fn(),
  warning: vi.fn(),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access }));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('#/api/dataforge/database', () => ({ getDatabaseTableMeta: vi.fn() }));
vi.mock('#/api/governance/standardcodeset', () => ({
  getStandardCodeSetPage: vi.fn(async () => ({ list: [], total: 0 })),
}));
vi.mock('#/api/governance/standardcodeitem', () => ({
  getStandardCodeItemPage: vi.fn(async () => ({ list: [], total: 0 })),
}));
vi.mock('#/api/governance/quality/task', () => ({
  sampleQualityTaskRule: vi.fn(),
  spotCheckQualityTaskRule: vi.fn(),
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: { onClosed?: () => void }) => {
    const visible = ref(false);
    const api = {
      open: () => {
        visible.value = true;
      },
      close: async () => {
        visible.value = false;
        options.onClosed?.();
      },
    };
    return [
      defineComponent({
        props: { title: { type: String, default: '' } },
        setup:
          (props, { slots }) =>
          () =>
            visible.value
              ? h('section', { 'data-modal': props.title }, [
                  h('h2', props.title),
                  slots.default?.(),
                  slots.footer?.(),
                ])
              : null,
      }),
      api,
    ];
  },
}));
vi.mock('antdv-next', () => {
  const container = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  const input = defineComponent({
    inheritAttrs: false,
    props: { value: { type: [String, Number], default: undefined }, disabled: Boolean },
    emits: ['update:value', 'change'],
    setup:
      (props, { attrs, emit }) =>
      () =>
        h('input', {
          ...attrs,
          value: props.value,
          disabled: props.disabled,
          onInput: (event: Event) => {
            const value = (event.target as HTMLInputElement).value;
            emit('update:value', value);
            emit('change', value);
          },
        }),
  });
  return {
    Alert: defineComponent({
      props: { message: { type: String, default: '' } },
      setup: (props) => () => h('div', { 'data-alert': '' }, props.message),
    }),
    Button: defineComponent({
      props: { disabled: Boolean, loading: Boolean },
      setup:
        (props, { attrs, slots }) =>
        () =>
          h(
            'button',
            { ...attrs, disabled: props.disabled || props.loading },
            slots.default?.(),
          ),
    }),
    Input: input,
    TextArea: input,
    InputNumber: input,
    Spin: container,
    Tabs: container,
    TabPane: container,
    Checkbox: defineComponent({
      props: { checked: Boolean, disabled: Boolean },
      emits: ['update:checked'],
      setup:
        (props, { emit, slots }) =>
        () =>
          h('label', [
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
    CheckboxGroup: container,
    RadioGroup: defineComponent({
      props: {
        value: { type: [Boolean, Number, String], default: undefined },
        disabled: Boolean,
        options: {
          type: Array as PropType<{ label: string; value: unknown }[]>,
          default: () => [],
        },
      },
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'div',
            props.options.map((option) =>
              h(
                'button',
                {
                  disabled: props.disabled,
                  onClick: (event: Event) => {
                    event.preventDefault();
                    emit('update:value', option.value);
                  },
                },
                option.label,
              ),
            ),
          ),
    }),
    Select: defineComponent({
      inheritAttrs: false,
      props: {
        value: { type: [String, Number, Array], default: undefined },
        disabled: Boolean,
        mode: { type: String, default: undefined },
        placeholder: { type: String, default: '' },
        options: {
          type: Array as PropType<{ label: string; value: number | string }[]>,
          default: () => [],
        },
      },
      emits: ['update:value', 'change'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'select',
            {
              value: props.value,
              disabled: props.disabled,
              multiple: props.mode === 'multiple',
              'data-placeholder': props.placeholder,
              onChange: (event: Event) => {
                const element = event.target as HTMLSelectElement;
                const selected =
                  props.mode === 'multiple'
                    ? [...element.selectedOptions].map((option) => option.value)
                    : element.value;
                emit('update:value', selected);
                emit('change', selected);
              },
            },
            [
              h('option', { value: '' }, props.placeholder),
              ...props.options.map((entry) =>
                h('option', { value: entry.value }, entry.label),
              ),
            ],
          ),
    }),
    message: { warning: harness.warning, success: harness.success },
  };
});
const catalog: QualityTaskApi.CatalogRule[] = [
  {
    ruleCode: '107',
    ruleName: '字段字符类型校验',
    strategyKey: 'CHARACTER_VALIDATION',
    qualityDim: 'ACCURACY',
    description: '检查字段字符组成',
    enabled: true,
    sampleSupported: true,
  },
];
const objects: QualityTaskApi.Object[] = [
  {
    clientKey: 'object-a',
    objectName: '对象 A',
    datasourceConnId: '9007199254740993',
    tableName: 'table_a',
  },
  {
    clientKey: 'object-b',
    objectName: '对象 B',
    datasourceConnId: '9007199254740995',
    tableName: 'table_b',
  },
];
function seed(): QualityTaskApi.Evaluation {
  return {
    id: '9223372036854775806',
    objectKey: 'object-a',
    evaluationName: '字符检查',
    ...catalog[0]!,
    severity: 'CRITICAL',
    status: 0,
    columnNames: ['SCENECD'],
    config: { useRegex: false, allowedTypes: ['digit'], ignoreNullValue: true },
  };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
let app: App;
let host: HTMLDivElement;
let editor: InstanceType<typeof RuleEditor>;
const saved = vi.fn();
async function settle() {
  for (let i = 0; i < 8; i++) await nextTick();
}
function button(label: string) {
  const result = [...host.querySelectorAll('button')].find(
    (element) => element.textContent?.trim() === label,
  );
  if (!result) throw new Error(`Button missing: ${label}`);
  return result;
}
function input(placeholder: string, value: string) {
  const element = host.querySelector<HTMLInputElement>(
    `input[placeholder="${placeholder}"]`,
  )!;
  element.value = value;
  element.dispatchEvent(new Event('input', { bubbles: true }));
}
beforeEach(async () => {
  vi.clearAllMocks();
  harness.access = reactive({ accessToken: 'actor-a', refreshToken: '' });
  vi.mocked(getDatabaseTableMeta).mockResolvedValue({
    columns: [{ fieldName: 'SCENECD', remarks: '应用场景编码' }],
  } as Awaited<ReturnType<typeof getDatabaseTableMeta>>);
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(RuleEditor, { catalog, objects, onSave: saved });
  editor = app.mount(host) as InstanceType<typeof RuleEditor>;
  await settle();
});
afterEach(() => {
  app.unmount();
  host.remove();
});

describe('quality rule editor', () => {
  it.each(['COMPOSITE_UNIQUENESS_VALIDATION', 'GROUP_FIELD_COMPLETENESS'])(
    '数据元模式下的 %s 可以配置多个检查字段',
    async (strategyKey) => {
      app.unmount();
      const value = ref<QualityTaskApi.Evaluation>({
        ...seed(),
        strategyKey,
        columnNames: [],
        config: {},
      });
      app = createApp({
        render: () =>
          h(RuleConfig, {
            context: 'standard',
            objects: [],
            fieldOptions: [
              { label: '订单编号', value: 'order_no' },
              { label: '订单日期', value: 'order_date' },
            ],
            value: value.value,
            'onUpdate:value': (updated) => {
              value.value = updated;
            },
          }),
      });
      app.mount(host);
      await settle();
      const fields = host.querySelector<HTMLSelectElement>(
        'select[data-placeholder="请选择检查字段"]',
      );
      expect(fields?.multiple).toBe(true);
      for (const option of fields!.options)
        option.selected = ['order_date', 'order_no'].includes(option.value);
      fields!.dispatchEvent(new Event('change'));
      await settle();
      expect(value.value.columnNames).toEqual(['order_no', 'order_date']);
      expect(getDatabaseTableMeta).not.toHaveBeenCalled();
    },
  );
  it.each([
    ['107', 'CHARACTER_VALIDATION', 'ACCURACY', '允许字符类型'],
    ['306', 'NUMERIC_RANGE_VALIDATION', 'CONSISTENCY', '是否包含边界值'],
    ['311', 'COMPOSITE_UNIQUENESS_VALIDATION', 'CONSISTENCY', '检查字段'],
    ['205', 'GROUP_FIELD_COMPLETENESS', 'CONSISTENCY', '必须全部填写'],
    ['115', 'TIME_ORDER_VALIDATION', 'COMPLETENESS', '字段'],
    ['118', 'MUTUAL_EXCLUSION_VALIDATION', 'COMPLETENESS', '互斥条件'],
    ['108', 'LENGTH_VALIDATION', 'COMPLETENESS', '最小长度'],
    ['110', 'DECIMAL_PRECISION_VALIDATION', 'COMPLETENESS', '忽略整数值'],
    ['111', 'ENUM_VALIDATION', 'COMPLETENESS', '选择代码表'],
    ['112', 'FOREIGN_KEY_VALIDATION', 'COMPLETENESS', '引用字段'],
  ])(
    'renders configuration for actual backend catalog %s / %s',
    async (ruleCode, strategyKey, qualityDim, label) => {
      const evaluation = newEvaluation({
        ruleCode,
        strategyKey,
        qualityDim,
        ruleName: strategyKey,
        description: '',
        enabled: true,
        sampleSupported: false,
      });
      evaluation.objectKey = 'object-a';
      editor.open(evaluation);
      await settle();
      expect(host.textContent).toContain(label);
    },
  );
  it('keeps the string rule ID in edits and clears it when starting a new rule', async () => {
    editor.open(seed(), 2);
    await settle();
    button('确定').click();
    await settle();
    expect(saved).toHaveBeenCalledWith(
      expect.objectContaining({ id: '9223372036854775806' }),
      2,
    );
    editor.open();
    await settle();
    [...host.querySelectorAll('button')]
      .find((entry) => entry.textContent?.includes('检查字段字符组成'))!
      .click();
    await settle();
    button('确定').click();
    await settle();
    expect(host.textContent).toContain('请输入评测名称');
    expect(saved).toHaveBeenCalledTimes(1);
  });
  it('retains suggested enum values and existing labels when adding a value', async () => {
    const evaluation = {
      ...seed(),
      strategyKey: 'ENUM_VALIDATION',
      config: {
        validValues: ['A', 'B'],
        valueLabels: [{ value: 'A', label: '已命名值' }],
      },
    };
    editor.open(evaluation);
    await settle();
    expect(
      [...host.querySelectorAll<HTMLInputElement>('input[placeholder="请输入代码值"]')].map((entry) => entry.value),
    ).toEqual(['A', 'B']);
    expect(
      [...host.querySelectorAll<HTMLInputElement>('input[placeholder="请输入代码名称"]')].map((entry) => entry.value),
    ).toEqual(['已命名值', 'B']);
    button('＋ 新增').click();
    await settle();
    const inputs = host.querySelectorAll<HTMLInputElement>('input[placeholder="请输入代码值"]');
    inputs[2]!.value = 'C';
    inputs[2]!.dispatchEvent(new Event('input', { bubbles: true }));
    await settle();
    button('确定').click();
    await settle();
    expect(saved).toHaveBeenCalledWith(
      expect.objectContaining({ config: expect.objectContaining({ validValues: ['A', 'B', 'C'] }) }),
      undefined,
    );
    expect(evaluation.config.validValues).toEqual(['A', 'B']);
    expect(evaluation.config.valueLabels).toEqual([{ value: 'A', label: '已命名值' }]);
  });
  it('shows mixed suggested boundaries without changing them and synchronizes an explicit choice', async () => {
    const evaluation = {
      ...seed(),
      strategyKey: 'NUMERIC_RANGE_VALIDATION',
      config: {
        minValue: '0',
        maxValue: '9007199254740993.000000000000000001',
        includeMin: true,
        includeMax: false,
      },
    };
    editor.open(evaluation);
    await settle();
    expect(host.textContent).toContain('包含最小值，不包含最大值');
    button('确定').click();
    await settle();
    expect(saved).toHaveBeenLastCalledWith(
      expect.objectContaining({ config: evaluation.config }),
      undefined,
    );
    editor.open(evaluation);
    await settle();
    button('不包含').click();
    await settle();
    button('确定').click();
    await settle();
    expect(saved).toHaveBeenLastCalledWith(
      expect.objectContaining({ config: { ...evaluation.config, includeBoundary: false, includeMin: false, includeMax: false } }),
      undefined,
    );
    editor.open(evaluation);
    await settle();
    button('包含').click();
    await settle();
    button('确定').click();
    await settle();
    expect(saved).toHaveBeenLastCalledWith(
      expect.objectContaining({ config: { ...evaluation.config, includeBoundary: true, includeMin: true, includeMax: true } }),
      undefined,
    );
  });
  it('keeps adjacent time comparisons linked while growing and shrinking the visible chain', async () => {
    const evaluation = {
      ...seed(),
      ruleCode: '115',
      strategyKey: 'TIME_ORDER_VALIDATION',
      columnNames: [],
      config: {
        conditions: [
          { leftField: 'SCENECD', operator: '<', rightField: 'SCENECD' },
        ],
      },
    };
    editor.open(evaluation);
    await settle();
    host
      .querySelector<HTMLButtonElement>('[aria-label="添加时间字段"]')!
      .click();
    await settle();
    const fields = host.querySelectorAll<HTMLSelectElement>(
      '.q-time-chain select[data-placeholder="字段"]',
    );
    expect(fields).toHaveLength(3);
    fields[1]!.value = '';
    fields[1]!.dispatchEvent(new Event('change', { bubbles: true }));
    await settle();
    fields[1]!.value = 'SCENECD';
    fields[1]!.dispatchEvent(new Event('change', { bubbles: true }));
    fields[2]!.value = 'SCENECD';
    fields[2]!.dispatchEvent(new Event('change', { bubbles: true }));
    await settle();
    button('确定').click();
    await settle();
    expect(saved).toHaveBeenCalledWith(
      expect.objectContaining({
        config: {
          conditions: [
            { leftField: 'SCENECD', operator: '<', rightField: 'SCENECD' },
            { leftField: 'SCENECD', operator: '<', rightField: 'SCENECD' },
          ],
        },
      }),
      undefined,
    );
    editor.open(evaluation);
    await settle();
    host
      .querySelector<HTMLButtonElement>('[aria-label="添加时间字段"]')!
      .click();
    await settle();
    host
      .querySelector<HTMLButtonElement>('[aria-label="删除末尾时间字段"]')!
      .click();
    await settle();
    expect(
      host.querySelectorAll('.q-time-chain select[data-placeholder="字段"]'),
    ).toHaveLength(2);
  });
  it('pairs each mutual exclusion field with its own true value and removes both together', async () => {
    const evaluation = {
      ...seed(),
      ruleCode: '118',
      strategyKey: 'MUTUAL_EXCLUSION_VALIDATION',
      columnNames: ['a', 'b'],
      config: { condition: 'IS_TRUE', trueValues: ['A', 'B'] },
    };
    editor.open(evaluation);
    await settle();
    expect(host.querySelectorAll('.q-mutex-row')).toHaveLength(2);
    expect(
      [...host.querySelectorAll<HTMLInputElement>('.q-mutex-row input')].map(
        (item) => item.value,
      ),
    ).toEqual(['A', 'B']);
    host
      .querySelector<HTMLButtonElement>('[aria-label="添加互斥字段"]')!
      .click();
    await settle();
    button('确定').click();
    await settle();
    expect(saved).not.toHaveBeenCalled();
    expect(host.textContent).toContain('请选择检查字段');
    host
      .querySelector<HTMLButtonElement>('[aria-label="删除互斥字段 3"]')!
      .click();
    await settle();
    button('确定').click();
    await settle();
    expect(saved).toHaveBeenCalledWith(
      expect.objectContaining({
        columnNames: ['a', 'b'],
        config: { condition: 'IS_TRUE', trueValues: ['A', 'B'] },
      }),
      undefined,
    );
  });
  it('rejects empty samples and discards responses after sample or login context changes', async () => {
    const first = deferred<{ message: string; passed: boolean; }>();
    vi.mocked(sampleQualityTaskRule).mockReturnValueOnce(first.promise);
    editor.open(seed(), 0);
    await settle();
    button('监测').click();
    await settle();
    expect(sampleQualityTaskRule).not.toHaveBeenCalled();
    expect(harness.warning).toHaveBeenCalledWith('校验未通过，请添加样例数据');
    input('请输入样例数据', '12333');
    await settle();
    button('监测').click();
    await settle();
    expect(sampleQualityTaskRule).toHaveBeenCalledWith(
      expect.objectContaining({ sampleData: '12333' }),
    );
    input('请输入样例数据', 'abcd');
    await settle();
    first.resolve({ passed: true, message: '旧样例通过' });
    await settle();
    expect(host.textContent).not.toContain('旧样例通过');
    const second = deferred<{ message: string; passed: boolean; }>();
    vi.mocked(sampleQualityTaskRule).mockReturnValueOnce(second.promise);
    button('监测').click();
    await settle();
    harness.access.accessToken = 'actor-b';
    second.resolve({ passed: true, message: '旧用户通过' });
    await settle();
    expect(host.textContent).not.toContain('旧用户通过');
  });
  it('uses backend spot-check rows and includes current objects for reference resolution', async () => {
    vi.mocked(spotCheckQualityTaskRule).mockResolvedValue({
      columns: ['SCENECD'],
      problemRows: [{ SCENECD: 'bad-row' }],
      normalRows: [{ SCENECD: '123' }],
    });
    editor.open(seed(), 0);
    await settle();
    button('抽查').click();
    await settle();
    expect(spotCheckQualityTaskRule).toHaveBeenCalledWith(
      expect.objectContaining({
        objects,
        object: objects[0],
        evaluation: expect.objectContaining({ id: '9223372036854775806' }),
      }),
    );
    expect(host.textContent).toContain('bad-row');
  });
  it('clears dependent fields and ignores a stale metadata response after switching objects', async () => {
    const old = deferred<Awaited<ReturnType<typeof getDatabaseTableMeta>>>();
    vi.mocked(getDatabaseTableMeta).mockReturnValueOnce(old.promise);
    editor.open(seed(), 0);
    await settle();
    const objectSelect = host.querySelector<HTMLSelectElement>(
      'select[data-placeholder="请选择评测对象"]',
    )!;
    objectSelect.value = 'object-b';
    objectSelect.dispatchEvent(new Event('change', { bubbles: true }));
    await settle();
    const fields = host.querySelector<HTMLSelectElement>(
      'select[data-placeholder="请选择检查字段"]',
    )!;
    expect(fields.value).toBe('');
    old.resolve({ columns: [{ fieldName: 'STALE_FIELD' }] } as Awaited<
      ReturnType<typeof getDatabaseTableMeta>
    >);
    await settle();
    expect(fields.textContent).not.toContain('STALE_FIELD');
    button('确定').click();
    await settle();
    expect(saved).not.toHaveBeenCalled();
    expect(host.textContent).toContain('请选择检查字段');
  });
  it('offers sample and spot checks in readonly details while preventing rule edits', async () => {
    editor.open(seed(), 0, true);
    await settle();
    expect(
      host.querySelector<HTMLInputElement>(
        'input[placeholder="请输入评测名称"]',
      )!.disabled,
    ).toBe(true);
    expect(
      host.querySelector<HTMLInputElement>(
        'input[placeholder="请输入样例数据"]',
      )!.disabled,
    ).toBe(false);
    expect(
      [...host.querySelectorAll('button')].some(
        (entry) => entry.textContent === '确定',
      ),
    ).toBe(false);
    expect(button('抽查')).toBeTruthy();
  });
  it('keeps explicit execution denial when the editor is reused by asset details', async () => {
    app.unmount();
    app = createApp(RuleEditor, { catalog, objects, allowExecution: false });
    editor = app.mount(host) as InstanceType<typeof RuleEditor>;
    editor.open(seed(), 0);
    await settle();
    expect(host.querySelector('input[placeholder="请输入样例数据"]')).toBeNull();
    expect([...host.querySelectorAll('button')].some((element) => ['抽查', '监测'].includes(element.textContent?.trim() || ''))).toBe(false);
    const state = Reflect.get(editor.$, 'setupState') as { spotCheck: () => Promise<void>; testSample: () => Promise<void>; };
    await state.testSample();
    await state.spotCheck();
    expect(sampleQualityTaskRule).not.toHaveBeenCalled();
    expect(spotCheckQualityTaskRule).not.toHaveBeenCalled();
  });
});
