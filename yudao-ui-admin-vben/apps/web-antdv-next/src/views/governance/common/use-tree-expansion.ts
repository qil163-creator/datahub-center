import type { Ref } from 'vue';

import { computed, watch } from 'vue';

interface NavigationTreeNode {
  children?: NavigationTreeNode[];
  id?: number | string;
  isLeaf?: boolean;
  key?: number | string;
}

/** Keep manual expansion through reloads while opening matching search paths. */
export function useTreeExpansion(
  tree: Readonly<Ref<NavigationTreeNode[]>>,
  expandedKeys: Ref<string[]>,
  keyword: Readonly<Ref<string>>,
) {
  function collect(nodes: NavigationTreeNode[]): string[] {
    return nodes.flatMap((node) => {
      const key = node.key ?? node.id;
      return [
        ...(key !== undefined &&
        (node.children?.length || node.isLeaf === false)
          ? [String(key)]
          : []),
        ...collect(node.children ?? []),
      ];
    });
  }
  const branchKeys = computed(() => collect(tree.value));
  const allExpanded = computed(
    () =>
      branchKeys.value.length > 0 &&
      branchKeys.value.every((key) => expandedKeys.value.includes(key)),
  );
  let initialized = false;
  watch(
    [branchKeys, () => keyword.value.trim()],
    ([keys, text], previous) => {
      const previousKeys = previous[0] ?? [];
      const previousText = previous[1];
      const expand =
        (!initialized && keys.length > 0) ||
        text !== previousText ||
        (previousKeys.length > 0 &&
          previousKeys.every((key) => expandedKeys.value.includes(key)));
      expandedKeys.value = expand
        ? keys
        : expandedKeys.value.filter((key) => keys.includes(key));
      if (keys.length > 0) initialized = true;
    },
    { immediate: true },
  );
  function toggleExpand() {
    initialized = true;
    expandedKeys.value = allExpanded.value ? [] : [...branchKeys.value];
  }
  return { allExpanded, toggleExpand };
}
