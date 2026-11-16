import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';
import type { GovernanceMasterMatchApi } from '#/api/governance/mastermatch';

type Algorithm = GovernanceMasterMatchApi.Algorithm;
type Survivorship = GovernanceMasterMatchApi.Survivorship;

export const ALGORITHM_OPTIONS: {
  description: string;
  label: string;
  value: Algorithm;
}[] = [
  {
    label: '精确匹配',
    value: 'EXACT',
    description: '忽略全半角、大小写和多余空白后完全相同',
  },
  {
    label: '文本相似',
    value: 'TEXT',
    description: 'Jaro-Winkler，适合名称、简称',
  },
  {
    label: '编辑距离',
    value: 'EDIT',
    description: '适合地址、证照号等有错漏字的文本',
  },
  {
    label: '数值接近',
    value: 'NUMERIC',
    description: '按相对差值计算接近程度',
  },
];

export const SURVIVORSHIP_OPTIONS: { label: string; value: Survivorship }[] = [
  { label: '取保留记录的值（为空时补全）', value: 'SURVIVOR' },
  { label: '取内容最完整的值', value: 'MOST_COMPLETE' },
  { label: '取出现次数最多的值', value: 'MOST_FREQUENT' },
  { label: '取最大值', value: 'MAX' },
  { label: '取最小值', value: 'MIN' },
];

export const GROUP_STATUS_OPTIONS: {
  color: string;
  label: string;
  value: GovernanceMasterMatchApi.GroupStatus;
}[] = [
  { label: '待处理', value: 'PENDING', color: 'processing' },
  { label: '合并审批中', value: 'MERGING', color: 'warning' },
  { label: '已合并', value: 'MERGED', color: 'success' },
  { label: '已忽略', value: 'IGNORED', color: 'default' },
  { label: '已被新结果取代', value: 'STALE', color: 'default' },
];

export const RUN_STATUS: Record<string, { color: string; label: string }> = {
  RUNNING: { label: '执行中', color: 'processing' },
  SUCCESS: { label: '成功', color: 'success' },
  FAILED: { label: '失败', color: 'error' },
};

const STRING_TYPE = 2;
const NUMERIC_TYPES = new Set([1, 5, 6]);
const BOOLEAN_TYPE = 4;

/** 按字段类型可选的比较算法（与服务端校验一致） */
export function algorithmOptions(fieldType?: number) {
  return ALGORITHM_OPTIONS.filter(
    (option) =>
      option.value === 'EXACT' ||
      ((option.value === 'TEXT' || option.value === 'EDIT') &&
        fieldType === STRING_TYPE) ||
      (option.value === 'NUMERIC' &&
        fieldType !== undefined &&
        NUMERIC_TYPES.has(fieldType)),
  );
}

/** 按字段类型可选的取值规则：布尔字段不比较大小 */
export function survivorshipOptions(fieldType?: number) {
  return fieldType === BOOLEAN_TYPE
    ? SURVIVORSHIP_OPTIONS.filter(
        (option) => option.value !== 'MAX' && option.value !== 'MIN',
      )
    : SURVIVORSHIP_OPTIONS;
}

export function groupStatus(value?: string) {
  return (
    GROUP_STATUS_OPTIONS.find((option) => option.value === value) ?? {
      label: value ?? '-',
      color: 'default',
    }
  );
}

/** 主键标识的可读形式：单主键显示值，多主键显示“字段=值” */
export function keyLabel(key: string) {
  if (key.startsWith('SHA256:')) return `主键摘要 ${key.slice(7, 15)}`;
  try {
    const parsed = JSON.parse(key) as Record<string, unknown>;
    const entries = Object.entries(parsed);
    if (entries.length === 1) return String(entries[0]?.[1] ?? '');
    return entries.map(([name, value]) => `${name}=${String(value)}`).join('，');
  } catch {
    return key;
  }
}

export function displayValue(value: unknown) {
  if (value === null || value === undefined || value === '') return '（空）';
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

/** 合并草稿：保留记录、参与合并的成员、各字段取值来源 */
export interface MergeDraft {
  survivor: number;
  participating: boolean[];
  sources: Record<string, number>;
}

function primaryNames(columns: GovernanceMasterDataApi.Column[]) {
  return new Set(
    columns.filter((column) => column.pk === 1).map((column) => column.name!),
  );
}

/** 按服务端建议初始化合并草稿；已不存在的成员不参与合并 */
export function initialDraft(
  group: GovernanceMasterMatchApi.Group,
  columns: GovernanceMasterDataApi.Column[],
): MergeDraft {
  const participating = group.members.map((member) => member.exists !== false);
  const firstExisting = Math.max(0, participating.indexOf(true));
  const survivor = group.suggestion?.survivor ?? firstExisting;
  const sources: Record<string, number> = {};
  for (const column of columns) {
    const name = column.name!;
    const suggested = group.suggestion?.sources?.[name];
    sources[name] =
      suggested !== undefined && participating[suggested]
        ? suggested
        : survivor;
  }
  return { survivor, participating, sources };
}

/** 切换保留记录：保留记录必须参与合并，主键改取新保留记录，原先取自它之外的值不变 */
export function chooseSurvivor(
  draft: MergeDraft,
  index: number,
  columns: GovernanceMasterDataApi.Column[],
) {
  draft.survivor = index;
  draft.participating[index] = true;
  for (const name of primaryNames(columns)) draft.sources[name] = index;
}

/** 取消某成员参与合并时，取自它的字段改取保留记录 */
export function toggleParticipant(
  draft: MergeDraft,
  index: number,
  value: boolean,
) {
  if (index === draft.survivor && !value) return;
  draft.participating[index] = value;
  if (!value) {
    for (const [name, source] of Object.entries(draft.sources)) {
      if (source === index) draft.sources[name] = draft.survivor;
    }
  }
}

/** 黄金记录的值：主键取保留记录，其余按取值来源 */
export function goldenValues(
  group: GovernanceMasterMatchApi.Group,
  draft: MergeDraft,
  columns: GovernanceMasterDataApi.Column[],
) {
  const primary = primaryNames(columns);
  const values: GovernanceMasterMatchApi.Row = {};
  for (const column of columns) {
    const name = column.name!;
    const source = primary.has(name)
      ? draft.survivor
      : (draft.sources[name] ?? draft.survivor);
    values[name] = group.members[source]?.row[name] ?? null;
  }
  return values;
}

/** 提交前校验；返回问题说明，无问题时返回 undefined */
export function mergeIssue(
  group: GovernanceMasterMatchApi.Group,
  draft: MergeDraft,
) {
  if (group.status !== 'PENDING') return '该组当前不能合并';
  const survivor = group.members[draft.survivor];
  if (!survivor || survivor.exists === false) return '请选择仍存在的记录作为保留记录';
  const merged = group.members.filter(
    (_member, index) => index !== draft.survivor && draft.participating[index],
  );
  if (merged.length === 0) return '请至少选择一条要合并进保留记录的记录';
  if (merged.some((member) => member.exists === false))
    return '已不存在的记录不能参与合并';
  return undefined;
}

export function mergeRequest(
  group: GovernanceMasterMatchApi.Group,
  draft: MergeDraft,
  columns: GovernanceMasterDataApi.Column[],
  requestId: string,
): GovernanceMasterMatchApi.MergeReq {
  return {
    groupId: group.id,
    requestId,
    survivorKey: group.members[draft.survivor]!.key,
    mergedKeys: group.members
      .filter(
        (_member, index) =>
          index !== draft.survivor && draft.participating[index],
      )
      .map((member) => member.key),
    values: goldenValues(group, draft, columns),
  };
}

/** 成员摘要：主键加上前两个匹配字段的值 */
export function memberSummary(
  member: GovernanceMasterMatchApi.Member,
  fields: string[],
) {
  const values = fields
    .slice(0, 2)
    .map((name) => member.row[name])
    .filter((value) => value !== null && value !== undefined && value !== '')
    .map(String);
  return [keyLabel(member.key), ...values].join(' · ');
}
