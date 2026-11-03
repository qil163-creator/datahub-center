import type { GovernanceStandardCodeItemApi } from '#/api/governance/standardcodeitem';

export interface StandardCodeItemTreeNode
  extends GovernanceStandardCodeItemApi.StandardCodeItem {
  children?: StandardCodeItemTreeNode[];
  levelNo: number;
}

function compareIds(left?: number | string, right?: number | string) {
  const a = String(left ?? 0);
  const b = String(right ?? 0);
  return a.length - b.length || a.localeCompare(b);
}

function compareCodeItems(
  left: GovernanceStandardCodeItemApi.StandardCodeItem,
  right: GovernanceStandardCodeItemApi.StandardCodeItem,
) {
  return (
    (left.sort ?? 0) - (right.sort ?? 0) ||
    String(left.label).localeCompare(String(right.label), 'zh-CN') ||
    compareIds(left.id, right.id)
  );
}

/** 将兼容的扁平响应稳定转换为树；后端已返回 children 时也能重新规范化。 */
export function buildCodeItemTree(
  rows: GovernanceStandardCodeItemApi.StandardCodeItem[],
): StandardCodeItemTreeNode[] {
  const nodes = new Map<string, StandardCodeItemTreeNode>();
  const roots: StandardCodeItemTreeNode[] = [];

  const flattened: GovernanceStandardCodeItemApi.StandardCodeItem[] = [];
  const seen = new Set<string>();
  const collect = (
    items: GovernanceStandardCodeItemApi.StandardCodeItem[],
    inherited?: number | string,
  ) => {
    for (const item of items) {
      if (item.id === undefined || seen.has(String(item.id))) continue;
      seen.add(String(item.id));
      flattened.push({ ...item, parentId: item.parentId ?? inherited });
      if (item.children) collect(item.children, item.id);
    }
  };
  collect(rows);
  for (const row of flattened) {
    if (row.id === undefined) {
      continue;
    }
    nodes.set(String(row.id), { ...row, children: [], levelNo: 0 });
  }

  // 按稳定顺序逐条接受父边；一旦新边能沿已接受父链回到自己，就断开该边。
  // 这样历史脏数据即使存在 A -> B -> A，也不会构造出循环对象交给树组件。
  const orderedNodes = [...nodes.values()].toSorted((left, right) =>
    compareIds(left.id, right.id),
  );
  const acceptedParents = new Map<string, string>();
  for (const node of orderedNodes) {
    const parent =
      node.parentId === undefined
        ? undefined
        : nodes.get(String(node.parentId));
    const sameCodeSet =
      parent &&
      (node.codeSetId === undefined ||
        parent.codeSetId === undefined ||
        String(node.codeSetId) === String(parent.codeSetId));
    if (!parent || String(parent.id) === String(node.id) || !sameCodeSet) {
      node.parentId = undefined;
      continue;
    }
    let cursor: string | undefined = String(parent.id);
    let createsCycle = false;
    while (cursor !== undefined) {
      if (cursor === String(node.id)) {
        createsCycle = true;
        break;
      }
      cursor = acceptedParents.get(cursor);
    }
    if (createsCycle) {
      node.parentId = undefined;
      continue;
    }
    acceptedParents.set(String(node.id), String(parent.id));
  }

  for (const node of orderedNodes) {
    const parentId = acceptedParents.get(String(node.id));
    const parent = parentId === undefined ? undefined : nodes.get(parentId);
    if (parent) {
      parent.children!.push(node);
    } else {
      roots.push(node);
    }
  }

  const visited = new Set<string>();
  const normalize = (node: StandardCodeItemTreeNode, levelNo: number) => {
    if (node.id === undefined || visited.has(String(node.id))) {
      return;
    }
    visited.add(String(node.id));
    node.levelNo = levelNo;
    node.children?.sort(compareCodeItems);
    node.children?.forEach((child) => normalize(child, levelNo + 1));
    if (node.children?.length === 0) {
      delete node.children;
    }
  };

  roots.sort(compareCodeItems);
  roots.forEach((root) => normalize(root, 0));

  return roots;
}

function toTimestamp(value: string) {
  return new Date(
    value.includes('T') ? value : value.replace(' ', 'T'),
  ).getTime();
}

/** 有效期统一采用 [validFrom, validTo)，validTo 为空表示长期有效。 */
export function validateHalfOpenInterval(validFrom?: string, validTo?: string) {
  if (!validFrom || !validTo) {
    return undefined;
  }
  const from = toTimestamp(validFrom);
  const to = toTimestamp(validTo);
  if (!Number.isFinite(from) || !Number.isFinite(to)) {
    return '请输入有效的生效时间和失效时间';
  }
  if (from >= to) {
    return '失效时间必须晚于生效时间（有效期左闭右开）';
  }
  return undefined;
}
