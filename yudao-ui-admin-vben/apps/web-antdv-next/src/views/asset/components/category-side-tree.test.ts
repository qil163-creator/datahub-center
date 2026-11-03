import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import CategorySideTree from './category-side-tree.vue';

vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/icons', () => ({
  IconifyIcon: defineComponent({ setup: () => () => null }),
}));
vi.mock('antdv-next', () => ({
  Button: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('button', slots.default?.()),
  }),
  Card: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  }),
  Dropdown: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', [slots.default?.(), slots.popupRender?.()]),
  }),
  Empty: defineComponent({ setup: () => () => null }),
  Input: defineComponent({ setup: () => () => null }),
  Menu: Object.assign(
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('div', slots.default?.()),
    }),
    {
      Item: defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h('button', slots.default?.()),
      }),
    },
  ),
  Spin: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  }),
  Tooltip: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  }),
  Tree: defineComponent({
    props: ['treeData'],
    emits: ['select'],
    setup:
      (props, { emit }) =>
      () =>
        h(
          'div',
          (props.treeData ?? []).flatMap(function flatten(node: {
            children?: unknown[];
            key: string;
            title: string;
          }): unknown[] {
            return [
              h(
                'button',
                {
                  'data-tree-key': node.key,
                  onClick: () => emit('select', [node.key]),
                },
                node.title,
              ),
              ...(node.children ?? []).flatMap((child) =>
                flatten(child as typeof node),
              ),
            ];
          }),
        ),
  }),
}));

let app: App | undefined;
let host: HTMLDivElement;

afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('asset category tree component', () => {
  it('retains a Long-sized category key through selection and root reset', async () => {
    const selected = ref<number | string>();
    const largeId = '9223372036854775807';
    host = document.createElement('div');
    document.body.append(host);
    app = createApp(
      defineComponent({
        setup: () => () =>
          h(CategorySideTree, {
            items: [
              { id: largeId, parentId: '0', categoryName: '父类目' },
              {
                id: '9223372036854775806',
                parentId: largeId,
                categoryName: '子类目',
              },
            ],
            rootTitle: '全部',
            selected: selected.value,
            'onUpdate:selected': (id: number | string | undefined) => {
              selected.value = id;
            },
          }),
      }),
    );
    app.mount(host);
    const click = async (key: string) => {
      host
        .querySelector<HTMLButtonElement>(`[data-tree-key="${key}"]`)!
        .click();
      await nextTick();
    };
    await click(largeId);
    expect(selected.value).toBe(largeId);
    await click('0');
    expect(selected.value).toBeUndefined();
  });
});
