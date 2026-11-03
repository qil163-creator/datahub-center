export type ResourceId = number | string;

export interface CategoryRow {
  id: ResourceId;
  parentId: ResourceId;
  key: string;
  name: string;
  description?: null | string;
  status: number;
  sort: number;
  creatorName?: null | string;
  createTime?: number | string;
  children?: CategoryRow[];
}

export interface CategoryPayload {
  id?: ResourceId;
  parentId: ResourceId;
  name: string;
  description: string;
  status: number;
  sort: number;
}

export interface CategoryAdapter {
  label: string;
  nameLabel: string;
  permissionPrefix: string;
  list: (params?: Record<string, unknown>) => Promise<unknown[]>;
  get: (id: ResourceId) => Promise<unknown>;
  create: (data: CategoryPayload) => Promise<unknown>;
  update: (data: CategoryPayload) => Promise<unknown>;
  remove: (id: ResourceId) => Promise<unknown>;
  normalize: (row: unknown) => CategoryRow;
  toPayload: (data: CategoryPayload) => unknown;
}

export function categoryTree(rows: CategoryRow[]): CategoryRow[] {
  const nodes = new Map<string, CategoryRow>();
  const roots: CategoryRow[] = [];
  for (const row of rows) {
    nodes.set(String(row.id), { ...row, key: String(row.id), children: [] });
  }
  for (const row of nodes.values()) {
    const parent = nodes.get(String(row.parentId ?? 0));
    if (parent && String(parent.id) !== String(row.id)) parent.children!.push(row);
    else roots.push(row);
  }
  const sort = (items: CategoryRow[]) => {
    items.sort((a, b) => a.sort - b.sort || String(a.id).localeCompare(String(b.id)));
    items.forEach((item) => sort(item.children ?? []));
  };
  sort(roots);
  return roots;
}

export function branchKeys(rows: CategoryRow[]): string[] {
  const keys: string[] = [];
  const visit = (items: CategoryRow[]) => {
    for (const item of items) {
      if (item.children?.length) {
        keys.push(String(item.id));
        visit(item.children);
      }
    }
  };
  visit(rows);
  return keys;
}

export function filterCategoryTree(
  rows: CategoryRow[],
  name: string,
  parentId?: ResourceId,
): CategoryRow[] {
  const needle = name.trim().toLowerCase();
  const parent = parentId === undefined || parentId === '' ? undefined : String(parentId);
  const filter = (items: CategoryRow[]): CategoryRow[] => {
    const result: CategoryRow[] = [];
    for (const item of items) {
      const children = filter(item.children ?? []);
      const matchesName = !needle || item.name.toLowerCase().includes(needle);
      const matchesParent = parent === undefined || String(item.parentId ?? 0) === parent;
      if ((matchesName && matchesParent) || children.length) {
        result.push({ ...item, children });
      }
    }
    return result;
  };
  return filter(rows);
}

export function parentOptions(
  rows: CategoryRow[],
  excludedIds?: Iterable<ResourceId> | ResourceId,
) {
  const excluded = new Set<string>();
  if (excludedIds !== undefined) {
    if (typeof excludedIds === 'string' || typeof excludedIds === 'number') {
      excluded.add(String(excludedIds));
    } else {
      for (const id of excludedIds) excluded.add(String(id));
    }
  }
  const source = categoryTree(rows);
  const prune = (items: CategoryRow[]): CategoryRow[] =>
    items
      .filter((item) => !excluded.has(String(item.id)))
      .map((item) => ({
        ...item,
        children: prune(item.children ?? []),
        title: item.name,
        value: String(item.id),
        key: String(item.id),
      }));
  return [
    { title: '顶级节点', value: '0', key: '0', children: prune(source) },
  ];
}
