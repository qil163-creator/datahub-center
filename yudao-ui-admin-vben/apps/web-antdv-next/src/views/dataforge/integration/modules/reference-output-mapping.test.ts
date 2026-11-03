/* eslint-disable vue/one-component-per-file -- 测试替身只模拟映射依赖的按钮与勾选事件。 */
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

import ReferenceOutputMapping from './reference-output-mapping.vue';

vi.mock('antdv-next', () => ({
  Button: defineComponent({
    props: { disabled: Boolean },
    setup:
      (props, { slots }) =>
      () =>
        h('button', { disabled: props.disabled }, slots.default?.()),
  }),
  Checkbox: defineComponent({
    inheritAttrs: false,
    props: { checked: Boolean, disabled: Boolean, indeterminate: Boolean },
    emits: ['update:checked'],
    setup:
      (props, { emit, slots, attrs }) =>
      () =>
        h('label', [
          h('input', {
            ...attrs,
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
}));

let app: App | undefined;
let host: HTMLDivElement;
const instance = shallowRef<{ autoMap: () => void; validate: () => boolean }>();
const inputFields = [
  { name: 'id', dataType: 'BIGINT' },
  { name: 'name', dataType: 'VARCHAR' },
];
const targetFields = [
  { name: 'id', type: 'BIGINT' },
  { name: 'name', type: 'VARCHAR' },
];
async function flush() {
  for (let count = 0; count < 4; count++) await nextTick();
}
async function mount(
  config: Record<string, any>,
  targets: null | typeof targetFields = targetFields,
  connectorMode = false,
) {
  host = document.createElement('div');
  document.body.append(host);
  const state = reactive({
    config,
    connectorMode,
    disabled: false,
    inputFields: inputFields.map((field) => ({ ...field })),
    targetFields: targets ?? undefined,
  });
  app = createApp({
    render: () => h(ReferenceOutputMapping, { ...state, ref: instance }),
  });
  app.mount(host);
  await flush();
  return state;
}
function checkbox(label: string) {
  const element = host.querySelector<HTMLInputElement>(
    `input[aria-label="${label}"]`,
  );
  expect(element).toBeTruthy();
  return element!;
}
async function check(label: string, checked: boolean) {
  const element = checkbox(label);
  element.checked = checked;
  element.dispatchEvent(new Event('change', { bubbles: true }));
  await flush();
}
function rows(side: string) {
  return [
    ...host.querySelectorAll<HTMLElement>(
      `[data-mapping-side="${side}"] .qi-output-mapping-row`,
    ),
  ];
}
function keydown(side: string, index: number, key: string) {
  rows(side)[index]!.dispatchEvent(
    new KeyboardEvent('keydown', { altKey: true, key, bubbles: true }),
  );
}
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('输出连接器双列字段映射', () => {
  it('挂载与验证不改写草稿，缺失目标保留并明确阻止映射通过', async () => {
    const config = {
      vendor: { value: 'keep' },
      fieldMappings: [
        { source: 'id', target: 'missing', enabled: true, vendor: 'keep' },
      ],
    };
    const before = JSON.stringify(config);
    await mount(config);
    expect(JSON.stringify(config)).toBe(before);
    expect(rows('target')[0]!.textContent).toContain('missing');
    expect(checkbox('目标字段missing').disabled).toBe(true);
    expect(instance.value!.validate()).toBe(false);
    await flush();
    expect(host.querySelector('[role="alert"]')).not.toBeNull();
    expect(JSON.stringify(config)).toBe(before);
  });

  it('独立勾选与全选保存两列布局，重开恢复半选与未知属性', async () => {
    const state = await mount({
      vendor: 'keep',
      fieldMappings: [
        { source: 'id', target: 'id', enabled: true, comment: '保留' },
        { source: 'name', target: 'name', enabled: true },
      ],
    });
    await check('目标字段id', false);
    expect(checkbox('来源字段id').checked).toBe(true);
    expect(state.config.fieldMappings[0]).toEqual({
      source: 'id',
      target: 'id',
      enabled: false,
      comment: '保留',
    });
    expect(state.config.vendor).toBe('keep');
    const saved = JSON.parse(JSON.stringify(state.config));
    app!.unmount();
    app = undefined;
    host.remove();
    const reopened = await mount(saved);
    expect(checkbox('来源字段id').checked).toBe(true);
    expect(checkbox('目标字段id').checked).toBe(false);
    await check('全选目标字段', true);
    expect(
      reopened.config.fieldMappings.every(
        (mapping: { enabled: boolean }) => mapping.enabled,
      ),
    ).toBe(true);
    await check('全选来源字段', false);
    expect(checkbox('目标字段id').checked).toBe(true);
    expect(instance.value!.validate()).toBe(false);
  });

  it('目标拖拽与来源键盘排序相互独立并按当前行配对', async () => {
    const state = await mount({});
    rows('target')[0]!.dispatchEvent(
      new Event('dragstart', { bubbles: true, cancelable: true }),
    );
    rows('target')[1]!.dispatchEvent(
      new Event('drop', { bubbles: true, cancelable: true }),
    );
    await flush();
    expect(state.config.fieldMappings).toEqual([
      { source: 'id', target: 'name', enabled: true },
      { source: 'name', target: 'id', enabled: true },
    ]);
    keydown('source', 0, 'ArrowDown');
    await flush();
    expect(state.config.fieldMappings).toEqual([
      { source: 'name', target: 'name', enabled: true },
      { source: 'id', target: 'id', enabled: true },
    ]);
    expect(
      host
        .querySelector<HTMLImageElement>('.qi-output-mapping-drag')!
        .getAttribute('src'),
    ).toBe('/assets/qdata-integration/icon-field-drag-ref.png');
  });

  it('元数据刷新保留刚调整的勾选和顺序并加入新的真实字段', async () => {
    const state = await mount({});
    keydown('target', 0, 'ArrowDown');
    await flush();
    await check('来源字段id', false);
    state.targetFields = [
      ...targetFields,
      { name: 'created', type: 'TIMESTAMP' },
    ];
    await flush();
    expect(rows('target').map((row) => row.dataset.fieldName)).toEqual([
      'name',
      'id',
      'created',
    ]);
    expect(checkbox('来源字段id').checked).toBe(false);
    expect(checkbox('目标字段created').checked).toBe(false);
  });

  it('只读与切换节点后旧拖拽都不能修改新草稿', async () => {
    const state = await mount({});
    rows('target')[0]!.dispatchEvent(new Event('dragstart', { bubbles: true }));
    state.config = { fieldMappings: [], vendor: 'new' };
    await flush();
    rows('target')[1]!.dispatchEvent(new Event('drop', { bubbles: true }));
    await flush();
    expect(state.config).toEqual({ fieldMappings: [], vendor: 'new' });
    state.disabled = true;
    await flush();
    const before = JSON.stringify(state.config);
    await check('全选目标字段', true);
    keydown('source', 0, 'ArrowDown');
    instance.value!.autoMap();
    await flush();
    expect(JSON.stringify(state.config)).toBe(before);
    expect(rows('target')[0]!.getAttribute('draggable')).toBe('false');
  });

  it('未传目标元数据时只读取已声明的columns，不把来源字段编造成目标', async () => {
    const state = await mount(
      {
        columns: [{ name: 'name', type: 'VARCHAR', vendor: true }],
        vendor: 'keep',
      },
      null,
    );
    expect(rows('target').map((row) => row.dataset.fieldName)).toEqual([
      '',
      'name',
    ]);
    instance.value!.autoMap();
    await flush();
    expect(state.config.columns).toEqual([
      { name: 'name', type: 'VARCHAR', vendor: true },
    ]);
    expect(state.config.fieldMappings).toEqual([
      { source: 'id', target: '', enabled: false },
      { source: 'name', target: 'name', enabled: true },
    ]);
    expect(instance.value!.validate()).toBe(true);
  });

  it('历史二元组按启用回显，编辑后保留原映射附加键', async () => {
    const state = await mount({
      fieldMappings: [{ source: 'id', target: 'id', legacy: 'keep' }],
    });
    expect(checkbox('来源字段id').checked).toBe(true);
    expect(state.config.fieldMappings[0].enabled).toBeUndefined();
    await check('目标字段id', false);
    expect(state.config.fieldMappings[0]).toEqual({
      source: 'id',
      target: 'id',
      enabled: false,
      legacy: 'keep',
    });
  });
  it('连接器勾选同步columns来源并只提交启用配对，保留未选元数据以便恢复', async () => {
    const state = await mount({ columns: [{ name: 'id', type: 'BIGINT', precision: 0, vendor: 7 }, { name: 'name', type: 'VARCHAR', vendor: 8 }] }, targetFields, true);
    await check('来源字段id', false);
    expect(state.config.columns).toEqual([{ name: 'name', type: 'VARCHAR', vendor: 8, key: 'name' }]);
    expect(state.config.fieldMappings).toEqual([{ source: 'name', target: 'name', enabled: true }]);
    expect(state.config.fieldMappingColumns).toHaveLength(2);
    await check('来源字段id', true);
    expect(state.config.columns).toEqual([{ name: 'id', type: 'BIGINT', precision: 0, vendor: 7, key: 'id' }, { name: 'name', type: 'VARCHAR', vendor: 8, key: 'name' }]);
    keydown('source', 0, 'ArrowDown'); await flush();
    expect(state.config.columns).toEqual([{ name: 'id', type: 'BIGINT', precision: 0, vendor: 7, key: 'name' }, { name: 'name', type: 'VARCHAR', vendor: 8, key: 'id' }]);
    expect(state.config.fieldMappings).toEqual([{ source: 'name', target: 'id', enabled: true }, { source: 'id', target: 'name', enabled: true }]);
  });

});
