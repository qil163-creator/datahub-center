interface NavigationTreeNode {
  children?: NavigationTreeNode[];
  key: number | string;
}

export function collectExpandableKeys(nodes: NavigationTreeNode[]): string[] {
  return nodes.flatMap((node) =>
    node.children?.length
      ? [String(node.key), ...collectExpandableKeys(node.children)]
      : [],
  );
}

export function isTreeExpanded(
  nodes: NavigationTreeNode[],
  expandedKeys: (number | string)[],
) {
  const keys = collectExpandableKeys(nodes);
  const expanded = new Set(expandedKeys.map(String));
  return keys.length > 0 && keys.every((key) => expanded.has(key));
}
