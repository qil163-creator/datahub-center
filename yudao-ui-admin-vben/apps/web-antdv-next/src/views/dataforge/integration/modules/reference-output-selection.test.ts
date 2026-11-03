/* eslint-disable vue/one-component-per-file -- 测试替身只模拟勾选事件。 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import ReferenceOutputSelection from './reference-output-selection.vue';

vi.mock('antdv-next', () => ({
  Checkbox: defineComponent({
    props: { checked: Boolean, disabled: Boolean },
    emits: ['change'],
    setup:
      (props, { emit }) =>
      () =>
        h('input', {
          type: 'checkbox',
          checked: props.checked,
          disabled: props.disabled,
          onChange: (event: Event) => emit('change', event),
        }),
  }),
}));
let app: App | undefined;
let host: HTMLDivElement;
afterEach(() => {
  app?.unmount();
  host?.remove();
});
function mount(config: Record<string, any>, disabled = false) {
  host = document.createElement('div');
  document.body.append(host);
  const state = reactive({
    config,
    disabled,
    inputFields: [
      { name: 'id', dataType: 'LONG', length: 20, scale: 0 },
      { name: 'name', dataType: 'STRING' },
    ],
  });
  app = createApp({ render: () => h(ReferenceOutputSelection, state) });
  app.mount(host);
  return state;
}
async function check(label: string, value: boolean) {
  const input = host.querySelector<HTMLInputElement>(
    `[aria-label="${label}"]`,
  )!;
  input.checked = value;
  input.dispatchEvent(new Event('change'));
  await nextTick();
}
describe('消息输出的上游字段选择', () => {
  it('挂载只展示真实上游，选择时生成对象字段与现有映射契约，保留零精度', async () => {
    const state = mount({ columns: [], custom: '保留' });
    expect(state.config).toEqual({ columns: [], custom: '保留' });
    expect(host.querySelector('table')?.textContent).toContain('id');
    expect(host.querySelector('button')).toBeNull();
    await check('选择属性字段id', true);
    expect(state.config.columns).toEqual([
      {
        name: 'id',
        key: 'id',
        type: 'LONG',
        index: 0,
        length: 20,
        precision: 0,
      },
    ]);
    expect(state.config.fieldMappings).toEqual([
      { source: 'id', target: 'id', enabled: true },
    ]);
    expect(state.config.custom).toBe('保留');
  });
  it('保留历史别名与未知配置，取消单项只移除当前选择，全选可恢复上游', async () => {
    const state = mount({
      columns: [{ name: '标题', key: 'name', type: 'STRING', custom: 42 }],
      fieldMappings: [
        { source: 'name', target: '标题', enabled: true, vendor: 'x' },
      ],
    });
    await check('选择属性字段id', true);
    expect(state.config.columns[0]).toMatchObject({
      name: '标题',
      key: 'name',
      custom: 42,
    });
    expect(state.config.fieldMappings[0]).toMatchObject({
      source: 'name',
      target: '标题',
      vendor: 'x',
    });
    await check('选择属性字段id', false);
    expect(state.config.columns).toHaveLength(1);
    await check('全选属性字段', true);
    expect(
      state.config.columns.map((field: { key: string }) => field.key),
    ).toEqual(['id', 'name']);
    expect(state.config.columns[1]).toMatchObject({ name: '标题', custom: 42 });
  });
  it('缺失的历史来源保持可见，不因元数据刷新静默丢配置', async () => {
    const state = mount({
      columns: [{ name: 'old', key: 'removed', type: 'LONG', vendor: 7 }],
    });
    expect(host.textContent).toContain('removed（上游已移除）');
    state.inputFields = [{ name: 'new', dataType: 'STRING' }];
    await nextTick();
    expect(state.config.columns).toEqual([
      { name: 'old', key: 'removed', type: 'LONG', vendor: 7 },
    ]);
    expect(host.textContent).toContain('removed（上游已移除）');
  });
  it('只读时即使收到伪造change也不写入配置', async () => {
    const state = mount({ columns: [{ name: 'id', type: 'LONG' }] }, true);
    await check('全选属性字段', false);
    expect(state.config).toEqual({ columns: [{ name: 'id', type: 'LONG' }] });
  });
});
