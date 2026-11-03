export type StandardId = number | string;

export interface StandardCategory {
  id: StandardId;
  parentId?: null | StandardId;
  categoryName?: string;
  sort?: number;
  children?: StandardCategory[];
}
export interface StandardTreeNode {
  key: string;
  title: string;
  id?: StandardId;
  children?: StandardTreeNode[];
}
export function standardCategoryTree(
  categories: StandardCategory[],
  search = '',
): StandardTreeNode[] {
  const rows = new Map<string, StandardTreeNode>();
  const parents = new Map<string, string>();
  function flatten(items: StandardCategory[], inherited?: StandardId) {
    for (const item of items) {
      const key = String(item.id);
      if (rows.has(key)) continue;
      rows.set(key, {
        key,
        id: item.id,
        title: item.categoryName ?? key,
        children: [],
      });
      parents.set(key, String(item.parentId ?? inherited ?? '0'));
      if (item.children) flatten(item.children, item.id);
    }
  }
  flatten(categories);
  const roots: StandardTreeNode[] = [];
  for (const row of rows.values()) {
    const ancestry = new Set([row.key]);
    let ancestor = parents.get(row.key);
    let cyclic = false;
    while (ancestor && rows.has(ancestor)) {
      if (ancestry.has(ancestor)) {
        cyclic = true;
        break;
      }
      ancestry.add(ancestor);
      ancestor = parents.get(ancestor);
    }
    const parent = rows.get(parents.get(row.key) ?? '');
    if (parent && !cyclic) parent.children!.push(row);
    else roots.push(row);
  }
  const needle = search.trim().toLocaleLowerCase();
  function filter(items: StandardTreeNode[]): StandardTreeNode[] {
    return items.flatMap((row) => {
      const children = filter(row.children ?? []);
      return !needle ||
        row.title.toLocaleLowerCase().includes(needle) ||
        children.length > 0
        ? [
            {
              ...row,
              children: row.title.toLocaleLowerCase().includes(needle)
                ? row.children
                : children,
            },
          ]
        : [];
    });
  }
  return [{ key: 'all', title: '标准数据元类目', children: filter(roots) }];
}
export function standardTreeKeys(nodes: StandardTreeNode[]): string[] {
  return nodes.flatMap((node) =>
    node.children?.length ? [node.key, ...standardTreeKeys(node.children)] : [],
  );
}
