/* eslint-disable vue/one-component-per-file, vue/require-default-prop -- 测试替身只模拟草稿交互。 */
import type { App } from 'vue';

import {
  createApp,
  defineComponent,
  h,
  nextTick,
  reactive,
  shallowRef,
} from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import { createRelationReadConfig } from './relation-input-config';
import {
  createTableInputBatchRead,
  validateTableInputConfig,
  validateTableInputTimeCondition,
} from './table-input-config';
import Editor from './table-input-read-options.vue';

vi.mock('#/components/monaco/sql-editor.vue', () => ({
  default: defineComponent({ props: { modelValue: String, readonly: Boolean }, emits: ['update:modelValue'], setup: (props, { emit }) => () => h('textarea', { value: props.modelValue, disabled: props.readonly, onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLTextAreaElement).value) }) }),
}));

vi.mock('antdv-next', () => {
  const Input = defineComponent({
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
  const Select = defineComponent({
    props: { value: [String, Number], options: Array, disabled: Boolean },
    emits: ['update:value'],
    setup:
      (props, { emit }) =>
      () =>
        h(
          'select',
          {
            value: props.value,
            disabled: props.disabled,
            onChange: (event: Event) =>
              emit('update:value', (event.target as HTMLSelectElement).value),
          },
          ((props.options as { label: string; value: string }[]) ?? []).map(
            (option) => h('option', { value: option.value }, option.label),
          ),
        ),
  });
  return {
    Input,
    DatePicker: Input,
    InputNumber: Input,
    TextArea: Input,
    Select,
    RadioGroup: Select,
    Switch: defineComponent({
      props: { checked: Boolean, disabled: Boolean },
      emits: ['update:checked'],
      setup:
        (props, { emit }) =>
        () =>
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
    }),
    Button: defineComponent({
      props: { disabled: Boolean },
      setup:
        (props, { slots }) =>
        () =>
          h('button', { disabled: props.disabled }, slots.default?.()),
    }),
    Alert: defineComponent({
      props: { message: String },
      setup: (props) => () => h('div', { role: 'alert' }, props.message),
    }),
    Table: defineComponent({
      props: { dataSource: Array },
      setup:
        (props, { slots }) =>
        () =>
          h(
            'div',
            ((props.dataSource as { index: number }[]) ?? []).map((record) =>
              h(
                'div',
                slots.bodyCell?.({ column: { key: 'actions' }, record }),
              ),
            ),
          ),
    }),
    Modal: defineComponent({
      props: { open: Boolean, width: Number },
      emits: ['ok'],
      setup:
        (props, { emit, slots }) =>
        () =>
          props.open
            ? h('div', { role: 'dialog', 'data-modal-width': props.width }, [
                slots.default?.(),
                h('button', { onClick: () => emit('ok') }, '保存条件'),
              ])
            : null,
    }),
  };
});

const fields = [
  { fieldName: 'id', fieldTypeName: 'BIGINT' },
  { fieldName: 'amount', fieldTypeName: 'DECIMAL(30,10)' },
  { fieldName: 'created_at', fieldTypeName: 'timestamp without time zone' },
  { fieldName: 'name', fieldTypeName: 'VARCHAR' },
];
let app: App | undefined;
let host: HTMLDivElement;
const editor = shallowRef<{ validate: () => boolean }>();
afterEach(() => {
  app?.unmount();
  host?.remove();
});
async function flush() {
  await nextTick();
  await nextTick();
}
async function mount(config: Record<string, any>, disabled = false, options: { batchEnabled?: boolean; conditionModalWidth?: number; conditionTableHeight?: number; fixedBaselineWidth?: number; showReadMode?: boolean; showStartRequired?: boolean; showWhere?: boolean; timeFormatWidth?: number } = {}) {
  host = document.createElement('div');
  document.body.append(host);
  const state = reactive({ config, fields, disabled });
  app = createApp({ render: () => h(Editor, { ...state, ...options, ref: editor }) });
  app.mount(host);
  await flush();
  return state;
}
async function input(label: string, value: string) {
  const element = host.querySelector<HTMLInputElement | HTMLSelectElement>(
    `[aria-label="${label}"]`,
  )!;
  expect(element).toBeTruthy();
  element.value = value;
  element.dispatchEvent(
    new Event(element.tagName === 'SELECT' ? 'change' : 'input', {
      bubbles: true,
    }),
  );
  await flush();
}
async function click(label: string) {
  const element = [...host.querySelectorAll('button')].find(
    (button) => button.textContent === label,
  )!;
  expect(element).toBeTruthy();
  element.click();
  await flush();
}

describe('表输入配置校验', () => {
  it('数值增量和分批ID保持超出安全整数范围的十进制字符串', () => {
    expect(
      validateTableInputConfig(
        {
          readMode: 'ID_INCREMENT',
          incrementColumn: 'id',
          startValue: '90071992547409931234567890',
        },
        fields,
      ),
    ).toEqual([]);
    const batch = {
      ...createTableInputBatchRead(),
      enabled: true,
      field: 'id',
      start: '9007199254740993',
      end: '9007199254740994',
      size: '100000000000000000000',
    };
    expect(validateTableInputConfig({ batchRead: batch }, fields)).toEqual([]);
    expect(
      validateTableInputConfig(
        { batchRead: { ...batch, end: '9007199254740992' } },
        fields,
      ),
    ).toContain('结束ID值必须大于起始ID值');
    expect(
      validateTableInputConfig(
        { batchRead: { ...batch, start: '-1.2', end: '-1.1' } },
        fields,
      ),
    ).toEqual([]);
  });
  it('只验证活动读取模式且不删除其他分支配置', () => {
    const config = {
      readMode: 'FULL',
      incrementColumn: 'name',
      startValue: '无效',
      timeConditions: [],
      batchRead: { enabled: false },
      custom: '保留',
    };
    expect(validateTableInputConfig(config, fields)).toEqual([]);
    expect(config.custom).toBe('保留');
    expect(
      validateTableInputConfig({ ...config, readMode: 'ID_INCREMENT' }, fields),
    ).toContain('id字段必须是当前表中的数值字段');
    expect(
      validateTableInputConfig(
        { ...config, readMode: 'TIME_INCREMENT' },
        fields,
      ),
    ).toContain('请至少新增一个时间增量条件');
  });
  it('固定时间、范围游标和SQL基准分别校验', () => {
    expect(
      validateTableInputTimeCondition(
        {
          field: 'created_at',
          baselineType: 'FIXED',
          operator: '>=',
          baselineValue: '2026-11-03',
        },
        fields,
      ),
    ).toEqual([]);
    expect(
      validateTableInputTimeCondition(
        {
          field: 'created_at',
          baselineType: 'FIXED',
          operator: '>',
          baselineValue: '2026-02-30',
        },
        fields,
      ),
    ).toContain('请选择有效的固定时间');
    expect(
      validateTableInputTimeCondition(
        {
          field: 'created_at',
          baselineType: 'TIME_RANGE',
          cursorTime: '2026-11-03',
        },
        fields,
      ),
    ).toEqual([]);
    expect(
      validateTableInputTimeCondition(
        {
          field: 'created_at',
          baselineType: 'SQL',
          operator: '<',
          baselineValue: 'CURRENT_TIMESTAMP',
        },
        fields,
      ),
    ).toEqual([]);
    expect(
      validateTableInputTimeCondition(
        {
          field: 'created_at',
          baselineType: 'SQL',
          operator: '<',
          baselineValue: 'CURRENT_TIMESTAMP; DROP TABLE source',
        },
        fields,
      ),
    ).toContain('SQL基准仅支持单条只读表达式');
  });
  it('分批时间校验类型、起止顺序、跨度和重试边界', () => {
    const batch = {
      ...createTableInputBatchRead(),
      enabled: true,
      mode: 'TIME',
      field: 'created_at',
      start: '2026-11-03 00:00:00',
      end: '2026-11-03 01:00:00',
    };
    expect(validateTableInputConfig({ batchRead: batch }, fields)).toEqual([]);
    expect(
      validateTableInputConfig(
        {
          batchRead: {
            ...batch,
            field: 'id',
            end: batch.start,
            size: '0',
            maxRetries: -1,
            retryIntervalSeconds: 0,
          },
        },
        fields,
      ),
    ).toEqual(
      expect.arrayContaining([
        '分批字段类型与分批方式不一致',
        '结束时间必须晚于起始时间',
        '每批时间跨度必须为正整数',
        '最大重试次数必须为0至100的整数',
        '读取重试间隔必须为1至86400秒的整数',
      ]),
    );
  });
});

describe('表输入读取配置交互', () => {
  it('切换读取模式保留各分支草稿并提供运行支持提示', async () => {
    const state = await mount({
      readMode: 'ID_INCREMENT',
      incrementColumn: 'id',
      startValue: '9007199254740993',
      timeFormat: 'yyyy-MM-dd',
      timeConditions: [
        {
          field: 'created_at',
          baselineType: 'TIME_RANGE',
          cursorTime: '2026-11-03',
        },
      ],
      whereCondition: 'id > 0',
    });
    expect(editor.value!.validate()).toBe(true);
    expect(
      host.querySelector('[aria-label="id字段"]')!.textContent,
    ).not.toContain('created_at');
    await input('读取模式', 'TIME_INCREMENT');
    expect(editor.value!.validate()).toBe(true);
    await input('读取模式', 'FULL');
    expect(editor.value!.validate()).toBe(true);
    await input('读取模式', 'ID_INCREMENT');
    expect(state.config.startValue).toBe('9007199254740993');
    expect(state.config.timeConditions).toHaveLength(1);
    expect(host.textContent).toContain('当前执行通道尚未接入持久游标');
  });
  it('新增、编辑和删除时间条件，取消前不写入配置', async () => {
    const state = await mount({
      readMode: 'TIME_INCREMENT',
      timeFormat: 'yyyy-MM-dd',
      timeConditions: [],
    });
    await click('+ 新增');
    await click('保存条件');
    expect(host.textContent).toContain('请选择基准类型');
    await input('基准类型', 'FIXED');
    await input('时间条件字段名称', 'created_at');
    await input('时间条件运算符', '>');
    await input('固定时间基准值', '2026-11-03');
    expect(state.config.timeConditions).toEqual([]);
    await click('保存条件');
    expect(state.config.timeConditions).toEqual([
      {
        field: 'created_at',
        baselineType: 'FIXED',
        baselineValue: '2026-11-03',
        operator: '>',
      },
    ]);
    await click('编辑');
    await input('基准类型', 'TIME_RANGE');
    await input('游标时间', '2026-11-02');
    await click('保存条件');
    expect(state.config.timeConditions[0]).toMatchObject({
      baselineType: 'TIME_RANGE',
      cursorTime: '2026-11-02',
    });
    await click('删除');
    expect(state.config.timeConditions).toEqual([]);
    expect(editor.value!.validate()).toBe(false);
  });
  it('启用分批初始化重试默认值并保持无损ID，关闭不丢草稿', async () => {
    const state = await mount({ readMode: 'FULL' });
    const toggle = host.querySelector<HTMLInputElement>(
      '[aria-label="启用分批读取"]',
    )!;
    toggle.checked = true;
    toggle.dispatchEvent(new Event('change', { bubbles: true }));
    await flush();
    expect(state.config.batchRead).toMatchObject({
      enabled: true,
      maxRetries: 3,
      retryIntervalSeconds: 60,
    });
    await input('分批字段', 'id');
    await input('起始ID值', '9007199254740993');
    await input('结束ID值', '9007199254740999');
    await input('每批ID跨度', '2');
    expect(editor.value!.validate()).toBe(true);
    toggle.checked = false;
    toggle.dispatchEvent(new Event('change', { bubbles: true }));
    await flush();
    expect(state.config.batchRead.start).toBe('9007199254740993');
    expect(editor.value!.validate()).toBe(true);
  });
  it('切换分批时间或固定时间时过滤不匹配的旧值且保留草稿', async () => {
    const state = await mount({ readMode: 'FULL', batchRead: { ...createTableInputBatchRead(), enabled: true, field: 'id', start: '9007199254740993', end: '9007199254740999' } });
    await input('分批方式', 'TIME');
    expect(host.querySelector<HTMLInputElement>('[aria-label="起始时间"]')!.value).toBe('');
    expect(host.querySelector<HTMLInputElement>('[aria-label="结束时间"]')!.value).toBe('');
    expect(state.config.batchRead.start).toBeUndefined();
    await input('分批字段', 'created_at');
    await input('起始时间', '2026-11-03 00:00:00');
    await input('分批方式', 'ID');
    expect(host.querySelector<HTMLInputElement>('[aria-label="起始ID值"]')!.value).toBe('9007199254740993');
    expect(state.config.batchRead.field).toBe('id');
    await input('分批方式', 'TIME');
    expect(state.config.batchRead.start).toBe('2026-11-03 00:00:00');
    expect(state.config.batchRead.field).toBe('created_at');
  });
  it('查询基准使用异步编辑器并完整保存表达式', async () => {
    const state = await mount({ readMode: 'TIME_INCREMENT', timeConditions: [] });
    await click('+ 新增'); await input('时间条件字段名称', 'created_at'); await input('基准类型', 'SQL'); await input('时间条件运算符', '>');
    await vi.waitFor(() => expect(host.querySelector('[aria-label="SQL基准表达式"]')).toBeTruthy());
    await input('SQL基准表达式', "CURRENT_TIMESTAMP - INTERVAL '1 day'"); await click('保存条件');
    expect(state.config.timeConditions[0].baselineValue).toBe("CURRENT_TIMESTAMP - INTERVAL '1 day'");
    expect(editor.value!.validate()).toBe(true);
    await click('编辑'); await input('基准类型', 'FIXED');
    expect(host.querySelector<HTMLInputElement>('[aria-label="固定时间基准值"]')!.value).toBe('');
  });
  it('同族组件可关闭分批并指定条件弹窗宽度，不改变表输入默认行为', async () => {
    const state = await mount({ readMode: 'FULL', batchRead: { enabled: true, mode: 'ID', start: 'invalid' } }, false, { batchEnabled: false, conditionModalWidth: 912 });
    expect(host.querySelector('[aria-label="启用分批读取"]')).toBeNull();
    expect(editor.value?.validate()).toBe(true);
    state.config.readMode = 'TIME_INCREMENT';
    await flush();
    await click('+ 新增');
    expect(host.querySelector<HTMLElement>('[role="dialog"]')?.dataset.modalWidth).toBe('912');
    expect(state.config.batchRead.enabled).toBe(true);
  });
  it('按Hive隐藏读取模式与where后仍可编辑时间条件，尺寸仅由显式参数覆盖', async () => {
    const state = await mount({ readMode: 'TIME_INCREMENT', timeConditions: [] }, false, { batchEnabled: false, showReadMode: false, showWhere: false, timeFormatWidth: 240, conditionTableHeight: 310, conditionModalWidth: 912, fixedBaselineWidth: 220 });
    expect(host.querySelector('[aria-label="读取模式"]')).toBeNull();
    expect(host.querySelector('[aria-label="where条件"]')).toBeNull();
    expect(host.querySelector<HTMLElement>('[aria-label="时间格式"]')?.style.width).toBe('240px');
    expect(host.querySelector<HTMLElement>('[aria-label="时间增量条件"]')?.style.height).toBe('310px');
    await click('+ 新增');
    await input('基准类型', 'FIXED');
    expect(host.querySelector<HTMLElement>('[aria-label="固定时间基准值"]')?.style.width).toBe('220px');
    await input('时间条件字段名称', 'created_at');
    await input('时间条件运算符', '>=');
    await input('固定时间基准值', '2026-11-03');
    await click('保存条件');
    expect(state.config.timeConditions[0].baselineValue).toBe('2026-11-03');
  });
  it('同族兼容代理关闭分批校验时保留旧协议ID和秒级时间的读取别名', async () => {
    const config = createRelationReadConfig({ readMode: 'ID_INCREMENT', incrementColumn: 'id', incrementStart: '9007199254740993', batchRead: { enabled: true, start: 'invalid' } });
    const state = await mount(config, false, { batchEnabled: false, showStartRequired: false });
    expect(editor.value?.validate()).toBe(true);
    expect(host.querySelector('[aria-label="开始值"]')?.parentElement?.querySelector('em')).toBeNull();
    state.config.startValue = '';
    expect(editor.value?.validate()).toBe(false);
    state.config.readMode = 'TIME_INCREMENT';
    state.config.timeFormat = 'yyyy-MM-dd HH:mm:ss';
    state.config.timeConditions = [{ field: 'created_at', baselineType: 'FIXED', operator: '>', baselineValue: '2026-11-03 01:02:03' }];
    expect(editor.value?.validate()).toBe(true);
  });
  it('只读组件不通过控件事件或旧弹窗保存修改配置', async () => {
    const state = await mount({
      readMode: 'TIME_INCREMENT',
      timeConditions: [],
    });
    await click('+ 新增');
    state.disabled = true;
    await flush();
    expect(host.querySelector('[role="dialog"]')).toBeNull();
    const before = JSON.stringify(state.config);
    await input('读取模式', 'FULL');
    await input('where条件', 'id > 1');
    await click('+ 新增');
    expect(JSON.stringify(state.config)).toBe(before);
    state.disabled = false;
    await flush();
    await click('+ 新增');
    state.config = { readMode: 'TIME_INCREMENT', timeConditions: [] };
    await flush();
    expect(host.querySelector('[role="dialog"]')).toBeNull();
  });
});
