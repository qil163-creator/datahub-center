import type { ProjectCategoryApi as Api } from '#/api/dataforge/projectcategory';

export interface CategoryNode extends Api.Category {
  key: string;
  children?: CategoryNode[];
}

export interface ParentOption {
  value: string;
  title: string;
  children?: ParentOption[];
}

export const categoryLabels: Record<Api.Kind, string> = {
  development: '数据开发类目',
  integration: '数据集成类目',
  masterPush: '主数据推送类目',
  push: '数据推送类目',
};

export function categoryPermission(kind: Api.Kind, action: string) {
  if (kind === 'masterPush') return `governance:master-push-category:${action}`;
  const resource = {
    development: 'dev-category',
    integration: 'integration-category',
    push: 'push-category',
  }[kind];
  return `dataforge:${resource}:${action}`;
}

/** 关联计数只判断是否为正，不把超出安全范围的 Long 转成数字。 */
export function isPositiveCount(value: Api.Count | null) {
  return /^[1-9]\d*$/.test(String(value));
}

/** 树节点使用十进制字符串键，保留接口返回的原始 ID。 */
export function categoryTree(rows: Api.Category[]): CategoryNode[] {
  const nodes = new Map(
    rows.map((row) => [
      String(row.id),
      { ...row, key: String(row.id) } as CategoryNode,
    ]),
  );
  const roots: CategoryNode[] = [];
  for (const row of rows) {
    const node = nodes.get(String(row.id))!;
    const parent = nodes.get(String(row.parentId));
    if (parent && parent !== node) (parent.children ??= []).push(node);
    else roots.push(node);
  }
  function sort(nodesToSort: CategoryNode[]) {
    nodesToSort.sort((left, right) => left.sort - right.sort);
    nodesToSort.forEach((node) => {
      if (node.children) sort(node.children);
    });
  }
  sort(roots);
  return roots;
}

/** 筛选时保留匹配节点的祖先，避免子类目在查询结果中失去层级。 */
export function filterCategoryTree(
  nodes: CategoryNode[],
  name: string,
  parentId?: Api.Id,
): CategoryNode[] {
  const keyword = name.trim().toLocaleLowerCase();
  const hasParent = parentId !== undefined && parentId !== '';
  if (!keyword && !hasParent) return nodes;
  return nodes.flatMap((node) => {
    const children = node.children
      ? filterCategoryTree(node.children, name, parentId)
      : [];
    const matches =
      (!keyword || node.name.toLocaleLowerCase().includes(keyword)) &&
      (!hasParent || String(node.parentId) === String(parentId));
    if (!matches && children.length === 0) return [];
    return [{ ...node, children: children.length > 0 ? children : undefined }];
  });
}

export function branchKeys(nodes: CategoryNode[]): string[] {
  return nodes.flatMap((node) =>
    node.children?.length ? [node.key, ...branchKeys(node.children)] : [],
  );
}

/** 编辑父级时排除自身和全部下级，后端仍负责校验循环与并发。 */
export function categoryParentOptions(
  rows: Api.Category[],
  editingId?: Api.Id,
): ParentOption[] {
  const excluded = new Set<string>();
  if (editingId !== undefined) {
    const pending = [String(editingId)];
    for (let cursor = 0; cursor < pending.length; cursor++) {
      const id = pending[cursor]!;
      if (excluded.has(id)) continue;
      excluded.add(id);
      rows.forEach((row) => {
        if (String(row.parentId) === id) pending.push(String(row.id));
      });
    }
  }
  function options(nodes: CategoryNode[]): ParentOption[] {
    return nodes.map((node) => ({
      value: node.key,
      title: node.name,
      children: node.children ? options(node.children) : undefined,
    }));
  }
  return [
    {
      value: '0',
      title: '顶级节点',
      children: options(
        categoryTree(rows.filter((row) => !excluded.has(String(row.id)))),
      ),
    },
  ];
}
