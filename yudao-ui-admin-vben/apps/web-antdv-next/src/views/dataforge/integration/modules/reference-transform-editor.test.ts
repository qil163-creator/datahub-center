import type { App, Component } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import ReferenceTransformEditor from './reference-transform-editor.vue';
import TransformNodeEditor from './transform-node-editor.vue';

const harness = vi.hoisted(() => ({
  confirm: undefined as (() => void) | undefined,
}));
vi.mock('#/views/dataforge/etl/pipeline/node-editors/java-script.vue', () => ({
  default: defineComponent({ setup: () => () => h('pre', 'Java编辑器') }),
}));
vi.mock('antdv-next', () => {
  const Box = defineComponent({
    setup:
      (_props, { slots }) =>
      () =>
        h('div', [slots.default?.(), slots.popupRender?.()]),
  });
  const Field = defineComponent({
    props: {
      value: [String, Number, Array],
      disabled: Boolean,
      options: { type: Array, default: undefined },
    },
    emits: ['update:value'],
    setup:
      (props, { emit }) =>
      () =>
        h('input', {
          value: props.value,
          disabled: props.disabled,
          'data-options': props.options ? JSON.stringify(props.options) : undefined,
          onInput: (event: Event) => {
            const value = (event.target as HTMLInputElement).value;
            const numericOptions = (props.options as undefined | { value?: unknown }[])
              ?.some((option) => typeof option.value === 'number');
            emit('update:value', numericOptions ? Number(value) : value);
          },
        }),
  });
  const Button = defineComponent({
    props: { disabled: Boolean },
    setup:
      (props, { slots }) =>
      () =>
        h('button', { disabled: props.disabled }, slots.default?.()),
  });
  return {
    Button,
    MenuItem: Button,
    Menu: Box,
    Dropdown: Box,
    Input: Field,
    TextArea: Field,
    InputNumber: Field,
    Select: Field,
    AutoComplete: Field,
    Checkbox: Box,
    Tooltip: Box,
    Modal: Object.assign(defineComponent({props:{open:Boolean},setup:(props,{slots}) => () => props.open ? h('div',[slots.default?.(),slots.footer?.()]) : null}), {confirm:(options:{onOk:()=>void}) => {harness.confirm=options.onOk;}}),
    Alert: defineComponent({
      props: { message: String },
      setup: (props) => () => h('p', { role: 'alert' }, props.message),
    }),
  };
});

let app: App | undefined;
let openFieldMerge: () => void;
let host: HTMLDivElement;
afterEach(() => {
  app?.unmount();
  host?.remove();
});
function mount(
  code: string,
  config: Record<string, any>,
  old = false,
  disabled = false,
) {
  host = document.createElement('div');
  document.body.append(host);
  const state = reactive({ config, disabled });
  const editor = ref<{ openFieldMerge?: () => void; validate: () => boolean; }>();
  app = createApp({
    render: () =>
      h((old ? TransformNodeEditor : ReferenceTransformEditor) as Component, {
        ref: editor,
        config: state.config,
        toolCode: code,
        disabled: state.disabled,
        inputFields: [{ name: 'A', dataType: 'BIGINT' }, { name: 'B' }],
      }),
  });
  app.mount(host);
  openFieldMerge = () => editor.value?.openFieldMerge?.();
  return { state, editor };
}
async function click(text: string) {
  const labels:Record<string,string> = {
    只增加新字段: '增加新的',
    增加所有字段: '增加所有',
    清空并增加所有字段: '清除并增加所有',
  };
  if (text in labels) {openFieldMerge(); await nextTick();}
  const target=[...host.querySelectorAll('button')].find((item) => item.textContent?.trim()===(labels[text] ?? text));
  expect(target).toBeTruthy();
  target!.click();
  await nextTick();
}

async function setInput(selector: string, value: string) {
  const field = host.querySelector<HTMLInputElement>(selector)!;
  field.value = value;
  field.dispatchEvent(new Event('input', { bubbles: true }));
  await nextTick();
}

async function setSplitType(value: 'DELIMITER' | 'REGEX') {
  const radio = host.querySelector<HTMLInputElement>(
    `input[name="qi-field-split-mode"][value="${value}"]`,
  )!;
  radio.checked = true;
  radio.dispatchEvent(new Event('change', { bubbles: true }));
  await nextTick();
}

async function setIdMode(value: 'SEQUENCE' | 'UUID') {
  const radio = host.querySelector<HTMLInputElement>(
    `input[name="qi-id-generate-mode"][value="${value}"]`,
  )!;
  radio.checked = true;
  radio.dispatchEvent(new Event('change', { bubbles: true }));
  await nextTick();
}

describe('序列与UUID生成器配置', () => {
  it('新配置保留1/1/0默认值与必填输入提示，删除底部序列说明', async () => {
    const { state, editor } = mount('id-generator', { vendor: 7 });
    expect(state.config).toEqual({
      mode: 'SEQUENCE',
      start: '1',
      step: '1',
      max: '0',
      vendor: 7,
    });
    expect(host.querySelectorAll('.qi-id-required')).toHaveLength(4);
    expect(host.querySelector('[aria-label="序列字段名称说明"]')).toBeTruthy();
    expect(
      host.querySelector('[title="会将自动生成的序列输出到这一列"]'),
    ).toBeTruthy();
    expect(
      [...host.querySelectorAll('input:not([type="radio"])')].map((field) =>
        field.getAttribute('placeholder'),
      ),
    ).toEqual([
      '请输入序列字段名称',
      '请输入起始值',
      '请输入递增幅度',
      '请输入最大值',
    ]);
    expect(host.querySelectorAll('[aria-required="true"]')).toHaveLength(4);
    expect(host.querySelector('.qi-id-sequence-help')).toBeNull();
    expect(editor.value!.validate()).toBe(false);
    await setInput('[aria-label="序列字段名称"]', 'generated_id');
    expect(editor.value!.validate()).toBe(true);
  });

  it('切换UUID隐藏序列参数且切回后保留无损Long、排序与未知配置', async () => {
    const config = {
      targetField: 'id', mode: 'SEQUENCE', start: '9007199254740993',
      step: '2', max: '9223372036854775807',
      orderBy: [{ field: 'A', direction: 'ASC' }], uniqueKeys: ['A'],
      vendor: { custom: true },
    };
    const original = structuredClone(config);
    const { state, editor } = mount('id-generator', config);
    await setIdMode('UUID');
    expect(host.querySelector('.qi-sequence-values')).toBeNull();
    expect(host.querySelector('.qi-id-sequence-help')).toBeNull();
    expect(state.config).toEqual({ ...original, mode: 'UUID' });
    expect(editor.value!.validate()).toBe(true);
    await setIdMode('SEQUENCE');
    expect(host.querySelector<HTMLInputElement>('[aria-label="起始值"]')!.value).toBe('9007199254740993');
    expect(state.config).toEqual(original);
    expect(editor.value!.validate()).toBe(true);
  });

  it('只读字段、模式与加减事件不能补写默认或修改草稿', async () => {
    const config = { targetField: 'id', start: '9007199254740993', vendor: 9 };
    const snapshot = JSON.stringify(config);
    const { state } = mount('id-generator', config, false, true);
    expect(JSON.stringify(state.config)).toBe(snapshot);
    await setInput('[aria-label="序列字段名称"]', 'changed');
    await setInput('[aria-label="起始值"]', '1');
    await setIdMode('UUID');
    host.querySelector<HTMLButtonElement>('[aria-label="增加起始值"]')!.click();
    await nextTick();
    expect(JSON.stringify(state.config)).toBe(snapshot);
  });
});

describe('字段拆分配置', () => {
  it('新增行默认String，字段提示、类型及去空格选项符合参考配置', async () => {
    const { state } = mount('field-split', { vendor: 7 });
    expect(state.config.splitType).toBe('DELIMITER');
    expect(state.config.outputs).toEqual([]);
    await click('新增');
    expect(state.config.outputs).toEqual([
      { name: '', type: 'String' },
    ]);
    expect(
      [...host.querySelectorAll('tbody input')].map((field) =>
        field.getAttribute('placeholder'),
      ),
    ).toEqual([
      '请输入字段',
      null,
      '请输入字段长度',
      '请输入字段精度',
      '请输入格式',
      '请选择',
    ]);
    const options = (label: string) =>
      JSON.parse(
        host.querySelector<HTMLInputElement>(`[aria-label="${label}"]`)!.dataset.options!,
      );
    expect(options('字段类型 1').map((option: { value: string }) => option.value)).toEqual([
      'BigNumber', 'Binary', 'Boolean', 'Date', 'Integer', 'InternetAddress',
      'Number', 'String', 'Timestamp',
    ]);
    expect(options('去除空格类型 1')).toEqual([
      { value: 'LEFT', label: '去除左空格' },
      { value: 'RIGHT', label: '去除右空格' },
      { value: 'BOTH', label: '去除两边空格' },
      { value: 'NONE', label: '不去空格' },
    ]);
    expect(host.querySelector('[aria-label="需要拆分的字段"]')!.getAttribute('placeholder')).toBe('请输入拆分的字段');
    expect(state.config.vendor).toBe(7);
  });

  it('历史行缺少type只回显String，调整顺序完整保留元数据及未知属性', async () => {
    const first = { name: 'first', length: '32', vendor: { rule: 1 } };
    const second = { name: 'second', type: 'Date', format: 'yyyy-MM-dd', vendor: 2 };
    const { state, editor } = mount('field-split', {
      sourceField: 'A', splitType: 'DELIMITER', delimiter: ',',
      outputs: [first, second], keepSource: true, vendor: 'legacy',
    });
    expect(host.querySelector<HTMLInputElement>('[aria-label="字段类型 1"]')!.value).toBe('String');
    expect(state.config.outputs[0]).not.toHaveProperty('type');
    expect(editor.value!.validate()).toBe(true);
    host.querySelector('tbody tr')!.dispatchEvent(new KeyboardEvent('keydown', {
      key: 'ArrowDown', altKey: true, bubbles: true,
    }));
    await nextTick();
    expect(state.config.outputs).toEqual([second, first]);
    expect(state.config.outputs[1]).not.toHaveProperty('type');
    expect(state.config.keepSource).toBe(true);
    expect(host.textContent).not.toContain('原字段处理');
    expect(state.config.vendor).toBe('legacy');
  });

  it('切换分隔符与正则模式保留双方输入和包裹字符', async () => {
    const { state, editor } = mount('field-split', {
      sourceField: 'A', splitType: 'DELIMITER', delimiter: ',', pattern: '[,;]',
      wrapper: '"', outputs: [{ name: 'part', vendor: 5 }], vendor: 7,
    });
    await setInput('[aria-label="分隔符"]', '|');
    await setSplitType('REGEX');
    expect(host.querySelector<HTMLInputElement>('[aria-label="正则表达式"]')!.value).toBe('[,;]');
    expect(host.querySelector('[aria-label="正则表达式"]')!.getAttribute('placeholder')).toBe('请输入正则表达式');
    const pattern = String.raw`\s+`;
    await setInput('[aria-label="正则表达式"]', pattern);
    await setSplitType('DELIMITER');
    expect(host.querySelector<HTMLInputElement>('[aria-label="分隔符"]')!.value).toBe('|');
    expect(state.config).toEqual({
      sourceField: 'A', splitType: 'DELIMITER', delimiter: '|', pattern,
      wrapper: '"', outputs: [{ name: 'part', vendor: 5 }], vendor: 7,
    });
    expect(editor.value!.validate()).toBe(true);
  });

  it('只读首次打开不补默认值，字段、模式、增删及顺序事件均不能写入草稿', async () => {
    const config = {
      sourceField: 'A', delimiter: ',', pattern: ';', wrapper: '"',
      outputs: [{ name: 'one', vendor: 1 }, { name: 'two', vendor: 2 }],
      keepSource: true, vendor: 7,
    };
    const snapshot = JSON.stringify(config);
    const { state } = mount('field-split', config, false, true);
    expect(JSON.stringify(state.config)).toBe(snapshot);
    expect(host.querySelector<HTMLInputElement>('[aria-label="字段类型 1"]')!.value).toBe('String');
    await setInput('[aria-label="需要拆分的字段"]', 'B');
    await setInput('[aria-label="字段类型 1"]', 'Integer');
    await setInput('[aria-label="分隔符"]', '|');
    await setSplitType('REGEX');
    await click('新增');
    await click('删除');
    host.querySelector('tbody tr')!.dispatchEvent(new KeyboardEvent('keydown', {
      key: 'ArrowDown', altKey: true, bubbles: true,
    }));
    await nextTick();
    expect(JSON.stringify(state.config)).toBe(snapshot);
  });
});

describe('计算器与常量及值映射参考配置', () => {
  it('计算类型按参数个数显示来源，隐藏单元格不清空历史字段或更改值类型', async () => {
    const { state } = mount('calculator', {
      rules: [{ targetField: 'result', calcType: 'ADD3', fieldA: 'A', fieldB: 'B', fieldC: 'C', valueType: 'DOUBLE', vendor: 7 }],
    });
    const source = (label: string) => host.querySelector<HTMLInputElement>(`[aria-label="${label} 1"]`)!;
    expect(source('字段A').style.display).not.toBe('none');
    expect(source('字段B').style.display).not.toBe('none');
    expect(source('字段C').style.display).not.toBe('none');
    expect(host.querySelector('.qi-calculator-type-value')!.textContent).toBe('number');
    await setInput('[aria-label="计算类型 1"]', 'ABS');
    expect(source('字段A').style.display).not.toBe('none');
    expect(source('字段B').style.display).toBe('none');
    expect(source('字段C').style.display).toBe('none');
    expect(source('字段B').disabled).toBe(true);
    expect(state.config.rules[0]).toMatchObject({ fieldB: 'B', fieldC: 'C', valueType: 'DOUBLE', vendor: 7 });
    await setInput('[aria-label="计算类型 1"]', 'UPPER');
    expect(state.config.rules[0].valueType).toBe('DOUBLE');
    expect(host.querySelector('.qi-calculator-type-value')!.textContent).toBe('number');
    await setInput('[aria-label="值类型 1"]', 'STRING');
    expect(host.querySelector('.qi-calculator-type-value')!.textContent).toBe('string');
    await setInput('[aria-label="计算类型 1"]', 'ADD3');
    expect(source('字段B').value).toBe('B');
    expect(source('字段C').value).toBe('C');
  });

  it('未选择计算类型时来源与值类型为空白，24项计算选项及下拉提示保留', () => {
    mount('calculator', { rules: [{ targetField: 'result', valueType: 'STRING' }] });
    for (const field of ['字段A', '字段B', '字段C']) {
      const input = host.querySelector<HTMLInputElement>(`[aria-label="${field} 1"]`)!;
      expect(input.style.display).toBe('none');
      expect(input.disabled).toBe(true);
      expect(input.placeholder).toBe('请选择...');
    }
    expect(host.querySelector<HTMLElement>('.qi-calculator-type')!.style.display).toBe('none');
    const calculation = host.querySelector<HTMLInputElement>('[aria-label="计算类型 1"]')!;
    expect(calculation.placeholder).toBe('请选择计算类型');
    const options = JSON.parse(calculation.dataset.options!);
    expect(options).toHaveLength(24);
    expect(options).toEqual(expect.arrayContaining([
      { value: 'ADD3', label: 'A + B + C', arity: 3 },
      { value: 'UPPER', label: 'Upper case(A)', arity: 1 },
    ]));
  });

  it('常量默认String与否，设为空串保留默认值并支持只读保护', async () => {
    const { state } = mount('add-constant', { vendor: 7 });
    await click('新增');
    expect(state.config.fields).toEqual([{ name: '', fieldType: 'String', value: '', setEmpty: false }]);
    const fieldType = host.querySelector<HTMLInputElement>('[aria-label="字段类型 1"]')!;
    expect(JSON.parse(fieldType.dataset.options!).map((option: { value: string }) => option.value)).toEqual([
      'String', 'Integer', 'Long', 'Double', 'Boolean', 'Date',
    ]);
    expect(host.querySelector('[aria-label="设为空串说明"]')).toBeTruthy();
    expect(host.querySelector<HTMLInputElement>('[aria-label="字段名称 1"]')!.placeholder).toBe('请输入');
    expect(host.querySelector<HTMLInputElement>('[aria-label="默认值 1"]')!.placeholder).toBe('请输入');
    await setInput('[aria-label="字段名称 1"]', 'constant');
    await setInput('[aria-label="默认值 1"]', 'retained');
    await setInput('[aria-label="设为空串 1"]', '1');
    expect(state.config.fields[0]).toMatchObject({ value: 'retained', setEmpty: true });
    expect(host.querySelector<HTMLInputElement>('[aria-label="默认值 1"]')!.disabled).toBe(true);
    await setInput('[aria-label="设为空串 1"]', '0');
    expect(host.querySelector<HTMLInputElement>('[aria-label="默认值 1"]')!.disabled).toBe(false);
    expect(state.config.fields[0].value).toBe('retained');
    state.disabled = true;
    await nextTick();
    await setInput('[aria-label="设为空串 1"]', '1');
    expect(state.config.fields[0].setEmpty).toBe(false);
    expect(state.config.vendor).toBe(7);
  });

  it('值映射提示与缺省值切换保留目标留空覆盖、未知属性及原有未匹配策略', async () => {
    const { state, editor } = mount('value-map', {
      sourceField: 'A', mappings: [{ from: 'old', to: 'new', vendor: 1 }], vendor: 7,
    });
    expect(host.querySelector<HTMLInputElement>('[aria-label="使用字段"]')!.placeholder).toBe('请选择字段名称');
    expect(host.querySelector<HTMLInputElement>('[aria-label="目标字段"]')!.placeholder).toBe('请输入目标字段');
    expect(host.querySelector<HTMLInputElement>('[aria-label="原值 1"]')!.placeholder).toBe('请输入原值');
    expect(host.querySelector<HTMLInputElement>('[aria-label="目标值 1"]')!.placeholder).toBe('请输入目标值');
    expect(host.querySelector('[aria-label="不匹配时的默认值说明"]')).toBeTruthy();
    expect(host.textContent).not.toContain('未匹配处理方式');
    expect(editor.value!.validate()).toBe(true);
    await setInput('[aria-label="不匹配时的默认值"]', 'fallback');
    expect(state.config.unmatched).toBe('DEFAULT');
    await setInput('[aria-label="不匹配时的默认值"]', '');
    expect(state.config.unmatched).toBe('KEEP');
    expect(state.config.targetField).toBeUndefined();
    expect(state.config.mappings).toEqual([{ from: 'old', to: 'new', vendor: 1 }]);
    expect(state.config.vendor).toBe(7);
    state.config.unmatched = 'SET_NULL';
    await nextTick();
    expect(host.querySelector<HTMLInputElement>('[aria-label="不匹配时的默认值"]')!.disabled).toBe(true);
    state.disabled = true;
    await nextTick();
    await setInput('[aria-label="目标字段"]', 'ignored');
    expect(state.config.targetField).toBeUndefined();
  });
});

describe('转换辅助操作', () => {
  it('序列步进保留Long整数精度并拒绝超界和只读修改', async () => {
    const {state}=mount('id-generator',{mode:'SEQUENCE',targetField:'id',start:'9007199254740993',step:'1',max:'9223372036854775807'});
    host.querySelector<HTMLButtonElement>('[aria-label="增加起始值"]')!.click(); await nextTick();
    expect(state.config.start).toBe('9007199254740994');
    host.querySelector<HTMLButtonElement>('[aria-label="增加最大值"]')!.click(); await nextTick();
    expect(state.config.max).toBe('9223372036854775807');
    state.disabled=true; await nextTick(); host.querySelector<HTMLButtonElement>('[aria-label="减少起始值"]')!.click(); await nextTick();
    expect(state.config.start).toBe('9007199254740994');
  });

  it('旧字符串组件获取新字段保留规则未知属性，支持顺序变化和只读保护', async () => {
    const { state } = mount(
      'string-operation',
      { rules: [{ inputField: 'A', outputField: 'OUT', custom: 42 }] },
      true,
    );
    await click('只增加新字段');
    expect(state.config.rules[0]).toEqual({
      inputField: 'A',
      outputField: 'OUT',
      custom: 42,
    });
    expect(state.config.rules[1].inputField).toBe('B');
    host.querySelector('tbody tr')!.dispatchEvent(
      new KeyboardEvent('keydown', {
        key: 'ArrowDown',
        altKey: true,
        bubbles: true,
      }),
    );
    await nextTick();
    expect(state.config.rules[0].inputField).toBe('B');
    state.disabled = true;
    await nextTick();
    host.querySelector('tbody tr')!.dispatchEvent(
      new KeyboardEvent('keydown', {
        key: 'ArrowDown',
        altKey: true,
        bubbles: true,
      }),
    );
    expect(state.config.rules[0].inputField).toBe('B');
  });

  it('空行和重复目标阻止继续新增，而不是堆积无法保存的空配置', async () => {
    const { state } = mount('sort-records', {
      sorts: [{ field: '', order: 'ASC' }],
    });
    await click('新增');
    expect(state.config.sorts).toHaveLength(1);
    expect(host.textContent).toContain('字段或原值不能为空');
    state.config.sorts = [
      { field: 'A', order: 'ASC' },
      { field: 'A', order: 'DESC' },
    ];
    await click('新增');
    expect(state.config.sorts).toHaveLength(2);
    expect(host.textContent).toContain('存在重复字段');
  });

  it('删除确认弹出后变为只读不能落入草稿', async () => {
    const { state } = mount('sort-records', {
      sorts: [
        { field: 'A', order: 'ASC' },
        { field: 'B', order: 'DESC' },
      ],
    });
    await click('删除');
    state.disabled = true;
    await nextTick();
    harness.confirm!();
    expect(state.config.sorts).toHaveLength(2);
  });

  it('java输出可不声明；获取上游只是声明提示，保留未知字段类型', async () => {
    const { state, editor } = mount('java-script', {
      code: 'existing code',
      vendor: true,
    });
    expect(editor.value?.validate()).toBe(true);
    await click('增加所有字段');
    expect(
      state.config.outputFields.map(
        (field: { dataType?: string; name: string }) => ({
          name: field.name,
          dataType: field.dataType,
        }),
      ),
    ).toEqual([
      { name: 'A', dataType: 'BIGINT' },
      { name: 'B', dataType: undefined },
    ]);
    expect(host.textContent).toContain('声明始终视为不完整');
    expect(state.config.vendor).toBe(true);
  });

  it('只读首次打开不初始化或覆写任何默认配置', () => {
    const { state } = mount('id-generator', { vendor: 42 }, false, true);
    expect(state.config).toEqual({ vendor: 42 });
  });

  it('计算器获取新增字段保留旧规则，按操作参数启用单元格并立即反馈未填写的计算类型', async () => {
    const { state, editor } = mount('calculator', {
      rules: [
        {
          targetField: 'A',
          calcType: 'ABS',
          fieldA: 'A',
          valueType: 'DOUBLE',
          vendor: 7,
        },
      ],
    });
    const cells = host.querySelector('tbody tr')!.querySelectorAll('td');
    expect(cells[3]!.querySelector('input')!.disabled).toBe(false);
    expect(cells[4]!.querySelector('input')!.disabled).toBe(true);
    expect(cells[5]!.querySelector('input')!.disabled).toBe(true);
    await click('只增加新字段');
    expect(state.config.rules[0].vendor).toBe(7);
    expect(state.config.rules[1]).toMatchObject({
      targetField: 'B',
      fieldA: 'B',
      valueType: 'STRING',
    });
    expect(editor.value!.validate()).toBe(false);
    await nextTick();
    expect(host.textContent).toContain('请选择计算类型');
    state.config.rules[1].calcType = 'UPPER';
    await nextTick();
    expect(editor.value!.validate()).toBe(true);
    host.querySelector('tbody tr')!.dispatchEvent(
      new KeyboardEvent('keydown', {
        key: 'ArrowDown',
        altKey: true,
        bubbles: true,
      }),
    );
    await nextTick();
    expect(state.config.rules[0].targetField).toBe('B');
    expect(state.config.rules[1].vendor).toBe(7);
  });

  it('字段派生删除元数据面板后仍支持来源字段获取与拖动，单来源操作不隐式丢弃字段', async () => {
    const { state, editor } = mount('field-derive', {
      operateType: 'CONCAT',
      targetField: 'new',
      sourceFields: ['A'],
      vendor: 7,
    });
    await click('只增加新字段');
    expect(state.config.sourceFields).toEqual(['A', 'B']);
    expect(host.querySelector('details table')).toBeNull();
    expect(host.textContent).not.toContain('来源字段元数据');
    expect(
      host.querySelector('tbody tr')!.querySelectorAll('input'),
    ).toHaveLength(1);
    expect(editor.value!.validate()).toBe(true);
    host.querySelector('tbody tr')!.dispatchEvent(
      new KeyboardEvent('keydown', {
        key: 'ArrowDown',
        altKey: true,
        bubbles: true,
      }),
    );
    await nextTick();
    expect(state.config.sourceFields).toEqual(['B', 'A']);
    await click('增加所有字段');
    expect(state.config.sourceFields).toEqual(['A', 'B']);
    state.config.operateType = 'SUBSTRING';
    await nextTick();
    await click('清空并增加所有字段');
    expect(state.config.sourceFields).toEqual(['A', 'B']);
    expect(host.textContent).toContain('当前操作只能选择一个来源字段');
    state.config.operateType = 'CONSTANT';
    state.config.constantValue = '';
    await nextTick();
    expect(editor.value!.validate()).toBe(true);
    expect(host.querySelector('table')).toBeNull();
    expect(state.config.vendor).toBe(7);
    state.disabled = true;
    await nextTick();
    const targetInput = host.querySelectorAll('input')[1]!;
    targetInput.value = 'changed';
    targetInput.dispatchEvent(new Event('input', { bubbles: true }));
    expect(state.config.targetField).toBe('new');
  });

  it('历史计算器重复结果字段仅在整份规则未变时允许保存，不重排或去重，新重复仍拦截', async () => {
    const original = [
      {
        targetField: 'A',
        calcType: 'ABS',
        fieldA: 'A',
        valueType: 'DOUBLE',
        vendor: 1,
      },
      {
        targetField: 'A',
        calcType: 'ADD',
        fieldA: 'A',
        fieldB: 'B',
        valueType: 'DOUBLE',
        vendor: 2,
      },
    ];
    const snapshot = JSON.stringify(original);
    const { state, editor } = mount('calculator', { rules: original });
    expect(editor.value!.validate()).toBe(true);
    expect(host.textContent).toContain('历史规则存在重复');
    expect(JSON.stringify(state.config.rules)).toBe(snapshot);
    await click('新增');
    expect(state.config.rules).toHaveLength(2);
    expect(host.textContent).toContain('存在重复字段或原值');
    const secondSource = host.querySelector<HTMLInputElement>(
      'tbody tr:nth-child(2) td:nth-child(5) input',
    )!;
    secondSource.value = 'C';
    secondSource.dispatchEvent(new Event('input', { bubbles: true }));
    await nextTick();
    expect(editor.value!.validate()).toBe(false);
    state.config.rules[1].fieldB = 'B';
    await nextTick();
    expect(editor.value!.validate()).toBe(true);
    state.disabled = true;
    await nextTick();
    state.disabled = false;
    await nextTick();
    expect(editor.value!.validate()).toBe(true);
    state.config = {
      rules: original.map((rule, index) => ({
        ...rule,
        targetField: index === 0 ? 'A' : 'B',
      })),
    };
    await nextTick();
    state.config.rules[1].targetField = 'A';
    expect(editor.value!.validate()).toBe(false);
  });

  it('历史值映射重复原值保留最后覆盖契约，任何规则改动均不豁免重复错误', async () => {
    const { state, editor } = mount('value-map', {
      sourceField: 'A',
      mappings: [
        { from: 'x', to: 'first' },
        { from: 'x', to: 'last' },
      ],
    });
    expect(editor.value!.validate()).toBe(true);
    expect(host.textContent).toContain('历史规则存在重复');
    const target = host.querySelector<HTMLInputElement>(
      'tbody tr:nth-child(2) td:nth-child(3) input',
    )!;
    target.value = 'changed';
    target.dispatchEvent(new Event('input', { bubbles: true }));
    await nextTick();
    expect(editor.value!.validate()).toBe(false);
    expect(state.config.mappings).toEqual([
      { from: 'x', to: 'first' },
      { from: 'x', to: 'changed' },
    ]);
    state.config.mappings[1].to = 'last';
    await nextTick();
    expect(editor.value!.validate()).toBe(true);
    state.config = {
      sourceField: 'A',
      mappings: [
        { from: 'x', to: 'first' },
        { from: 'y', to: 'second' },
      ],
    };
    await nextTick();
    state.config.mappings[1].from = 'x';
    expect(editor.value!.validate()).toBe(false);
  });
  it('字符串替换三种来源互斥，字段值替换保留选择并阻止只读写入', async () => {
    const { state } = mount('string-replace', { rules: [{ inputField: 'A', outputField: 'O', search: 'find', replacement: 'literal', emptyReplacement: false, replacementField: '', regex: false, wholeWord: true, caseSensitive: true, vendor: 7 }] }, true);
    const field = host.querySelector<HTMLInputElement>('tbody tr td:nth-child(8) input')!;
    field.value = 'B'; field.dispatchEvent(new Event('input')); await nextTick();
    expect(state.config.rules[0]).toMatchObject({ replacementField: 'B', replacement: '', emptyReplacement: false, vendor: 7 });
    const literal = host.querySelector<HTMLInputElement>('tbody tr td:nth-child(6) input')!;
    literal.value = 'changed'; literal.dispatchEvent(new Event('input')); await nextTick();
    expect(state.config.rules[0]).toMatchObject({ replacementField: '', replacement: 'changed' });
    state.disabled = true; await nextTick();
    field.value = 'ignored'; field.dispatchEvent(new Event('input')); await nextTick();
    expect(state.config.rules[0].replacementField).toBe('');
  });
});
