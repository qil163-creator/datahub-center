import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import SubflowBoundary from './subflow-boundary.vue';

const harness = vi.hoisted(() => ({ numberValue: undefined as unknown }));
vi.mock('antdv-next', () => ({
  Alert: defineComponent({
    props: ['message'],
    setup: (props) => () => h('p', props.message),
  }),
  Button: defineComponent({
    setup:
      (_, { attrs, slots }) =>
      () =>
        h('button', attrs, slots.default?.()),
  }),
  Input: defineComponent({
    props: ['value'],
    emits: ['update:value'],
    setup:
      (props, { attrs, emit }) =>
      () =>
        h('input', {
          ...attrs,
          value: props.value,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLInputElement).value),
        }),
  }),
  InputNumber: defineComponent({
    props: ['value'],
    emits: ['update:value'],
    setup:
      (props, { attrs, emit }) =>
      () =>
        h(
          'button',
          {
            ...attrs,
            'data-value': props.value,
            onClick: () => emit('update:value', harness.numberValue),
          },
          '数值输入',
        ),
  }),
  Select: defineComponent({
    props: ['value', 'options'],
    emits: ['update:value'],
    setup:
      (props, { attrs, emit }) =>
      () =>
        h(
          'select',
          {
            ...attrs,
            value: props.value,
            onChange: (event: Event) =>
              emit('update:value', (event.target as HTMLSelectElement).value),
          },
          props.options.map((option: { label: string; value: string }) =>
            h('option', { value: option.value }, option.label),
          ),
        ),
  }),
}));
let app: App | undefined;
let host: HTMLDivElement;
function mount(config: Record<string, any>) {
  const value = reactive(config);
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(SubflowBoundary, { config: value });
  app.mount(host);
  return value;
}
function button(text: string) {
  return [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === text,
  )!;
}
async function select(label: string, value: string) {
  const field = host.querySelector<HTMLSelectElement>(
    `select[aria-label="${label}"]`,
  )!;
  field.value = value;
  field.dispatchEvent(new Event('change'));
  await nextTick();
}
async function number(label: string, value: unknown) {
  harness.numberValue = value;
  host
    .querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`)!
    .click();
  await nextTick();
}
afterEach(() => {
  app?.unmount();
  host?.remove();
});
describe('子流程边界字段约束', () => {
  it('新增字段保持未知可空性，不自动填true/false或精度', async () => {
    const config = mount({});
    await nextTick();
    expect(config.fields).toEqual([{ name: '', type: 'Object' }]);
    button('添加字段').click();
    await nextTick();
    expect(config.fields).toEqual([
      { name: '', type: 'Object' },
      { name: '', type: 'Object' },
    ]);
    expect(host.textContent).toContain('可空性 未知');
    expect(host.textContent).toContain('允许空值，只有显式');
  });
  it('旧null属性原样加载，可空性三态仅由明确选择修改', async () => {
    const original = {
      name: 'amount',
      type: 'Decimal',
      nullable: null,
      precision: null,
      scale: null,
      timezone: null,
    };
    const config = mount({ fields: [{ ...original }] });
    await nextTick();
    expect(config.fields[0]).toEqual(original);
    expect(
      host.querySelector<HTMLSelectElement>('select[aria-label="可空性"]')!
        .value,
    ).toBe('UNKNOWN');
    await select('可空性', 'NOT_NULL');
    expect(config.fields[0].nullable).toBe(false);
    await select('可空性', 'NULLABLE');
    expect(config.fields[0].nullable).toBe(true);
    await select('可空性', 'UNKNOWN');
    expect(config.fields[0].nullable).toBeNull();
  });
  it('精度和scale独立编辑，允许负scale和scale大于精度，清空不发字符串', async () => {
    const config = mount({ fields: [{ name: 'amount', type: 'Decimal' }] });
    await nextTick();
    await number('Decimalscale', -2);
    expect(config.fields[0]).toEqual({
      name: 'amount',
      type: 'Decimal',
      scale: -2,
    });
    await number('Decimal精度', 1);
    await number('Decimalscale', 3);
    expect(config.fields[0]).toMatchObject({ precision: 1, scale: 3 });
    await number('Decimal精度', '25');
    await number('Decimal精度', 1001);
    await number('Decimal精度', 1.5);
    expect(config.fields[0].precision).toBe(1);
    await number('Decimal精度', undefined);
    await number('Decimalscale', '');
    expect(config.fields[0]).toMatchObject({ precision: null, scale: null });
  });
  it('切换到非Decimal清除不适用数值约束，但不改变可空性', async () => {
    const config = mount({
      fields: [
        {
          name: 'amount',
          type: 'Decimal',
          nullable: false,
          precision: 20,
          scale: -2,
        },
      ],
    });
    await nextTick();
    await select('字段类型', 'String');
    expect(config.fields[0]).toEqual({
      name: 'amount',
      type: 'String',
      nullable: false,
      precision: null,
      scale: null,
    });
    expect(host.querySelector('[aria-label="Decimal精度"]')).toBeNull();
  });
  it('历史时区声明明确报不支持，只有主动清除才去掉', async () => {
    const config = mount({
      fields: [{ name: 'time', type: 'Date', timezone: 'Asia/Shanghai' }],
    });
    await nextTick();
    expect(config.fields[0].timezone).toBe('Asia/Shanghai');
    expect(host.textContent).toContain('当前边界含时区声明，服务端暂不支持');
    button('清除不支持的时区声明').click();
    await nextTick();
    expect(config.fields[0].timezone).toBeNull();
    expect(host.textContent).toContain('时区 未知');
  });
});
