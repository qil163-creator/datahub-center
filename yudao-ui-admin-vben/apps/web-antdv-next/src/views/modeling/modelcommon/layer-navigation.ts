export interface LayerNavigationItem {
  id: number | string;
  layerCode?: string;
  layerName?: string;
  nodeType?: string;
  parentId?: number | string;
  /** 分层默认表类型；用于识别顶层贴源 LAYER */
  defaultTableType?: string;
}

function isSourceCategory(item: {
  layerName?: string;
  nodeType?: string;
}): boolean {
  return (
    item.nodeType === 'CATEGORY' &&
    (item.layerName ?? '').replace(/\s+/g, '') === '贴源层'
  );
}

function isOrphanSourceLayer(item: {
  defaultTableType?: string;
  layerCode?: string;
  nodeType?: string;
  parentId?: string;
}): boolean {
  if (item.nodeType !== 'LAYER') return false;
  const parent = item.parentId ?? '0';
  if (parent !== '0' && parent !== '') return false;
  const code = (item.layerCode ?? '').trim().toUpperCase();
  const tableType = (item.defaultTableType ?? '').trim().toUpperCase();
  return code === 'ODS' || tableType === 'SOURCE';
}

/** 前端隐藏贴源层整棵子树，以及顶层 ODS/SOURCE LAYER；不删库表 */
export function filterSourceLayerNavigation<
  T extends {
    defaultTableType?: string;
    id: string;
    layerCode?: string;
    layerName?: string;
    nodeType?: string;
    parentId: string;
  },
>(items: readonly T[]): T[] {
  const hideIds = new Set<string>();
  for (const item of items) {
    if (isSourceCategory(item) || isOrphanSourceLayer(item)) {
      hideIds.add(item.id);
    }
  }
  if (hideIds.size === 0) return [...items];

  let grew = true;
  while (grew) {
    grew = false;
    for (const item of items) {
      if (hideIds.has(item.id)) continue;
      if (hideIds.has(item.parentId)) {
        hideIds.add(item.id);
        grew = true;
      }
    }
  }
  return items.filter((item) => !hideIds.has(item.id));
}

/** API 扁平分层；补齐 id/parentId 规范 key，树结构交接给调用方 handleTree。 */
export function prepareLayerNavigation<T extends LayerNavigationItem>(
  items: readonly T[],
  keyword = '',
) {
  const source = filterSourceLayerNavigation(
    items.map((item) => ({
      ...item,
      id: String(item.id),
      parentId: String(item.parentId ?? 0),
      selectable: item.nodeType === 'LAYER',
    })),
  );
  const text = keyword.trim().toLowerCase();
  if (!text) return source;

  const byId = new Map(source.map((item) => [item.id, item]));
  const keepIds = new Set<string>();
  for (const item of source) {
    if (
      !item.layerName?.toLowerCase().includes(text) &&
      !item.layerCode?.toLowerCase().includes(text)
    )
      continue;
    let current: (typeof source)[number] | undefined = item;
    while (current && !keepIds.has(current.id)) {
      keepIds.add(current.id);
      current = byId.get(current.parentId);
    }
  }
  return source.filter((item) => keepIds.has(item.id));
}

/** 模型管理左树：节点类型与列表筛选载荷（对齐 qData type 0/1/2/3，筛选用 id） */
export type ModelNavNodeType =
  | 'APP_ROOT'
  | 'BIZ_CATEGORY'
  | 'DATA_DOMAIN'
  | 'PUBLIC_ROOT'
  | 'SUBJECT_DOMAIN';

export interface ModelNavFilter {
  bizCategoryId?: number | string;
  dataDomainId?: number | string;
  subjectDomainId?: number | string;
}

export interface ModelNavFlatNode {
  id: string;
  parentId: string;
  title: string;
  nodeType: ModelNavNodeType;
  selectable: boolean;
  filter: ModelNavFilter;
}

function isEnabledStatus(status?: null | number | string) {
  return status === undefined || status === null || Number(status) === 0;
}

/**
 * 对齐 qData getTreeData：固定合成根「公共层」「应用层」，不读数仓分层。
 * 公共层 → 业务分类树 → 0136 关联数据域；应用层 → 主题域树。
 */
export function buildModelNavFlatNodes(input: {
  bizCategories: Array<{
    dataDomains?: Array<{
      code?: string;
      id?: number | string;
      name?: string;
      status?: number | string;
    }>;
    id?: number | string;
    name?: string;
    parentId?: number | string;
    status?: number | string;
  }>;
  keyword?: string;
  subjectDomains: Array<{
    id?: number | string;
    name?: string;
    parentId?: number | string;
    status?: number | string;
  }>;
}): ModelNavFlatNode[] {
  const PUBLIC_ROOT = 'nav:public';
  const APP_ROOT = 'nav:app';

  const nodes: ModelNavFlatNode[] = [
    {
      id: PUBLIC_ROOT,
      parentId: '0',
      title: '公共层',
      nodeType: 'PUBLIC_ROOT',
      selectable: true,
      filter: {},
    },
    {
      id: APP_ROOT,
      parentId: '0',
      title: '应用层',
      nodeType: 'APP_ROOT',
      selectable: true,
      filter: {},
    },
  ];

  const cats = (input.bizCategories ?? []).filter((c) =>
    isEnabledStatus(c.status),
  );
  const catIds = new Set(cats.map((c) => String(c.id)));
  for (const cat of cats) {
    if (cat.id == null) continue;
    const catId = `bc:${cat.id}`;
    const rawParent = cat.parentId != null ? String(cat.parentId) : '0';
    const parent =
      rawParent !== '0' && catIds.has(rawParent)
        ? `bc:${rawParent}`
        : PUBLIC_ROOT;
    nodes.push({
      id: catId,
      parentId: parent,
      title: cat.name ?? catId,
      nodeType: 'BIZ_CATEGORY',
      selectable: true,
      filter: { bizCategoryId: cat.id },
    });
    for (const domain of cat.dataDomains ?? []) {
      if (domain.id == null || !isEnabledStatus(domain.status)) continue;
      nodes.push({
        id: `dd:${cat.id}:${domain.id}`,
        parentId: catId,
        title: domain.name ?? String(domain.id),
        nodeType: 'DATA_DOMAIN',
        selectable: true,
        filter: { bizCategoryId: cat.id, dataDomainId: domain.id },
      });
    }
  }

  const subjects = (input.subjectDomains ?? []).filter((s) =>
    isEnabledStatus(s.status),
  );
  const subjectIds = new Set(subjects.map((s) => String(s.id)));
  for (const sub of subjects) {
    if (sub.id == null) continue;
    const sid = `sd:${sub.id}`;
    const rawParent = sub.parentId != null ? String(sub.parentId) : '0';
    const parent =
      rawParent !== '0' && subjectIds.has(rawParent)
        ? `sd:${rawParent}`
        : APP_ROOT;
    nodes.push({
      id: sid,
      parentId: parent,
      title: sub.name ?? sid,
      nodeType: 'SUBJECT_DOMAIN',
      selectable: true,
      filter: { subjectDomainId: sub.id },
    });
  }

  const text = (input.keyword ?? '').trim().toLowerCase();
  if (!text) return nodes;

  const byId = new Map(nodes.map((n) => [n.id, n]));
  const keep = new Set<string>();
  for (const n of nodes) {
    if (!n.title.toLowerCase().includes(text)) continue;
    let cur: ModelNavFlatNode | undefined = n;
    while (cur && !keep.has(cur.id)) {
      keep.add(cur.id);
      cur = byId.get(cur.parentId);
    }
  }
  return nodes.filter((n) => keep.has(n.id));
}

export function parseModelNavSelection(
  selectedId?: null | number | string,
  nodes: ModelNavFlatNode[] = [],
): ModelNavFilter {
  if (selectedId == null || selectedId === '') return {};
  const hit = nodes.find((n) => n.id === String(selectedId));
  return hit?.filter ?? {};
}
