/* eslint-disable vue/one-component-per-file -- 用原生控件模拟展示组件，验证字段与预览的实际交互 */
import type { App, Component, PropType } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import ApiDocument from './api-document.vue';
import FieldsTab from './fields-tab.vue';
import PreviewTab from './preview-tab.vue';

vi.mock('#/components/text-ellipsis-cell', () => ({
  TextEllipsisCell: defineComponent({
    props: { content: { type: String, default: '' } },
    setup: (props) => () => h('span', props.content),
  }),
}));

vi.mock('antdv-next', () => {
  const box = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('span', slots.default?.()),
  });
  const input = defineComponent({
    props: { value: { type: String, default: '' } },
    emits: ['update:value'],
    setup:
      (props, { emit }) =>
      () =>
        h('input', {
          value: props.value,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLInputElement).value),
        }),
  });
  return {
    Alert: defineComponent({
      props: { message: { type: String, default: '' } },
      setup: (props) => () => h('p', props.message),
    }),
    Button: defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('button', slots.default?.()),
    }),
    Checkbox: box,
    CheckboxGroup: box,
    Dropdown: defineComponent({
      props: { open: Boolean },
      emits: ['update:open'],
      setup:
        (props, { emit, slots }) =>
        () =>
          h('div', [
            h(
              'div',
              { onClick: () => emit('update:open', !props.open) },
              slots.default?.(),
            ),
            props.open ? slots.popupRender?.() : null,
          ]),
    }),
    Input: input,
    Select: defineComponent({
      props: {
        value: { type: String, default: '' },
        options: {
          type: Array as PropType<{ label: string; value: string }[]>,
          default: () => [],
        },
      },
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'select',
            {
              value: props.value,
              onChange: (event: Event) =>
                emit('update:value', (event.target as HTMLSelectElement).value),
            },
            props.options.map((option) =>
              h('option', { value: option.value }, option.label),
            ),
          ),
    }),
    Table: defineComponent({
      props: {
        columns: {
          type: Array as PropType<{ key: string }[]>,
          default: () => [],
        },
        dataSource: {
          type: Array as PropType<Record<string, unknown>[]>,
          default: () => [],
        },
        rowKey: {
          type: [String, Function] as PropType<
            ((row: Record<string, unknown>) => string) | string
          >,
          default: 'id',
        },
      },
      setup:
        (props, { slots }) =>
        () =>
          h(
            'div',
            { class: 'test-table' },
            props.dataSource.map((record, index) =>
              h(
                'div',
                {
                  class: 'test-row',
                  'data-key':
                    typeof props.rowKey === 'function'
                      ? props.rowKey(record)
                      : String(record[props.rowKey]),
                },
                props.columns.map((column) =>
                  h(
                    'span',
                    { 'data-column': column.key },
                    slots.bodyCell?.({ column, record, index }),
                  ),
                ),
              ),
            ),
          ),
    }),
    Tag: box,
    Tooltip: box,
  };
});

describe('审批详情 API 文档', () => {
  it('按快照字段显示参数、响应字段与可空状态，缺失值不误判', async () => {
    await mount(ApiDocument, {
      document: {
        requestParams: [
          { name: 'required', required: true, type: 'String', description: '必填说明', example: 0 },
          { name: 'optional', required: false },
          { name: 'unknown' },
        ],
        responseFields: [{ name: 'RVNM', description: '河流名称', type: 'VARCHAR' }],
      },
    });
    const rows = host.querySelectorAll('.test-row');
    expect(rows[0]?.querySelector('[data-column="nullable"]')?.textContent).toBe('否');
    expect(rows[1]?.querySelector('[data-column="nullable"]')?.textContent).toBe('是');
    expect(rows[2]?.querySelector('[data-column="nullable"]')?.textContent).toBe('-');
    expect(rows[0]?.querySelector('[data-column="example"]')?.textContent).toBe('0');
    expect(rows[0]?.querySelector('[data-column="description"]')?.textContent).toBe('必填说明');
    expect(rows[3]?.textContent).toContain('RVNM河流名称VARCHAR');
  });
});

let app: App | undefined;
let host: HTMLDivElement;
async function mount(component: Component, props: Record<string, unknown>) {
  host = document.createElement('div');
  app = createApp({ render: () => h(component, props) });
  app.mount(host);
  await nextTick();
}
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});
function button(text: string) {
  const element = [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === text,
  );
  if (!element) throw new Error(`找不到按钮：${text}`);
  return element;
}
async function setValue(
  element: HTMLInputElement | HTMLSelectElement,
  value: string,
) {
  element.value = value;
  element.dispatchEvent(
    new Event(element instanceof HTMLSelectElement ? 'change' : 'input', {
      bubbles: true,
    }),
  );
  await nextTick();
}

describe('审批详情字段快照', () => {
  it('保留长字段编号，展示描述回退与缺失标志，搜索术语后仍可刷新', async () => {
    const refresh = vi.fn();
    await mount(FieldsTab, {
      active: true,
      onRefresh: refresh,
      columns: [
        {
          id: '9223372036854775807',
          columnName: 'RVNM',
          customComment: '河流名称',
          remark: '沿用字段说明',
          nullableFlag: null,
          termName: '水系名称',
        },
        {
          id: '9223372036854775806',
          columnName: 'CODE',
          businessName: '编码',
          nullableFlag: 1,
          pkFlag: 0,
        },
      ],
    });
    const first = host.querySelector<HTMLDivElement>('.test-row');
    expect(first?.dataset.key).toBe('9223372036854775807');
    expect(
      first?.querySelector('[data-column="businessName"]')?.textContent,
    ).toBe('河流名称');
    expect(
      first?.querySelector('[data-column="businessDefinition"]')?.textContent,
    ).toBe('沿用字段说明');
    expect(
      first?.querySelector('[data-column="nullableFlag"]')?.textContent,
    ).toBe('-');
    expect(first?.querySelector('[data-column="pkFlag"]')?.textContent).toBe(
      '-',
    );
    expect(
      host.querySelectorAll('[data-column="nullableFlag"]')[1]?.textContent,
    ).toBe('是');
    expect(
      host.querySelectorAll('[data-column="pkFlag"]')[1]?.textContent,
    ).toBe('否');
    (
      host.querySelector('[aria-label="表格设置"]') as HTMLButtonElement
    ).click();
    await nextTick();
    await setValue(host.querySelector('input')!, '  水系  ');
    expect(host.querySelectorAll('.test-row')).toHaveLength(1);
    expect(host.querySelector('.test-row')?.textContent).toContain('RVNM');
    (
      host.querySelector('[aria-label="刷新字段"]') as HTMLButtonElement
    ).click();
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('切换离开字段页签时关闭设置浮层', async () => {
    const props = reactive({ active: true, columns: [] });
    await mount(FieldsTab, props);
    (
      host.querySelector('[aria-label="表格设置"]') as HTMLButtonElement
    ).click();
    await nextTick();
    expect(host.querySelector('input')).not.toBeNull();
    props.active = false;
    await nextTick();
    expect(host.querySelector('input')).toBeNull();
  });
});

describe('审批详情预览快照交互', () => {
  const preview = () => ({
    columns: [
      { name: 'ID', label: '编号' },
      { name: 'amount', label: '精确值' },
    ],
    rows: [
      { ID: '9007199254740992', amount: '0.12345678901234567890' },
      { ID: '9007199254740993', amount: '0.12345678901234567891' },
    ],
    truncated: true,
  });

  it('编辑条件仅在查询后生效，重置恢复完整快照并保持原始精度', async () => {
    await mount(PreviewTab, { preview: preview() });
    expect(host.textContent).toContain('预览仅包含部分记录');
    expect(host.querySelectorAll('.test-row')).toHaveLength(2);
    (
      host.querySelector('[aria-label="添加筛选条件"]') as HTMLButtonElement
    ).click();
    await nextTick();
    await setValue(host.querySelector('input')!, '9007199254740993');
    expect(host.querySelectorAll('.test-row')).toHaveLength(2);
    button('查询').click();
    await nextTick();
    expect(host.querySelectorAll('.test-row')).toHaveLength(1);
    expect(host.querySelector('.test-row')?.textContent).toBe(
      '90071992547409930.12345678901234567891',
    );
    await setValue(host.querySelector('input')!, '9007199254740992');
    expect(host.querySelector('.test-row')?.textContent).toContain(
      '9007199254740993',
    );
    button('重置').click();
    await nextTick();
    expect(host.querySelectorAll('.test-row')).toHaveLength(2);
    expect(host.querySelector('input')).toBeNull();
  });

  it('换入新快照时清空旧条件，撤销预览后清空行并禁用操作', async () => {
    const props = reactive<{ preview: ReturnType<typeof preview> | undefined }>(
      { preview: preview() },
    );
    await mount(PreviewTab, props);
    (
      host.querySelector('[aria-label="添加筛选条件"]') as HTMLButtonElement
    ).click();
    await nextTick();
    await setValue(host.querySelector('input')!, '9007199254740993');
    button('查询').click();
    await nextTick();
    props.preview = { ...preview(), rows: [{ ID: '新快照', amount: '42.00' }] };
    await nextTick();
    expect(host.querySelector('input')).toBeNull();
    expect(host.querySelector('.test-row')?.textContent).toBe('新快照42.00');
    props.preview = undefined;
    await nextTick();
    expect(host.querySelectorAll('.test-row')).toHaveLength(0);
    expect(host.textContent).toContain('暂无已保存的数据预览');
    expect(button('查询').disabled).toBe(true);
    expect(button('重置').disabled).toBe(true);
    expect(
      (host.querySelector('[aria-label="添加筛选条件"]') as HTMLButtonElement)
        .disabled,
    ).toBe(true);
  });
});
