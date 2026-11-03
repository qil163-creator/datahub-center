import { effectScope, nextTick, ref } from 'vue';

import { describe, expect, it } from 'vitest';

import { useTreeExpansion } from './use-tree-expansion';

interface Node {
  children?: Node[];
  key: string;
}

function setup() {
  const tree = ref<Node[]>([]);
  const expanded = ref<string[]>([]);
  const search = ref('');
  const scope = effectScope();
  const controls = scope.run(() => useTreeExpansion(tree, expanded, search))!;
  return { tree, expanded, search, scope, ...controls };
}

const nested = (): Node[] => [
  {
    key: 'all',
    children: [{ key: '9007199254740993', children: [{ key: 'leaf' }] }],
  },
];

describe('governance navigation tree expansion', () => {
  it('opens the first asynchronously loaded branches without losing long IDs', async () => {
    const state = setup();
    state.tree.value = [{ key: 'all', children: [] }];
    await nextTick();
    state.tree.value = nested();
    await nextTick();
    expect(state.expanded.value).toEqual(['all', '9007199254740993']);
    expect(state.allExpanded.value).toBe(true);
    state.scope.stop();
  });

  it('preserves a collapsed tree when refreshed and expands all on the next toggle', async () => {
    const state = setup();
    state.tree.value = nested();
    await nextTick();
    state.toggleExpand();
    state.tree.value = nested();
    await nextTick();
    expect(state.expanded.value).toEqual([]);
    expect(state.allExpanded.value).toBe(false);
    state.toggleExpand();
    expect(state.expanded.value).toEqual(['all', '9007199254740993']);
    state.scope.stop();
  });

  it('preserves manual partial expansion and reports the real state', async () => {
    const state = setup();
    state.tree.value = nested();
    await nextTick();
    state.expanded.value = ['all'];
    state.tree.value = nested();
    await nextTick();
    expect(state.expanded.value).toEqual(['all']);
    expect(state.allExpanded.value).toBe(false);
    state.scope.stop();
  });

  it('opens a changed search but allows collapse and reload with the search intact', async () => {
    const state = setup();
    state.tree.value = nested();
    await nextTick();
    state.toggleExpand();
    state.search.value = 'leaf';
    await nextTick();
    expect(state.allExpanded.value).toBe(true);
    state.toggleExpand();
    state.tree.value = nested();
    await nextTick();
    expect(state.search.value).toBe('leaf');
    expect(state.expanded.value).toEqual([]);
    state.scope.stop();
  });
});
