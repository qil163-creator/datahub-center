import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import ReferencePivotEditor from './reference-pivot-editor.vue';
vi.mock('antdv-next', () => {
  const Field = defineComponent({
    props: { value: [String, Number], disabled: Boolean },
    emits: ['update:value'],
    setup:
      (props, { emit }) =>
      () =>
        h('input', {
          value: props.value,
          disabled: props.disabled,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLInputElement).value),
        }),
  });
  return {
    Tooltip: defineComponent({
      props: { title: String },
      setup:
        (props, { slots }) =>
        () =>
          h('span', { 'data-tooltip': props.title }, slots.default?.()),
    }),
    Modal: defineComponent({
      props: { open: Boolean, title: String },
      setup:
        (props, { slots }) =>
        () =>
          props.open
            ? h(
                'div',
                { role: 'dialog', 'aria-label': props.title },
                slots.default?.(),
              )
            : null,
    }),
    AutoComplete: Field,
    Input: Field,
    Select: Field,
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
    Alert: defineComponent({
      props: { message: String },
      setup: (props) => () => h('p', props.message),
    }),
    Button: defineComponent({
      props: { disabled: Boolean },
      setup:
        (props, { slots }) =>
        () =>
          h('button', { disabled: props.disabled }, slots.default?.()),
    }),
  };
});
let app: App | undefined, host: HTMLDivElement;
afterEach(() => {
  app?.unmount();
  host?.remove();
});
function mount(
  config: Record<string, any>,
  disabled = false,
  componentCode = 'column-to-row',
) {
  host = document.createElement('div');
  document.body.append(host);
  const state = reactive({ config, disabled });
  const editor = ref<{ validate: () => boolean }>();
  app = createApp({
    render: () =>
      h(ReferencePivotEditor, {
        ...state,
        ref: editor,
        componentCode,
        inputFields: [
          { name: 'group_id', dataType: 'BIGINT' },
          { name: 'code', dataType: 'STRING' },
          { name: 'amount', dataType: 'DECIMAL' },
        ],
      }),
  });
  app.mount(host);
  return { state, editor };
}
async function input(selector: string, value: string) {
  const field = host.querySelector<HTMLInputElement>(selector)!;
  expect(field).toBeTruthy();
  field.value = value;
  field.dispatchEvent(new Event('input'));
  await nextTick();
}
describe('行列V2表单与历史执行协议', () => {
  it('空态不初始化映射，信息提示和表格控件使用真实宽转长契约', async () => {
    const config = {
      mode: 'COL2ROW',
      rowColumnVersion: 2,
      unpivotMappings: [],
      vendor: 9,
    };
    const { state, editor } = mount(config, false, 'row-to-column');
    expect(state.config).toEqual(config);
    expect(host.textContent).not.toContain('执行契约');
    expect(host.textContent).not.toContain('历史执行模式');
    expect(
      host.querySelector<HTMLInputElement>('[aria-label="key字段"]')
        ?.placeholder,
    ).toBe('未设置时，key 的值与字段名相同');
    expect(
      [...host.querySelectorAll('.qi-unpivot-mapping-table th')].map((cell) =>
        cell.textContent?.trim(),
      ),
    ).toEqual(['序号', '字段名', 'Key', '输出字段', '操作']);
    expect(
      host.querySelector(
        '[data-tooltip="新增一列，用来存放每行拆分出的字段名（例如：name、age）"]',
      ),
    ).toBeTruthy();
    expect(host.querySelector('.qi-pivot-empty img')?.getAttribute('src')).toBe(
      '/assets/qdata-integration/node-empty-reference.png',
    );
    expect(editor.value!.validate()).toBe(false);
    await nextTick();
    expect(host.textContent).toContain('请填写Key字段');
  });
  it('宽转长新增删除保留顺序、未知属性和只读配置', async () => {
    const { state } = mount(
      {
        mode: 'COL2ROW',
        rowColumnVersion: 2,
        keyField: 'metric',
        unpivotMappings: [
          {
            sourceField: 'code',
            keyValue: 'first',
            targetField: 'value',
            vendor: 7,
          },
        ],
        vendor: 9,
      },
      false,
      'row-to-column',
    );
    [...host.querySelectorAll('button')]
      .find((button) => button.textContent?.trim() === '新增')!
      .click();
    await nextTick();
    expect(state.config.unpivotMappings).toEqual([
      {
        sourceField: 'code',
        keyValue: 'first',
        targetField: 'value',
        vendor: 7,
      },
      { sourceField: '', keyValue: '', targetField: '' },
    ]);
    await input(
      '.qi-unpivot-mapping-table tbody tr:nth-child(2) td:nth-child(2) input',
      'amount',
    );
    await input(
      '.qi-unpivot-mapping-table tbody tr:nth-child(2) td:nth-child(3) input',
      'second',
    );
    await input(
      '.qi-unpivot-mapping-table tbody tr:nth-child(2) td:nth-child(4) input',
      'price',
    );
    host
      .querySelector<HTMLButtonElement>(
        '.qi-unpivot-mapping-table tbody tr button',
      )!
      .click();
    await nextTick();
    expect(state.config.unpivotMappings).toEqual([
      { sourceField: 'amount', keyValue: 'second', targetField: 'price' },
    ]);
    state.disabled = true;
    await nextTick();
    const before = JSON.stringify(state.config);
    await input(
      '.qi-unpivot-mapping-table tbody td:nth-child(3) input',
      'ignored',
    );
    expect(
      [
        ...host.querySelectorAll<HTMLButtonElement>(
          '.qi-reference-button,.qi-unpivot-mapping-table .ant-btn',
        ),
      ].every((button) => button.disabled),
    ).toBe(true);
    expect(JSON.stringify(state.config)).toBe(before);
    expect(state.config.vendor).toBe(9);
  });
  it('分组字段获取使用真实冲突弹窗，取消与三种合并操作保留映射', async () => {
    const { state } = mount({
      mode: 'ROW2COL',
      rowColumnVersion: 2,
      keyField: 'code',
      groupFields: ['legacy'],
      pivotMappings: [
        {
          targetField: 'total',
          sourceField: 'amount',
          keyValue: 'sale',
          vendor: 7,
        },
      ],
      vendor: 9,
    });
    const open = async () => {
      host
        .querySelector<HTMLButtonElement>('.qi-reference-merge-button')!
        .click();
      await nextTick();
    };
    const choose = async (label: string) => {
      [...host.querySelectorAll<HTMLButtonElement>('[role="dialog"] button')]
        .find((button) => button.textContent?.trim() === label)!
        .click();
      await nextTick();
    };
    await open();
    expect(
      host.querySelector('[role="dialog"]')?.getAttribute('aria-label'),
    ).toBe('字段冲突处理');
    expect(host.querySelector('[role="dialog"]')?.textContent).toContain(
      '已有1个字段，检测到3个新字段',
    );
    await choose('取消');
    expect(state.config.groupFields).toEqual(['legacy']);
    expect(host.querySelector('[role="dialog"]')).toBeNull();
    await open();
    await choose('增加新的');
    expect(state.config.groupFields).toEqual(['legacy', 'group_id', 'amount']);
    await open();
    await choose('增加所有');
    expect(state.config.groupFields).toEqual(['group_id', 'amount']);
    state.config.groupFields = ['legacy'];
    await nextTick();
    await open();
    await choose('清除并增加所有');
    expect(state.config.groupFields).toEqual(['group_id', 'amount']);
    expect(state.config.pivotMappings).toEqual([
      {
        targetField: 'total',
        sourceField: 'amount',
        keyValue: 'sale',
        vendor: 7,
      },
    ]);
    expect(state.config.vendor).toBe(9);
  });
  it('长转宽提示与新增列继续使用现有有效类型默认值', async () => {
    const { state } = mount({
      mode: 'ROW2COL',
      rowColumnVersion: 2,
      keyField: 'code',
      groupFields: [],
      pivotMappings: [],
    });
    expect(
      host.querySelector<HTMLInputElement>('[aria-label="key字段"]')
        ?.placeholder,
    ).toBe('请输入key字段');
    expect(
      host.querySelector(
        '[data-tooltip="指定原表中的列，用于填充【目标字段】中的值（需结合关键字字段来确保数据正确映射）"]',
      ),
    ).toBeTruthy();
    expect(
      host.querySelector(
        '[data-tooltip="可填写【key字段】列中的全部数据。结合【key字段】+ 分组 +【目标字段】，锁定具体值并写入目标字段"]',
      ),
    ).toBeTruthy();
    [...host.querySelectorAll('button')]
      .filter((button) => button.textContent?.trim() === '新增')[1]!
      .click();
    await nextTick();
    expect(state.config.pivotMappings).toEqual([
      {
        targetField: '',
        sourceField: '',
        keyValue: '',
        type: 'STRING',
        format: '',
        length: '',
        precision: '',
        nullIf: '',
        aggregation: '',
      },
    ]);
    state.disabled = true;
    await nextTick();
    const before = JSON.stringify(state.config);
    host
      .querySelector<HTMLButtonElement>('.qi-reference-merge-button')!
      .click();
    await nextTick();
    expect(host.querySelector('[role="dialog"]')).toBeNull();
    expect(JSON.stringify(state.config)).toBe(before);
  });

  it('宽转长每行保存独立Key和输出列，不改写旧配置', async () => {
    const { state, editor } = mount({
      mode: 'COL2ROW',
      rowColumnVersion: 2,
      keyField: 'metric',
      unpivotMappings: [
        {
          sourceField: 'amount',
          keyValue: 'sales',
          targetField: 'price',
          vendor: 7,
        },
      ],
      valueField: 'legacy',
      vendor: 9,
    });
    await input('tbody tr td:nth-child(3) input', 'cost');
    await input('tbody tr td:nth-child(4) input', 'expense');
    expect(state.config.unpivotMappings).toEqual([
      {
        sourceField: 'amount',
        keyValue: 'cost',
        targetField: 'expense',
        vendor: 7,
      },
    ]);
    expect(state.config.valueField).toBe('legacy');
    expect(state.config.vendor).toBe(9);
    expect(editor.value!.validate()).toBe(true);
  });
  it('长转宽完整表列写入类型格式精度nullIf及聚合，清空聚合不丢映射', async () => {
    const { state, editor } = mount({
      mode: 'ROW2COL',
      rowColumnVersion: 2,
      keyField: 'code',
      groupFields: ['group_id'],
      pivotMappings: [
        {
          sourceField: 'amount',
          targetField: 'total',
          keyValue: 'sale',
          type: 'DECIMAL',
          vendor: 7,
        },
      ],
    });
    expect(
      [...host.querySelectorAll('.qi-pivot-mapping-table th')].map(
        (field) => field.textContent,
      ),
    ).toEqual([
      '序号',
      '新增字段',
      '数据字段',
      '关键字',
      '字段类型',
      '格式',
      '字段长度',
      '字段精度',
      'null If',
      '聚合方式',
      '操作',
    ]);
    await input('.qi-pivot-mapping-table td:nth-child(7) input', '20');
    await input('.qi-pivot-mapping-table td:nth-child(8) input', '4');
    await input('.qi-pivot-mapping-table td:nth-child(9) input', 'missing');
    await input('.qi-pivot-mapping-table td:nth-child(10) input', 'Sum');
    expect(state.config.pivotMappings[0]).toMatchObject({
      length: '20',
      precision: '4',
      nullIf: 'missing',
      aggregation: 'Sum',
      vendor: 7,
    });
    expect(editor.value!.validate()).toBe(true);
    await input('.qi-pivot-mapping-table td:nth-child(10) input', '');
    expect(state.config.pivotMappings[0].aggregation).toBe('');
    expect(state.config.pivotMappings[0].targetField).toBe('total');
  });
  it('历史模式只在用户显式升级后生成V2映射，且只读输入不写配置', async () => {
    const { state } = mount({
      mode: 'ROW2COL',
      keyField: 'code',
      valueField: 'amount',
      groupFields: ['group_id'],
      mappings: [{ fieldName: 'total', keyValue: 'sale' }],
      vendor: 9,
    });
    expect(state.config.rowColumnVersion).toBeUndefined();
    [...host.querySelectorAll('button')]
      .find((button) => button.textContent?.trim() === '使用完整行列配置')!
      .click();
    await nextTick();
    expect(state.config.mode).toBe('ROW2COL');
    expect(state.config.pivotMappings).toEqual([
      {
        targetField: 'total',
        sourceField: 'amount',
        keyValue: 'sale',
        aggregation: '',
      },
    ]);
    expect(state.config.vendor).toBe(9);
    state.disabled = true;
    await nextTick();
    const before = JSON.stringify(state.config);
    await input('.qi-pivot-mapping-table td:nth-child(2) input', 'ignored');
    expect(JSON.stringify(state.config)).toBe(before);
  });
});
