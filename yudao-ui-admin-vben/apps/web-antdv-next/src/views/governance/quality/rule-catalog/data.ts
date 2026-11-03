import type { QualityCatalogApi } from '#/api/governance/quality/catalog';

export interface CatalogTreeNode {
  key: string;
  title: string;
  children: CatalogTreeNode[];
}

/** 树标识全程使用字符串，并在搜索时保留匹配节点的父链。 */
export function catalogTree(
  categories: QualityCatalogApi.Category[],
  keyword = '',
): CatalogTreeNode[] {
  const flat: QualityCatalogApi.Category[] = [];
  function flatten(
    rows: QualityCatalogApi.Category[],
    parentId?: QualityCatalogApi.Id,
  ) {
    rows.forEach((row) => {
      flat.push({ ...row, parentId: row.parentId ?? parentId });
      if (row.children) flatten(row.children, row.id);
    });
  }
  flatten(categories);
  const nodes = new Map(
    flat.map((row) => [
      String(row.id),
      {
        key: String(row.id),
        title: row.categoryName ?? row.categoryCode ?? String(row.id),
        children: [] as CatalogTreeNode[],
      },
    ]),
  );
  const roots: CatalogTreeNode[] = [];
  flat.forEach((row) => {
    const node = nodes.get(String(row.id))!;
    const parent =
      row.parentId === undefined ? undefined : nodes.get(String(row.parentId));
    if (parent && parent !== node) parent.children.push(node);
    else roots.push(node);
  });
  const text = keyword.trim().toLocaleLowerCase();
  const filter = (rows: CatalogTreeNode[]): CatalogTreeNode[] =>
    rows.flatMap((row) => {
      if (row.title.toLocaleLowerCase().includes(text)) return [row];
      const children = filter(row.children);
      return children.length > 0 ? [{ ...row, children }] : [];
    });
  return text ? filter(roots) : roots;
}

export function treeKeys(rows: CatalogTreeNode[]): string[] {
  return rows.flatMap((row) => [row.key, ...treeKeys(row.children)]);
}

export const catalogCopy = {
  audit: {
    title: '稽查规则',
    category: '质量维度',
    summary:
      '是保障数据质量的核心手段，基于标准数据元的定义（如字段类型、值域等）制定具体的检查逻辑，用于评估数据的质量表现。',
    description:
      '这些规则配置后，可被绑定到数据质量任务中，通过定时调度执行，自动扫描并识别数据异常，形成质量报告，支撑数据问题的发现与治理闭环。',
  },
  clean: {
    title: '清洗规则',
    category: '清洗规则类目',
    summary:
      '用于定义数据加工过程中的标准化处理逻辑，确保数据在集成过程中实现格式统一、值域规范、逻辑合理。',
    description:
      '您可基于标准数据元的定义创建清洗规则，并在数据集成任务中调用，对源端数据进行自动化清洗，保障进入中台的数据质量。',
  },
} as const;
