import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import TransformNodeEditor from './transform-node-editor.vue';

vi.mock('./reference-field-merge-dialog.vue', () => ({
  default: { render: () => null },
}));
vi.mock('antdv-next', () => {
  const Field = defineComponent({
    props: {
      value: [String, Number, Array],
      disabled: Boolean,
      options: Array,
    },
    emits: ['update:value'],
    setup: (props, { emit }) => () => h('input', {
      value: props.value,
      disabled: props.disabled,
      'data-options': JSON.stringify(props.options),
      onInput: (event: Event) => {
        const value = (event.target as HTMLInputElement).value;
        const numeric = (props.options as undefined | { value: unknown }[])
          ?.some((option) => typeof option.value === 'number');
        emit('update:value', numeric ? Number(value) : value);
      },
    }),
  });
  return {
    AutoComplete: Field,
    Input: Field,
    InputNumber: Field,
    Select: Field,
    TextArea: Field,
    Alert: { render: () => null },
    Checkbox: { render: () => null },
    Tooltip: defineComponent({
      setup: (_props, { slots }) => () => h('span', slots.default?.()),
    }),
    Modal: { confirm: vi.fn() },
    Button: defineComponent({
      props: { disabled: Boolean },
      setup: (props, { slots }) => () => h('button', { disabled: props.disabled }, slots.default?.()),
    }),
  };
});

let app: App | undefined;
let host: HTMLDivElement;
afterEach(() => { app?.unmount(); host?.remove(); });
function mount(toolCode: string, config: Record<string, any>, disabled = false) {
  host = document.createElement('div');
  document.body.append(host);
  const state = reactive({ config, disabled });
  app = createApp({
    render: () => h(TransformNodeEditor, {
      ...state,
      toolCode,
      inputFields: [{ name: 'name' }, { name: 'city' }],
    }),
  });
  app.mount(host);
  return state;
}
async function input(label: string, value: string) {
  const field = host.querySelector<HTMLInputElement>(`[aria-label="${label}"]`)!;
  field.value = value;
  field.dispatchEvent(new Event('input'));
  await nextTick();
}
function choices(label: string) {
  return JSON.parse(host.querySelector<HTMLInputElement>(`[aria-label="${label}"]`)!.dataset.options!);
}

describe('字符串和字段赋值配置', () => {
  it('去空格、大小写和填充下拉沿用原枚举，填充长度及未知参数在修改时保留', async () => {
    const state = mount('string-operation', {
      vendor: 7,
      rules: [{ inputField: 'name', outputField: '', trim: 'NONE', caseMode: 'NONE', padDirection: 'NONE', padCharacter: '*', padLength: 12, custom: 9 }],
    });
    expect(choices('第1行去空格方式').map((option: { value: string }) => option.value)).toEqual(['NONE', 'LEFT', 'RIGHT', 'BOTH']);
    expect(choices('第1行大小写转换').map((option: { value: string }) => option.value)).toEqual(['NONE', 'LOWER', 'UPPER']);
    expect(choices('第1行填充方向').map((option: { value: string }) => option.value)).toEqual(['NONE', 'LEFT', 'RIGHT', 'BOTH']);
    expect(host.querySelector<HTMLInputElement>('[aria-label="第1行填充字符"]')!.disabled).toBe(true);
    await input('第1行填充方向', 'LEFT');
    expect(host.querySelector<HTMLInputElement>('[aria-label="第1行填充字符"]')!.disabled).toBe(false);
    await input('第1行去空格方式', 'BOTH');
    expect(state.config.rules[0]).toMatchObject({ trim: 'BOTH', padDirection: 'LEFT', padCharacter: '*', padLength: 12, custom: 9 });
    expect(state.config.vendor).toBe(7);
    expect(host.textContent).not.toContain('填充长度配置');
  });

  it('新增替换规则默认不区分大小写，使用字段值替换不能选择自身输入字段', async () => {
    const state = mount('string-replace', { rules: [] });
    expect(state.config.rules).toEqual([]);
    state.config.rules = [{ inputField: 'name', search: '', replacement: '', regex: false, wholeWord: false, caseSensitive: false, emptyReplacement: false, replacementField: '' }];
    await nextTick();
    expect(choices('第1行使用字段值替换')).toEqual([
      { value: 'name', label: 'name', disabled: true },
      { value: 'city', label: 'city', disabled: false },
    ]);
    expect(host.querySelector<HTMLInputElement>('[aria-label="第1行大小写敏感"]')!.value).toBe('0');
  });

  it('替换选项下拉只保存boolean，并保持三种替换来源互斥及正则切换契约', async () => {
    const state = mount('string-replace', {
      rules: [{ inputField: 'name', search: 'x', replacement: 'literal', replacementField: '', emptyReplacement: false, regex: false, wholeWord: true, caseSensitive: true, custom: 8 }],
    });
    await input('第1行使用正则表达式', '1');
    expect(state.config.rules[0]).toMatchObject({ regex: true, wholeWord: false, caseSensitive: false, custom: 8 });
    expect(host.querySelector<HTMLInputElement>('[aria-label="第1行整个单词匹配"]')!.disabled).toBe(true);
    expect(host.querySelector<HTMLInputElement>('[aria-label="第1行大小写敏感"]')!.disabled).toBe(true);
    await input('第1行使用正则表达式', '0');
    expect(host.querySelector<HTMLInputElement>('[aria-label="第1行整个单词匹配"]')!.disabled).toBe(false);
    await input('第1行整个单词匹配', '1');
    await input('第1行大小写敏感', '1');
    expect(state.config.rules[0]).toMatchObject({ wholeWord: true, caseSensitive: true });
    await input('第1行设置为空串', '1');
    expect(state.config.rules[0]).toMatchObject({ replacement: '', replacementField: '', emptyReplacement: true });
    expect(host.querySelector<HTMLInputElement>('[aria-label="第1行替换成的内容"]')!.disabled).toBe(true);
    await input('第1行使用字段值替换', 'city');
    expect(state.config.rules[0]).toMatchObject({ replacement: '', replacementField: 'city', emptyReplacement: false });
    await input('第1行替换成的内容', 'new');
    expect(state.config.rules[0]).toMatchObject({ replacement: 'new', replacementField: '', emptyReplacement: false });
  });

  it('字符串规则可按键盘调整顺序，只读时下拉、内容和顺序均不能回写', async () => {
    const state = mount('string-replace', {
      rules: [{ inputField: 'name', search: 'x', custom: 1 }, { inputField: 'city', search: 'y', custom: 2 }],
    });
    const first = host.querySelector('tbody tr')!;
    first.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', altKey: true, bubbles: true }));
    await nextTick();
    expect(state.config.rules.map((rule: { custom: number }) => rule.custom)).toEqual([2, 1]);
    state.disabled = true;
    await nextTick();
    const saved = JSON.stringify(state.config);
    await input('第1行使用正则表达式', '1');
    await input('第1行搜索', 'changed');
    host.querySelector('tbody tr')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', altKey: true, bubbles: true }));
    await nextTick();
    expect(JSON.stringify(state.config)).toBe(saved);
  });

  it('字段赋值的两个下拉分别保存目标与来源，未知参数不丢失，空表显示空态', async () => {
    const state = mount('set-field-value', { vendor: 7, rules: [{ targetField: 'name', sourceField: 'city', custom: 9 }] });
    expect(choices('第1行字段名称')).toEqual([{ value: 'name', label: 'name' }, { value: 'city', label: 'city' }]);
    await input('第1行替换字段名称', 'name');
    await input('第1行字段名称', 'city');
    expect(state.config.rules[0]).toMatchObject({ targetField: 'city', sourceField: 'name', custom: 9 });
    expect(state.config.vendor).toBe(7);
    state.config.rules = [];
    await nextTick();
    expect(host.querySelector('.qi-reference-empty')!.textContent).toBe('暂无记录');
    expect(host.querySelector('.qi-reference-empty')!.getAttribute('colspan')).toBe('4');
  });
});
