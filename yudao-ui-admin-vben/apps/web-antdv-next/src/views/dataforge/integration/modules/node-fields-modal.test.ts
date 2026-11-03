import type { App } from 'vue';

import type { NodeFieldSource } from './node-fields-modal.vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import NodeFieldsModal from './node-fields-modal.vue';

vi.mock('antdv-next', () => ({
  Modal: defineComponent({
    props: { open: Boolean },
    setup:
      (props, { slots }) =>
      () =>
        props.open ? h('section', [slots.default?.(), slots.footer?.()]) : null,
  }),
  Button: defineComponent({
    setup:
      (_props, { slots }) =>
      () =>
        h('button', slots.default?.()),
  }),
}));

let app: App | undefined;
let host: HTMLDivElement;
afterEach(() => {
  app?.unmount();
  host?.remove();
});
function mount(fields: readonly NodeFieldSource[]) {
  host = document.createElement('div');
  document.body.append(host);
  const state = reactive({
    open: true,
    direction: 'input' as 'input' | 'output',
    stepName: '表输出组件',
    fields,
    loading: false,
    error: '',
    partial: false,
  });
  const closed = vi.fn();
  app = createApp({
    render: () => h(NodeFieldsModal, { ...state, 'onUpdate:open': closed }),
  });
  app.mount(host);
  return { state, closed };
}
async function click(text: string) {
  const button = [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === text,
  )!;
  button.click();
  await nextTick();
}
function names() {
  return [...host.querySelectorAll('tbody tr')].map(
    (row) => row.children[1]?.textContent,
  );
}

describe('字段来源只读弹窗', () => {
  it('按整数排序长度，无损比较 Long 字符串、保留零并让未知值始终在末尾', async () => {
    const fields = Object.freeze([
      Object.freeze({ name: 'unknown' }),
      Object.freeze({ name: 'ten', length: 10 }),
      Object.freeze({ name: 'zero', length: 0 }),
      Object.freeze({ name: 'large', length: '9007199254740993' }),
      Object.freeze({ name: 'smaller', length: '9007199254740992' }),
      Object.freeze({ name: 'two', length: '2' }),
    ]);
    mount(fields);
    await click('字段长度');
    expect(names()).toEqual([
      'zero',
      'two',
      'ten',
      'smaller',
      'large',
      'unknown',
    ]);
    expect(host.querySelector('tbody tr')?.children[3]?.textContent).toBe('0');
    await click('字段长度');
    expect(names()).toEqual([
      'large',
      'smaller',
      'ten',
      'two',
      'zero',
      'unknown',
    ]);
    await click('字段长度');
    expect(names()).toEqual(fields.map((field) => field.name));
  });

  it('精度排序保留同值顺序；切换步骤清理排序', async () => {
    const { state } = mount([
      { name: 'a', precision: '12' },
      { name: 'b', precision: 2 },
      { name: 'c', precision: '2' },
      { name: 'd', precision: null },
    ]);
    await click('字段精度');
    expect(names()).toEqual(['b', 'c', 'a', 'd']);
    state.stepName = '另一步骤';
    await nextTick();
    expect(names()).toEqual(['a', 'b', 'c', 'd']);
  });

  it('加载和错误状态不展示旧字段，关闭仅发出只读事件', async () => {
    const { state, closed } = mount([{ name: '旧字段' }]);
    state.loading = true;
    await nextTick();
    expect(host.textContent).toContain('正在加载');
    expect(host.textContent).not.toContain('旧字段');
    state.loading = false;
    state.error = '元数据无权限';
    await nextTick();
    expect(host.textContent).toContain('元数据无权限');
    expect(host.textContent).not.toContain('旧字段');
    await click('关闭');
    expect(closed).toHaveBeenCalledExactlyOnceWith(false);
  });
});
