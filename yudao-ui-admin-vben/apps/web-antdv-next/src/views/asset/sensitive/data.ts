import type { SensitiveListApi } from '#/api/asset/sensitive';

export interface CategoryTreeNode {
  key: string;
  value: string;
  title: string;
  selectable: boolean;
  disabled: boolean;
  children?: CategoryTreeNode[];
}
export function categoryTree(
  values: SensitiveListApi.Category[],
  leafOnly = false,
): CategoryTreeNode[] {
  const nodes = new Map(
    values.map((value) => [
      value.key,
      {
        key: value.key,
        value: value.key,
        title: value.name,
        selectable: !leafOnly || value.type === 'CATEGORY',
        disabled:
          leafOnly &&
          (value.type !== 'CATEGORY' ||
            !value.selectable ||
            value.status !== 0),
        children: [] as CategoryTreeNode[],
      },
    ]),
  );
  const roots: CategoryTreeNode[] = [];
  for (const value of values) {
    const node = nodes.get(value.key)!;
    const parent =
      value.parentKey && value.parentKey !== value.key
        ? nodes.get(value.parentKey)
        : undefined;
    if (parent) parent.children.push(node);
    else roots.push(node);
  }
  return roots;
}
export function categoryKey(id?: SensitiveListApi.Id) {
  return id === undefined ? undefined : `category:${String(id)}`;
}
export const statusOptions = [
  { label: '启用', value: 0 },
  { label: '禁用', value: 1 },
];
