/**
 * 多级下发：下发候选、默认截止时间与下级可执行操作的纯函数。
 *
 * 编号统一按字符串比较，不做数值转换（后端 Long 可能以字符串返回）。
 */

export interface DeptNode {
  deptId: number | string;
  parentId?: number | string;
}

/** 某个部门及其下属部门的编号（只在候选部门范围内查找） */
export function subtreeDeptIds(depts: DeptNode[], rootId: number | string): Set<string> {
  const children = new Map<string, string[]>();
  for (const dept of depts) {
    const parent = String(dept.parentId ?? '');
    children.set(parent, [...(children.get(parent) ?? []), String(dept.deptId)]);
  }
  const result = new Set<string>();
  const queue = [String(rootId)];
  while (queue.length > 0) {
    const current = queue.shift()!;
    if (result.has(current)) continue;
    result.add(current);
    queue.push(...(children.get(current) ?? []));
  }
  return result;
}

/**
 * 默认截止时间：本单位截止时间提前 offsetHours 小时；结果早于当前时间时取本单位截止时间。
 * 本单位没有截止时间时返回 undefined，由用户选择。
 */
export function defaultChildDeadline(
  parentDeadline: number | string | undefined,
  offsetHours: number | undefined,
  now: number,
): number | undefined {
  if (parentDeadline === undefined || parentDeadline === null || parentDeadline === '') {
    return undefined;
  }
  const parent = typeof parentDeadline === 'number' ? parentDeadline : Date.parse(parentDeadline);
  if (Number.isNaN(parent)) return undefined;
  const candidate = parent - Math.max(0, offsetHours ?? 0) * 3_600_000;
  return candidate > now ? candidate : parent;
}

export interface ChildLike {
  awaitingReview?: boolean;
  currentSubmissionId?: number | string;
  status: number;
}

/** 下级可执行的操作；editable 为本单位（上级）仍可编辑、尚未上报 */
export function childActions(child: ChildLike, editable: boolean) {
  const draft = [0, 1, 5].includes(child.status);
  return {
    cancel: editable && draft,
    deadline: editable && draft,
    review: editable && child.awaitingReview === true,
    returnBack: editable && child.status === 11,
    view: child.currentSubmissionId !== undefined && child.currentSubmissionId !== null,
  };
}
