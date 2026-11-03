import type { App, SetupContext } from 'vue';

import { createApp, h, nextTick, ref } from 'vue';

import { afterEach, expect, it, vi } from 'vitest';

import MetricCategoryTree from './metric-category-tree.vue';

const harness = vi.hoisted(() => ({
  expanded: [] as string[],
  selected: [] as string[],
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => h('i') }));
vi.mock('#/components/tree-toolbar/index.vue', () => ({
  default: () => h('div'),
}));
vi.mock('antdv-next', () => ({
  Input: () => h('input'),
  Spin: (_: unknown, { slots }: SetupContext) => h('div', slots.default?.()),
  Tree: (
    props: { 'expanded-keys': string[]; 'selected-keys': string[] },
    { attrs }: SetupContext,
  ) => {
    harness.expanded = props['expanded-keys'];
    harness.selected = props['selected-keys'];
    return h('div', [
      h(
        'button',
        {
          onClick: () =>
            (attrs['onUpdate:expandedKeys'] as (value: string[]) => void)([
              'all',
            ]),
        },
        '收起域',
      ),
      h(
        'button',
        {
          onClick: () =>
            (attrs.onSelect as (keys: string[], info: unknown) => void)(
              ['model:3'],
              {
                node: {
                  filter: {
                    dataDomainId: '1',
                    subjectDomainId: '2',
                    sourceModelId: '3',
                  },
                },
              },
            ),
        },
        '来源模型',
      ),
    ]);
  },
}));
let app: App;
let host: HTMLDivElement;
afterEach(() => {
  app?.unmount();
  host?.remove();
});
it('用户能够收起目录，父级重置后选中根节点', async () => {
  const value = ref(
    {} as {
      dataDomainId?: string;
      sourceModelId?: string;
      subjectDomainId?: string;
    },
  );
  host = document.createElement('div');
  document.body.append(host);
  app = createApp({
    setup: () => () =>
      h(MetricCategoryTree, {
        domains: [{ id: '1', name: '交易' }],
        subjects: [{ id: '2', name: '订单' }],
        models: [
          { id: '3', name: '订单表', dataDomainId: '1', subjectDomainId: '2' },
        ],
        value: value.value,
        'onUpdate:value': (next) => {
          value.value = next as typeof value.value;
        },
      }),
  });
  app.mount(host);
  await nextTick();
  expect(harness.expanded).toEqual(['all']);
  host.querySelectorAll('button')[0]!.click();
  await nextTick();
  expect(harness.expanded).toEqual(['all']);
  host.querySelectorAll('button')[1]!.click();
  await nextTick();
  expect(value.value.sourceModelId).toBe('3');
  expect(harness.selected).toEqual(['model:3']);
  value.value = {};
  await nextTick();
  expect(harness.selected).toEqual(['all']);
  expect(harness.expanded).toEqual(['all']);
});
