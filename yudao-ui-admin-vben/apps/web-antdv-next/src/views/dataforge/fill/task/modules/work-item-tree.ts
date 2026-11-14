/**
 * 任务详情：把组织实例按上下级组织成树（表格树形展示）。
 * 上级不在列表中的实例作为根；编号按字符串比较，避免大数精度问题。
 */
export type TreeItem<T> = T & { children?: Array<TreeItem<T>> };

export function buildWorkItemTree<
  T extends { id: number | string; parentWorkItemId?: null | number | string },
>(items: T[]): Array<TreeItem<T>> {
  const nodes = new Map<string, TreeItem<T>>();
  for (const item of items) nodes.set(String(item.id), { ...item });
  const roots: Array<TreeItem<T>> = [];
  for (const node of nodes.values()) {
    const parentKey =
      node.parentWorkItemId === undefined || node.parentWorkItemId === null
        ? undefined
        : String(node.parentWorkItemId);
    const parent = parentKey === undefined ? undefined : nodes.get(parentKey);
    if (parent && parent !== node) {
      parent.children = [...(parent.children ?? []), node];
    } else {
      roots.push(node);
    }
  }
  return roots;
}
