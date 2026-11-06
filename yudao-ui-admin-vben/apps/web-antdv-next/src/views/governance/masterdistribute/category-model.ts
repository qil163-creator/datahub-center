import type { MasterPushCategory } from '#/api/governance/masterdistribute/category';

export interface CategoryNode {
  key: string;
  title: string;
  status: number;
  children: CategoryNode[];
}

/** 使用字符串作为树节点键，避免大编号丢失精度。 */
export function buildCategoryTree(rows: MasterPushCategory[], keyword = ''): CategoryNode[] {
  const nodes = new Map<string, CategoryNode>();
  rows.forEach((row) => nodes.set(String(row.id), {
    key: String(row.id),
    title: row.name,
    status: row.status,
    children: [],
  }));
  const roots: CategoryNode[] = [];
  rows.toSorted((a, b) => a.sort - b.sort).forEach((row) => {
    const node = nodes.get(String(row.id))!;
    const parent = nodes.get(String(row.parentId));
    if (parent && parent !== node) parent.children.push(node);
    else roots.push(node);
  });
  const query = keyword.trim().toLocaleLowerCase();
  const filter = (items: CategoryNode[], parentMatches = false): CategoryNode[] =>
    items.flatMap((item) => {
      const matches = parentMatches || item.title.toLocaleLowerCase().includes(query);
      const children = filter(item.children, matches);
      return matches || children.length > 0 ? [{ ...item, children }] : [];
    });
  return filter(roots);
}

interface CategoryOption {
  label: string;
  value: string;
  disabled: boolean;
  children: CategoryOption[];
}

export function buildCategoryOptions(rows: MasterPushCategory[]): CategoryOption[] {
  const map = (nodes: CategoryNode[]): CategoryOption[] =>
    nodes.map((node) => {
      const disabled = node.status === 1;
      return {
        label: node.title,
        value: node.key,
        disabled,
        children: map(node.children),
      };
    });
  return map(buildCategoryTree(rows));
}
